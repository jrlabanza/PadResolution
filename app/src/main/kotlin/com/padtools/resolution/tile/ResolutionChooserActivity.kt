package com.padtools.resolution.tile

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padtools.resolution.display.DisplayController
import com.padtools.resolution.display.DisplayMath
import com.padtools.resolution.display.ResolutionMode
import com.padtools.resolution.games.GameStore
import com.padtools.resolution.games.RunningApps
import com.padtools.resolution.games.appLabel
import com.padtools.resolution.ui.theme.PadResolutionTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The list the Quick Settings tile opens: pick a resolution, watch it switch, done.
 *
 * It is a screen of its own, though all it shows is a dialog, because a display change only
 * goes through reliably while the app is in front. From the tile's background service
 * HyperOS stops telling the app about the display, and the change undoes itself.
 */
class ResolutionChooserActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A fresh open starts at the list, unless a switch from a moment ago is still running.
    if (savedInstanceState == null && progress.value is Progress.Done) progress.value = Progress.Choosing

    setContent {
      PadResolutionTheme {
        val state by progress.collectAsStateWithLifecycle()
        when (val now = state) {
          Progress.Choosing -> Chooser(onPick = { switchTo(applicationContext, it) }, onClose = ::finish)
          is Progress.Switching -> SwitchingNotice(now.mode)
          is Progress.Done ->
            LaunchedEffect(now) {
              now.error?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
              progress.value = Progress.Choosing
              finish()
            }
        }
      }
    }
  }

  private sealed interface Progress {
    data object Choosing : Progress

    data class Switching(val mode: ResolutionMode) : Progress

    data class Done(val error: String?) : Progress
  }

  private companion object {
    // Both outlive the screen: the gesture fix-up rebuilds every open screen, this one
    // included, and the rebuilt one has to pick up where the first left off.
    val changes = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val progress = MutableStateFlow<Progress>(Progress.Choosing)

    fun switchTo(context: Context, mode: ResolutionMode) {
      if (progress.value is Progress.Switching) return
      progress.value = Progress.Switching(mode)
      val controller = DisplayController(context)
      val native = controller.nativeConfig()
      val previous = controller.currentConfig()
      // Keep the icon and text size the user chose in the app.
      val target = DisplayMath.configFor(mode, native, DisplayMath.uiScaleOf(previous, native))
      // A change made by hand is the user taking over: the game watcher must not undo it later.
      GameStore(context).displayBeforeGame = null
      changes.launch {
        val result = controller.apply(target)
        // The size may have changed before the density failed, so put both back.
        if (result.isFailure) controller.apply(previous)
        ResolutionTileService.refresh(context)
        progress.value = Progress.Done(result.exceptionOrNull()?.message)
      }
    }
  }
}

@Composable
private fun Chooser(onPick: (ResolutionMode) -> Unit, onClose: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val controller = remember { DisplayController(context) }
  val native = remember { controller.nativeConfig() }
  val current = remember { DisplayMath.modeOf(controller.currentConfig(), native) }
  // No game survives a switch, so say which one it will close.
  val openGame = remember {
    val runningApps = RunningApps(context)
    GameStore(context).games().firstOrNull { runningApps.isRunning(it.packageName) == true }?.let {
      appLabel(context, it.packageName)
    }
  }

  AlertDialog(
    onDismissRequest = onClose,
    title = { Text("Resolution") },
    text = {
      Column(Modifier.selectableGroup()) {
        DisplayMath.availableModes(native).forEach { mode ->
          val size = DisplayMath.configFor(mode, native)
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .selectable(
                  selected = mode == current,
                  role = Role.RadioButton,
                  onClick = { if (mode == current) onClose() else onPick(mode) },
                )
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = mode == current, onClick = null)
            Text("${mode.label} · ${size.width} × ${size.height}", style = MaterialTheme.typography.bodyLarge)
          }
        }
        if (openGame != null) {
          Text(
            "$openGame is open and will close.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
  )
}

@Composable
private fun SwitchingNotice(mode: ResolutionMode) {
  AlertDialog(
    onDismissRequest = {}, // Stays until the switch has finished.
    text = {
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text("Switching to ${mode.label}…", style = MaterialTheme.typography.bodyLarge)
      }
    },
    confirmButton = {},
  )
}
