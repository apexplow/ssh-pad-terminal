package com.taosun.hanterm.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.data.profile.ConnectionProfiles
import com.taosun.hanterm.ui.TestActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #18: [ConfigScreen] no longer takes [com.taosun.hanterm.data.profile.ConnectionProfile]
 * directly — it takes a [ConnectionDraftEditor] constructed at the call site.
 * Tests build the editor inside the `setContent` lambda so the
 * `rememberCoroutineScope()` lifetime matches the screen's composition.
 *
 * The two UI cases (Save reflects status + persists; Clear resets fields +
 * status) still drive the editor end-to-end through Compose semantics. They
 * stay shallow on purpose — the persistence / state-machine assertions
 * live in [ConnectionDraftEditorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfigScreenUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        AppPreferences(context).clear()
        AppPreferences(context).apply {
            host = "old.example.com"
            port = 22
            username = "olduser"
        }
    }

    @Test
    fun save_reflectsStatusAndPersistsFields() {
        val prefs = AppPreferences(context)
        val profile = ConnectionProfiles.create(context, prefs)

        composeTestRule.setContent {
            val scope = rememberCoroutineScope()
            val debugLog = remember { AndroidDebugLogSink(context) }
            val editor = remember { ConnectionDraftEditor(profile, scope, debugLog) }
            ConfigScreen(editor = editor)
        }

        composeTestRule.onNodeWithText("Host").apply {
            performTextClearance()
            performTextInput("h.example.com")
        }
        composeTestRule.onNodeWithText("Username").apply {
            performTextClearance()
            performTextInput("ops")
        }
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()
        assertEquals("h.example.com", prefs.host)
        assertEquals("ops", prefs.username)
    }

    @Test
    fun clear_resetsFieldsAndStatus() {
        val prefs = AppPreferences(context)
        prefs.host = "h.example.com"
        prefs.username = "ops"
        val profile = ConnectionProfiles.create(context, prefs)

        composeTestRule.setContent {
            val scope = rememberCoroutineScope()
            val debugLog = remember { AndroidDebugLogSink(context) }
            val editor = remember { ConnectionDraftEditor(profile, scope, debugLog) }
            ConfigScreen(editor = editor)
        }

        composeTestRule.onNodeWithText("Clear").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cleared").assertIsDisplayed()
        assertEquals("", prefs.host)
        assertEquals("", prefs.username)
    }
}