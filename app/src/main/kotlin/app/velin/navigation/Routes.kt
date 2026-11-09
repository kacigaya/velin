package app.velin.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// Back stack keys. They are saved across process death, so they hold ids only, never mail content.

@Serializable
data object MailboxRoute : NavKey

@Serializable
data class ConversationRoute(val conversationId: String) : NavKey

/** New mail when [draftId] is null, otherwise an existing draft (UI id). */
@Serializable
data class ComposerRoute(val draftId: String? = null) : NavKey

@Serializable
data object SettingsRoute : NavKey
