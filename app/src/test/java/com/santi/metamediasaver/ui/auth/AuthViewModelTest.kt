package com.santi.metamediasaver.ui.auth

import com.santi.metamediasaver.data.auth.AuthRepository
import com.santi.metamediasaver.data.model.AuthUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun submit_blank_email_password_sets_error() =
        runTest {
            val viewModel = AuthViewModel(FakeAuthRepository())

            viewModel.submit()

            assertEquals("Email and password are required.", viewModel.uiState.value.error)
        }

    @Test
    fun create_mode_without_username_sets_error() =
        runTest {
            val viewModel = AuthViewModel(FakeAuthRepository())
            viewModel.updateEmail("user@example.com")
            viewModel.updatePassword("secret123")
            viewModel.setCreateMode(true)

            viewModel.submit()

            assertEquals("Choose a username for your app profile.", viewModel.uiState.value.error)
        }

    @Test
    fun sign_in_success_clears_password() =
        runTest {
            val repo = FakeAuthRepository()
            val viewModel = AuthViewModel(repo)
            viewModel.updateEmail("user@example.com")
            viewModel.updatePassword("secret123")

            viewModel.submit()
            advanceUntilIdle()

            assertEquals("user@example.com" to "secret123", repo.signInArgs)
            assertEquals("", viewModel.uiState.value.password)
            assertFalse(viewModel.uiState.value.isSubmitting)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun sign_in_failure_sets_error() =
        runTest {
            val repo = FakeAuthRepository(signInError = IllegalStateException("Wrong credentials"))
            val viewModel = AuthViewModel(repo)
            viewModel.updateEmail("user@example.com")
            viewModel.updatePassword("wrong")

            viewModel.submit()
            advanceUntilIdle()

            assertTrue(repo.signInCalled)
            assertEquals("Wrong credentials", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isSubmitting)
        }
}

private class FakeAuthRepository(
    private val signInError: Throwable? = null,
) : AuthRepository {
    private val current = MutableStateFlow<AuthUser?>(null)

    var signInArgs: Pair<String, String>? = null
    var signInCalled: Boolean = false

    override val currentUser: Flow<AuthUser?> = current

    override suspend fun signIn(
        email: String,
        password: String,
    ) {
        signInCalled = true
        signInArgs = email to password
        signInError?.let { throw it }
    }

    override suspend fun signUp(
        username: String,
        email: String,
        password: String,
    ) = Unit

    override suspend fun signOut() = Unit
}
