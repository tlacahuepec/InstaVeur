package com.santi.metamediasaver

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.santi.metamediasaver.ui.auth.AuthScreen
import com.santi.metamediasaver.ui.auth.AuthViewModel
import com.santi.metamediasaver.ui.home.HomeEvent
import com.santi.metamediasaver.ui.home.HomeScreen
import com.santi.metamediasaver.ui.home.HomeViewModel
import com.santi.metamediasaver.ui.theme.MetaMediaSaverTheme

@Composable
fun MetaMediaSaverApp(
    appContainer: AppContainer,
    deepLinks: DeepLinkFlow,
    modifier: Modifier = Modifier
) {
    MetaMediaSaverTheme {
        Surface(modifier = modifier) {
            val authViewModel: AuthViewModel = viewModel(
                factory = SimpleViewModelFactory {
                    AuthViewModel(appContainer.authRepository)
                }
            )
            val authState by authViewModel.uiState.collectAsStateWithLifecycle()
            val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()

            val user = currentUser
            if (user == null) {
                AuthScreen(
                    state = authState,
                    onEmailChange = authViewModel::updateEmail,
                    onPasswordChange = authViewModel::updatePassword,
                    onUsernameChange = authViewModel::updateUsername,
                    onModeChange = authViewModel::setCreateMode,
                    onSubmit = authViewModel::submit
                )
            } else {
                val homeViewModel: HomeViewModel = viewModel(
                    key = user.uid,
                    factory = SimpleViewModelFactory {
                        HomeViewModel(
                            user = user,
                            metaRepository = appContainer.metaRepository,
                            downloadRepository = appContainer.downloadRepository
                        )
                    }
                )
                val homeState by homeViewModel.state.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val context = LocalContext.current

                LaunchedEffect(homeViewModel) {
                    deepLinks.collect { uri -> homeViewModel.finishConnection(uri) }
                }

                LaunchedEffect(homeViewModel) {
                    homeViewModel.events.collect { event ->
                        when (event) {
                            is HomeEvent.OpenAuthorizationUrl -> {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(event.url)
                                    )
                                )
                            }
                            is HomeEvent.Message -> snackbarHostState.showSnackbar(event.text)
                        }
                    }
                }

                androidx.compose.material3.Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { _ ->
                    HomeScreen(
                        state = homeState,
                        onConnect = homeViewModel::startConnection,
                        onRefreshAccounts = homeViewModel::loadAccounts,
                        onRefreshMedia = homeViewModel::refreshMedia,
                        onSelectAccount = homeViewModel::selectAccount,
                        onDisconnect = homeViewModel::disconnectSelected,
                        onLoadMore = homeViewModel::loadMore,
                        onDownload = homeViewModel::download,
                        onRetryDownload = homeViewModel::retry,
                        onCancelDownload = homeViewModel::cancel,
                        onSignOut = authViewModel::signOut
                    )
                }
            }
        }
    }
}
