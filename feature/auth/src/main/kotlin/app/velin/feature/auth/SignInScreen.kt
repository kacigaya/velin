package app.velin.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.core.designsystem.R as DesignR
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class SavedAccountUi(val id: String, val address: String)

enum class SignInError { InvalidCredentials, TooManyAttempts, Offline, InvalidCode, SecurityKeyUnsupported, OutdatedClient, Unknown }

sealed interface SignInUiState {
    val busy: Boolean
    val error: SignInError?

    data class Credentials(
        val savedAccounts: ImmutableList<SavedAccountUi> = persistentListOf(),
        override val busy: Boolean = false,
        override val error: SignInError? = null,
    ) : SignInUiState

    /** Upstream offers TOTP, approval from another session and security keys, in any combination. */
    data class SecondFactor(
        val address: String,
        val totp: Boolean,
        val securityKey: Boolean,
        val otherSession: Boolean,
        override val busy: Boolean = false,
        override val error: SignInError? = null,
    ) : SignInUiState
}

sealed interface SignInEvent {
    /** The password field is cleared right after this event; the receiver owns the chars. */
    data class SignIn(val address: String, val password: CharArray) : SignInEvent {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
    data class ResumeAccount(val id: String) : SignInEvent
    data class RemoveAccount(val id: String) : SignInEvent
    data class SubmitCode(val code: String) : SignInEvent
    data object CancelSecondFactor : SignInEvent
}

private enum class SignInPage { Accounts, Email, Password, Verification }

@Composable
fun SignInScreen(state: SignInUiState, onEvent: (SignInEvent) -> Unit, modifier: Modifier = Modifier) {
    val address = rememberTextFieldState()
    var showEmail by rememberSaveable { mutableStateOf(false) }
    var confirmedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedAddress by rememberSaveable { mutableStateOf<String?>(null) }

    val credentials = state as? SignInUiState.Credentials
    val verification = state as? SignInUiState.SecondFactor
    val account = confirmedAddress
    val accounts = credentials?.savedAccounts.orEmpty()
    val page = when {
        verification != null -> SignInPage.Verification
        account != null -> SignInPage.Password
        accounts.isNotEmpty() && !showEmail -> SignInPage.Accounts
        else -> SignInPage.Email
    }
    val canGoBack = when (page) {
        SignInPage.Accounts -> false
        SignInPage.Email -> accounts.isNotEmpty()
        SignInPage.Password -> true
        SignInPage.Verification -> true
    }
    val back: () -> Unit = {
        when (page) {
            SignInPage.Accounts -> Unit
            SignInPage.Email -> if (!state.busy) showEmail = false
            SignInPage.Password -> if (!state.busy) confirmedAddress = null
            SignInPage.Verification -> onEvent(SignInEvent.CancelSecondFactor)
        }
    }
    BackHandler(enabled = canGoBack, onBack = back)

    val error = credentials?.error?.takeIf { submittedAddress == null || submittedAddress == confirmedAddress }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        key(page) {
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    when {
                        verification != null -> SecondFactorStep(verification, onEvent, back)
                        account != null -> PasswordStep(account, state.busy, error, back) {
                            submittedAddress = account
                            onEvent(SignInEvent.SignIn(account, it))
                        }
                        page == SignInPage.Accounts -> AccountStep(
                            accounts,
                            state.busy,
                            error,
                            onEvent = {
                                if (it is SignInEvent.ResumeAccount) submittedAddress = null
                                onEvent(it)
                            },
                            onAdd = {
                                showEmail = true
                                address.edit { replace(0, length, "") }
                            },
                        )
                        else -> EmailStep(address, state.busy, error, canGoBack, back) {
                            confirmedAddress = it
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailStep(
    address: TextFieldState,
    busy: Boolean,
    error: SignInError?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    val email = address.text.toString().trim()
    val submit = {
        if (!busy && email.isNotEmpty()) {
            focus.clearFocus()
            onContinue(email)
        }
    }
    Column {
        if (canGoBack) StepBar(onBack, enabled = !busy)
        BrandHeader(R.string.signin_title, R.string.signin_subtitle)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(
                state = address,
                label = { Text(stringResource(R.string.signin_address)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                onKeyboardAction = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
            )
            ErrorText(error)
            PrimaryAction(R.string.signin_continue, busy, email.isNotEmpty(), submit)
        }
    }
}

@Composable
private fun PasswordStep(
    address: String,
    busy: Boolean,
    error: SignInError?,
    onBack: () -> Unit,
    onSignIn: (CharArray) -> Unit,
) {
    val password = remember { TextFieldState() }
    val focus = LocalFocusManager.current
    val submit = {
        if (!busy && password.text.isNotEmpty()) {
            val chars = CharArray(password.text.length) { password.text[it] }
            password.edit { replace(0, length, "") }
            focus.clearFocus()
            onSignIn(chars)
        }
    }
    Column {
        StepBar(onBack, enabled = !busy)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepTitle(stringResource(R.string.signin_password_title))
            AccountIdentity(address)
            PasswordField(password, enabled = !busy, isError = error == SignInError.InvalidCredentials, onDone = submit)
            ErrorText(error)
            PrimaryAction(R.string.signin_action, busy, password.text.isNotEmpty(), submit)
        }
    }
}

@Composable
private fun PasswordField(state: TextFieldState, enabled: Boolean, isError: Boolean, onDone: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    SecureTextField(
        state = state,
        label = { Text(stringResource(R.string.signin_password)) },
        enabled = enabled,
        isError = isError,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        onKeyboardAction = { onDone() },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    painterResource(VelinIcons.Visibility),
                    stringResource(if (visible) R.string.signin_hide_password else R.string.signin_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    )
}

@Composable
private fun AccountStep(
    accounts: List<SavedAccountUi>,
    busy: Boolean,
    error: SignInError?,
    onEvent: (SignInEvent) -> Unit,
    onAdd: () -> Unit,
) {
    Column {
        BrandHeader(R.string.signin_accounts_title, R.string.signin_accounts_subtitle)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (account in accounts) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { onEvent(SignInEvent.ResumeAccount(account.id)) },
                            enabled = !busy,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.weight(1f),
                        ) {
                            AccountLabel(account.address, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        AccountOptions(account.address, enabled = !busy) {
                            onEvent(SignInEvent.RemoveAccount(account.id))
                        }
                    }
                }
            }
            ErrorText(error)
            if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BusyIndicator() }
            TextButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.signin_other_account))
            }
        }
    }
}

@Composable
private fun AccountOptions(address: String, enabled: Boolean, onRemove: () -> Unit) {
    var expanded by remember(address) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(painterResource(VelinIcons.MoreVert), stringResource(R.string.signin_account_options, address))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.signin_remove_account)) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun SecondFactorStep(state: SignInUiState.SecondFactor, onEvent: (SignInEvent) -> Unit, onBack: () -> Unit) {
    val code = rememberTextFieldState()
    val digits = code.text.filter { it in '0'..'9' }.toString()
    val canVerify = digits.length == 6
    val submit = { if (!state.busy && canVerify) onEvent(SignInEvent.SubmitCode(digits)) }
    Column {
        StepBar(onBack, backLabel = R.string.signin_2fa_cancel)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepTitle(stringResource(R.string.signin_2fa_title))
                if (state.totp) {
                    Text(stringResource(R.string.signin_2fa_subtitle), style = MaterialTheme.typography.bodyMedium)
                }
            }
            AccountIdentity(state.address)
            if (state.totp) {
                TextField(
                    state = code,
                    label = { Text(stringResource(R.string.signin_2fa_code)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    enabled = !state.busy,
                    isError = state.error == SignInError.InvalidCode,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    onKeyboardAction = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.SmsOtpCode },
                )
            }
            ErrorText(state.error)
            if (state.totp) PrimaryAction(R.string.signin_2fa_verify, state.busy, canVerify, submit)
            if (state.otherSession) {
                Text(
                    stringResource(if (state.totp) R.string.signin_2fa_other_session else R.string.signin_2fa_approve),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.securityKey && state.error != SignInError.SecurityKeyUnsupported) {
                Text(
                    stringResource(R.string.signin_error_security_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrandHeader(title: Int, subtitle: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(24.dp)) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = colorResource(DesignR.color.ic_launcher_background),
            ) {
                Image(painterResource(DesignR.drawable.ic_launcher_foreground), contentDescription = null)
            }
            Spacer(Modifier.height(16.dp))
            StepTitle(stringResource(title))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StepBar(onBack: () -> Unit, enabled: Boolean = true, backLabel: Int = R.string.signin_back) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, enabled = enabled) {
            Icon(painterResource(VelinIcons.ArrowBack), stringResource(backLabel))
        }
        Text(stringResource(R.string.signin_brand), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StepTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun AccountIdentity(address: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AccountLabel(address, Modifier.padding(12.dp))
    }
}

@Composable
private fun AccountLabel(address: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SenderAvatar(
            name = "",
            address = address,
            size = 32.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(address, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrimaryAction(label: Int, busy: Boolean, ready: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy && ready,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        if (busy) BusyIndicator() else Text(stringResource(label))
    }
}

@Composable
private fun ErrorText(error: SignInError?) {
    if (error == null) return
    val text = stringResource(
        when (error) {
            SignInError.InvalidCredentials -> R.string.signin_error_credentials
            SignInError.TooManyAttempts -> R.string.signin_error_too_many
            SignInError.Offline -> R.string.signin_error_offline
            SignInError.InvalidCode -> R.string.signin_error_code
            SignInError.SecurityKeyUnsupported -> R.string.signin_error_security_key
            SignInError.OutdatedClient -> R.string.signin_error_outdated
            SignInError.Unknown -> R.string.signin_error_unknown
        },
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BusyIndicator() {
    // The button is disabled while busy, so follow its content color instead of onPrimary.
    val label = stringResource(R.string.signin_busy)
    LoadingIndicator(Modifier.size(32.dp).semantics { contentDescription = label }, color = LocalContentColor.current)
}
