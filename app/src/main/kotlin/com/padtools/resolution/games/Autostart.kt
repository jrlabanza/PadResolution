package com.padtools.resolution.games

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** HyperOS's own number for its "Autostart" switch, which Android itself knows nothing of. */
private const val OP_AUTOSTART = 10008

/**
 * Whether HyperOS lets the app start by itself: its "Autostart" switch on the App info page.
 * With it off, nothing can bring the game watcher back after HyperOS closes the app, not a
 * restart of the tablet and not the watcher's own alarm.
 *
 * Null on a tablet that has no such switch, or will not say.
 */
fun autostartAllowed(context: Context): Boolean? =
  try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      HiddenApiBypass.addHiddenApiExemptions("Landroid/app/AppOpsManager;")
    }
    val check =
      AppOpsManager::class
        .java
        .getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
    val mode = check.invoke(context.getSystemService(AppOpsManager::class.java), OP_AUTOSTART, Process.myUid(), context.packageName)
    (mode as? Int)?.let { it == AppOpsManager.MODE_ALLOWED }
  } catch (e: ReflectiveOperationException) {
    null // Not HyperOS: Android rejects a number it does not know.
  } catch (e: RuntimeException) {
    null
  }
