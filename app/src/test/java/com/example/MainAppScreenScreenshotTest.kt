package com.example

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.example.platform.AndroidAppContainer
import com.example.platform.UnavailableVoiceInput
import com.example.speech.TTSHelper
import com.example.ui.App
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
 * These references gate the UI moves: the move-only splits of MainActivity.kt and
 * the Phase 3 pull into the shared [App] composable must leave this render
 * byte-identical (`verifyRoborazziDebug`).
 *
 * [App] builds its own TranslateViewModel via the CMP `viewModel {}` factory;
 * under Robolectric that resolves against the host ComponentActivity and the real
 * Room database in the default (empty) state, which is the deterministic initial
 * "translate" tab with no history. Voice input is a no-op here (the launcher is
 * Activity-scoped and irrelevant to the initial frame).
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
      val context = composeRule.activity
      val ttsHelper = TTSHelper(context) {}
      val container = AndroidAppContainer(
        context = context,
        ttsHelper = ttsHelper,
        voiceInput = UnavailableVoiceInput,
      )
      App(container = container, launchVoiceInput = { _, _ -> })
    }
    composeRule.onRoot().captureRoboImage()
  }
}
