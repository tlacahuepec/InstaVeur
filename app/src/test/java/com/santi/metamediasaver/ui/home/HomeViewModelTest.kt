package com.santi.metamediasaver.ui.home

import com.santi.metamediasaver.data.download.DownloadRepository
import com.santi.metamediasaver.data.meta.MetaRepository
import com.santi.metamediasaver.data.model.AuthUser
import com.santi.metamediasaver.data.model.ConnectedAccount
import com.santi.metamediasaver.data.model.DownloadRecord
import com.santi.metamediasaver.data.model.MediaItem
import com.santi.metamediasaver.data.model.MediaType
import com.santi.metamediasaver.data.model.PagedMedia
import com.santi.metamediasaver.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun load_accounts_selects_first_when_none_selected() = runTest {
        val repo = FakeMetaRepository(
            accounts = listOf(account("a"), account("b")),
            mediaPages = mapOf("a" to mutableMapOf(null to page(media("m1", "a"), next = null)))
        )
        val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())

        advanceUntilIdle()

        assertEquals("a", viewModel.state.value.selectedAccountId)
        assertEquals(listOf(media("m1", "a")), viewModel.state.value.media)
    }

    @Test
    fun select_account_resets_media() = runTest {
        val repo = FakeMetaRepository(
            accounts = listOf(account("a"), account("b")),
            mediaPages = mapOf(
                "a" to mutableMapOf(null to page(media("m1", "a"), next = null)),
                "b" to mutableMapOf(null to page(media("m2", "b"), next = null))
            )
        )
        val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
        advanceUntilIdle()

        viewModel.selectAccount("b")
        advanceUntilIdle()

        assertEquals("b", viewModel.state.value.selectedAccountId)
        assertEquals(listOf(media("m2", "b")), viewModel.state.value.media)
        assertEquals(listOf("a" to null, "b" to null), repo.listMediaCalls)
    }

    @Test
    fun load_more_ignores_when_loading() = runTest {
        val repo = FakeMetaRepository(
            accounts = listOf(account("a")),
            mediaPages = mapOf(
                "a" to mutableMapOf(
                    null to page(media("m1", "a"), next = "cursor-1"),
                    "cursor-1" to page(media("m2", "a"), next = null)
                )
            )
        )
        val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
        advanceUntilIdle()

        repo.blockListMedia = true
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLoadingMore)
        assertEquals(listOf("a" to null, "a" to "cursor-1"), repo.listMediaCalls)
    }

    @Test
    fun load_more_appends_items() = runTest {
        val repo = FakeMetaRepository(
            accounts = listOf(account("a")),
            mediaPages = mapOf(
                "a" to mutableMapOf(
                    null to page(media("m1", "a"), next = "cursor-1"),
                    "cursor-1" to page(media("m2", "a"), media("m3", "a"), next = null)
                )
            )
        )
        val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(
            listOf(media("m1", "a"), media("m2", "a"), media("m3", "a")),
            viewModel.state.value.media
        )
        assertEquals(null, viewModel.state.value.nextCursor)
    }

    private fun user() = AuthUser("uid", "user@example.com", "user")

    private fun account(id: String) = ConnectedAccount(
        id = id,
        displayName = "Account $id",
        username = "user_$id",
        sourceType = SourceType.INSTAGRAM,
        avatarUrl = null
    )

    private fun media(id: String, accountId: String) = MediaItem(
        id = id,
        accountId = accountId,
        caption = null,
        mediaType = MediaType.IMAGE,
        mediaUrl = "https://example.com/$id.jpg",
        thumbnailUrl = null,
        permalink = null,
        sourceType = SourceType.INSTAGRAM,
        timestamp = null
    )

    private fun page(vararg items: MediaItem, next: String?) = PagedMedia(items.toList(), next)
}

private class FakeMetaRepository(
    private val accounts: List<ConnectedAccount> = emptyList(),
    private val mediaPages: Map<String, MutableMap<String?, PagedMedia>> = emptyMap()
) : MetaRepository {
    var blockListMedia: Boolean = false
    val listMediaCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun startConnection(): String = "https://example.com/connect"

    override suspend fun finishConnection(code: String, state: String): List<ConnectedAccount> = accounts

    override suspend fun listConnectedAccounts(): List<ConnectedAccount> = accounts

    override suspend fun listMedia(accountId: String, cursor: String?): PagedMedia {
        listMediaCalls += accountId to cursor
        if (blockListMedia && cursor != null) {
            awaitCancellation()
        }
        return mediaPages[accountId]?.get(cursor) ?: PagedMedia(emptyList(), null)
    }

    override suspend fun refreshMediaUrl(mediaId: String): String? = null

    override suspend fun disconnectMeta(accountId: String) = Unit
}

private class FakeDownloadRepository : DownloadRepository {
    override fun observeDownloads(): Flow<List<DownloadRecord>> = emptyFlow()

    override suspend fun enqueue(item: MediaItem) = throw UnsupportedOperationException()

    override suspend fun retry(record: DownloadRecord) = throw UnsupportedOperationException()

    override suspend fun cancel(workId: String) = Unit
}
