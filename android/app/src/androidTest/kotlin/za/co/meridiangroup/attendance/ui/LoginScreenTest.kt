package za.co.meridiangroup.attendance.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.TokenStore
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.repository.AuthRepository
import za.co.meridiangroup.attendance.data.repository.SessionState
import za.co.meridiangroup.attendance.fakes.FakeAttendanceApi
import za.co.meridiangroup.attendance.ui.auth.LoginScreen
import za.co.meridiangroup.attendance.ui.auth.LoginViewModel
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var api: FakeAttendanceApi
    private lateinit var repo: AuthRepository
    private lateinit var tokens: TokenStore
    private var loggedInCallbacks = 0

    @Before
    fun setUp() {
        api = FakeAttendanceApi()
        tokens = TokenStore(context)
        tokens.clear()
        repo = AuthRepository(api, ApiCaller { }, tokens, AppPreferences(context), { System.currentTimeMillis() }, onLocalDataWipe = {})
        compose.setContent { MeridianTheme(darkTheme = false) { LoginScreen(LoginViewModel(repo) { loggedInCallbacks++ }) } }
    }

    @After
    fun tearDown() = runBlocking { repo.forceLogout() }

    @Test
    fun emptyFormShowsValidationErrorsWithoutCallingServer() {
        compose.onNodeWithTag("login_submit").performClick()
        compose.onNodeWithText("Enter your employee number or email address.").assertIsDisplayed()
        compose.onNodeWithText("Enter your password.").assertIsDisplayed()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun wrongPasswordShowsServerError() {
        compose.onNodeWithTag("login_identifier").performTextInput("MG-0042")
        compose.onNodeWithTag("login_password").performTextInput("nope")
        compose.onNodeWithTag("login_submit").performClick()
        compose.waitUntil(5_000) { api.calls.contains("login") }
        compose.onNodeWithTag("login_error").assertIsDisplayed()
        compose.onNodeWithText("Your employee number/email or password is incorrect.").assertIsDisplayed()
        assertEquals(SessionState.LoggedOut, repo.state.value)
    }

    @Test
    fun correctPasswordStoresEncryptedTokensAndLogsIn() {
        compose.onNodeWithTag("login_identifier").performTextInput("MG-0042")
        compose.onNodeWithTag("login_password").performTextInput(api.validPassword)
        compose.onNodeWithTag("login_submit").performClick()
        compose.waitUntil(5_000) { loggedInCallbacks == 1 }
        assertEquals(SessionState.LoggedIn(mustChangePassword = false), repo.state.value)
        assertEquals("access", tokens.current()?.accessToken)
        // A fresh TokenStore instance must decrypt the persisted blob with the Keystore key.
        assertEquals("refresh", TokenStore(context).current()?.refreshToken)
    }

    @Test
    fun forgotPasswordAlwaysShowsNeutralConfirmation() {
        compose.onNodeWithText("Forgot password?").performClick()
        compose.onNodeWithTag("forgot_identifier").performTextInput("someone@example.co.za")
        compose.onNodeWithTag("forgot_send").performClick()
        compose.waitUntil(5_000) { api.calls.contains("forgotPassword") }
        compose.onNodeWithText("If the details match an account", substring = true).assertIsDisplayed()
    }

    @Test
    fun offlineLoginShowsOfflineMessage() {
        api.offline = true
        compose.onNodeWithTag("login_identifier").performTextInput("MG-0042")
        compose.onNodeWithTag("login_password").performTextInput("whatever-pass")
        compose.onNodeWithTag("login_submit").performClick()
        compose.waitUntil(5_000) { api.calls.contains("login") }
        compose.onNodeWithText("You're offline", substring = true).assertIsDisplayed()
    }
}
