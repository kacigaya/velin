package app.velin.feature.mail.screenshot

import app.velin.feature.mail.model.AccountUi
import app.velin.feature.mail.model.AttachmentUi
import app.velin.feature.mail.model.BodyUi
import app.velin.feature.mail.model.ConversationUi
import app.velin.feature.mail.model.FolderKind
import app.velin.feature.mail.model.FolderUi
import app.velin.feature.mail.model.LabelUi
import app.velin.feature.mail.model.MailBannerUi
import app.velin.feature.mail.model.MailSummaryUi
import app.velin.feature.mail.model.MessageUi
import app.velin.feature.mail.model.RecipientUi
import app.velin.feature.mail.model.ReplyState
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.collections.immutable.persistentListOf

/** Fixed sample content for screenshot tests. Not shipped. */
internal object SampleMail {
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS)
    val account = AccountUi("u1", "Alex Martin", "alex@tuta.com")
    val inbox = FolderUi("f-inbox", "Inbox", FolderKind.Inbox, unreadCount = 3)
    private val work = LabelUi("l1", "Work", "#2196F3")
    private val travel = LabelUi("l2", "Travel", "#FFB300")

    val systemFolders = persistentListOf(
        inbox,
        FolderUi("f-drafts", "Drafts", FolderKind.Drafts, 0),
        FolderUi("f-sent", "Sent", FolderKind.Sent, 0),
        FolderUi("f-sched", "Scheduled", FolderKind.Scheduled, 0),
        FolderUi("f-archive", "Archive", FolderKind.Archive, 0),
        FolderUi("f-spam", "Spam", FolderKind.Spam, 1),
        FolderUi("f-trash", "Trash", FolderKind.Trash, 0),
    )
    val customFolders = persistentListOf(
        FolderUi("c1", "Projects", FolderKind.Custom, 2),
        FolderUi("c2", "Velin", FolderKind.Custom, 0, depth = 1),
    )
    val labels = persistentListOf(work, travel)

    val mails = persistentListOf(
        MailSummaryUi(
            id = "m1", conversationId = "c1", heading = "Lena Fischer", headingAddress = "lena@tuta.com",
            subject = "Design review moved to Thursday", receivedAt = now.minus(1, ChronoUnit.HOURS),
            unread = true, hasAttachments = true, confidential = true, labels = persistentListOf(work), conversationSize = 3,
        ),
        MailSummaryUi(
            id = "m2", conversationId = "c2", heading = "Rail Europe", headingAddress = "tickets@example.com",
            subject = "Your booking confirmation", receivedAt = now.minus(5, ChronoUnit.HOURS),
            unread = true, hasAttachments = true, confidential = false, labels = persistentListOf(travel),
        ),
        MailSummaryUi(
            id = "m3", conversationId = "c3", heading = "Sam Okafor", headingAddress = "sam@tutanota.de",
            subject = "Re: Weekend hike", receivedAt = now.minus(2, ChronoUnit.DAYS),
            unread = false, hasAttachments = false, confidential = true, replyState = ReplyState.Replied,
        ),
        MailSummaryUi(
            id = "m4", conversationId = "c4", heading = "Open Source Weekly", headingAddress = "news@example.org",
            subject = "Issue 214: Kotlin 2.4, Compose and more", receivedAt = now.minus(9, ChronoUnit.DAYS),
            unread = false, hasAttachments = false, confidential = false,
        ),
    )

    val conversation = ConversationUi(
        id = "c1",
        subject = "Design review moved to Thursday",
        labels = persistentListOf(work),
        folderKind = FolderKind.Inbox,
        messages = persistentListOf(
            MessageUi(
                id = "msg1", sender = RecipientUi("Alex Martin", "alex@tuta.com"),
                to = persistentListOf(RecipientUi("Lena Fischer", "lena@tuta.com")),
                sentAt = now.minus(1, ChronoUnit.DAYS), confidential = true, unread = false,
                body = BodyUi.Loaded("<p>Can we move the review?</p>"),
            ),
            MessageUi(
                id = "msg2", sender = RecipientUi("Lena Fischer", "lena@tuta.com"),
                to = persistentListOf(RecipientUi("Alex Martin", "alex@tuta.com"), RecipientUi("Sam Okafor", "sam@tutanota.de")),
                sentAt = now.minus(1, ChronoUnit.HOURS), confidential = true, unread = true,
                body = BodyUi.Loading,
                attachments = persistentListOf(AttachmentUi("a1", "review-notes.pdf", "application/pdf", 482_133)),
                banners = persistentListOf(MailBannerUi.ExternalContentBlocked),
            ),
        ),
    )
}
