package app.velin.feature.mail.mailbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.velin.core.designsystem.theme.VelinTheme
import app.velin.feature.mail.screenshot.SampleMail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MailboxPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun accountStaysOutOfSearchAndFolderActionWorks() {
        val events = mutableListOf<MailboxEvent>()
        compose.setContent {
            VelinTheme(dynamicColor = false) {
                MailboxScreen(
                    state = MailboxUiState(account = SampleMail.account, folder = SampleMail.inbox, mails = SampleMail.mails, loading = false),
                    onEvent = events::add,
                )
            }
        }
        compose.onNodeWithContentDescription("Account alex@tuta.com").assertDoesNotExist()
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open folders").performClick()
        assertEquals(listOf(MailboxEvent.OpenFolders), events)
        compose.onNodeWithContentDescription("Design review moved to Thursday", substring = true).performClick()
        assertEquals(MailboxEvent.Open(SampleMail.mails.first()), events.last())
    }
}
