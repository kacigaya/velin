package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.html.DraftHtml
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ResponseDraftTest {
    private val me = SenderAlias(Address("me@tuta.com", "Me"), true)
    private val alias = SenderAlias(Address("alias@tuta.com", "Alias"), true)
    private val disabled = SenderAlias(Address("old@tuta.com"), false)
    private val id = MailId(EntityId(ListId("mails"), ElementId("previous")))
    private fun message(from: Address = Address("ann@tuta.com", "Ann"), to: List<Address> = listOf(alias.address, Address("bob@tuta.com")),
        cc: List<Address> = listOf(Address("Carl@tuta.com"), Address("bob@tuta.com")), replyTo: List<Address> = emptyList(),
        subject: String = "Plans", html: String? = "Hello<blockquote>Earlier</blockquote>", attachments: Int = 0, received: Boolean = true) =
        MailMessage(id, from, to, cc, listOf(Address("hidden@tuta.com")), replyTo, subject, Instant.parse("2026-11-12T09:30:00Z"),
            true, false, false, html?.let { BodyState.Loaded(it, false, false, emptyMap()) } ?: BodyState.Failed(Failure.Offline),
            List(attachments) { Attachment(AttachmentId(EntityId(ListId("files"), ElementId("f$it"))), "f$it", "text/plain", 1) },
            MailAuthentication.Authenticated, null, null, received)
    private fun respond(previous: MailMessage, kind: ResponseKind, senders: List<SenderAlias> = listOf(disabled, me, alias)) =
        responseDraft(previous, kind, senders, ZoneOffset.UTC, Locale.UK)!!
    private val PreparedResponse.recipients get() = listOf(draft.to, draft.cc, draft.bcc).map { list -> list.map { it.address.address } }

    @Test fun replyGoesToSenderOrReplyToFromTheReceivingAddress() {
        val reply = respond(message(), ResponseKind.Reply)
        assertEquals(listOf(listOf("ann@tuta.com"), emptyList(), emptyList()), reply.recipients)
        assertEquals(alias, reply.draft.sender)
        assertEquals("Re: Plans", reply.draft.subject)
        assertEquals(DraftResponse(id, ResponseKind.Reply), reply.draft.response)
        assertTrue(reply.draft.confidential && reply.draft.id == null && reply.draft.attachments.isEmpty())
        val replyTo = respond(message(replyTo = listOf(Address("list@tuta.com"))), ResponseKind.Reply)
        assertEquals(listOf("list@tuta.com"), replyTo.recipients[0])
    }
    @Test fun replyAllAddsOtherRecipientsOnceWithoutOwnAddresses() {
        val reply = respond(message(to = listOf(alias.address, Address("bob@tuta.com"), Address("ME@tuta.com"))), ResponseKind.ReplyAll)
        assertEquals(listOf(listOf("ann@tuta.com"), listOf("bob@tuta.com", "Carl@tuta.com"), emptyList()), reply.recipients)
    }
    @Test fun replyToSentMailGoesToItsRecipientsAgain() {
        val sent = message(from = me.address, received = false)
        assertEquals(listOf(listOf("alias@tuta.com", "bob@tuta.com"), emptyList(), emptyList()), respond(sent, ResponseKind.Reply).recipients)
        val all = respond(sent, ResponseKind.ReplyAll)
        assertEquals(listOf(listOf("alias@tuta.com", "bob@tuta.com"), listOf("Carl@tuta.com"), listOf("hidden@tuta.com")), all.recipients)
        assertEquals(me, all.draft.sender)
        // Mail state decides the direction, not the sender address.
        val oldAlias = respond(message(from = disabled.address, received = false), ResponseKind.Reply)
        assertEquals(listOf("alias@tuta.com", "bob@tuta.com"), oldAlias.recipients[0])
        assertEquals(me, oldAlias.draft.sender)
        val selfSent = respond(message(from = me.address, replyTo = listOf(Address("list@tuta.com"))), ResponseKind.Reply)
        assertEquals(listOf("list@tuta.com"), selfSent.recipients[0])
    }
    @Test fun replyQuotesTheBodyWithOneMoreLevel() {
        val reply = respond(message(), ResponseKind.Reply)
        assertEquals("\n\n12 Nov 2026, 09:30 by ann@tuta.com:\n> Hello\n>> Earlier", reply.draft.plainText)
        assertEquals("<br><br>12 Nov 2026, 09:30 by ann@tuta.com:<blockquote>Hello<blockquote>Earlier</blockquote></blockquote>",
            DraftHtml.fromPlainText(reply.draft.plainText))
    }
    @Test fun replySubjectPrefixIsNotRepeated() {
        assertEquals("RE: Plans", respond(message(subject = "RE: Plans"), ResponseKind.Reply).draft.subject)
        assertEquals("", respond(message(subject = ""), ResponseKind.Reply).draft.subject)
    }
    @Test fun forwardHasNoRecipientsAHeaderAndReportsOmittedFiles() {
        val forward = respond(message(attachments = 2), ResponseKind.Forward)
        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList()), forward.recipients)
        assertEquals("FWD: Plans", forward.draft.subject)
        assertEquals(2, forward.omittedAttachments)
        assertEquals(0, respond(message(attachments = 2), ResponseKind.Reply).omittedAttachments)
        assertEquals("\n\nDate: 12 Nov 2026, 09:30\nFrom: ann@tuta.com\nTo: alias@tuta.com, bob@tuta.com\n" +
            "Cc: Carl@tuta.com, bob@tuta.com\nSubject: Plans\n\n> Hello\n>> Earlier", forward.draft.plainText)
    }
    @Test fun withoutEnabledSenderThereIsNoResponse() {
        assertNull(responseDraft(message(), ResponseKind.Reply, listOf(disabled)))
        assertEquals(me, respond(message(to = listOf(Address("other@tuta.com")), cc = emptyList()), ResponseKind.Reply).draft.sender)
    }
}
