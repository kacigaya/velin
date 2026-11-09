package app.velin.feature.auth

import app.velin.core.domain.Account
import app.velin.core.domain.AccountId
import app.velin.core.domain.Address
import app.velin.core.domain.AuthError
import app.velin.core.domain.AuthRepository
import app.velin.core.domain.AuthState
import app.velin.core.domain.ElementId
import app.velin.core.domain.Outcome
import app.velin.core.domain.SecondFactorAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private class FakeAuth : AuthRepository {
        override val state = MutableStateFlow<AuthState>(AuthState.Credentials(emptyList()))
        val calls = mutableListOf<String>()
        override suspend fun signIn(address: String, password: CharArray) { calls += "signIn:$address:${String(password)}" }
        override suspend fun answerSecondFactor(answer: SecondFactorAnswer) { calls += "2fa:$answer" }
        override suspend fun cancelSignIn() { calls += "cancel" }
        override suspend fun resume(account: AccountId) { calls += "resume:${account.value.value}" }
        override suspend fun removeSavedAccount(account: AccountId): Outcome<Unit> { calls += "remove"; return Outcome.Success(Unit) }
        override suspend fun signOut(account: AccountId): Outcome<Unit> = Outcome.Success(Unit)
    }

    private val auth = FakeAuth()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun mapsSecondFactorState() = runTest {
        val vm = SignInViewModel(auth)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        auth.state.value = AuthState.SecondFactor("a@tuta.com", totp = true, securityKey = false, otherSession = true, error = AuthError.InvalidCode)
        assertEquals(
            SignInUiState.SecondFactor("a@tuta.com", totp = true, securityKey = false, error = SignInError.InvalidCode),
            vm.state.value,
        )
    }

    @Test
    fun mapsSavedAccountsAndOutdatedClient() {
        val account = Account(AccountId(ElementId("u1")), Address("a@tuta.com"))
        val ui = toUiState(AuthState.Credentials(listOf(account), error = AuthError.InvalidSoftwareVersion)) as SignInUiState.Credentials
        assertEquals(listOf(SavedAccountUi("u1", "a@tuta.com")), ui.savedAccounts)
        assertEquals(SignInError.OutdatedClient, ui.error)
    }

    @Test
    fun forwardsEventsToRepository() = runTest {
        val vm = SignInViewModel(auth)
        vm.onEvent(SignInEvent.SignIn("a@tuta.com", "pw".toCharArray()))
        vm.onEvent(SignInEvent.SubmitCode("123456"))
        vm.onEvent(SignInEvent.ResumeAccount("u1"))
        vm.onEvent(SignInEvent.CancelSecondFactor)
        assertEquals(listOf("signIn:a@tuta.com:pw", "2fa:Totp(code=123456)", "resume:u1", "cancel"), auth.calls)
    }

    @Test
    fun reportsSignedInOnlyWhenActive() = runTest {
        val vm = SignInViewModel(auth)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.signedIn.collect {} }
        assertTrue(!vm.signedIn.value)
        auth.state.value = AuthState.Active(Account(AccountId(ElementId("u1")), Address("a@tuta.com")))
        assertTrue(vm.signedIn.value)
    }

    @Test
    fun resumesTheOnlySavedAccountOnce() = runTest {
        val account = Account(AccountId(ElementId("u1")), Address("a@tuta.com"))
        auth.state.value = AuthState.Credentials(listOf(account), busy = true)
        SignInViewModel(auth)
        assertTrue("waits until saved accounts are loaded", auth.calls.isEmpty())
        auth.state.value = AuthState.Credentials(listOf(account))
        auth.state.value = AuthState.Credentials(listOf(account), error = AuthError.Offline)
        auth.state.value = AuthState.Credentials(listOf(account))
        assertEquals(listOf("resume:u1"), auth.calls)
    }

    @Test
    fun doesNotResumeWithSeveralAccountsOrAnError() = runTest {
        val a = Account(AccountId(ElementId("u1")), Address("a@tuta.com"))
        val b = Account(AccountId(ElementId("u2")), Address("b@tuta.com"))
        auth.state.value = AuthState.Credentials(listOf(a, b))
        SignInViewModel(auth)
        auth.state.value = AuthState.Credentials(listOf(a), error = AuthError.Offline)
        SignInViewModel(auth)
        assertTrue(auth.calls.isEmpty())
    }
}
