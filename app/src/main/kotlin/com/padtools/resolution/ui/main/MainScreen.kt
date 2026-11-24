package com.padtools.resolution.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.padtools.resolution.display.DisplayConfig
import com.padtools.resolution.display.DisplayController
import com.padtools.resolution.display.DisplayMath
import com.padtools.resolution.display.ResolutionMode
import com.padtools.resolution.display.ShizukuState
import com.padtools.resolution.display.homeScreenAppInfoIntent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainScreenViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val askForNotifications =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.setAutoSwitch(true) }

  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }

  // The permission is granted from a computer while this screen stays open, so nothing tells
  // the app when it happens. Keep checking until it does.
  if (!state.hasPermission) {
    LaunchedEffect(Unit) {
      while (true) {
        delay(SETUP_CHECK_INTERVAL_MS)
        viewModel.refresh()
      }
    }
  }

  MainScreen(
    state = state,
    onSelectMode = viewModel::selectMode,
    onUiScaleChange = viewModel::setUiScale,
    onApply = viewModel::apply,
    onReset = viewModel::resetToDefault,
    onKeep = viewModel::keepChange,
    onRevert = viewModel::revertChange,
    onDismissError = viewModel::dismissError,
    onSetUpWithShizuku = viewModel::setUpWithShizuku,
    onOpenShizuku = {
      try {
        viewModel.openShizukuIntent()?.let(context::startActivity)
      } catch (e: ActivityNotFoundException) {
        // Shizuku was uninstalled a moment ago; the next refresh hides this option.
      }
    },
    gameActions =
      GameActions(
        setAutoSwitch = { on ->
          // The watcher's permanent notification needs this permission on Android 13 and up.
          // Ask once when switching on; the watcher runs either way.
          val mustAsk =
            on &&
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
              context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
          if (mustAsk) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setAutoSwitch(on)
        },
        openUsageAccess = { context.openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
        allowBackground = {
          // On plain Android this is a yes/no prompt. HyperOS opens the app's battery page
          // instead, where "No restrictions" is the choice that grants the same exemption.
          context.open(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        },
        openAppInfo = {
          context.open(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        },
        openPicker = viewModel::openGamePicker,
        closePicker = viewModel::closeGamePicker,
        add = viewModel::addGame,
        setMode = viewModel::setGameMode,
        remove = viewModel::removeGame,
        play = viewModel::playGame,
      ),
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
  state: MainUiState,
  onSelectMode: (ResolutionMode) -> Unit,
  onUiScaleChange: (Float) -> Unit,
  onApply: () -> Unit,
  onReset: () -> Unit,
  onKeep: () -> Unit,
  onRevert: () -> Unit,
  onDismissError: () -> Unit,
  onSetUpWithShizuku: () -> Unit,
  onOpenShizuku: () -> Unit,
  gameActions: GameActions,
) {
  Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Pad Resolution") }) }) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        CurrentDisplayCard(state)
        if (!state.hasPermission) SetupGuide(state, onSetUpWithShizuku, onOpenShizuku)
        ResolutionPicker(state, onSelectMode)
        UiSizeCard(state, onUiScaleChange)
        Actions(state, onApply, onReset)
        HorizontalDivider()
        GamesSection(state, gameActions)
      }
    }
  }

  state.confirmSecondsLeft?.let { secondsLeft ->
    AlertDialog(
      onDismissRequest = {}, // Only the two buttons or the timer close this.
      title = { Text("Keep this display mode?") },
      text = {
        Text(
          "The display is now ${describe(state.current)}. " +
            "It goes back to the previous mode in $secondsLeft s unless you keep it."
        )
      },
      confirmButton = { Button(onClick = onKeep) { Text("Keep") } },
      dismissButton = { TextButton(onClick = onRevert) { Text("Go back") } },
    )
  }

  state.error?.let { message ->
    AlertDialog(
      onDismissRequest = onDismissError,
      title = { Text("Display not changed") },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
    )
  }
}

@Composable
private fun CurrentDisplayCard(state: MainUiState) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Current display", style = MaterialTheme.typography.labelLarge)
      Text(
        "${state.current.width} × ${state.current.height}",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
      )
      val modeName = state.currentMode?.label ?: "Custom size"
      Text("$modeName · ${state.current.density} dpi", style = MaterialTheme.typography.bodyLarge)
    }
  }
}

/**
 * The one-time setup as numbered steps. Shown until the permission is granted; the steps the
 * app can see for itself tick themselves off, and the whole guide disappears when it is done.
 */
@Composable
private fun SetupGuide(state: MainUiState, onSetUpWithShizuku: () -> Unit, onOpenShizuku: () -> Unit) {
  val context = LocalContext.current
  val setup = state.setup
  val command = "adb shell pm grant ${context.packageName} ${DisplayController.PERMISSION}"
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Set up Pad Resolution", style = MaterialTheme.typography.titleLarge)
        Text(
          "Android only lets an app change the resolution after a permission is granted from a " +
            "computer. You do this once, and again if the app is reinstalled or the tablet is reset.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (state.shizuku != ShizukuState.NOT_INSTALLED) {
        ShizukuSetupOption(state, onSetUpWithShizuku, onOpenShizuku)
        Text("Or use a computer", style = MaterialTheme.typography.titleMedium)
      }
      SetupStep(
        number = 1,
        done = setup.developerOptions,
        title = "Turn on Developer options",
        description = "Open About tablet and tap OS version seven times.",
        actionLabel = "Open About tablet",
        onAction = { context.openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) },
      )
      SetupStep(
        number = 2,
        done = setup.usbDebugging,
        title = "Turn on USB debugging",
        description = "It is a switch in Developer options, under Additional settings.",
        // Developer options does not exist as a page until step 1 is done.
        actionLabel = "Open Developer options".takeIf { setup.developerOptions },
        onAction = { context.openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
      )
      SetupStep(
        number = 3,
        done = null,
        title = "Turn on USB debugging (Security settings)",
        description =
          "Also in Developer options. HyperOS asks you to sign in to a Mi account and accept " +
            "three warnings. Without it the tablet refuses the command in step 5.",
      )
      SetupStep(
        number = 4,
        done = setup.usbConnected,
        title = "Connect the tablet to a computer",
        description = "Use a USB cable that carries data, then tap Allow when the tablet asks about USB debugging.",
      )
      SetupStep(
        number = 5,
        done = false,
        title = "Run this command on the computer",
        description =
          "The computer needs Android platform-tools installed. This guide closes by itself " +
            "as soon as the command has worked.",
        actionLabel = "Copy command",
        onAction = {
          context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Setup command", command))
        },
      ) {
        SelectionContainer {
          Text(command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
      }
    }
  }
}

/**
 * Setup without a computer, offered when the Shizuku app is installed. It mostly runs by
 * itself: this only tells the user what is happening and what, if anything, they need to do.
 */
@Composable
private fun ShizukuSetupOption(state: MainUiState, onSetUpWithShizuku: () -> Unit, onOpenShizuku: () -> Unit) {
  val settingUp = "Shizuku is setting this up. No computer is needed."
  val (message, action) =
    when {
      state.shizukuBusy -> settingUp to null
      state.shizuku == ShizukuState.NOT_RUNNING ->
        "Shizuku is installed but not running. Start it and setup finishes by itself, with no computer." to
          ("Open Shizuku" to onOpenShizuku)
      state.shizuku == ShizukuState.NEEDS_ACCESS ->
        "Shizuku is running. Allow Pad Resolution in Shizuku and setup finishes by itself, with no computer." to
          ("Set up with Shizuku" to onSetUpWithShizuku)
      state.shizukuError != null ->
        "Shizuku could not finish setup. ${state.shizukuError}" to ("Try again" to onSetUpWithShizuku)
      else -> settingUp to null
    }
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
    }
  }
}

/** One step of the setup guide. [done] is null for a step the app has no way to check. */
@Composable
private fun SetupStep(
  number: Int,
  done: Boolean?,
  title: String,
  description: String,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
  extra: @Composable (() -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  val isDone = done == true
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Surface(
      modifier = Modifier.size(32.dp).clearAndSetSemantics {
        contentDescription = if (isDone) "Step $number, done" else "Step $number"
      },
      shape = CircleShape,
      color = if (isDone) colors.primary else colors.surfaceVariant,
      contentColor = if (isDone) colors.onPrimary else colors.onSurfaceVariant,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(if (isDone) "✓" else number.toString(), style = MaterialTheme.typography.labelLarge)
      }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        if (isDone) "$title · done" else title,
        style = MaterialTheme.typography.titleMedium,
        color = if (isDone) colors.onSurfaceVariant else colors.onSurface,
      )
      if (!isDone) {
        Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        extra?.invoke()
        if (actionLabel != null) {
          OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(actionLabel) }
        }
      }
    }
  }
}

private fun Context.openSettings(action: String) = open(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun Context.open(intent: Intent) {
  try {
    startActivity(intent)
  } catch (e: ActivityNotFoundException) {
    // This tablet has no such page; the text beside the button says where to find it by hand.
  }
}

@Composable
private fun ResolutionPicker(state: MainUiState, onSelectMode: (ResolutionMode) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Resolution", style = MaterialTheme.typography.titleMedium)
    BoxWithConstraints {
      val enabled = !state.busy && state.confirmSecondsLeft == null
      if (maxWidth >= 560.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          state.modes.forEach { mode ->
            ModeCard(mode, state, enabled, onSelectMode, Modifier.weight(1f))
          }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          state.modes.forEach { mode ->
            ModeCard(mode, state, enabled, onSelectMode, Modifier.fillMaxWidth())
          }
        }
      }
    }
    HomeScreenNote()
  }
}

/** Explains why home screen icons lag behind a switch, and offers the way to restart it. */
@Composable
private fun HomeScreenNote() {
  val context = LocalContext.current
  val appInfo = remember(context) { homeScreenAppInfoIntent(context) }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      if (appInfo != null) {
        "Apps and games use the new resolution straight away. Home screen icons keep their old " +
          "size until the home screen restarts: tap the button, then Force stop."
      } else {
        "Apps and games use the new resolution straight away. Home screen icons keep their old " +
          "size until the tablet restarts."
      },
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (appInfo != null) {
      OutlinedButton(onClick = { context.open(appInfo) }) { Text("Restart home screen") }
    }
  }
}

@Composable
private fun ModeCard(
  mode: ResolutionMode,
  state: MainUiState,
  enabled: Boolean,
  onSelectMode: (ResolutionMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val isSelected = mode == state.selectedMode
  val config = DisplayMath.configFor(mode, state.native)
  val colors = MaterialTheme.colorScheme
  OutlinedCard(
    onClick = { onSelectMode(mode) },
    enabled = enabled,
    modifier = modifier.semantics {
      selected = isSelected
      role = Role.RadioButton
    },
    colors = CardDefaults.outlinedCardColors(
      containerColor = if (isSelected) colors.primaryContainer else colors.surface
    ),
    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.primary else colors.outlineVariant),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(mode.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
      Text("${config.width} × ${config.height}", style = MaterialTheme.typography.bodyLarge)
      Text(mode.tagline, style = MaterialTheme.typography.bodyMedium)
      val percent = DisplayMath.pixelPercent(config, state.native)
      Text(
        if (mode.shortSide == null) "Tablet default" else "${100 - percent}% fewer pixels to draw",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun UiSizeCard(state: MainUiState, onUiScaleChange: (Float) -> Unit) {
  val stepCount =
    ((DisplayMath.MAX_UI_SCALE - DisplayMath.MIN_UI_SCALE) / DisplayMath.UI_SCALE_STEP).roundToInt() - 1
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Icon and text size", style = MaterialTheme.typography.titleMedium)
      Text(
        "${(state.uiScale * 100).roundToInt()}% · ${state.target.density} dpi",
        style = MaterialTheme.typography.titleMedium,
      )
    }
    Slider(
      value = state.uiScale,
      onValueChange = onUiScaleChange,
      valueRange = DisplayMath.MIN_UI_SCALE..DisplayMath.MAX_UI_SCALE,
      steps = stepCount,
      enabled = !state.busy && state.confirmSecondsLeft == null,
    )
    Text(
      if (DisplayMath.sameUiScale(state.uiScale, 1f)) {
        "100% keeps icons and text the same size as at 2K, at any resolution."
      } else if (state.uiScale < 1f) {
        "Smaller than standard: more fits on screen."
      } else {
        "Larger than standard: easier to read and tap."
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Actions(state: MainUiState, onApply: () -> Unit, onReset: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
    Button(onClick = onApply, enabled = state.canApply, modifier = Modifier.fillMaxWidth().height(56.dp)) {
      Text(
        when {
          state.busy -> "Changing display…"
          state.target == state.current -> "Already using ${describe(state.current)}"
          else -> "Apply ${describe(state.target)}"
        },
        style = MaterialTheme.typography.titleMedium,
      )
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onReset, enabled = state.canReset) { Text("Reset to tablet default") }
  }
}

private const val SETUP_CHECK_INTERVAL_MS = 1000L

private fun describe(config: DisplayConfig): String =
  "${config.width} × ${config.height} at ${config.density} dpi"
