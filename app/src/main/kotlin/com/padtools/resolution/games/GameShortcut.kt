package com.padtools.resolution.games

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.padtools.resolution.MainActivity

/**
 * Asks the home screen to add an icon that switches to [packageName]'s resolution and then
 * starts it, so the user does not have to open this app to play. Returns false when the home
 * screen does not take such requests.
 */
fun requestGameShortcut(context: Context, packageName: String): Boolean {
  if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
  val shortcut =
    ShortcutInfoCompat.Builder(context, "game:$packageName")
      .setShortLabel(appLabel(context, packageName))
      .setIntent(MainActivity.playIntent(context, packageName))
  try {
    val icon = context.packageManager.getApplicationIcon(packageName).toBitmap(SHORTCUT_ICON_PX, SHORTCUT_ICON_PX)
    shortcut.setIcon(IconCompat.createWithBitmap(icon))
  } catch (e: PackageManager.NameNotFoundException) {
    return false // Uninstalled a moment ago.
  }
  return ShortcutManagerCompat.requestPinShortcut(context, shortcut.build(), null)
}

/** Whether the home screen has added [packageName]'s shortcut. */
fun hasGameShortcut(context: Context, packageName: String): Boolean =
  ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).any {
    it.id == "game:$packageName"
  }

private const val SHORTCUT_ICON_PX = 192
