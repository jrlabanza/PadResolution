package com.padtools.resolution.display

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * An intent that opens the system's App info page for the home screen app, or null when the
 * home screen app cannot be identified.
 *
 * The HyperOS launcher fixes its icon sizes when it starts, so after a resolution change they
 * stay wrong until it restarts. Android does not let one app stop another, but the App info
 * page has a Force stop button the user can tap; Android then starts the launcher again.
 */
fun homeScreenAppInfoIntent(context: Context): Intent? {
  val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
  val packageName =
    context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
  // "android" is the chooser Android shows when no default home screen app is set.
  if (packageName == null || packageName == "android" || packageName == context.packageName) return null
  return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
