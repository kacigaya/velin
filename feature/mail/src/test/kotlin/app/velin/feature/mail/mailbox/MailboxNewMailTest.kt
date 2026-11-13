package app.velin.feature.mail.mailbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import app.velin.core.designsystem.theme.VelinTheme
import app.velin.feature.mail.screenshot.SampleMail
import kotlinx.collections.immutable.toPersistentList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MailboxNewMailTest {
    @get:Rule
    val compose = createComposeRule()

    private val older = (0 until 30).map { SampleMail.mails.first().copy(id = "old-$it", subject = "Older $it") }
    private val state = mutableStateOf(MailboxUiState(mails = older.toPersistentList(), loading = false))
    private val newMail = SampleMail.mails.first().copy(id = "new", subject = "Just arrived")

    private fun show() = compose.setContent {
        VelinTheme(darkTheme = false, dynamicColor = false) { MailboxScreen(state.value, onEvent = {}) }
    }
    private fun arrive() {
        state.value = state.value.copy(mails = (listOf(newMail) + older).toPersistentList())
        compose.waitForIdle()
    }

    @Test
    fun aNewMailShowsWhenTheListIsAtTheTop() {
        show()
        arrive()
        compose.onNodeWithContentDescription("Just arrived", substring = true).assertIsDisplayed()
    }

    @Test
    fun aNewMailKeepsTheReadingPositionFurtherDown() {
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        arrive()
        compose.onNodeWithContentDescription("Just arrived", substring = true).assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Older 14", substring = true).assertIsDisplayed()
    }
}
