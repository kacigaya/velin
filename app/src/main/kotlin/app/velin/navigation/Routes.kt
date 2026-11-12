package app.velin.navigation

import androidx.navigation3.runtime.NavKey
import app.velin.core.domain.ResponseKind
import kotlinx.serialization.Serializable

// Back stack keys. They are saved across process death, so they hold ids only, never mail content.

@Serializable
data object MailboxRoute : NavKey

@Serializable
data class ConversationRoute(val conversationId: String) : NavKey

/** An existing draft when [draftId] is set, a reply or forward of [respondTo] (UI id), otherwise new mail. */
@Serializable
data class ComposerRoute(val draftId: String? = null, val respondTo: String? = null, val kind: ResponseKind? = null) : NavKey

@Serializable
data object SettingsRoute : NavKey
