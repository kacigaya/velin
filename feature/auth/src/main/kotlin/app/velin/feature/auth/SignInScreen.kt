package app.velin.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.icon.VelinIcons
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class SavedAccountUi(val id: String, val address: String)

enum class SignInError { InvalidCredentials, TooManyAttempts, Offline, InvalidCode, SecurityKeyUnsupported, Unknown }

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
    data object UseSecurityKey : SignInEvent
    data object CancelSecondFactor : SignInEvent
}

@Composable
fun SignInScreen(state: SignInUiState, onEvent: (SignInEvent) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppMark()
                AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = {
                        val forward = targetState is SignInUiState.SecondFactor
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    label = "signInStep",
                ) { step ->
                    when (step) {
                        is SignInUiState.Credentials -> CredentialsStep(step, onEvent)
                        is SignInUiState.SecondFactor -> SecondFactorStep(step, onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppMark() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(VelinIcons.Mail), contentDescription = null, Modifier.size(32.dp))
        }
    }
}

@Composable
private fun CredentialsStep(state: SignInUiState.Credentials, onEvent: (SignInEvent) -> Unit) {
    val address = rememberTextFieldState()
    // Not saveable: the password must not end up in the saved instance state bundle.
    val password = remember { TextFieldState() }
    val submit = {
        if (!state.busy && address.text.isNotBlank() && password.text.isNotEmpty()) {
            val chars = CharArray(password.text.length) { password.text[it] }
            password.edit { replace(0, length, "") }
            onEvent(SignInEvent.SignIn(address.text.toString().trim(), chars))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title(stringResource(R.string.signin_title), stringResource(R.string.signin_subtitle))
        if (state.savedAccounts.isNotEmpty()) SavedAccounts(state, onEvent)
        OutlinedTextField(
            state = address,
            label = { Text(stringResource(R.string.signin_address)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            enabled = !state.busy,
            isError = state.error == SignInError.InvalidCredentials,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
        )
        PasswordField(password, enabled = !state.busy, isError = state.error == SignInError.InvalidCredentials, onDone = submit)
        ErrorText(state.error)
        Button(
            onClick = submit,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (state.busy) BusyIndicator() else Text(stringResource(R.string.signin_action))
        }
    }
}

@Composable
private fun PasswordField(state: TextFieldState, enabled: Boolean, isError: Boolean, onDone: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        label = { Text(stringResource(R.string.signin_password)) },
        enabled = enabled,
        isError = isError,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        onKeyboardAction = { onDone() },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(VelinIcons.Visibility),
                    stringResource(if (visible) R.string.signin_hide_password else R.string.signin_show_password),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
    )
}

@Composable
private fun SavedAccounts(state: SignInUiState.Credentials, onEvent: (SignInEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.savedAccounts.forEach { account ->
            Surface(
                onClick = { onEvent(SignInEvent.ResumeAccount(account.id)) },
                enabled = !state.busy,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SenderAvatar(name = "", address = account.address)
                    Text(account.address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { onEvent(SignInEvent.RemoveAccount(account.id)) }, enabled = !state.busy) {
                        Icon(painterResource(VelinIcons.Close), stringResource(R.string.signin_remove_account, account.address))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.signin_other_account),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SecondFactorStep(state: SignInUiState.SecondFactor, onEvent: (SignInEvent) -> Unit) {
    val code = rememberTextFieldState()
    val submit = { if (!state.busy && code.text.isNotBlank()) onEvent(SignInEvent.SubmitCode(code.text.toString().filter(Char::isDigit))) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title(stringResource(R.string.signin_2fa_title), stringResource(R.string.signin_2fa_subtitle, state.address))
        if (state.totp) {
            OutlinedTextField(
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
            ErrorText(state.error)
            Button(onClick = submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (state.busy) BusyIndicator() else Text(stringResource(R.string.signin_2fa_verify))
            }
        }
        if (state.securityKey) {
            OutlinedButton(
                onClick = { onEvent(SignInEvent.UseSecurityKey) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(painterResource(VelinIcons.Key), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.signin_2fa_security_key))
            }
        }
        Text(
            stringResource(R.string.signin_2fa_other_session),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.totp) ErrorText(state.error)
        TextButton(onClick = { onEvent(SignInEvent.CancelSecondFactor) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.signin_2fa_cancel))
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SignInError.Unknown -> R.string.signin_error_unknown
        },
    )
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BusyIndicator() {
    // The button is disabled while busy, so follow its content color instead of onPrimary.
    val label = stringResource(R.string.signin_busy)
    LoadingIndicator(Modifier.size(32.dp).semantics { contentDescription = label }, color = LocalContentColor.current)
}
