package com.padtools.resolution.ui.main

import android.app.Application
import android.content.Intent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
  private val controller = DisplayController(application)
  private val shizuku = ShizukuSetup(application)
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
      )
    }
    if (!hasPermission) continueShizukuSetup(shizukuState, automatic = true)
  }

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
  }
}
