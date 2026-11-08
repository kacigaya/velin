package app.velin.feature.mail.mailbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.LoadingPane
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.component.StatusMessage
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R
import app.velin.feature.mail.model.AccountUi
import app.velin.feature.mail.model.FolderKind
import app.velin.feature.mail.model.FolderUi
import app.velin.feature.mail.model.MailSummaryUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

data class MailboxUiState(
    val account: AccountUi? = null,
    val folder: FolderUi? = null,
    val mails: ImmutableList<MailSummaryUi> = persistentListOf(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val canLoadMore: Boolean = false,
    val selectedIds: ImmutableSet<String> = persistentSetOf(),
    /** Mail shown in the detail pane, highlighted when both panes are visible. */
    val openMailId: String? = null,
)

sealed interface MailboxEvent {
    data class Open(val mail: MailSummaryUi) : MailboxEvent
    data class ToggleSelection(val mailId: String) : MailboxEvent
    data object ClearSelection : MailboxEvent
    data object Refresh : MailboxEvent
    data object LoadMore : MailboxEvent
    data object Compose : MailboxEvent
    data object OpenFolders : MailboxEvent
    data object OpenAccount : MailboxEvent
    data class Search(val query: String) : MailboxEvent
    data class Archive(val mailIds: Set<String>) : MailboxEvent
    data class Trash(val mailIds: Set<String>) : MailboxEvent
    data class SetUnread(val mailIds: Set<String>, val unread: Boolean) : MailboxEvent
    data class ReportSpam(val mailIds: Set<String>) : MailboxEvent
    data class Move(val mailIds: Set<String>) : MailboxEvent
    data class RestoreToInbox(val mailIds: Set<String>) : MailboxEvent
    data class Label(val mailIds: Set<String>) : MailboxEvent
}

/**
 * Mail list pane. Stateless: the caller owns [state] and handles every [MailboxEvent].
 * [showFolderButton] is false when the folder pane is permanently visible.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MailboxScreen(
    state: MailboxUiState,
    onEvent: (MailboxEvent) -> Unit,
    modifier: Modifier = Modifier,
    showFolderButton: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()
    val selectionMode = state.selectedIds.isNotEmpty()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val searchScrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    LoadMoreEffect(listState, state, onEvent)

    Scaffold(
        modifier = modifier.nestedScroll(searchScrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "mailboxTopBar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(state, onEvent)
                } else {
                    SearchTopBar(state, onEvent, showFolderButton, searchScrollBehavior)
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                MediumExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.mailbox_compose)) },
                    icon = { Icon(painterResource(VelinIcons.Edit), contentDescription = null) },
                    onClick = { onEvent(MailboxEvent.Compose) },
                    expanded = fabExpanded,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.mails.isEmpty() -> LoadingPane(stringResource(R.string.mailbox_loading))
                else -> MailList(state, listState, selectionMode, onEvent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MailList(
    state: MailboxUiState,
    listState: LazyListState,
    selectionMode: Boolean,
    onEvent: (MailboxEvent) -> Unit,
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onEvent(MailboxEvent.Refresh) },
        state = refreshState,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = refreshState,
                isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        if (state.mails.isEmpty()) {
            StatusMessage(
                icon = state.folder?.kind?.let(::folderIcon) ?: VelinIcons.Inbox,
                title = stringResource(R.string.mailbox_empty_title),
                body = stringResource(R.string.mailbox_empty_body),
            )
            return@PullToRefreshBox
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room for the FAB so the last row stays reachable.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "offline", contentType = "banner") {
                AnimatedVisibility(
                    visible = state.offline,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) { OfflineBanner() }
            }
            items(state.mails, key = { it.id }, contentType = { "mail" }) { mail ->
                SwipeableMailRow(
                    mail = mail,
                    folderKind = state.folder?.kind,
                    selected = mail.id in state.selectedIds || (!selectionMode && mail.id == state.openMailId),
                    selectionMode = selectionMode,
                    onEvent = onEvent,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMailRow(
    mail: MailSummaryUi,
    folderKind: FolderKind?,
    selected: Boolean,
    selectionMode: Boolean,
    onEvent: (MailboxEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = setOf(mail.id)
    // Matches the official client: start-to-end archives (or restores to inbox from the
    // archive), end-to-start moves to trash.
    val archiveEvent = if (folderKind == FolderKind.Archive) MailboxEvent.RestoreToInbox(ids) else MailboxEvent.Archive(ids)
    val actions = MailRowActions(
        onArchive = { onEvent(archiveEvent) },
        onTrash = { onEvent(MailboxEvent.Trash(ids)) },
        onToggleRead = { onEvent(MailboxEvent.SetUnread(ids, !mail.unread)) },
    )
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier,
        gesturesEnabled = !selectionMode,
        backgroundContent = { SwipeBackground(swipeState.dismissDirection, folderKind) },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> actions.onArchive()
                SwipeToDismissBoxValue.EndToStart -> actions.onTrash()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The row leaves the list on success. If the action fails or is undone it stays,
            // so it must not remain swiped away.
            scope.launch { swipeState.reset() }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            MailRow(
                mail = mail,
                selected = selected,
                selectionMode = selectionMode,
                onClick = {
                    if (selectionMode) onEvent(MailboxEvent.ToggleSelection(mail.id)) else onEvent(MailboxEvent.Open(mail))
                },
                onLongClick = { onEvent(MailboxEvent.ToggleSelection(mail.id)) },
                actions = actions,
            )
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, folderKind: FolderKind?) {
    val colors = MaterialTheme.colorScheme
    val (container, content, icon) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            if (folderKind == FolderKind.Archive) VelinIcons.MoveToInbox else VelinIcons.Archive,
        )
        SwipeToDismissBoxValue.EndToStart -> Triple(colors.errorContainer, colors.onErrorContainer, VelinIcons.Delete)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, Color.Transparent, null)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchTopBar(
    state: MailboxUiState,
    onEvent: (MailboxEvent) -> Unit,
    showFolderButton: Boolean,
    scrollBehavior: SearchBarScrollBehavior,
) {
    val searchBarState = rememberSearchBarState()
    val query = rememberTextFieldState()
    AppBarWithSearch(
        state = searchBarState,
        scrollBehavior = scrollBehavior,
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = query,
                searchBarState = searchBarState,
                onSearch = { onEvent(MailboxEvent.Search(it)) },
                placeholder = {
                    Text(
                        text = state.folder?.let { stringResource(R.string.mailbox_search_in, folderName(it)) }
                            ?: stringResource(R.string.mailbox_search_hint),
                        maxLines = 1,
                    )
                },
                leadingIcon = if (showFolderButton) {
                    {
                        IconButton(onClick = { onEvent(MailboxEvent.OpenFolders) }) {
                            Icon(painterResource(VelinIcons.Menu), stringResource(R.string.mailbox_open_folders))
                        }
                    }
                } else {
                    { Icon(painterResource(VelinIcons.Search), contentDescription = null) }
                },
                trailingIcon = state.account?.let { account ->
                    {
                        IconButton(onClick = { onEvent(MailboxEvent.OpenAccount) }) {
                            SenderAvatar(name = account.displayName, address = account.address, size = 32.dp)
                        }
                    }
                },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: MailboxUiState, onEvent: (MailboxEvent) -> Unit) {
    val ids: Set<String> = state.selectedIds
    val anyUnread = state.mails.any { it.id in ids && it.unread }
    TopAppBar(
        title = { Text(stringResource(R.string.mailbox_selected_count, ids.size)) },
        navigationIcon = {
            IconButton(onClick = { onEvent(MailboxEvent.ClearSelection) }) {
                Icon(painterResource(VelinIcons.Close), stringResource(R.string.mailbox_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = { onEvent(MailboxEvent.Archive(ids)) }) {
                Icon(painterResource(VelinIcons.Archive), stringResource(R.string.action_archive))
            }
            IconButton(onClick = { onEvent(MailboxEvent.Trash(ids)) }) {
                Icon(painterResource(VelinIcons.Delete), stringResource(R.string.action_trash))
            }
            IconButton(onClick = { onEvent(MailboxEvent.SetUnread(ids, !anyUnread)) }) {
                if (anyUnread) {
                    Icon(painterResource(VelinIcons.MarkRead), stringResource(R.string.action_mark_read))
                } else {
                    Icon(painterResource(VelinIcons.MarkUnread), stringResource(R.string.action_mark_unread))
                }
            }
            OverflowMenu(
                listOf(
                    OverflowAction(stringResource(R.string.action_move), VelinIcons.Move) { onEvent(MailboxEvent.Move(ids)) },
                    OverflowAction(stringResource(R.string.action_label), VelinIcons.Label) { onEvent(MailboxEvent.Label(ids)) },
                    OverflowAction(stringResource(R.string.action_report_spam), VelinIcons.Report) { onEvent(MailboxEvent.ReportSpam(ids)) },
                ),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(VelinIcons.CloudOff), contentDescription = null)
            Text(stringResource(R.string.mailbox_offline), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Requests the next page when the last visible row is within [PREFETCH_DISTANCE] of the end. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, state: MailboxUiState, onEvent: (MailboxEvent) -> Unit) {
    LaunchedEffect(listState, state.canLoadMore) {
        if (!state.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - PREFETCH_DISTANCE
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onEvent(MailboxEvent.LoadMore) }
    }
}

private const val PREFETCH_DISTANCE = 10
