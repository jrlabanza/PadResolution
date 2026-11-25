package com.padtools.resolution.tile

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.padtools.resolution.MainActivity
import com.padtools.resolution.R
import com.padtools.resolution.display.DisplayController
import com.padtools.resolution.display.DisplayMath
import com.padtools.resolution.display.ResolutionMode

/**
 * The Quick Settings tile. It shows the current resolution, and a tap opens a short list to
 * pick another, so the user can switch from the pull-down shade without opening the app.
 *
 * A list rather than "tap for the next one": every switch takes a few seconds and rearranges
 * the screen, so passing through a resolution the user did not want is worth avoiding.
 */
class ResolutionTileService : TileService() {
  private val controller by lazy { DisplayController(this) }

  override fun onTileAdded() = rememberAdded(this, true)

  override fun onTileRemoved() = rememberAdded(this, false)

  override fun onStartListening() {
    rememberAdded(this, true) // Covers a tile added before the app kept track.
    showCurrent()
  }

  override fun onClick() {
    // Without the permission there is nothing to choose; the app's guide explains setup.
    val screen = if (controller.hasPermission()) ResolutionChooserActivity::class.java else MainActivity::class.java
    val open = Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(PendingIntent.getActivity(this, screen.hashCode(), open, PendingIntent.FLAG_IMMUTABLE))
    } else {
      @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(open)
    }
  }

  private fun showCurrent() {
    val tile = qsTile ?: return
    val native = controller.nativeConfig()
    val current = controller.currentConfig()
    val mode = DisplayMath.modeOf(current, native)
    val name = mode?.label ?: "${current.width} × ${current.height}"
    tile.state =
      when {
        !controller.hasPermission() -> Tile.STATE_UNAVAILABLE
        mode == ResolutionMode.NATIVE -> Tile.STATE_INACTIVE
        else -> Tile.STATE_ACTIVE // Lit while the tablet is below its own resolution.
      }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      tile.label = TILE_LABEL
      tile.subtitle = if (controller.hasPermission()) name else "Finish setup in the app"
    } else {
      tile.label = name
    }
    tile.updateTile()
  }

  companion object {
    private const val TILE_LABEL = "Resolution"
    private const val PREFS = "tile"
    private const val KEY_ADDED = "added"

    /** Has the tile redrawn, after the resolution changed while the shade was closed. */
    fun refresh(context: Context) {
      requestListeningState(context, ComponentName(context, ResolutionTileService::class.java))
    }

    /**
     * Whether the tile is in the user's Quick Settings. Android only says so at the moment it
     * is added or removed, so the answer is kept on disk.
     */
    fun isAdded(context: Context): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADDED, false)

    private fun rememberAdded(context: Context, added: Boolean) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ADDED, added).apply()
    }

    /** Whether this Android version can offer the tile to the user with its own prompt. */
    val canOfferTile: Boolean
      get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Shows Android's "add this tile?" prompt. [onResult] gets true when the tile is now in
     * Quick Settings, or was there already.
     */
    fun offerTile(context: Context, onResult: (added: Boolean) -> Unit) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onResult(false)
      context
        .getSystemService(StatusBarManager::class.java)
        .requestAddTileService(
          ComponentName(context, ResolutionTileService::class.java),
          TILE_LABEL,
          Icon.createWithResource(context, R.drawable.ic_stat_resolution),
          context.mainExecutor,
        ) { result ->
          val added =
            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
              result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
          if (added) rememberAdded(context, true)
          onResult(added)
        }
    }
  }
}
