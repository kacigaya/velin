package app.velin.feature.mail.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.velin.core.domain.Account
import app.velin.core.domain.Failure
import app.velin.core.domain.FolderId
import app.velin.core.domain.FolderKind as DomainFolderKind
import app.velin.core.domain.MailId
import app.velin.core.domain.MailPage
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailboxRepository
import app.velin.core.domain.Outcome
import app.velin.core.domain.SyncState
import app.velin.feature.mail.model.AccountUi
import app.velin.feature.mail.model.entityIdOf
import app.velin.feature.mail.model.toUi
import app.velin.feature.mail.model.toUiId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-off results the screen host handles: navigation, pickers and messages. */
sealed interface MailboxEffect {
    data class OpenConversation(val conversationId: String) : MailboxEffect
    data object OpenComposer : MailboxEffect
    data object OpenFolders : MailboxEffect
    data object OpenAccount : MailboxEffect
    data class OpenSearch(val query: String) : MailboxEffect
    data class PickFolder(val mailIds: Set<String>) : MailboxEffect
    data class PickLabels(val mailIds: Set<String>) : MailboxEffect
    data class ActionFailed(val failure: Failure) : MailboxEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModel(
    private val account: Account,
    private val mailbox: MailboxRepository,
    private val mail: MailRepository,
) : ViewModel() {
    private val selectedFolderId = MutableStateFlow<FolderId?>(null)
    private val selection = MutableStateFlow(persistentSetOf<String>())
    private val openMailId = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val effectChannel = Channel<MailboxEffect>(Channel.BUFFERED)
    val effects: Flow<MailboxEffect> = effectChannel.receiveAsFlow()

    private val folders = mailbox.folders(account.id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** The selected folder, or the inbox until the user picks one. */
    private val currentFolder = combine(folders, selectedFolderId) { all, selected ->
        all.firstOrNull { it.id == selected } ?: all.firstOrNull { it.kind == DomainFolderKind.Inbox }
    }

    // Shared so paging reads the same page the screen shows.
    private val page: StateFlow<MailPage?> = currentFolder
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) emptyFlow() else mail.messages(account.id, id).onStart<MailPage?> { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val folderPane: StateFlow<FolderPaneState> = combine(folders, mailbox.labels(account.id), currentFolder) { all, labels, current ->
        FolderPaneState(
            account = accountUi,
            systemFolders = all.filter { it.kind != DomainFolderKind.Custom }.map { it.toUi() }.toImmutableList(),
            customFolders = all.filter { it.kind == DomainFolderKind.Custom }.map { it.toUi() }.toImmutableList(),
            labels = labels.map { it.toUi() }.toImmutableList(),
            selectedId = current?.id?.value?.toUiId(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        FolderPaneState(accountUi, persistentListOf(), persistentListOf(), persistentListOf(), null),
    )

    val state: StateFlow<MailboxUiState> = combine(
        currentFolder,
        page,
        mailbox.syncState(account.id),
        combine(selection, openMailId, refreshing, ::Triple),
    ) { folder, page, sync, (selected, open, isRefreshing) ->
        val mails = page?.mails.orEmpty().map { it.toUi() }.toImmutableList()
        MailboxUiState(
            account = accountUi,
            folder = folder?.toUi(),
            mails = mails,
            loading = page == null,
            refreshing = isRefreshing || sync is SyncState.Syncing,
            offline = sync is SyncState.Offline,
            canLoadMore = page?.next != null,
            // Drop ids that left the list, e.g. after a move from another device.
            selectedIds = selected.filter { id -> mails.any { it.id == id } }.toImmutableSet(),
            openMailId = open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MailboxUiState(account = accountUi))

    private val accountUi get() = AccountUi(account.id.value.value, account.address.name, account.address.address)

    fun selectFolder(folderUiId: String) {
        selectedFolderId.value = FolderId(entityIdOf(folderUiId))
        selection.value = persistentSetOf()
    }

    fun onEvent(event: MailboxEvent) {
        when (event) {
            is MailboxEvent.Open -> {
                openMailId.value = event.mail.id
                effectChannel.trySend(MailboxEffect.OpenConversation(event.mail.conversationId))
            }
            is MailboxEvent.ToggleSelection -> selection.update { if (event.mailId in it) it.removing(event.mailId) else it.adding(event.mailId) }
            MailboxEvent.ClearSelection -> selection.value = persistentSetOf()
            MailboxEvent.Refresh -> viewModelScope.launch {
                refreshing.value = true
                report(mailbox.sync(account.id))
                refreshing.value = false
            }
            MailboxEvent.LoadMore -> viewModelScope.launch {
                val folder = state.value.folder ?: return@launch
                val cursor = page.value?.next ?: return@launch
                report(mail.loadMore(account.id, FolderId(entityIdOf(folder.id)), cursor))
            }
            MailboxEvent.Compose -> effectChannel.trySend(MailboxEffect.OpenComposer)
            MailboxEvent.OpenFolders -> effectChannel.trySend(MailboxEffect.OpenFolders)
            MailboxEvent.OpenAccount -> effectChannel.trySend(MailboxEffect.OpenAccount)
            is MailboxEvent.Search -> effectChannel.trySend(MailboxEffect.OpenSearch(event.query))
            is MailboxEvent.Archive -> moveTo(event.mailIds, DomainFolderKind.Archive)
            is MailboxEvent.Trash -> moveTo(event.mailIds, DomainFolderKind.Trash)
            is MailboxEvent.ReportSpam -> moveTo(event.mailIds, DomainFolderKind.Spam)
            is MailboxEvent.SetUnread -> act(event.mailIds) { mail.setUnread(account.id, it, event.unread) }
            is MailboxEvent.RestoreToInbox -> moveTo(event.mailIds, DomainFolderKind.Inbox)
            is MailboxEvent.Move -> effectChannel.trySend(MailboxEffect.PickFolder(event.mailIds))
            is MailboxEvent.Label -> effectChannel.trySend(MailboxEffect.PickLabels(event.mailIds))
        }
    }

    /** Moves to a folder chosen in a picker. */
    fun moveToFolder(mailIds: Set<String>, folderUiId: String) =
        act(mailIds) { mail.move(account.id, it, FolderId(entityIdOf(folderUiId))) }

    private fun moveTo(mailIds: Set<String>, kind: DomainFolderKind) {
        val target = folders.value.firstOrNull { it.kind == kind } ?: return
        act(mailIds) { mail.move(account.id, it, target.id) }
    }

    private fun act(mailIds: Set<String>, action: suspend (List<MailId>) -> Outcome<Unit>) {
        if (mailIds.isEmpty()) return
        selection.value = persistentSetOf()
        viewModelScope.launch { report(action(mailIds.map { MailId(entityIdOf(it)) })) }
    }

    private fun report(outcome: Outcome<Unit>) {
        if (outcome is Outcome.Failed) effectChannel.trySend(MailboxEffect.ActionFailed(outcome.failure))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
