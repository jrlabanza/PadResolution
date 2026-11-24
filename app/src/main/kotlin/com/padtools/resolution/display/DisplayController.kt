package com.padtools.resolution.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass

class DisplayChangeException(message: String) : Exception(message)

/** Reads the tablet's display size and density, and changes them through the window manager. */
class DisplayController(private val context: Context) {

  private val windowManager by lazy { WindowManagerBridge() }
  private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)

  private val display: Display
    get() = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

  fun hasPermission(): Boolean =
    context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

  /** The panel's own size and the density the tablet ships with. */
  fun nativeConfig(): DisplayConfig {
    val mode = display.mode
    return DisplayConfig(mode.physicalWidth, mode.physicalHeight, DisplayMetrics.DENSITY_DEVICE_STABLE)
  }

  /** The size and density in use right now, in the same orientation as [nativeConfig]. */
  fun currentConfig(): DisplayConfig {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") // Still the only public way to read the live size and density together.
    display.getRealMetrics(metrics)
    val shortSide = min(metrics.widthPixels, metrics.heightPixels)
    val longSide = max(metrics.widthPixels, metrics.heightPixels)
    val native = nativeConfig()
    return if (native.width <= native.height) {
      DisplayConfig(shortSide, longSide, metrics.densityDpi)
    } else {
      DisplayConfig(longSide, shortSide, metrics.densityDpi)
    }
  }

  /** Switches the display to [target] and waits until the system reports the new values. */
  suspend fun apply(target: DisplayConfig): Result<Unit> =
    withContext(Dispatchers.IO) {
      // The screen and the background game watcher can both ask for a change; one at a time.
      changeLock.withLock { applyLocked(target) }
    }

  private suspend fun applyLocked(target: DisplayConfig): Result<Unit> =
    run {
      try {
        val native = nativeConfig()
        val before = currentConfig()
        if (target.width == native.width && target.height == native.height) {
          windowManager.clearSize()
        } else {
          windowManager.setSize(target.width, target.height)
        }
        if (target.density == native.density) {
          windowManager.clearDensity()
        } else {
          windowManager.setDensity(target.density)
        }
        awaitConfig(target)
        if (target.width != before.width || target.height != before.height) remeasureGestureNavigation()
        Result.success(Unit)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  private suspend fun awaitConfig(target: DisplayConfig) {
    repeat(VERIFY_ATTEMPTS) {
      if (currentConfig() == target) return
      delay(VERIFY_INTERVAL_MS)
    }
    val now = currentConfig()
    throw DisplayChangeException(
      "The display stayed at ${now.width} × ${now.height}, ${now.density} dpi instead of changing."
    )
  }

  /**
   * Makes HyperOS measure the screen again for its swipe gestures.
   *
   * HyperOS places its swipe-up area in pixels when gesture navigation is switched on and does
   * not move it when the resolution changes, so after a change the swipe from the bottom edge
   * stops working. Switching gesture navigation off and back on makes it measure afresh. The
   * setting only exists on Xiaomi software, and nothing happens when the tablet uses buttons.
   */
  private suspend fun remeasureGestureNavigation() {
    // Buttons are the user's choice; leave them alone. "Owed" means an earlier attempt of
    // ours left gestures off, so off is not the user's choice.
    if (!gesturesOn() && !gesturesOwed) return
    // Never stop halfway: that would leave the tablet on button navigation.
    withContext(NonCancellable) {
      gesturesOwed = true
      try {
        setGestures(false)
        // Switching back on before the system has really gone to buttons does nothing: a busy
        // launcher sees "off, on" as no change and never measures. Fixed waits were not
        // enough right after a resolution change, so wait for the system to say it is done.
        awaitNavigationMode(NAVIGATION_MODE_BUTTONS)
        // Even then the launcher needs a little longer to finish, or it re-measures from
        // stale numbers. Holding for this long made the difference in testing.
        delay(GESTURE_NAVIGATION_OFF_HOLD_MS)
      } finally {
        setGestures(true)
      }
      // The launcher can also write "off" itself a moment late, undoing our "on". Put it back
      // if so, and finish once the system confirms it is on gestures again.
      val giveUpAt = SystemClock.elapsedRealtime() + GESTURE_NAVIGATION_LIMIT_MS
      while (SystemClock.elapsedRealtime() < giveUpAt) {
        delay(GESTURE_NAVIGATION_CHECK_MS)
        if (!gesturesOn()) {
          setGestures(true)
        } else if (navigationMode() == NAVIGATION_MODE_GESTURES) {
          break
        }
      }
      delay(GESTURE_NAVIGATION_ON_SETTLE_MS)
      if (gesturesOn()) gesturesOwed = false
    }
  }

  /** Waits until Android reports [mode] as its navigation mode, or a time limit passes. */
  private suspend fun awaitNavigationMode(mode: Int) {
    val giveUpAt = SystemClock.elapsedRealtime() + GESTURE_NAVIGATION_LIMIT_MS
    while (SystemClock.elapsedRealtime() < giveUpAt) {
      delay(GESTURE_NAVIGATION_CHECK_MS)
      val now = navigationMode()
      if (now == mode) return
      if (now == null) {
        // This Android version will not let the app read the mode; fall back to a long wait.
        delay(GESTURE_NAVIGATION_BLIND_WAIT_MS)
        return
      }
    }
  }

  /** Android's own record of the navigation style in use, or null if the app may not read it. */
  private fun navigationMode(): Int? =
    try {
      Settings.Secure.getInt(context.contentResolver, NAVIGATION_MODE_SETTING, -1).takeIf { it >= 0 }
    } catch (e: SecurityException) {
      null
    }

  /**
   * Switches gesture navigation back on if an earlier resolution change was cut off before it
   * could, for example because the app was closed at that moment. Safe to call at any time.
   */
  fun restoreGesturesIfOwed() {
    if (!gesturesOwed) return
    try {
      setGestures(true)
      gesturesOwed = false
    } catch (e: SecurityException) {
      // The permission has gone; it will be tried again once setup has been redone.
    }
  }

  private fun gesturesOn(): Boolean =
    Settings.Global.getInt(context.contentResolver, GESTURE_NAVIGATION_SETTING, 0) == 1

  private fun setGestures(on: Boolean) {
    Settings.Global.putInt(context.contentResolver, GESTURE_NAVIGATION_SETTING, if (on) 1 else 0)
  }

  /** Saved to disk, so it is still known after the app has been closed and reopened. */
  private var gesturesOwed: Boolean
    get() = prefs.getBoolean(KEY_GESTURES_OWED, false)
    set(value) {
      prefs.edit().putBoolean(KEY_GESTURES_OWED, value).commit()
    }

  companion object {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    /** Shared by every instance, since they all change the one display. */
    private val changeLock = Mutex()

    /** Xiaomi's "full screen gestures" switch: 1 for swipe gestures, 0 for the three buttons. */
    private const val GESTURE_NAVIGATION_SETTING = "force_fsg_nav_bar"
    /**
     * Android's record of the navigation style actually in use. It changes a moment after
     * the Xiaomi switch above, once the system has finished switching, which makes it the
     * signal to wait for.
     */
    private const val NAVIGATION_MODE_SETTING = "navigation_mode"
    private const val NAVIGATION_MODE_BUTTONS = 0
    private const val NAVIGATION_MODE_GESTURES = 2

    private const val GESTURE_NAVIGATION_CHECK_MS = 100L
    private const val GESTURE_NAVIGATION_OFF_HOLD_MS = 1600L
    private const val GESTURE_NAVIGATION_ON_SETTLE_MS = 600L
    private const val GESTURE_NAVIGATION_LIMIT_MS = 6000L
    private const val GESTURE_NAVIGATION_BLIND_WAIT_MS = 2500L
    private const val KEY_GESTURES_OWED = "gestures_owed"
    private const val VERIFY_ATTEMPTS = 30
    private const val VERIFY_INTERVAL_MS = 100L
  }
}

/**
 * Calls the display-override functions of Android's window manager, the ones behind
 * `adb shell wm size` and `wm density`.
 *
 * They are not part of the public SDK, so they are reached by reflection. The window manager
 * itself only lets callers holding WRITE_SECURE_SETTINGS use them, which is why the one-time
 * adb grant is needed. The `wm` tool cannot be used from an app instead: it changes density for
 * "the current user", which needs a permission only system apps can hold, whereas these calls
 * name the app's own user.
 */
@SuppressLint("PrivateApi")
private class WindowManagerBridge {
  private val type: Class<*>
  private val service: Any

  init {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Android 9+ hides non-SDK interfaces from apps; lift that for these two classes only.
        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/IWindowManager", "Landroid/view/WindowManagerGlobal")
      }
      type = Class.forName("android.view.IWindowManager")
      service =
        Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
          ?: throw DisplayChangeException("This tablet's window manager is not available.")
    } catch (e: ReflectiveOperationException) {
      throw DisplayChangeException("This Android version does not let apps reach its window manager.")
    }
  }

  fun setSize(width: Int, height: Int) = call("setForcedDisplaySize", Display.DEFAULT_DISPLAY, width, height)

  fun clearSize() = call("clearForcedDisplaySize", Display.DEFAULT_DISPLAY)

  fun setDensity(density: Int) =
    call("setForcedDisplayDensityForUser", Display.DEFAULT_DISPLAY, density, USER_ID)

  fun clearDensity() = call("clearForcedDisplayDensityForUser", Display.DEFAULT_DISPLAY, USER_ID)

  private fun call(name: String, vararg args: Int) {
    try {
      val parameterTypes = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
      type.getMethod(name, *parameterTypes).invoke(service, *args.toTypedArray())
    } catch (e: InvocationTargetException) {
      val cause = e.targetException
      throw DisplayChangeException("The system refused the change: ${cause.message ?: cause.javaClass.simpleName}")
    } catch (e: ReflectiveOperationException) {
      throw DisplayChangeException("This Android version does not offer \"$name\" to apps.")
    }
  }

  private companion object {
    /** The Android user this app runs as: each user owns a block of 100,000 uids. */
    val USER_ID = Process.myUid() / 100_000
  }
}
