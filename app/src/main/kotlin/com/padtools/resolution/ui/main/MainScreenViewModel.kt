package com.padtools.resolution.ui.main

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.padtools.resolution.display.DisplayConfig
import com.padtools.resolution.display.DisplayController
import com.padtools.resolution.display.DisplayMath
import com.padtools.resolution.display.PendingRevertStore
import com.padtools.resolution.display.ResolutionMode
import com.padtools.resolution.display.SetupStatus
import com.padtools.resolution.display.ShizukuSetup
import com.padtools.resolution.display.ShizukuState
import com.padtools.resolution.display.readSetupStatus
import com.padtools.resolution.games.ForegroundAppWatcher
import com.padtools.resolution.games.GameStore
import com.padtools.resolution.games.GameSwitcher
import com.padtools.resolution.games.GameWatchService
import com.padtools.resolution.games.InstalledApp
import com.padtools.resolution.games.RunningApps
import com.padtools.resolution.games.appLabel
import com.padtools.resolution.games.autostartAllowed
import com.padtools.resolution.games.hasGameShortcut
import com.padtools.resolution.games.launchIntent
import com.padtools.resolution.games.launchableApps
import com.padtools.resolution.games.requestGameShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

data class MainUiState(
  val native: DisplayConfig,
  val current: DisplayConfig,
  val hasPermission: Boolean,
  val setup: SetupStatus,
  val shizuku: ShizukuState,
  val selectedMode: ResolutionMode,
  val uiScale: Float,
  /** Shizuku is granting the display permission right now. */
  val shizukuBusy: Boolean = false,
  /** Why Shizuku's last attempt at setup failed; null when it has not failed. */
  val shizukuError: String? = null,
  val games: List<GameRow> = emptyList(),
  /** The user wants the display switched by itself when one of [games] is opened. */
  val autoSwitch: Boolean = false,
  /** Android's Usage access is on, so the app can tell which app is in front. */
  val usageAccess: Boolean = false,
  /** The app is exempt from battery optimisation, so HyperOS does not pause the watcher. */
  val backgroundAllowed: Boolean = false,
  /** HyperOS's Autostart switch for the app; null when the tablet has none or will not say. */
  val autostartAllowed: Boolean? = null,
  /** Apps to choose from while the "Add a game" picker is open; null while it is closed. */
  val gamePicker: List<InstalledApp>? = null,
  /** The home screen did not add a game shortcut the user asked for. */
  val shortcutBlocked: Boolean = false,
  val busy: Boolean = false,
  /** Seconds left to confirm a change before it is undone; null when nothing is waiting. */
  val confirmSecondsLeft: Int? = null,
  val error: String? = null,
) {
  val modes: List<ResolutionMode> get() = DisplayMath.availableModes(native)
  val target: DisplayConfig get() = DisplayMath.configFor(selectedMode, native, uiScale)
  val currentMode: ResolutionMode? get() = DisplayMath.modeOf(current, native)
  val canApply: Boolean get() = hasPermission && !busy && confirmSecondsLeft == null && target != current
  val canReset: Boolean
    get() = hasPermission && !busy && confirmSecondsLeft == null && current != native
}

/** One of the user's games, as the list shows it. */
data class GameRow(val packageName: String, val label: String, val mode: ResolutionMode)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
  private val controller = DisplayController(application)
  private val shizuku = ShizukuSetup(application)
  private val games = GameStore(application)
  private val gameSwitcher = GameSwitcher(controller, games)
  private val foregroundApp = ForegroundAppWatcher(application)
  private val runningApps = RunningApps(application)
  private val pendingRevert = PendingRevertStore(application)
  private var countdown: Job? = null

  // Each automatic Shizuku step is tried once, so a refusal is not asked again in a loop.
  private var shizukuAccessRequested = false
  private var shizukuGrantTried = false

  private val _uiState = MutableStateFlow(initialState())
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  // Shizuku can start, stop or answer its "allow this app?" dialog at any time; follow along.
  private val onShizukuStarted = Shizuku.OnBinderReceivedListener { refresh() }
  private val onShizukuStopped = Shizuku.OnBinderDeadListener { refresh() }
  private val onShizukuAccess = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

  init {
    Shizuku.addBinderReceivedListenerSticky(onShizukuStarted)
    Shizuku.addBinderDeadListener(onShizukuStopped)
    Shizuku.addRequestPermissionResultListener(onShizukuAccess)
    // Not while a game holds the display: this fix-up is one of the things that closes games.
    if (!gameSwitcher.isSwitched) controller.restoreGesturesIfOwed()

    // A change was never confirmed last time (the app was closed mid-countdown): undo it now.
    if (pendingRevert.load() != null) {
      viewModelScope.launch { revertPending() }
    }
  }

  /**
   * Re-reads the display, the permission and the setup steps. Called when the app comes back
   * to the foreground, and once a second while the setup guide is showing.
   */
  fun refresh() {
    val hasPermission = controller.hasPermission()
    // Shizuku only matters until the permission is granted; after that it is never used.
    val shizukuState = if (hasPermission) _uiState.value.shizuku else shizuku.state()
    _uiState.update {
      it.copy(
        current = controller.currentConfig(),
        hasPermission = hasPermission,
        setup = readSetupStatus(getApplication()),
        shizuku = shizukuState,
        games = gameRows(),
        autoSwitch = games.autoSwitch,
        usageAccess = foregroundApp.hasUsageAccess(),
        backgroundAllowed =
          getApplication<Application>()
            .getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(getApplication<Application>().packageName),
        autostartAllowed = autostartAllowed(getApplication()),
      )
    }
    if (!hasPermission) continueShizukuSetup(shizukuState, automatic = true)
    GameWatchService.startIfWanted(getApplication())
  }

  fun setAutoSwitch(on: Boolean) {
    // Turning it off needs no more than this: the watcher sees the change within a second,
    // puts the display back if a game had changed it, and stops itself.
    games.autoSwitch = on
    refresh()
  }

  fun openGamePicker() {
    viewModelScope.launch {
      val apps =
        withContext(Dispatchers.Default) {
          launchableApps(getApplication()).filter { games.modeFor(it.packageName) == null }
        }
      _uiState.update { it.copy(gamePicker = apps) }
    }
  }

  fun closeGamePicker() {
    _uiState.update { it.copy(gamePicker = null) }
  }

  fun addGame(packageName: String) {
    games.setGame(packageName, ResolutionMode.P1200)
    _uiState.update { it.copy(gamePicker = null) }
    refresh()
  }

  fun setGameMode(packageName: String, mode: ResolutionMode) {
    games.setGame(packageName, mode)
    refresh()
  }

  fun removeGame(packageName: String) {
    games.removeGame(packageName)
    refresh()
  }

  /**
   * Switches to the game's resolution, then opens it. Doing it in this order means the game
   * starts at the resolution it will run at, which a game opened from the home screen cannot.
   */
  fun playGame(packageName: String) {
    val mode = games.modeFor(packageName) ?: return
    val open = launchIntent(getApplication(), packageName)
    if (open == null) {
      _uiState.update { it.copy(error = "That game is no longer installed.") }
      return
    }
    if (_uiState.value.busy) return
    _uiState.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      // A game that is already open cannot live through the change: it would come back
      // broken, or close as the user returns to it. Close it now so it starts afresh.
      val mustRestart = !gameSwitcher.isAt(mode) && runningApps.isRunning(packageName) == true
      if (mustRestart) runningApps.close(packageName)
      val result = gameSwitcher.enter(mode)
      if (result.isSuccess && mustRestart) {
        withTimeoutOrNull(CLOSE_GAME_LIMIT_MS) {
          while (runningApps.isRunning(packageName) == true) {
            runningApps.close(packageName)
            delay(CLOSE_GAME_CHECK_MS)
          }
        }
      }
      _uiState.update {
        it.copy(busy = false, current = controller.currentConfig(), error = result.exceptionOrNull()?.message)
      }
      if (result.isSuccess) {
        GameWatchService.startIfWanted(getApplication())
        getApplication<Application>().startActivity(open)
      }
    }
  }

  /** Puts an icon on the home screen that does what Play does. */
  fun addGameShortcut(packageName: String) {
    val asked = requestGameShortcut(getApplication(), packageName)
    viewModelScope.launch {
      // HyperOS refuses silently until the user allows "Home screen shortcuts" for the app,
      // so the only way to tell is to look whether the shortcut turned up.
      if (asked) delay(SHORTCUT_CHECK_AFTER_MS)
      if (!hasGameShortcut(getApplication(), packageName)) _uiState.update { it.copy(shortcutBlocked = true) }
    }
  }

  fun closeShortcutHelp() {
    _uiState.update { it.copy(shortcutBlocked = false) }
  }

  private fun gameRows(): List<GameRow> =
    games.games()
      .map { GameRow(it.packageName, appLabel(getApplication(), it.packageName), it.mode) }
      .sortedBy { it.label.lowercase() }

  /** The setup guide's Shizuku button: retries whichever step is next, even if already tried. */
  fun setUpWithShizuku() = continueShizukuSetup(shizuku.state(), automatic = false)

  fun openShizukuIntent(): Intent? = shizuku.openShizukuIntent()

  override fun onCleared() {
    Shizuku.removeBinderReceivedListener(onShizukuStarted)
    Shizuku.removeBinderDeadListener(onShizukuStopped)
    Shizuku.removeRequestPermissionResultListener(onShizukuAccess)
  }

  /**
   * Takes the next step of setup through Shizuku, if there is one: ask Shizuku to trust this
   * app, then have it grant the display permission.
   */
  private fun continueShizukuSetup(state: ShizukuState, automatic: Boolean) {
    when (state) {
      ShizukuState.NEEDS_ACCESS ->
        if (!automatic || !shizukuAccessRequested) {
          shizukuAccessRequested = true
          shizuku.requestAccess()
        }
      ShizukuState.READY ->
        if ((!automatic || !shizukuGrantTried) && !_uiState.value.shizukuBusy) {
          shizukuGrantTried = true
          _uiState.update { it.copy(shizukuBusy = true, shizukuError = null) }
          viewModelScope.launch {
            val result = shizuku.grantDisplayPermission()
            _uiState.update {
              it.copy(
                shizukuBusy = false,
                shizukuError = result.exceptionOrNull()?.message,
                hasPermission = controller.hasPermission(),
              )
            }
          }
        }
      ShizukuState.NOT_RUNNING, ShizukuState.NOT_INSTALLED -> Unit
    }
  }

  fun selectMode(mode: ResolutionMode) {
    _uiState.update { it.copy(selectedMode = mode) }
  }

  fun setUiScale(scale: Float) {
    _uiState.update { it.copy(uiScale = DisplayMath.snapUiScale(scale)) }
  }

  fun apply() {
    val state = _uiState.value
    if (!state.canApply) return
    val target = state.target
    val previous = controller.currentConfig()
    // A change made by hand is the user taking over: the game watcher must not undo it later.
    games.displayBeforeGame = null
    _uiState.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      pendingRevert.save(previous)
      val result = controller.apply(target)
      if (result.isSuccess) {
        _uiState.update { it.copy(busy = false, current = controller.currentConfig()) }
        startCountdown()
      } else {
        // The size may have changed before the density failed, so put both back.
        controller.apply(previous)
        pendingRevert.clear()
        showCurrent(error = result.exceptionOrNull()?.message)
      }
    }
  }

  fun keepChange() {
    countdown?.cancel()
    pendingRevert.clear()
    _uiState.update { it.copy(confirmSecondsLeft = null) }
  }

  fun revertChange() {
    countdown?.cancel()
    viewModelScope.launch { revertPending() }
  }

  fun resetToDefault() {
    val state = _uiState.value
    if (!state.canReset) return
    games.displayBeforeGame = null
    _uiState.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      val result = controller.apply(state.native)
      showCurrent(error = result.exceptionOrNull()?.message)
    }
  }

  fun dismissError() {
    _uiState.update { it.copy(error = null) }
  }

  private fun startCountdown() {
    countdown?.cancel()
    countdown = viewModelScope.launch {
      for (secondsLeft in CONFIRM_SECONDS downTo 1) {
        _uiState.update { it.copy(confirmSecondsLeft = secondsLeft) }
        delay(1000)
      }
      revertPending()
    }
  }

  private suspend fun revertPending() {
    val previous = pendingRevert.load()
    _uiState.update { it.copy(busy = true, confirmSecondsLeft = null) }
    val result = if (previous != null) controller.apply(previous) else Result.success(Unit)
    if (result.isSuccess) pendingRevert.clear()
    showCurrent(error = result.exceptionOrNull()?.message)
  }

  /** Ends the current operation and points the selection at whatever the display is now. */
  private fun showCurrent(error: String?) {
    val current = controller.currentConfig()
    _uiState.update {
      it.copy(
        current = current,
        hasPermission = controller.hasPermission(),
        setup = readSetupStatus(getApplication()),
        selectedMode = DisplayMath.modeOf(current, it.native) ?: ResolutionMode.NATIVE,
        uiScale = DisplayMath.uiScaleOf(current, it.native),
        busy = false,
        confirmSecondsLeft = null,
        error = error,
      )
    }
  }

  private fun initialState(): MainUiState {
    val native = controller.nativeConfig()
    val current = controller.currentConfig()
    return MainUiState(
      native = native,
      current = current,
      hasPermission = controller.hasPermission(),
      setup = readSetupStatus(getApplication()),
      shizuku = shizuku.state(),
      selectedMode = DisplayMath.modeOf(current, native) ?: ResolutionMode.NATIVE,
      uiScale = DisplayMath.uiScaleOf(current, native),
    )
  }

  private companion object {
    const val CONFIRM_SECONDS = 15
    const val CLOSE_GAME_LIMIT_MS = 3_000L
    const val CLOSE_GAME_CHECK_MS = 250L
    const val SHORTCUT_CHECK_AFTER_MS = 1_500L
  }
}
