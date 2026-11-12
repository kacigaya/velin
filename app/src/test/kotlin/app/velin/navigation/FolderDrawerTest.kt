package app.velin.navigation

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.velin.core.designsystem.theme.VelinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FolderDrawerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun advance(millis: Long) {
        // View model coroutines run on the main looper, which Robolectric pauses.
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(millis)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun menuOpensAgainAfterFolderPickedWhileDrawerOpens() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VelinTheme(darkTheme = false, dynamicColor = false) { VelinApp(FakeGraph) } }
        advance(500)

        compose.onNodeWithContentDescription("Open folders").performClick()
        advance(50) // The drawer is still opening.
        compose.onNodeWithText("Archive").performSemanticsAction(SemanticsActions.OnClick)
        advance(1_000)
        compose.onNodeWithText("Search in Archive").assertIsDisplayed()
        compose.onNodeWithText("Inbox").assertIsNotDisplayed()

        compose.onNodeWithContentDescription("Open folders").performClick()
        advance(1_000)
        compose.onNodeWithText("Inbox").assertIsDisplayed()
    }
}
