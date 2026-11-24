package com.padtools.resolution.games

import android.os.SystemClock
import com.padtools.resolution.display.DisplayController
import com.padtools.resolution.display.DisplayMath
import com.padtools.resolution.display.ResolutionMode

/**
 * Changes the display for a game and puts it back afterwards. Used both by the background
 * watcher and by the Play button, so they agree on what "back" means.
 */
class GameSwitcher(private val controller: DisplayController, private val store: GameStore) {

  /** Whether a game currently has the display, i.e. there is something to put back. */
  val isSwitched: Boolean
    get() = store.displayBeforeGame != null

  /**
   * Whether a game was switched for moments ago and may not have opened yet. Until it has,
   * "no game is open" does not mean the user has finished playing.
   */
  val isStartingGame: Boolean
    get() = SystemClock.elapsedRealtime() - lastEnterAt < GAME_START_GRACE_MS

  /** Whether the display is already at [mode]'s resolution. */
  fun isAt(mode: ResolutionMode): Boolean {
    val target = DisplayMath.configFor(mode, controller.nativeConfig())
    val current = controller.currentConfig()
    return current.width == target.width && current.height == target.height
  }

  /** Switches to [mode] at standard icon and text size, remembering what to go back to. */
  suspend fun enter(mode: ResolutionMode): Result<Unit> {
    lastEnterAt = SystemClock.elapsedRealtime()
    if (isAt(mode)) return Result.success(Unit)
    // Going from one game straight into another keeps the original settings to return to.
    if (store.displayBeforeGame == null) store.displayBeforeGame = controller.currentConfig()
    val result = controller.apply(DisplayMath.configFor(mode, controller.nativeConfig()))
    lastEnterAt = SystemClock.elapsedRealtime() // The change itself takes several seconds.
    return result
  }

  /** Puts back the display from before the game. Does nothing if no game changed it. */
  suspend fun leave(): Result<Unit> {
    val before = store.displayBeforeGame ?: return Result.success(Unit)
    val result = controller.apply(before)
    if (result.isSuccess) store.displayBeforeGame = null
    return result
  }

  private companion object {
    const val GAME_START_GRACE_MS = 15_000L

    // Shared by the screen's switcher and the watcher's, which live in the same process.
    @Volatile var lastEnterAt = -GAME_START_GRACE_MS
  }
}
