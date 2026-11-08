package app.velin

import android.app.Application
import app.velin.core.data.VelinBackend
import app.velin.core.domain.AuthRepository
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailboxRepository
import app.velin.navigation.AppGraph

/** Owns the single backend instance for the process. */
class VelinApplication : Application(), AppGraph {
    private val backend by lazy { VelinBackend(this) }
    override val auth: AuthRepository get() = backend.auth
    override val mailbox: MailboxRepository get() = backend.mailbox
    override val mail: MailRepository get() = backend.mail
}
