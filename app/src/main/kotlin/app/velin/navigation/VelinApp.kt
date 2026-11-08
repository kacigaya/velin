package app.velin.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.velin.feature.auth.SignInScreen
import app.velin.feature.auth.SignInViewModel
import app.velin.feature.mail.mailbox.FolderPane
import app.velin.feature.mail.mailbox.MailboxEffect
import app.velin.feature.mail.mailbox.MailboxScreen
import app.velin.feature.mail.mailbox.MailboxViewModel
import app.velin.feature.mail.reader.ConversationEffect
import app.velin.feature.mail.reader.ConversationScreen
import app.velin.feature.mail.reader.ConversationViewModel
import kotlinx.coroutines.launch

/** Backend entry points. :app builds the real graph; tests can pass fakes. */
interface AppGraph {
    val auth: AuthRepository
    /** Null until the mail backend exists (milestone M3). */
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

    LaunchedEffect(mailboxViewModel) {
        mailboxViewModel.effects.collect { effect ->
            when (effect) {
                is MailboxEffect.OpenConversation -> backStack.showDetail(ConversationRoute(effect.conversationId))
                MailboxEffect.OpenFolders -> drawerState.open()
                is MailboxEffect.ActionFailed -> snackbar.showSnackbar(actionFailed)
                // Composer, search, account switching and pickers land with their milestones.
                MailboxEffect.OpenComposer, MailboxEffect.OpenAccount, is MailboxEffect.OpenSearch,
                is MailboxEffect.PickFolder, is MailboxEffect.PickLabels -> snackbar.showSnackbar(notAvailable)
            }
        }
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
                    )
                }
                entry<ConversationRoute>(metadata = ListDetailSceneStrategy.detailPane()) { route ->
                    val viewModel = viewModel(key = route.conversationId) {
                        ConversationViewModel(account, route.conversationId, mailbox, mail)
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(viewModel) {
                        viewModel.effects.collect { effect ->
                            when (effect) {
                                ConversationEffect.Close -> backStack.remove(route)
                                is ConversationEffect.OpenLink -> openLink(context, effect.uri)
                                is ConversationEffect.ActionFailed -> snackbar.showSnackbar(actionFailed)
                                else -> snackbar.showSnackbar(notAvailable)
                            }
                        }
                    }
                    ConversationScreen(state = state, onEvent = viewModel::onEvent)
                }
                entry<SettingsRoute> {
                    StatusMessage(icon = VelinIcons.Settings, title = stringResource(R.string.not_available_yet))
                }
            },
        )
    }
}

/** Interim screen after sign-in while the mail backend is missing; it still allows sign-out. */
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
