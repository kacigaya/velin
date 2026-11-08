package app.velin.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// Back stack keys. They are saved across process death, so they hold ids only, never mail content.

@Serializable
data object MailboxRoute : NavKey

@Serializable
data class ConversationRoute(val conversationId: String) : NavKey

@Serializable
data object SettingsRoute : NavKey
