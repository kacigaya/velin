package app.velin.feature.mail.model

import app.velin.core.domain.Address
import app.velin.core.domain.Attachment
import app.velin.core.domain.AttachmentId
import app.velin.core.domain.BodyState
import app.velin.core.domain.MailAuthentication
import app.velin.core.domain.MailFinding
import app.velin.core.domain.MailId
import app.velin.core.domain.MailMessage
import app.velin.feature.mail.eid
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainMappingTest {
    private fun message(finding: MailFinding?, body: BodyState, scheduled: Instant? = null) = MailMessage(
        id = MailId(eid("mails", "m1")), from = Address("a@tuta.com"), to = emptyList(), cc = emptyList(), bcc = emptyList(),
        replyTo = emptyList(), subject = "s", sentAt = Instant.EPOCH, confidential = true, unread = false, isDraft = false,
        body = body, attachments = listOf(
            Attachment(AttachmentId(eid("files", "f1")), "a.pdf", "application/pdf", 10),
            Attachment(AttachmentId(eid("files", "f2")), "logo.png", "image/png", 10, contentId = "logo"),
        ),
        authentication = MailAuthentication.Authenticated, finding = finding, scheduledSendAt = scheduled,
    )

    @Test
    fun idsRoundTrip() {
        val id = eid("LIST-a_1", "ELEM-b_2")
        assertEquals(id, entityIdOf(id.toUiId()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedIds() {
        entityIdOf("no-separator")
    }

    @Test
    fun mapsFindingThenBlockedContentThenSchedule() {
        val at = Instant.parse("2026-11-09T08:00:00Z")
        val ui = message(MailFinding.Phishing, BodyState.Loaded("<p>x</p>", true, true, emptyMap()), at).toUi()
        assertEquals(listOf(MailBannerUi.Phishing, MailBannerUi.ExternalContentBlocked, MailBannerUi.ScheduledSend(at)), ui.banners)
    }

    @Test
    fun noBlockedBannerWhenContentAllowed() {
        val ui = message(null, BodyState.Loaded("<p>x</p>", true, false, emptyMap())).toUi()
        assertEquals(emptyList<MailBannerUi>(), ui.banners)
    }

    @Test
    fun hidesInlineImagesFromAttachmentList() {
        val ui = message(null, BodyState.Loading).toUi()
        assertEquals(listOf("a.pdf"), ui.attachments.map { it.name })
    }
}
