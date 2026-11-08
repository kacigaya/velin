package app.velin.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.velin.core.domain.AccountId
import app.velin.core.domain.AuthError
import app.velin.core.domain.AuthRepository
import app.velin.core.domain.AuthState
import app.velin.core.domain.ElementId
import app.velin.core.domain.SecondFactorAnswer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Maps [AuthState] to [SignInUiState]. Signed-in and expired states have no sign-in screen;
 * [signedIn] tells the navigation layer to leave this screen.
 */
class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    val state: StateFlow<SignInUiState> = auth.state
        .map(::toUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SignInUiState.Credentials(busy = true))

    val signedIn: StateFlow<Boolean> = auth.state
        .map { it is AuthState.Active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun onEvent(event: SignInEvent) {
        viewModelScope.launch {
            when (event) {
                is SignInEvent.SignIn -> auth.signIn(event.address, event.password)
                is SignInEvent.ResumeAccount -> auth.resume(accountId(event.id))
                is SignInEvent.RemoveAccount -> auth.removeSavedAccount(accountId(event.id))
                is SignInEvent.SubmitCode -> auth.answerSecondFactor(SecondFactorAnswer.Totp(event.code))
                SignInEvent.UseSecurityKey -> auth.answerSecondFactor(SecondFactorAnswer.SecurityKey)
                SignInEvent.CancelSecondFactor -> auth.cancelSignIn()
            }
        }
    }

    private fun accountId(id: String) = AccountId(ElementId(id))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal fun toUiState(state: AuthState): SignInUiState = when (state) {
    is AuthState.Credentials -> SignInUiState.Credentials(
        savedAccounts = state.accounts.map { SavedAccountUi(it.id.value.value, it.address.address) }.toImmutableList(),
        busy = state.busy,
        error = state.error?.toUi(),
    )
    is AuthState.SecondFactor -> SignInUiState.SecondFactor(
        address = state.address,
        totp = state.totp,
        securityKey = state.securityKey,
        busy = state.busy,
        error = state.error?.toUi(),
    )
    is AuthState.Locked -> SignInUiState.Credentials(
        savedAccounts = state.accounts.map { SavedAccountUi(it.id.value.value, it.address.address) }.toImmutableList(),
    )
    // Navigation leaves this screen for Active. Expired asks for the password again.
    is AuthState.Active, is AuthState.Expired -> SignInUiState.Credentials()
}

internal fun AuthError.toUi(): SignInError = when (this) {
    AuthError.InvalidCredentials -> SignInError.InvalidCredentials
    AuthError.TooManyAttempts -> SignInError.TooManyAttempts
    AuthError.Offline -> SignInError.Offline
    AuthError.InvalidCode -> SignInError.InvalidCode
    AuthError.SecurityKeyUnsupported -> SignInError.SecurityKeyUnsupported
    AuthError.InvalidSoftwareVersion -> SignInError.OutdatedClient
    AuthError.Unknown -> SignInError.Unknown
}
