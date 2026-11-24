package com.padtools.resolution.games

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Tells whether an app is still open, meaning Android still has a process for it, whether or
 * not it is on screen. Allowed by the same Usage access that tells the app what is in front.
 */
class RunningApps(context: Context) {
  private val activityManager = context.getSystemService(ActivityManager::class.java)

  // Android offers this to system apps and to apps with Usage access, but leaves it out of
  // the public SDK, so it has to be looked up by name.
  private val packageImportance: Method? =
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityManager;")
      }
      ActivityManager::class.java.getMethod("getPackageImportance", String::class.java)
    } catch (e: ReflectiveOperationException) {
      null
    }

  /** Whether [packageName] has a process; null when Android will not say. */
  fun isRunning(packageName: String): Boolean? =
    try {
      (packageImportance?.invoke(activityManager, packageName) as? Int)?.let { it < IMPORTANCE_GONE }
    } catch (e: ReflectiveOperationException) {
      null // Usage access is off, or this Android version has dropped the call.
    }

  /** Asks Android to close [packageName]. Android only does so while the app is in the background. */
  fun close(packageName: String) {
    activityManager.killBackgroundProcesses(packageName)
  }

  private companion object {
    const val IMPORTANCE_GONE = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
  }
}
