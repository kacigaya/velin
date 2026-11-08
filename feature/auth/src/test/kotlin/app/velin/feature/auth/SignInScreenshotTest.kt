package app.velin.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.velin.core.designsystem.theme.VelinTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SignInScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { VelinTheme(darkTheme = dark, dynamicColor = false, content = content) }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_$theme.png")
        }
    }

    @Test
    fun credentials() = capture("signin") {
        SignInScreen(
            SignInUiState.Credentials(savedAccounts = persistentListOf(SavedAccountUi("1", "alex@tuta.com"))),
            onEvent = {},
        )
    }

    @Test
    fun secondFactor() = capture("signin_2fa") {
        SignInScreen(
            SignInUiState.SecondFactor("alex@tuta.com", totp = true, securityKey = true, error = SignInError.InvalidCode),
            onEvent = {},
        )
    }
}
