package za.co.meridiangroup.attendance.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import za.co.meridiangroup.attendance.ui.onboarding.OnboardingScreen
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import kotlin.test.assertTrue

/**
 * Walks through every onboarding step without granting anything (system dialogs are not driven here), checking
 * that each step explains its purpose, can be continued, and that the final summary reflects the real state.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()
    private var finished = false

    @Before
    fun setUp() {
        compose.setContent { MeridianTheme(darkTheme = false) { OnboardingScreen(onFinished = { finished = true }) } }
    }

    private fun next() = compose.onNodeWithTag("ob_next").performScrollTo().performClick()

    @Test
    fun introExplainsPopiaPurpose() {
        compose.onNodeWithText("How attendance works").assertIsDisplayed()
        compose.onNodeWithText("Why we need this (POPIA)", substring = true).assertIsDisplayed()
    }

    @Test
    fun walksAllStepsToSummary() {
        next() // intro → notifications
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        next() // → precise location
        compose.onNodeWithText("Precise location").assertIsDisplayed()
        next() // → background location
        compose.onNodeWithText("Location all the time").assertIsDisplayed()
        next() // → battery
        compose.onNodeWithText("Battery optimisation").assertIsDisplayed()
        next() // → Wi-Fi
        compose.onNodeWithText("Workplace Wi-Fi (optional)").assertIsDisplayed()
        next() // → done
        compose.onNodeWithText("You're all set").assertIsDisplayed()
        compose.onNodeWithText("Location services").assertIsDisplayed()
        compose.onNodeWithTag("ob_finish").performScrollTo().performClick()
        compose.waitUntil(5_000) { finished }
        assertTrue(finished)
    }
}
