package com.padtools.resolution.games

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

  /** Switches to [mode] at standard icon and text size, remembering what to go back to. */
  suspend fun enter(mode: ResolutionMode): Result<Unit> {
    val target = DisplayMath.configFor(mode, controller.nativeConfig())
    val current = controller.currentConfig()
    if (current.width == target.width && current.height == target.height) return Result.success(Unit)
    // Going from one game straight into another keeps the original settings to return to.
    if (store.displayBeforeGame == null) store.displayBeforeGame = current
    return controller.apply(target)
  }

  /** Puts back the display from before the game. Does nothing if no game changed it. */
  suspend fun leave(): Result<Unit> {
    val before = store.displayBeforeGame ?: return Result.success(Unit)
    val result = controller.apply(before)
    if (result.isSuccess) store.displayBeforeGame = null
    return result
  }
}
