package com.padtools.resolution.display

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings

/** How far the one-time setup has got, for the steps the app is able to see for itself. */
data class SetupStatus(
  val developerOptions: Boolean,
  val usbDebugging: Boolean,
  /** Plugged into a computer's USB port, as opposed to a wall charger or nothing. */
  val usbConnected: Boolean,
)

fun readSetupStatus(context: Context): SetupStatus {
  val resolver = context.contentResolver
  // The battery broadcast is "sticky": registering with no receiver returns the latest one.
  val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val pluggedInto = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
  return SetupStatus(
    developerOptions = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
    usbDebugging = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1,
    usbConnected = pluggedInto == BatteryManager.BATTERY_PLUGGED_USB,
  )
}
