package com.padtools.resolution.games

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.padtools.resolution.MainActivity
import com.padtools.resolution.R
import com.padtools.resolution.display.DisplayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches which app is on screen and switches the display when one of the user's games comes
 * to the front, then puts it back when they leave.
 *
 * It has to be a foreground service, with its permanent notification, or Android would stop
 * it within minutes of the app being closed.
 */
class GameWatchService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var store: GameStore
  private lateinit var controller: DisplayController
  private lateinit var switcher: GameSwitcher
  private lateinit var foregroundApp: ForegroundAppWatcher

  override fun onCreate() {
    super.onCreate()
    store = GameStore(this)
    controller = DisplayController(this)
    switcher = GameSwitcher(controller, store)
    foregroundApp = ForegroundAppWatcher(this)
    controller.restoreGesturesIfOwed()

    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Game switching", NotificationManager.IMPORTANCE_LOW)
      )
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

    scope.launch { watch() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private suspend fun watch() {
    val powerManager = getSystemService(PowerManager::class.java)
    val homeScreen = homeScreenPackage()
    // When a game was last seen in front; null while one is, or when none has the display.
    var leftGameAt: Long? = null
    var appInFront: String? = null
    var inFrontSince = 0L
    var notificationSaysPlaying = switcher.isSwitched

    while (scope.isActive) {
      delay(CHECK_INTERVAL_MS)
      if (!store.autoSwitch || store.games().isEmpty()) {
        switcher.leave()
        stopSelf()
        return
      }
      if (!powerManager.isInteractive) continue // Screen off: nothing can change.
      if (!foregroundApp.hasUsageAccess() || !controller.hasPermission()) continue

      val inFront = foregroundApp.current() ?: continue
      val gameMode = store.modeFor(inFront)
      val now = SystemClock.elapsedRealtime()
      if (inFront != appInFront) {
        appInFront = inFront
        inFrontSince = now
      }
      when {
        gameMode != null -> {
          leftGameAt = null
          // Changing the resolution while the launcher is still animating the game open
          // leaves its swipe-up area in the wrong place, and no later fix-up corrects it.
          // Wait for the animation to be well over.
          if (now - inFrontSince >= SWITCH_AFTER_OPENING_MS) switcher.enter(gameMode)
        }
        switcher.isSwitched -> {
          val since = leftGameAt ?: now.also { leftGameAt = it }
          // Going home is a clear "I'm done". Anything else may be a sign-in page or a quick
          // reply, so wait longer before changing the screen under the user.
          val patience = if (inFront == homeScreen) RETURN_AFTER_HOME_MS else RETURN_AFTER_OTHER_APP_MS
          if (now - since >= patience && switcher.leave().isSuccess) leftGameAt = null
        }
        else -> leftGameAt = null
      }

      // Rewrite the notification only when what it says has changed.
      if (switcher.isSwitched != notificationSaysPlaying) {
        notificationSaysPlaying = switcher.isSwitched
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
      }
    }
  }

  private fun notification(): Notification {
    val openApp =
      PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val text =
      if (::switcher.isInitialized && switcher.isSwitched) {
        val now = controller.currentConfig()
        "Playing at ${now.width} × ${now.height}. Goes back when you leave the game."
      } else {
        "Watching for your games."
      }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_resolution)
      .setContentTitle("Pad Resolution")
      .setContentText(text)
      .setContentIntent(openApp)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private fun homeScreenPackage(): String? {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
  }

  companion object {
    private const val CHANNEL_ID = "game_switching"
    private const val NOTIFICATION_ID = 1
    private const val CHECK_INTERVAL_MS = 1000L
    private const val SWITCH_AFTER_OPENING_MS = 2_500L
    private const val RETURN_AFTER_HOME_MS = 2_000L
    private const val RETURN_AFTER_OTHER_APP_MS = 10_000L

    /** Starts the watcher if the user has switched it on and picked at least one game. */
    fun startIfWanted(context: Context) {
      val store = GameStore(context)
      if (!store.autoSwitch || store.games().isEmpty()) return
      ContextCompat.startForegroundService(context, Intent(context, GameWatchService::class.java))
    }
  }
}

/** Starts the watcher again after the tablet restarts. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) GameWatchService.startIfWanted(context)
  }
}
