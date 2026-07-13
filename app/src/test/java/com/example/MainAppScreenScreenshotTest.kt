package com.example

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline for the main screen (Phase 0, task 2 of the KMP restructure, #1).
 *
 * These references gate the Phase 1 UI decomposition: the move-only splits of
 * MainActivity.kt must leave this render byte-identical (`verifyRoborazziDebug`).
 *
 * MainAppScreen() builds its own TranslateViewModel via viewModel(); under
 * Robolectric that resolves against the host ComponentActivity and the real
 * Room database in the default (empty) state, which is the deterministic
 * initial "translate" tab with no history.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MainAppScreenScreenshotTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun mainAppScreen_initialTranslateTab() {
    composeRule.activity.setContent {
      MyApplicationTheme {
        MainAppScreen()
      }
    }
    composeRule.onRoot().captureRoboImage()
  }
}
