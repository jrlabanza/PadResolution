package com.padtools.resolution.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayMathTest {
  /** POCO Pad M1: 1600 × 2560 panel at 320 dpi. */
  private val pocoPadM1 = DisplayConfig(1600, 2560, 320)

  @Test
  fun nativeModeIsThePanelItself() {
    assertEquals(pocoPadM1, DisplayMath.configFor(ResolutionMode.NATIVE, pocoPadM1))
  }

  @Test
  fun lowerResolutionsKeepAspectRatioAndScaleDensityToMatch() {
    assertEquals(DisplayConfig(1200, 1920, 240), DisplayMath.configFor(ResolutionMode.P1200, pocoPadM1))
    assertEquals(DisplayConfig(800, 1280, 160), DisplayMath.configFor(ResolutionMode.P800, pocoPadM1))
  }

  @Test
  fun everyModeIsTheSameWidthInDp() {
    for (mode in ResolutionMode.entries) {
      val config = DisplayMath.configFor(mode, pocoPadM1)
      assertEquals(mode.name, 800, config.width * 160 / config.density)
    }
  }

  @Test
  fun uiScaleChangesOnlyTheDensity() {
    assertEquals(DisplayConfig(1200, 1920, 276), DisplayMath.configFor(ResolutionMode.P1200, pocoPadM1, 1.15f))
    assertEquals(DisplayConfig(800, 1280, 136), DisplayMath.configFor(ResolutionMode.P800, pocoPadM1, 0.85f))
  }

  @Test
  fun currentModeAndScaleAreRecognised() {
    val current = DisplayConfig(1200, 1920, 276)
    assertEquals(ResolutionMode.P1200, DisplayMath.modeOf(current, pocoPadM1))
    assertEquals(1.15f, DisplayMath.uiScaleOf(current, pocoPadM1), 0.001f)
    assertNull(DisplayMath.modeOf(DisplayConfig(1080, 1728, 216), pocoPadM1))
  }

  @Test
  fun pixelSavingsAreReported() {
    assertEquals(56, DisplayMath.pixelPercent(DisplayMath.configFor(ResolutionMode.P1200, pocoPadM1), pocoPadM1))
    assertEquals(25, DisplayMath.pixelPercent(DisplayMath.configFor(ResolutionMode.P800, pocoPadM1), pocoPadM1))
  }

  @Test
  fun modesLargerThanThePanelAreNotOffered() {
    val smallPanel = DisplayConfig(1200, 1920, 240)
    assertEquals(listOf(ResolutionMode.NATIVE, ResolutionMode.P800), DisplayMath.availableModes(smallPanel))
  }
}
