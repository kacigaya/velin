package app.velin.feature.mail.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.theme.VelinTheme
import androidx.compose.foundation.text.input.rememberTextFieldState
import app.velin.feature.mail.composer.ComposerRecipientUi
import app.velin.feature.mail.composer.ComposerScreen
import app.velin.feature.mail.composer.ComposerUiState
import app.velin.feature.mail.mailbox.FolderPane
import app.velin.feature.mail.model.AttachmentUi
import app.velin.feature.mail.model.RecipientUi
import kotlinx.collections.immutable.persistentListOf
import app.velin.feature.mail.mailbox.FolderPaneState
import app.velin.feature.mail.mailbox.MailboxScreen
import app.velin.feature.mail.mailbox.MailboxUiState
import app.velin.feature.mail.reader.ConversationScreen
import app.velin.feature.mail.reader.ConversationUiState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import app.velin.feature.mail.mailbox.LabelSheet
import app.velin.feature.mail.mailbox.MoveToSheet
import app.velin.feature.mail.mailbox.labelChoices
import org.junit.Rule
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders key screens in light and dark for design review. Output: build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MailScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // The clock is paused: loading indicators animate forever and would keep Compose from idling.
    private fun capture(name: String, content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { VelinTheme(darkTheme = dark, dynamicColor = false, content = content) }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_$theme.png")
        }
    }

    @Test
    fun mailbox() = capture("mailbox") {
        MailboxScreen(
            state = MailboxUiState(
                account = SampleMail.account,
                folder = SampleMail.inbox,
                mails = SampleMail.mails,
                loading = false,
            ),
            onEvent = {},
        )
    }

    @Test
    fun mailboxSelection() = capture("mailbox_selection") {
        MailboxScreen(
            state = MailboxUiState(
                folder = SampleMail.inbox,
                mails = SampleMail.mails,
                loading = false,
                offline = true,
                selectedIds = persistentSetOf("m2", "m3"),
            ),
            onEvent = {},
        )
    }

    @Test
    fun folderDrawer() = capture("folders") {
        ModalDrawerSheet(Modifier.width(320.dp)) {
            FolderPane(
                state = FolderPaneState(
                    account = SampleMail.account,
                    systemFolders = SampleMail.systemFolders,
                    customFolders = SampleMail.customFolders,
                    labels = SampleMail.labels,
                    selectedId = SampleMail.inbox.id,
                ),
                onFolderClick = {},
                onLabelClick = {},
                onSettingsClick = {},
            )
        }
    }

    @Test
    fun conversation() = capture("conversation") {
        ConversationScreen(state = ConversationUiState.Loaded(SampleMail.conversation), onEvent = {})
    }

    @Test
    fun composer() = capture("composer") {
        ComposerScreen(
            state = ComposerUiState(
                senders = persistentListOf("alex@tuta.com", "alex@tutanota.de"),
                sender = "alex@tuta.com",
                to = persistentListOf(
                    ComposerRecipientUi(RecipientUi("Lena Fischer", "lena@tuta.com"), external = false),
                    ComposerRecipientUi(RecipientUi("", "friend@example.com"), external = true),
                ),
                attachments = persistentListOf(AttachmentUi("a1", "itinerary.pdf", "application/pdf", 182_000)),
                confidential = true,
                canSend = true,
            ),
            subject = rememberTextFieldState("Trip plans"),
            body = rememberTextFieldState("Hi both,\n\nHere is the plan for Saturday."),
            onEvent = {},
        )
    }

    // Sheets draw in their own window, so capture the whole screen, not the root node.
    private fun captureScreen(name: String, content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { VelinTheme(darkTheme = dark, dynamicColor = false, content = content) }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            captureScreenRoboImage("build/outputs/roborazzi/${name}_$theme.png")
        }
    }

    @Test
    fun moveSheet() = captureScreen("move_sheet") {
        MoveToSheet(
            folders = SampleMail.systemFolders.drop(1) + SampleMail.customFolders,
            onPick = {},
            onDismiss = {},
        )
    }

    @Test
    fun labelSheet() = captureScreen("label_sheet") {
        LabelSheet(
            choices = labelChoices(SampleMail.labels, listOf(setOf("l1", "l2"), setOf("l1"))),
            onApply = { _, _ -> },
            onDismiss = {},
        )
    }
}

private const val SETTLE_MS = 1_000L
