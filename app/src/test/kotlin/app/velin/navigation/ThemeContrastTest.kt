package app.velin.navigation

import android.app.UiModeManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.velin.core.designsystem.theme.VelinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun systemContrastChangesRefreshThePaletteWithoutRecreatingTheScreen() {
        lateinit var manager: UiModeManager
        compose.setContent {
            manager = LocalContext.current.getSystemService(UiModeManager::class.java)
            VelinTheme(darkTheme = false, dynamicColor = false) {
                Text(MaterialTheme.colorScheme.onSurface.toArgb().toUInt().toString(16))
            }
        }
        compose.onNodeWithText("ff1a1b20").assertIsDisplayed()
        compose.runOnIdle { shadowOf(manager).setContrast(1f) }
        compose.onNodeWithText("ff000000").assertIsDisplayed()
        compose.runOnIdle { shadowOf(manager).setContrast(0f) }
        compose.onNodeWithText("ff1a1b20").assertIsDisplayed()
    }
}
