package com.padtools.resolution.display

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** A display size and density, with width and height in the panel's natural orientation. */
data class DisplayConfig(val width: Int, val height: Int, val density: Int) {
  val shortSide: Int get() = min(width, height)
  val pixels: Long get() = width.toLong() * height
}

/** The resolutions the app offers. [shortSide] is null for the panel's own resolution. */
enum class ResolutionMode(val label: String, val tagline: String, val shortSide: Int?) {
  NATIVE("2K", "Sharpest picture", null),
  P1200("1200p", "Balanced for gaming", 1200),
  P800("800p", "Highest frame rate", 800),
}

object DisplayMath {
  const val MIN_UI_SCALE = 0.80f
  const val MAX_UI_SCALE = 1.30f
  const val UI_SCALE_STEP = 0.05f

  /** Android refuses densities below this. */
  private const val MIN_DENSITY = 72

  /** Modes that make sense on this panel: never offer a size larger than the panel itself. */
  fun availableModes(native: DisplayConfig): List<ResolutionMode> =
    ResolutionMode.entries.filter { it.shortSide == null || it.shortSide < native.shortSide }

  /**
   * The size and density for [mode].
   *
   * The size keeps the panel's aspect ratio. The density is scaled by the same factor as the
   * size, so the screen stays the same number of dp wide and icons and text keep the physical
   * size they have at native resolution. [uiScale] then makes everything larger or smaller.
   */
  fun configFor(mode: ResolutionMode, native: DisplayConfig, uiScale: Float = 1f): DisplayConfig {
    val factor = (mode.shortSide ?: native.shortSide).toDouble() / native.shortSide
    return DisplayConfig(
      width = even(native.width * factor),
      height = even(native.height * factor),
      density = (native.density * factor * uiScale).roundToInt().coerceAtLeast(MIN_DENSITY),
    )
  }

  /** The mode whose size matches [current], or null for a size this app did not set. */
  fun modeOf(current: DisplayConfig, native: DisplayConfig): ResolutionMode? =
    availableModes(native).firstOrNull { mode ->
      val config = configFor(mode, native)
      config.width == current.width && config.height == current.height
    }

  /** How much larger or smaller than standard the UI currently is, snapped to the slider steps. */
  fun uiScaleOf(current: DisplayConfig, native: DisplayConfig): Float {
    val standardDensity = native.density.toDouble() * current.shortSide / native.shortSide
    return snapUiScale((current.density / standardDensity).toFloat())
  }

  fun snapUiScale(scale: Float): Float {
    val steps = (scale / UI_SCALE_STEP).roundToInt()
    return (steps * UI_SCALE_STEP).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
  }

  /** Pixels drawn per frame as a percentage of the native resolution. */
  fun pixelPercent(config: DisplayConfig, native: DisplayConfig): Int =
    (config.pixels * 100.0 / native.pixels).roundToInt()

  fun sameUiScale(a: Float, b: Float): Boolean = abs(a - b) < UI_SCALE_STEP / 2

  private fun even(value: Double): Int = (value / 2).roundToInt() * 2
}
