package app.velin.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.velin.core.designsystem.theme.VelinTheme
import app.velin.core.domain.Account
import app.velin.core.domain.AccountId
import app.velin.core.domain.Address
import app.velin.core.domain.ElementId
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val account = Account(AccountId(ElementId("test")), Address("alex@tuta.com", "Alex Martin"))

    @Test
    fun systemSettingsActionsAndSignOutConfirmation() {
        var display = 0
        var accessibility = 0
        var signOut = 0
        compose.setContent {
            VelinTheme(dynamicColor = false) {
                SettingsScreen(account, false, SnackbarHostState(), {}, { display++ }, { accessibility++ }, { signOut++ })
            }
        }
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Contrast").performClick()
        compose.onNodeWithText("Accessibility").performClick()
        assertEquals(1, display)
        assertEquals(2, accessibility)
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        assertEquals(0, signOut)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Sign out?").assertDoesNotExist()
        assertEquals(0, signOut)
        compose.onNodeWithText("Sign out").performClick()
        compose.onAllNodesWithText("Sign out")[1].performClick()
        assertEquals(1, signOut)
    }

    @Test
    fun light() = capture(false)

    @Test
    fun dark() = capture(true)

    private fun capture(dark: Boolean) {
        compose.setContent {
            VelinTheme(darkTheme = dark, dynamicColor = false) {
                SettingsScreen(account, false, SnackbarHostState(), {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_${if (dark) "dark" else "light"}.png")
    }
}
