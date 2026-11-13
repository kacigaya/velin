package app.velin.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.velin.R
import app.velin.core.designsystem.component.StatusMessage
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.core.domain.Account
import app.velin.core.domain.AuthRepository
import app.velin.core.domain.AuthState
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailboxRepository
import app.velin.core.domain.ResponseKind
import app.velin.feature.auth.SignInScreen
import app.velin.feature.auth.SignInViewModel
import androidx.activity.compose.BackHandler
import app.velin.core.domain.Failure
import app.velin.feature.mail.composer.ComposerEffect
import app.velin.feature.mail.composer.ComposerEvent
import app.velin.feature.mail.composer.ComposerScreen
import app.velin.feature.mail.composer.ComposerViewModel
import app.velin.feature.mail.composer.composerFailureMessage
import app.velin.feature.mail.mailbox.FolderPane
import app.velin.feature.mail.mailbox.DeleteForeverDialog
import app.velin.feature.mail.mailbox.LabelSheet
import app.velin.feature.mail.mailbox.MoveToSheet
import app.velin.feature.mail.mailbox.PhishingReportDialog
import app.velin.feature.mail.mailbox.SpamReportDialog
import app.velin.feature.mail.mailbox.folderName
import app.velin.feature.mail.R as MailR
import app.velin.feature.mail.mailbox.MailboxEffect
import app.velin.feature.mail.mailbox.MailboxScreen
import app.velin.feature.mail.mailbox.MailboxViewModel
import app.velin.feature.mail.reader.ConversationEffect
import app.velin.feature.mail.reader.ConversationScreen
import app.velin.feature.mail.reader.ConversationUiState
import app.velin.feature.mail.reader.ConversationViewModel
import kotlinx.coroutines.launch

/** Backend entry points. :app builds the real graph; tests can pass fakes. */
interface AppGraph {
    val auth: AuthRepository
    /** Null without a mail backend; the app then offers only sign-out. */
    val mailbox: MailboxRepository?
    val mail: MailRepository?
}

@Composable
fun VelinApp(graph: AppGraph) {
    val authState by graph.auth.state.collectAsStateWithLifecycle(initialValue = null)
    val mailbox = graph.mailbox
    val mail = graph.mail
    when (val state = authState) {
        null -> Unit // First emission is immediate; nothing to draw before it.
        is AuthState.Active -> if (mailbox != null && mail != null) {
            MailApp(state.account, graph.auth, mailbox, mail)
        } else {
            SignedInWithoutMail(state.account, graph.auth)
        }
        else -> {
            val viewModel = viewModel { SignInViewModel(graph.auth) }
            val uiState by viewModel.state.collectAsStateWithLifecycle()
            SignInScreen(uiState, viewModel::onEvent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MailApp(account: Account, auth: AuthRepository, mailbox: MailboxRepository, mail: MailRepository) {
    val mailboxViewModel = viewModel(key = "mailbox-${account.id.value.value}") {
        MailboxViewModel(account, mailbox, mail)
    }
    val backStack = rememberNavBackStack(MailboxRoute)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val permanent = isFolderPanePermanent()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notAvailable = stringResource(R.string.not_available_yet)
    val actionFailed = stringResource(R.string.action_failed)

    val undoLabel = stringResource(MailR.string.action_undo)
    var moveRequest by remember { mutableStateOf<Set<String>?>(null) }
    var labelRequest by remember { mutableStateOf<Set<String>?>(null) }
    var deleteRequest by remember { mutableStateOf<Set<String>?>(null) }
    var spamReportRequest by remember { mutableStateOf<Set<String>?>(null) }
    val tooManyLabels = stringResource(MailR.string.too_many_labels)
    val mailSent = stringResource(MailR.string.composer_sent)
    val noViewer = stringResource(MailR.string.reader_no_viewer)

    LaunchedEffect(mailboxViewModel) {
        mailboxViewModel.effects.collect { effect ->
            // Suspending calls run in their own coroutine: a snackbar would stall navigation,
            // and a cancelled drawer open would end this collector.
            when (effect) {
                is MailboxEffect.OpenConversation -> backStack.showDetail(ConversationRoute(effect.conversationId))
                MailboxEffect.OpenFolders -> scope.launch { drawerState.open() }
                is MailboxEffect.PickFolder -> moveRequest = effect.mailIds
                is MailboxEffect.PickLabels -> labelRequest = effect.mailIds
                is MailboxEffect.ConfirmDelete -> deleteRequest = effect.mailIds
                is MailboxEffect.ConfirmSpamReport -> spamReportRequest = effect.mailIds
                MailboxEffect.TooManyLabels -> scope.launch { snackbar.showSnackbar(tooManyLabels) }
                is MailboxEffect.Moved -> scope.launch {
                    val resources = context.resources
                    val moved = resources.getQuantityString(
                        MailR.plurals.moved_to_folder, effect.count, effect.count, folderName(resources, effect.target),
                    )
                    val text = if (effect.report.isEmpty()) moved
                        else moved + " " + resources.getQuantityString(MailR.plurals.moved_will_report, effect.count)
                    val result = snackbar.showSnackbar(text, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) mailboxViewModel.undoLastMove()
                    else mailboxViewModel.reportSpam(effect.report)
                }
                is MailboxEffect.ActionFailed -> scope.launch { snackbar.showSnackbar(actionFailed) }
                MailboxEffect.OpenComposer -> backStack.add(ComposerRoute())
                // Search and account switching land with their milestones.
                MailboxEffect.OpenAccount, is MailboxEffect.OpenSearch ->
                    scope.launch { snackbar.showSnackbar(notAvailable) }
            }
        }
    }

    labelRequest?.let { ids ->
        LabelSheet(
            choices = remember(ids) { mailboxViewModel.labelChoices(ids) },
            onApply = { added, removed ->
                labelRequest = null
                mailboxViewModel.applyLabels(ids, added, removed)
            },
            onDismiss = { labelRequest = null },
        )
    }
    deleteRequest?.let { ids ->
        DeleteForeverDialog(
            count = ids.size,
            onConfirm = {
                deleteRequest = null
                mailboxViewModel.deleteForever(ids)
            },
            onDismiss = { deleteRequest = null },
        )
    }
    spamReportRequest?.let { ids ->
        SpamReportDialog(
            count = ids.size,
            onAnswer = { report ->
                spamReportRequest = null
                mailboxViewModel.moveToSpam(ids, report)
            },
            onDismiss = { spamReportRequest = null },
        )
    }
    moveRequest?.let { ids ->
        MoveToSheet(
            folders = mailboxViewModel.moveTargets(),
            onPick = { folder ->
                moveRequest = null
                mailboxViewModel.moveToFolder(ids, folder.id)
            },
            onDismiss = { moveRequest = null },
        )
    }

    val folderPaneState by mailboxViewModel.folderPane.collectAsStateWithLifecycle()
    AdaptiveShell(
        drawerState = drawerState,
        permanent = permanent,
        folderPane = {
            FolderPane(
                state = folderPaneState,
                onFolderClick = { folder ->
                    mailboxViewModel.selectFolder(folder.id)
                    backStack.popTo(MailboxRoute)
                    scope.launch { drawerState.close() }
                },
                onLabelClick = { scope.launch { snackbar.showSnackbar(notAvailable) } },
                onSettingsClick = {
                    backStack.add(SettingsRoute)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            sceneStrategies = listOf(rememberListDetailSceneStrategy()),
            entryProvider = entryProvider {
                entry<MailboxRoute>(
                    metadata = ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            StatusMessage(icon = VelinIcons.Mail, title = stringResource(R.string.no_mail_selected))
                        },
                    ),
                ) {
                    val state by mailboxViewModel.state.collectAsStateWithLifecycle()
                    MailboxScreen(
                        state = state,
                        onEvent = mailboxViewModel::onEvent,
                        showFolderButton = !permanent,
                        snackbarHostState = snackbar,
                        highlightOpenMail = permanent,
                    )
                }
                entry<ConversationRoute>(metadata = ListDetailSceneStrategy.detailPane()) { route ->
                    val viewModel = viewModel(key = route.conversationId) {
                        ConversationViewModel(account, route.conversationId, mailbox, mail)
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    var pickingFolder by remember { mutableStateOf(false) }
                    var askingSpamReport by remember { mutableStateOf(false) }
                    var askingPhishingReport by remember { mutableStateOf(false) }
                    LaunchedEffect(viewModel) {
                        viewModel.effects.collect { effect ->
                            when (effect) {
                                ConversationEffect.Close -> backStack.remove(route)
                                ConversationEffect.PickFolder -> pickingFolder = true
                                ConversationEffect.ConfirmSpamReport -> askingSpamReport = true
                                ConversationEffect.ConfirmPhishingReport -> askingPhishingReport = true
                                is ConversationEffect.EditDraft -> backStack.add(ComposerRoute(effect.messageId))
                                is ConversationEffect.Reply -> backStack.add(ComposerRoute(respondTo = effect.messageId,
                                    kind = if (effect.all) ResponseKind.ReplyAll else ResponseKind.Reply))
                                is ConversationEffect.Forward ->
                                    backStack.add(ComposerRoute(respondTo = effect.messageId, kind = ResponseKind.Forward))
                                is ConversationEffect.OpenLink -> openLink(context, effect.uri)
                                is ConversationEffect.OpenAttachment -> if (!openAttachment(context, effect.file, effect.mimeType)) {
                                    scope.launch { snackbar.showSnackbar(noViewer) }
                                }
                                is ConversationEffect.ActionFailed -> scope.launch { snackbar.showSnackbar(actionFailed) }
                                else -> scope.launch { snackbar.showSnackbar(notAvailable) }
                            }
                        }
                    }
                    if (pickingFolder) {
                        MoveToSheet(
                            folders = viewModel.moveTargets(),
                            onPick = { folder ->
                                pickingFolder = false
                                viewModel.moveToFolder(folder.id)
                            },
                            onDismiss = { pickingFolder = false },
                        )
                    }
                    if (askingSpamReport) {
                        val count = (state as? ConversationUiState.Loaded)?.conversation?.messages?.size ?: 1
                        SpamReportDialog(
                            count = count,
                            onAnswer = { report ->
                                askingSpamReport = false
                                viewModel.moveToSpam(report)
                            },
                            onDismiss = { askingSpamReport = false },
                        )
                    }
                    if (askingPhishingReport) {
                        PhishingReportDialog(
                            onConfirm = {
                                askingPhishingReport = false
                                viewModel.reportPhishing()
                            },
                            onDismiss = { askingPhishingReport = false },
                        )
                    }
                    ConversationScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                        // List and detail share the screen from the same 840dp breakpoint.
                        showBack = !permanent,
                        loadAttachmentImage = viewModel::loadAttachmentImage,
                        snackbarHostState = if (permanent) null else snackbar,
                    )
                }
                // Full screen: the composer is a task, not a detail of the list.
                entry<ComposerRoute> { route ->
                    val viewModel = viewModel(key = "composer-${route.draftId}-${route.respondTo}-${route.kind}") {
                        ComposerViewModel(account, mailbox, mail, route.draftId, route.respondTo, route.kind ?: ResponseKind.Reply)
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(viewModel) {
                        viewModel.effects.collect { effect ->
                            when (effect) {
                                ComposerEffect.Close -> backStack.remove(route)
                                ComposerEffect.Sent -> scope.launch { snackbar.showSnackbar(mailSent) }
                                is ComposerEffect.AttachmentsNotForwarded -> scope.launch {
                                    snackbar.showSnackbar(context.resources.getQuantityString(
                                        MailR.plurals.composer_attachments_not_forwarded, effect.count, effect.count))
                                }
                                is ComposerEffect.Failed -> scope.launch {
                                    val message = composerFailureMessage(effect.failure)?.let(context::getString)
                                        ?: if (effect.failure is Failure.Unsupported) notAvailable else actionFailed
                                    snackbar.showSnackbar(message)
                                }
                            }
                        }
                    }
                    // System back saves the draft too, like the close button.
                    BackHandler { viewModel.onEvent(ComposerEvent.Close) }
                    ComposerScreen(
                        state = state,
                        subject = viewModel.subject,
                        body = viewModel.body,
                        onEvent = viewModel::onEvent,
                        snackbarHostState = snackbar,
                    )
                }
                entry<SettingsRoute> {
                    StatusMessage(icon = VelinIcons.Settings, title = stringResource(R.string.not_available_yet))
                }
            },
        )
    }
}

/** Screen after sign-in when the graph has no mail backend; it still allows sign-out. */
@Composable
private fun SignedInWithoutMail(account: Account, auth: AuthRepository) {
    val scope = rememberCoroutineScope()
    StatusMessage(
        icon = VelinIcons.Mail,
        title = stringResource(R.string.signed_in_as, account.address.address),
        body = stringResource(R.string.mail_not_available_yet),
        actionLabel = stringResource(R.string.sign_out),
        onAction = { scope.launch { auth.signOut(account.id) } },
    )
}

/** Keeps one conversation on the stack: opening another replaces it instead of stacking up. */
private fun NavBackStack<NavKey>.showDetail(route: ConversationRoute) {
    removeAll { it is ConversationRoute }
    add(route)
}

private fun NavBackStack<NavKey>.popTo(route: NavKey) {
    while (size > 1 && last() != route) removeAt(lastIndex)
}
