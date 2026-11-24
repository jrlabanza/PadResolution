package com.padtools.resolution.games

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An app the user could add as a game. [isGame] is what the app itself declares. */
data class InstalledApp(val packageName: String, val label: String, val isGame: Boolean)

/** Every app with a home screen icon, games first, then by name. */
fun launchableApps(context: Context): List<InstalledApp> {
  val packageManager = context.packageManager
  val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return packageManager
    .queryIntentActivities(launcher, 0)
    .map { it.activityInfo.applicationInfo }
    .distinctBy { it.packageName }
    .filter { it.packageName != context.packageName }
    .map { info ->
      InstalledApp(
        packageName = info.packageName,
        label = packageManager.getApplicationLabel(info).toString(),
        isGame = info.category == ApplicationInfo.CATEGORY_GAME,
      )
    }
    .sortedWith(compareByDescending<InstalledApp> { it.isGame }.thenBy { it.label.lowercase() })
}

/** The app's name as shown on the home screen, or its package name if it has been uninstalled. */
fun appLabel(context: Context, packageName: String): String =
  try {
    val packageManager = context.packageManager
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (e: PackageManager.NameNotFoundException) {
    packageName
  }

fun appIcon(context: Context, packageName: String): ImageBitmap? =
  try {
    context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
  } catch (e: PackageManager.NameNotFoundException) {
    null
  }

fun launchIntent(context: Context, packageName: String): Intent? =
  context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private const val ICON_PX = 96
