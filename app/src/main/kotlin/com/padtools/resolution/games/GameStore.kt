package com.padtools.resolution.games

import android.content.Context
import com.padtools.resolution.display.DisplayConfig
import com.padtools.resolution.display.ResolutionMode

/** A game the user picked, and the resolution to play it at. */
data class GameProfile(val packageName: String, val mode: ResolutionMode)

/**
 * The user's games, whether automatic switching is on, and, while a game has changed the
 * display, the settings to go back to afterwards.
 */
class GameStore(context: Context) {
  private val prefs = context.getSharedPreferences("games", Context.MODE_PRIVATE)

  var autoSwitch: Boolean
    get() = prefs.getBoolean(KEY_AUTO_SWITCH, false)
    set(value) = prefs.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()

  fun games(): List<GameProfile> =
    prefs.all.mapNotNull { (key, value) ->
      if (!key.startsWith(GAME_PREFIX)) return@mapNotNull null
      val mode = ResolutionMode.entries.firstOrNull { it.name == value } ?: return@mapNotNull null
      GameProfile(key.removePrefix(GAME_PREFIX), mode)
    }

  fun modeFor(packageName: String): ResolutionMode? {
    val saved = prefs.getString(GAME_PREFIX + packageName, null) ?: return null
    return ResolutionMode.entries.firstOrNull { it.name == saved }
  }

  fun setGame(packageName: String, mode: ResolutionMode) {
    prefs.edit().putString(GAME_PREFIX + packageName, mode.name).apply()
  }

  fun removeGame(packageName: String) {
    prefs.edit().remove(GAME_PREFIX + packageName).apply()
  }

  /**
   * The display settings from before a game changed them; null when no game has. Written to
   * disk straight away so they survive the app being closed in the middle of a game.
   */
  var displayBeforeGame: DisplayConfig?
    get() {
      if (!prefs.contains(KEY_BEFORE_WIDTH)) return null
      return DisplayConfig(
        width = prefs.getInt(KEY_BEFORE_WIDTH, 0),
        height = prefs.getInt(KEY_BEFORE_HEIGHT, 0),
        density = prefs.getInt(KEY_BEFORE_DENSITY, 0),
      )
    }
    set(value) {
      val editor = prefs.edit()
      if (value == null) {
        editor.remove(KEY_BEFORE_WIDTH).remove(KEY_BEFORE_HEIGHT).remove(KEY_BEFORE_DENSITY)
      } else {
        editor
          .putInt(KEY_BEFORE_WIDTH, value.width)
          .putInt(KEY_BEFORE_HEIGHT, value.height)
          .putInt(KEY_BEFORE_DENSITY, value.density)
      }
      editor.commit()
    }

  private companion object {
    const val KEY_AUTO_SWITCH = "auto_switch"
    const val GAME_PREFIX = "game:"
    const val KEY_BEFORE_WIDTH = "before_width"
    const val KEY_BEFORE_HEIGHT = "before_height"
    const val KEY_BEFORE_DENSITY = "before_density"
  }
}
