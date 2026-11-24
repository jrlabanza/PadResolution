package com.padtools.resolution.games

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Tells which app is on screen, from Android's usage records.
 *
 * This needs the "Usage access" permission, which the user switches on in Settings. Android
 * offers ordinary apps no other way to learn which app is in front.
 */
class ForegroundAppWatcher(private val context: Context) {
  private val usageStats = context.getSystemService(UsageStatsManager::class.java)
  private var lastCheck = System.currentTimeMillis() - FIRST_LOOK_BACK_MS
  private var foreground: String? = null

  fun hasUsageAccess(): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    val op = AppOpsManager.OPSTR_GET_USAGE_STATS
    val mode =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
      } else {
        @Suppress("DEPRECATION") appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
      }
    return mode == AppOpsManager.MODE_ALLOWED
  }

  /**
   * The package of the app in front, or null if it is not known yet. Reads the usage records
   * added since the last call, so it is cheap to call every second.
   */
  fun current(): String? {
    val now = System.currentTimeMillis()
    val events = usageStats.queryEvents(lastCheck, now)
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      // Records arrive oldest first, so the last "came to the front" wins.
      if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.packageName
    }
    // Overlap slightly: a record can be written a moment after the time it carries.
    lastCheck = now - OVERLAP_MS
    return foreground
  }

  private companion object {
    /** How far back the first call looks, to find an app that was opened before watching began. */
    const val FIRST_LOOK_BACK_MS = 60 * 60 * 1000L
    const val OVERLAP_MS = 1500L
  }
}
