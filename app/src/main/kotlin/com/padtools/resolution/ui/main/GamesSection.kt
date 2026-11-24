package com.padtools.resolution.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.padtools.resolution.display.DisplayMath
import com.padtools.resolution.display.ResolutionMode
import com.padtools.resolution.games.InstalledApp
import com.padtools.resolution.games.appIcon

/** What the Games section can ask the rest of the app to do. */
internal class GameActions(
  val setAutoSwitch: (Boolean) -> Unit,
  val openUsageAccess: () -> Unit,
  val allowBackground: () -> Unit,
  val openAppInfo: () -> Unit,
  val openPicker: () -> Unit,
  val closePicker: () -> Unit,
  val add: (packageName: String) -> Unit,
  val setMode: (packageName: String, ResolutionMode) -> Unit,
  val remove: (packageName: String) -> Unit,
  val addShortcut: (packageName: String) -> Unit,
  val closeShortcutHelp: () -> Unit,
  val play: (packageName: String) -> Unit,
)

/** The user's games: which resolution each plays at, and the switch that makes it automatic. */
@Composable
internal fun GamesSection(state: MainUiState, actions: GameActions) {
  val secondary = MaterialTheme.colorScheme.onSurfaceVariant
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Switch back when the game closes", style = MaterialTheme.typography.titleMedium)
        Text(
          "Start a game with Play, or with its home screen shortcut, and it opens at its lower " +
            "resolution. The tablet stays there while the game is open, even if you step out " +
            "for a moment, and goes back once you close the game.",
          style = MaterialTheme.typography.bodyMedium,
          color = secondary,
        )
      }
      Switch(
        checked = state.autoSwitch,
        onCheckedChange = actions.setAutoSwitch,
        modifier = Modifier.semantics { contentDescription = "Switch back when the game closes" },
      )
    }

    if (state.autoSwitch && !state.usageAccess) {
      NoteWithButton(
        text = "Turn on Usage access for Pad Resolution, so it can tell when a game is open.",
        button = "Open Usage access",
        onClick = actions.openUsageAccess,
      )
    }

    if (state.autoSwitch && !state.backgroundAllowed) {
      NoteWithButton(
        text =
          "HyperOS pauses apps a few seconds after you leave them, and then Pad Resolution " +
            "cannot see your games open. On the battery page, choose No restrictions.",
        button = "Open battery page",
        onClick = actions.allowBackground,
      )
    }

    state.games.forEach { game -> GameCard(game, state, actions) }

    OutlinedButton(onClick = actions.openPicker) { Text("Add a game") }

    if (state.games.isNotEmpty()) {
      Text(
        "A game opened from its own icon keeps whatever resolution the tablet is at. Changing " +
          "the resolution while a game is open makes the game restart, so it is only changed " +
          "before the game starts.",
        style = MaterialTheme.typography.bodyMedium,
        color = secondary,
      )
    }

    if (state.autoSwitch && state.games.isNotEmpty()) {
      NoteWithButton(
        text =
          "After the tablet restarts, or after you clear all recent apps, open Pad Resolution " +
            "once to start watching again, or allow Autostart for it so that happens by itself.",
        button = "Open App info",
        onClick = actions.openAppInfo,
      )
    }
  }

  state.gamePicker?.let { apps -> GamePicker(apps, onPick = actions.add, onClose = actions.closePicker) }

  if (state.shortcutBlocked) {
    AlertDialog(
      onDismissRequest = actions.closeShortcutHelp,
      title = { Text("Shortcut not added") },
      text = {
        Text(
          "HyperOS blocks home screen shortcuts until you allow them. Open App info, then " +
            "Other permissions, and allow Home screen shortcuts. Then tap Add to home screen again."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            actions.closeShortcutHelp()
            actions.openAppInfo()
          }
        ) {
          Text("Open App info")
        }
      },
      dismissButton = { TextButton(onClick = actions.closeShortcutHelp) { Text("Close") } },
    )
  }
}

@Composable
private fun GameCard(game: GameRow, state: MainUiState, actions: GameActions) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AppIcon(game.packageName)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(game.label, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          GAME_MODES.forEach { mode ->
            val size = DisplayMath.configFor(mode, state.native)
            FilterChip(
              selected = game.mode == mode,
              onClick = { actions.setMode(game.packageName, mode) },
              label = { Text("${mode.label} · ${size.width} × ${size.height}") },
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { actions.addShortcut(game.packageName) }) { Text("Add to home screen") }
          TextButton(onClick = { actions.remove(game.packageName) }) { Text("Remove") }
        }
      }
      Button(onClick = { actions.play(game.packageName) }, enabled = state.hasPermission && !state.busy) {
        Text("Play")
      }
    }
  }
}

@Composable
private fun GamePicker(apps: List<InstalledApp>, onPick: (String) -> Unit, onClose: () -> Unit) {
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text("Add a game") },
    text = {
      if (apps.isEmpty()) {
        Text("Every app on this tablet is already in your list.")
      } else {
        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
          items(apps, key = { it.packageName }) { app ->
            Row(
              modifier = Modifier.fillMaxWidth().clickable { onPick(app.packageName) }.padding(vertical = 10.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              AppIcon(app.packageName)
              Column {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                if (app.isGame) {
                  Text(
                    "Game",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
  )
}

@Composable
private fun AppIcon(packageName: String) {
  val context = LocalContext.current
  val icon = remember(packageName) { appIcon(context, packageName) }
  Box(Modifier.size(40.dp)) {
    // Decorative: the app's name is always written beside it.
    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
  }
}

@Composable
private fun NoteWithButton(text: String, button: String, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onClick) { Text(button) }
  }
}

/** A game never needs the panel's own resolution: that is what you get by not adding it. */
private val GAME_MODES = listOf(ResolutionMode.P1200, ResolutionMode.P800)
