package com.padtools.resolution.display

import android.content.Context

/**
 * Remembers the display settings to go back to while a change is waiting to be confirmed.
 *
 * It is written to disk before the display changes, so if the app is closed or killed before
 * the user confirms, the next launch can still put the old settings back.
 */
class PendingRevertStore(context: Context) {
  private val prefs = context.getSharedPreferences("pending_revert", Context.MODE_PRIVATE)

  fun save(previous: DisplayConfig) {
    prefs.edit()
      .putInt(KEY_WIDTH, previous.width)
      .putInt(KEY_HEIGHT, previous.height)
      .putInt(KEY_DENSITY, previous.density)
      .commit()
  }

  fun load(): DisplayConfig? {
    if (!prefs.contains(KEY_WIDTH)) return null
    return DisplayConfig(
      width = prefs.getInt(KEY_WIDTH, 0),
      height = prefs.getInt(KEY_HEIGHT, 0),
      density = prefs.getInt(KEY_DENSITY, 0),
    )
  }

  fun clear() {
    prefs.edit().clear().commit()
  }

  private companion object {
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_DENSITY = "density"
  }
}
