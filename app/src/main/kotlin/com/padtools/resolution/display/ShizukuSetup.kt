package com.padtools.resolution.display

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Whether Shizuku can do the one-time setup instead of a computer, and if not, what is missing. */
enum class ShizukuState {
  NOT_INSTALLED,

  /** Installed, but its service has not been started since the tablet last booted. */
  NOT_RUNNING,

  /** Running, but it has not been told to trust this app yet. */
  NEEDS_ACCESS,

  READY,
}

/**
 * Does the one-time setup through Shizuku, so no computer is needed.
 *
 * Shizuku runs a service with the same rights as `adb shell`. Through it the app runs the same
 * `pm grant` command the setup guide asks for on a computer. That is all Shizuku is used for:
 * once the permission is granted the app never talks to it again.
 */
class ShizukuSetup(private val context: Context) {

  fun state(): ShizukuState {
    // Versions before 11 used a different permission model that this app does not support.
    val running = Shizuku.pingBinder() && !Shizuku.isPreV11()
    return when {
      running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
      // Shizuku's service keeps running after its app is uninstalled, but with the app gone
      // there is nothing to show its "allow this app?" dialog.
      !isInstalled() -> ShizukuState.NOT_INSTALLED
      running -> ShizukuState.NEEDS_ACCESS
      else -> ShizukuState.NOT_RUNNING
    }
  }

  /** Shows Shizuku's "allow this app?" dialog. The answer arrives through [Shizuku]'s listeners. */
  fun requestAccess() {
    if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(ACCESS_REQUEST_CODE)
  }

  /** An intent that opens the Shizuku app, or null when it is not installed. */
  fun openShizukuIntent(): Intent? =
    context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** Runs `pm grant` for this app's display permission. Fails with a readable reason. */
  suspend fun grantDisplayPermission(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        if (state() != ShizukuState.READY) throw DisplayChangeException("Shizuku is not ready.")
        val process = shizukuProcess("pm", "grant", context.packageName, DisplayController.PERMISSION)
        if (!process.waitFor(GRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          process.destroy()
          throw DisplayChangeException("Shizuku did not answer.")
        }
        val errors = process.errorStream.bufferedReader().use { it.readText() }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0 || context.checkSelfPermission(DisplayController.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
          throw DisplayChangeException(refusalReason(errors + output))
        }
        Result.success(Unit)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(if (e is DisplayChangeException) e else DisplayChangeException(e.message ?: e.javaClass.simpleName))
      }
    }

  /**
   * Starts a command with Shizuku's rights. The library keeps this method private because it
   * recommends a heavier mechanism for apps that run many commands; for a single `pm grant`
   * it is the simplest correct tool, so it is reached by reflection.
   */
  private fun shizukuProcess(vararg command: String): Process {
    val newProcess =
      Shizuku::class.java.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java,
        Array<String>::class.java,
        String::class.java,
      )
    newProcess.isAccessible = true
    return newProcess.invoke(null, command, null, null) as Process
  }

  private fun refusalReason(commandOutput: String): String =
    if (commandOutput.contains("GRANT_RUNTIME_PERMISSIONS") || commandOutput.contains("SecurityException")) {
      // HyperOS blocks permission grants from debugging tools until this switch is on.
      "HyperOS refused. Turn on USB debugging (Security settings) in Developer options, then try again."
    } else {
      commandOutput.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "The tablet refused without a reason."
    }

  private fun isInstalled(): Boolean =
    try {
      context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
      true
    } catch (e: PackageManager.NameNotFoundException) {
      false
    }

  companion object {
    const val ACCESS_REQUEST_CODE = 1
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val GRANT_TIMEOUT_SECONDS = 10L
  }
}
