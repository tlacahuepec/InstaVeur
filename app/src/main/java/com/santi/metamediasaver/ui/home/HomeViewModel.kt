package com.santi.metamediasaver.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santi.metamediasaver.data.auth.OAuthRedirect
import com.santi.metamediasaver.data.auth.OAuthRedirectParser
import com.santi.metamediasaver.data.download.DownloadRepository
import com.santi.metamediasaver.data.meta.MetaRepository
import com.santi.metamediasaver.data.model.AuthUser
import com.santi.metamediasaver.data.model.ConnectedAccount
import com.santi.metamediasaver.data.model.DownloadRecord
import com.santi.metamediasaver.data.model.MediaItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val user: AuthUser,
    val accounts: List<ConnectedAccount> = emptyList(),
    val selectedAccountId: String? = null,
    val media: List<MediaItem> = emptyList(),
    val nextCursor: String? = null,
    val downloads: List<DownloadRecord> = emptyList(),
    val isLoadingAccounts: Boolean = false,
    val isLoadingMedia: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null,
) {
    val selectedAccount: ConnectedAccount?
        get() = accounts.firstOrNull { it.id == selectedAccountId }
}

sealed interface HomeEvent {
    data class OpenAuthorizationUrl(val url: String) : HomeEvent

    data class Message(val text: String) : HomeEvent
}

class HomeViewModel(
    user: AuthUser,
    private val metaRepository: MetaRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(user = user))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    init {
        loadAccounts()
        viewModelScope.launch {
            downloadRepository.observeDownloads().collect { downloads ->
                _state.update { it.copy(downloads = downloads) }
            }
        }
    }

    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAccounts = true, error = null) }
            runCatching { metaRepository.listConnectedAccounts() }
                .onSuccess { accounts ->
                    val selectedId =
                        state.value.selectedAccountId
                            ?.takeIf { id -> accounts.any { it.id == id } }
                            ?: accounts.firstOrNull()?.id

                    _state.update {
                        it.copy(
                            accounts = accounts,
                            selectedAccountId = selectedId,
                            isLoadingAccounts = false,
                        )
                    }

                    if (selectedId != null) {
                        refreshMedia()
                    } else {
                        _state.update { it.copy(media = emptyList(), nextCursor = null) }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingAccounts = false,
                            error = error.message ?: "Could not load connected accounts.",
                        )
                    }
                }
        }
    }

    fun selectAccount(accountId: String) {
        if (accountId == state.value.selectedAccountId) return
        _state.update {
            it.copy(
                selectedAccountId = accountId,
                media = emptyList(),
                nextCursor = null,
                error = null,
            )
        }
        refreshMedia()
    }

    fun refreshMedia() {
        val accountId = state.value.selectedAccountId ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoadingMedia = true,
                    media = emptyList(),
                    nextCursor = null,
                    error = null,
                )
            }
            runCatching { metaRepository.listMedia(accountId) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            media = page.items,
                            nextCursor = page.nextCursor,
                            isLoadingMedia = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingMedia = false,
                            error = error.message ?: "Could not load media.",
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val current = state.value
        val accountId = current.selectedAccountId ?: return
        val cursor = current.nextCursor ?: return
        if (current.isLoadingMore) return

        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, error = null) }
            runCatching { metaRepository.listMedia(accountId, cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            media = it.media + page.items,
                            nextCursor = page.nextCursor,
                            isLoadingMore = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingMore = false,
                            error = error.message ?: "Could not load more media.",
                        )
                    }
                }
        }
    }

    fun startConnection() {
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = true, error = null) }
            runCatching { metaRepository.startConnection() }
                .onSuccess { url ->
                    _state.update { it.copy(isConnecting = false) }
                    _events.emit(HomeEvent.OpenAuthorizationUrl(url))
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            error = error.message ?: "Could not start Meta connection.",
                        )
                    }
                }
        }
    }

    fun finishConnection(uri: Uri) {
        finishConnection(uri.toString())
    }

    fun finishConnection(uriString: String) {
        when (val parsed = OAuthRedirectParser.parse(uriString)) {
            is OAuthRedirect.Error -> {
                viewModelScope.launch { _events.emit(HomeEvent.Message(parsed.message)) }
            }
            OAuthRedirect.Malformed -> {
                // Ignore: redirects without code+state aren't actionable.
            }
            is OAuthRedirect.Success -> finishConnection(parsed.code, parsed.state)
        }
    }

    private fun finishConnection(
        code: String,
        stateParam: String,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = true, error = null) }
            runCatching { metaRepository.finishConnection(code, stateParam) }
                .onSuccess { accounts ->
                    val selectedId = accounts.firstOrNull()?.id
                    _state.update {
                        it.copy(
                            accounts = accounts,
                            selectedAccountId = selectedId,
                            isConnecting = false,
                        )
                    }
                    _events.emit(HomeEvent.Message("Meta account connected."))
                    if (selectedId != null) refreshMedia()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            error = failure.message ?: "Could not finish Meta connection.",
                        )
                    }
                }
        }
    }

    fun disconnectSelected() {
        val accountId = state.value.selectedAccountId ?: return
        viewModelScope.launch {
            runCatching { metaRepository.disconnectMeta(accountId) }
                .onSuccess {
                    _events.emit(HomeEvent.Message("Account disconnected."))
                    loadAccounts()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.message ?: "Could not disconnect account.")
                    }
                }
        }
    }

    fun download(item: MediaItem) {
        if (!item.downloadable) {
            viewModelScope.launch {
                _events.emit(HomeEvent.Message("This item is unavailable for download."))
            }
            return
        }

        viewModelScope.launch {
            runCatching { downloadRepository.enqueue(item) }
                .onSuccess { _events.emit(HomeEvent.Message("Download queued.")) }
                .onFailure { error ->
                    _events.emit(HomeEvent.Message(error.message ?: "Could not queue download."))
                }
        }
    }

    fun retry(record: DownloadRecord) {
        viewModelScope.launch {
            val id = downloadRepository.retry(record)
            _events.emit(
                HomeEvent.Message(
                    if (id == null) "No retry URL is available." else "Retry queued.",
                ),
            )
        }
    }

    fun cancel(record: DownloadRecord) {
        viewModelScope.launch { downloadRepository.cancel(record.workId) }
    }
}
