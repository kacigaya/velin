package app.velin.navigation

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.velin.core.designsystem.theme.VelinTheme
import app.velin.core.domain.Account
import app.velin.core.domain.AccountId
import app.velin.core.domain.Address
import app.velin.core.domain.AttachmentId
import app.velin.core.domain.AuthRepository
import app.velin.core.domain.AuthState
import app.velin.core.domain.Conversation
import app.velin.core.domain.ConversationId
import app.velin.core.domain.Draft
import app.velin.core.domain.DraftId
import app.velin.core.domain.ElementId
import app.velin.core.domain.EntityId
import app.velin.core.domain.Failure
import app.velin.core.domain.Folder
import app.velin.core.domain.FolderId
import app.velin.core.domain.FolderKind
import app.velin.core.domain.Label
import app.velin.core.domain.LabelId
import app.velin.core.domain.ListId
import app.velin.core.domain.MailId
import app.velin.core.domain.MailMessage
import app.velin.core.domain.MailPage
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailSummary
import app.velin.core.domain.MailboxRepository
import app.velin.core.domain.Outcome
import app.velin.core.domain.PageCursor
import app.velin.core.domain.Recipient
import app.velin.core.domain.ReplyType
import app.velin.core.domain.SecondFactorAnswer
import app.velin.core.domain.SenderAlias
import app.velin.core.domain.SyncState
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Renders the signed-in shell at phone and tablet widths to check the adaptive layout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, graph: AppGraph = FakeGraph) {
        compose.mainClock.autoAdvance = false
        compose.setContent { VelinTheme(darkTheme = false, dynamicColor = false) { VelinApp(graph) } }
        // View model coroutines run on the main looper, which Robolectric pauses.
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(500)
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun phone() = capture("shell_phone")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
    fun tablet() = capture("shell_tablet")

    /** What a signed-in user sees until the mail backend exists. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun signedInWithoutMail() = capture("shell_signed_in_no_mail", object : AppGraph {
        override val auth = FakeGraph.auth
        override val mailbox: MailboxRepository? = null
        override val mail: MailRepository? = null
    })
}

private fun id(list: String, element: String) = EntityId(ListId(list), ElementId(element))

private object FakeGraph : AppGraph {
    private val account = Account(AccountId(ElementId("u1")), Address("alex@tuta.com", "Alex Martin"))
    private val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
    private val folders = listOf(
        Folder(FolderId(id("f", "inbox")), "Inbox", FolderKind.Inbox, 2),
        Folder(FolderId(id("f", "drafts")), "Drafts", FolderKind.Drafts, 0),
        Folder(FolderId(id("f", "sent")), "Sent", FolderKind.Sent, 0),
        Folder(FolderId(id("f", "archive")), "Archive", FolderKind.Archive, 0),
        Folder(FolderId(id("f", "spam")), "Spam", FolderKind.Spam, 0),
        Folder(FolderId(id("f", "trash")), "Trash", FolderKind.Trash, 0),
        Folder(FolderId(id("f", "projects")), "Projects", FolderKind.Custom, 1),
    )
    private fun mail(n: Int, from: String, subject: String, hoursAgo: Long, unread: Boolean) = MailSummary(
        MailId(id("m", "m$n")), ConversationId(id("c", "c$n")), listOf(Address("$from@tuta.com".lowercase(), from)),
        subject, now.minus(hoursAgo, ChronoUnit.HOURS), unread, n == 1, true, ReplyType.None, emptyList(), 1,
    )

    override val auth = object : AuthRepository {
        override val state: Flow<AuthState> = MutableStateFlow(AuthState.Active(account))
        override suspend fun signIn(address: String, password: CharArray) = Unit
        override suspend fun answerSecondFactor(answer: SecondFactorAnswer) = Unit
        override suspend fun cancelSignIn() = Unit
        override suspend fun resume(account: AccountId) = Unit
        override suspend fun removeSavedAccount(account: AccountId): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun signOut(account: AccountId): Outcome<Unit> = Outcome.Success(Unit)
    }
    override val mailbox: MailboxRepository = object : MailboxRepository {
        override fun folders(account: AccountId): Flow<List<Folder>> = flowOf(folders)
        override fun labels(account: AccountId): Flow<List<Label>> = flowOf(emptyList())
        override fun senders(account: AccountId): Flow<List<SenderAlias>> = flowOf(emptyList())
        override fun syncState(account: AccountId): Flow<SyncState> = flowOf(SyncState.Idle)
        override suspend fun sync(account: AccountId): Outcome<Unit> = Outcome.Success(Unit)
    }
    override val mail: MailRepository = object : MailRepository {
        override fun messages(account: AccountId, folder: FolderId): Flow<MailPage> = flowOf(
            MailPage(
                listOf(
                    mail(1, "Lena Fischer", "Design review moved to Thursday", 1, true),
                    mail(2, "Sam Okafor", "Weekend hike", 4, true),
                    mail(3, "Mira Chen", "Invoice for October", 30, false),
                ),
                null,
            ),
        )
        override fun conversation(account: AccountId, id: ConversationId): Flow<Outcome<Conversation>> = flowOf(Outcome.Failed(Failure.Offline))
        override suspend fun loadMore(account: AccountId, folder: FolderId, cursor: PageCursor): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun load(account: AccountId, id: MailId): Outcome<MailMessage> = Outcome.Failed(Failure.Unsupported)
        override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> = Outcome.Failed(Failure.Unsupported)
        override suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
        override suspend fun downloadAttachment(account: AccountId, id: AttachmentId): Outcome<File> = Outcome.Failed(Failure.Unsupported)
        override fun search(account: AccountId, query: String): Flow<Outcome<List<MailSummary>>> = flowOf(Outcome.Success(emptyList()))
        override fun suggestRecipients(account: AccountId, query: String): Flow<List<Recipient>> = flowOf(emptyList())
    }
}
