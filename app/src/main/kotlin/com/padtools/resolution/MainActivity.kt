package com.padtools.resolution

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.padtools.resolution.ui.main.MainScreen
import com.padtools.resolution.ui.main.MainScreenViewModel
import com.padtools.resolution.ui.theme.PadResolutionTheme

class MainActivity : ComponentActivity() {
  private val viewModel: MainScreenViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent { PadResolutionTheme { MainScreen(viewModel) } }

    // Only on a fresh start: a display change rebuilds this screen with the same intent, and
    // the game must not be started a second time.
    if (savedInstanceState == null) playRequestedGame(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    playRequestedGame(intent)
  }

  private fun playRequestedGame(intent: Intent) {
    if (intent.action != ACTION_PLAY) return
    intent.getStringExtra(EXTRA_GAME)?.let(viewModel::playGame)
  }

  companion object {
    private const val ACTION_PLAY = "com.padtools.resolution.PLAY"
    private const val EXTRA_GAME = "game"

    /**
     * Opens the app to switch to [packageName]'s resolution and then start it. Used by the
     * home screen shortcuts and by the watcher's "restart at the right resolution" offer.
     */
    fun playIntent(context: Context, packageName: String): Intent =
      Intent(context, MainActivity::class.java)
        .setAction(ACTION_PLAY)
        .putExtra(EXTRA_GAME, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
}
