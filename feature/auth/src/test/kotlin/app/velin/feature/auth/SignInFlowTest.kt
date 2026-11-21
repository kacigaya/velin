package app.velin.feature.auth

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.velin.core.designsystem.theme.VelinTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignInFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmsEmailBeforeSignInAndDropsPasswordWhenEditingAddress() {
        val attempts = mutableListOf<Pair<String, String>>()
        compose.setContent {
            VelinTheme {
                SignInScreen(SignInUiState.Credentials(), onEvent = {
                    if (it is SignInEvent.SignIn) attempts += it.address to String(it.password)
                })
            }
        }

        compose.onNodeWithText("Password").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Email address").performTextInput("first@example.com")
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        compose.onNodeWithText("first@example.com").assertExists()
        compose.runOnIdle { assertEquals(emptyList<Pair<String, String>>(), attempts) }

        compose.onNodeWithText("Password").performTextInput("discard me")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("first@example.com").assertExists()
        compose.onNodeWithText("Email address").performTextReplacement("second@example.com")
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("second@example.com").assertExists()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Password").performTextInput("submit me")
        compose.onNodeWithText("Sign in").performClick()

        compose.runOnIdle { assertEquals(listOf("second@example.com" to "submit me"), attempts) }
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun cancelsVerificationAndReturnsToTheConfirmedAccount() {
        val credentials = SignInUiState.Credentials()
        val state = mutableStateOf<SignInUiState>(credentials)
        compose.setContent {
            VelinTheme {
                SignInScreen(state.value, onEvent = { event ->
                    state.value = when (event) {
                        is SignInEvent.SignIn -> SignInUiState.SecondFactor(
                            event.address,
                            totp = true,
                            securityKey = false,
                            otherSession = false,
                        )
                        SignInEvent.CancelSecondFactor -> credentials
                        else -> state.value
                    }
                })
            }
        }

        compose.onNodeWithText("Email address").performTextInput("alex@example.com")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Password").performTextInput("sample password")
        compose.onNodeWithText("Sign in").performClick()

        compose.onNodeWithText("Two-step verification").assertExists()
        compose.onNodeWithText("alex@example.com").assertExists()
        compose.onNodeWithText("Approve this sign-in", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel verification").performClick()

        compose.onNodeWithText("Enter your password").assertExists()
        compose.onNodeWithText("alex@example.com").assertExists()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun returnsFromNewEmailToSavedAccounts() {
        val accounts = persistentListOf(SavedAccountUi("1", "first@example.com"), SavedAccountUi("2", "second@example.com"))
        val events = mutableListOf<SignInEvent>()
        compose.setContent {
            VelinTheme { SignInScreen(SignInUiState.Credentials(savedAccounts = accounts), onEvent = { events += it }) }
        }

        compose.onNodeWithText("Use another account").performClick()
        compose.onNodeWithText("Email address").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("second@example.com").performClick()

        compose.runOnIdle { assertEquals(listOf(SignInEvent.ResumeAccount("2")), events) }
    }
}
