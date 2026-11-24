package com.padtools.resolution.games

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Watches the user's games and puts the display back once the game that changed it has been
 * closed.
 *
 * It never changes the display while a game is open. A game cannot live through that: the
 * gesture fix-up makes Android rebuild every open screen, which closes most games, and
 * HyperOS itself closes a game left in the background when the resolution changes. So the
 * resolution is changed before the game starts (the Play button and the home screen
 * shortcuts) and held until the game is gone, however often the user steps out and back.
 *
 * A game opened from its own icon is therefore left alone, and the user is offered a restart
 * at its resolution instead.
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
  private lateinit var runningApps: RunningApps
  private lateinit var notifications: NotificationManager

  /** The user asked, from the notification, to go back without waiting for the game to close. */
  @Volatile private var returnNow = false

  override fun onCreate() {
    super.onCreate()
    store = GameStore(this)
    controller = DisplayController(this)
    switcher = GameSwitcher(controller, store)
    foregroundApp = ForegroundAppWatcher(this)
    runningApps = RunningApps(this)
    notifications = getSystemService(NotificationManager::class.java)
    // Not while a game holds the display: this fix-up is one of the things that closes games.
    if (!switcher.isSwitched) controller.restoreGesturesIfOwed()

    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Game switching", NotificationManager.IMPORTANCE_LOW)
    )
    notifications.createNotificationChannel(
      NotificationChannel(RESTART_CHANNEL_ID, "Game opened at the wrong resolution", NotificationManager.IMPORTANCE_HIGH)
    )
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

    scope.launch { watch() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_RETURN_NOW) returnNow = true
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    scope.cancel()
    notifications.cancel(RESTART_NOTIFICATION_ID)
    super.onDestroy()
  }

  private suspend fun watch() {
    val powerManager = getSystemService(PowerManager::class.java)
    // When every game was first seen closed; null while one is open or none has the display.
    var closedSince: Long? = null
    var appInFront: String? = null
    var inFrontSince = 0L
    var restartOfferedFor: String? = null
    var notificationSaysPlaying = switcher.isSwitched

    while (scope.isActive) {
      delay(CHECK_INTERVAL_MS)
      val games = store.games()
      if (!store.autoSwitch || games.isEmpty()) {
        switcher.leave()
        stopSelf()
        return
      }
      if (!powerManager.isInteractive) continue // Screen off: nothing can change.
      if (!foregroundApp.hasUsageAccess() || !controller.hasPermission()) continue

      val inFront = foregroundApp.current() ?: continue
      val now = SystemClock.elapsedRealtime()
      if (inFront != appInFront) {
        appInFront = inFront
        inFrontSince = now
      }
      val gameInFront = games.firstOrNull { it.packageName == inFront }

      // A game opened from its own icon is running at whatever the display was. Offer to
      // restart it rather than change the display under it.
      val needsRestart =
        gameInFront?.takeIf {
          !switcher.isAt(it.mode) && !switcher.isStartingGame && now - inFrontSince >= OFFER_AFTER_OPENING_MS
        }
      if (needsRestart?.packageName != restartOfferedFor) {
        restartOfferedFor = needsRestart?.packageName
        if (needsRestart != null) {
          notifications.notify(RESTART_NOTIFICATION_ID, restartOffer(needsRestart))
        } else {
          notifications.cancel(RESTART_NOTIFICATION_ID)
        }
      }

      val goBackNow = returnNow
      returnNow = false
      when {
        !switcher.isSwitched -> closedSince = null
        goBackNow -> {
          switcher.leave()
          closedSince = null
        }
        // If Android will not say whether a game is still open, having left it counts as closed.
        gameInFront != null || switcher.isStartingGame || games.any { runningApps.isRunning(it.packageName) == true } ->
          closedSince = null
        else -> {
          val since = closedSince ?: now.also { closedSince = it }
          if (now - since >= RETURN_AFTER_CLOSING_MS && switcher.leave().isSuccess) closedSince = null
        }
      }

      // Rewrite the notification only when what it says has changed.
      if (switcher.isSwitched != notificationSaysPlaying) {
        notificationSaysPlaying = switcher.isSwitched
        notifications.notify(NOTIFICATION_ID, notification())
      }
    }
  }

  private fun notification(): Notification {
    val openApp =
      PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val builder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_resolution)
        .setContentTitle("Pad Resolution")
        .setContentIntent(openApp)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
    if (::switcher.isInitialized && switcher.isSwitched) {
      val now = controller.currentConfig()
      val goBack =
        PendingIntent.getService(
          this,
          0,
          Intent(this, GameWatchService::class.java).setAction(ACTION_RETURN_NOW),
          PendingIntent.FLAG_IMMUTABLE,
        )
      builder
        .setContentText("Staying at ${now.width} × ${now.height} until you close the game.")
        .addAction(0, "Go back now (closes the game)", goBack)
    } else {
      builder.setContentText("Watching for your games.")
    }
    return builder.build()
  }

  private fun restartOffer(game: GameProfile): Notification {
    val now = controller.currentConfig()
    val restart =
      PendingIntent.getActivity(
        this,
        game.packageName.hashCode(),
        MainActivity.playIntent(this, game.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, RESTART_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_resolution)
      .setContentTitle("${appLabel(this, game.packageName)} opened at ${now.width} × ${now.height}")
      .setContentText("Tap to restart it at ${game.mode.label}.")
      .setContentIntent(restart)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "game_switching"
    private const val RESTART_CHANNEL_ID = "game_restart"
    private const val NOTIFICATION_ID = 1
    private const val RESTART_NOTIFICATION_ID = 2
    private const val ACTION_RETURN_NOW = "com.padtools.resolution.RETURN_NOW"
    private const val CHECK_INTERVAL_MS = 1000L
    private const val OFFER_AFTER_OPENING_MS = 2_000L
    private const val RETURN_AFTER_CLOSING_MS = 3_000L

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
