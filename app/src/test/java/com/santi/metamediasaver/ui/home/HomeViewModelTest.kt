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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    fun load_accounts_selects_first_when_none_selected() =
        runTest {
            val repo =
                FakeMetaRepository(
                    accounts = listOf(account("a"), account("b")),
                    mediaPages = mapOf("a" to mutableMapOf(null to page(media("m1", "a"), next = null))),
                )
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())

            advanceUntilIdle()

            assertEquals("a", viewModel.state.value.selectedAccountId)
            assertEquals(listOf(media("m1", "a")), viewModel.state.value.media)
        }

    @Test
    fun select_account_resets_media() =
        runTest {
            val repo =
                FakeMetaRepository(
                    accounts = listOf(account("a"), account("b")),
                    mediaPages =
                        mapOf(
                            "a" to mutableMapOf(null to page(media("m1", "a"), next = null)),
                            "b" to mutableMapOf(null to page(media("m2", "b"), next = null)),
                        ),
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
    fun load_more_ignores_when_loading() =
        runTest {
            val repo =
                FakeMetaRepository(
                    accounts = listOf(account("a")),
                    mediaPages =
                        mapOf(
                            "a" to
                                mutableMapOf(
                                    null to page(media("m1", "a"), next = "cursor-1"),
                                    "cursor-1" to page(media("m2", "a"), next = null),
                                ),
                        ),
                )
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            advanceUntilIdle()

            repo.blockListMedia = true
            viewModel.loadMore()
            // Let the first launch run far enough to flip isLoadingMore=true so the
            // second loadMore() short-circuits via the guard.
            runCurrent()
            viewModel.loadMore()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isLoadingMore)
            assertEquals(listOf("a" to null, "a" to "cursor-1"), repo.listMediaCalls)
        }

    @Test
    fun load_more_appends_items() =
        runTest {
            val repo =
                FakeMetaRepository(
                    accounts = listOf(account("a")),
                    mediaPages =
                        mapOf(
                            "a" to
                                mutableMapOf(
                                    null to page(media("m1", "a"), next = "cursor-1"),
                                    "cursor-1" to page(media("m2", "a"), media("m3", "a"), next = null),
                                ),
                        ),
                )
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                listOf(media("m1", "a"), media("m2", "a"), media("m3", "a")),
                viewModel.state.value.media,
            )
            assertEquals(null, viewModel.state.value.nextCursor)
        }

    @Test
    fun start_connection_emits_open_url() =
        runTest {
            val repo = FakeMetaRepository(startConnectionUrl = "https://example.com/oauth")
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            val received = collectEvents(viewModel)

            viewModel.startConnection()
            advanceUntilIdle()

            assertEquals(
                listOf<HomeEvent>(HomeEvent.OpenAuthorizationUrl("https://example.com/oauth")),
                received,
            )
            assertEquals(false, viewModel.state.value.isConnecting)
        }

    @Test
    fun finish_connection_with_error_emits_message() =
        runTest {
            val viewModel = HomeViewModel(user(), FakeMetaRepository(), FakeDownloadRepository())
            val received = collectEvents(viewModel)

            viewModel.finishConnection("instaveur://oauth?error=denied")
            advanceUntilIdle()

            assertEquals(listOf<HomeEvent>(HomeEvent.Message("denied")), received)
        }

    @Test
    fun finish_connection_success_redirect_invokes_repository() =
        runTest {
            val repo = FakeMetaRepository(accounts = listOf(account("a")))
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            advanceUntilIdle()
            repo.listConnectedAccountsCalls = 0
            val received = collectEvents(viewModel)

            viewModel.finishConnection("metamediasaver://oauth/meta?code=abc&state=xyz")
            advanceUntilIdle()

            assertEquals(listOf("abc" to "xyz"), repo.finishConnectionCalls)
            assertTrue(received.contains(HomeEvent.Message("Meta account connected.")))
            assertEquals("a", viewModel.state.value.selectedAccountId)
        }

    @Test
    fun finish_connection_user_denied_uses_error_description() =
        runTest {
            val repo = FakeMetaRepository()
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            val received = collectEvents(viewModel)

            viewModel.finishConnection(
                "metamediasaver://oauth/meta?error=access_denied&error_description=User+denied",
            )
            advanceUntilIdle()

            assertEquals(listOf<HomeEvent>(HomeEvent.Message("User denied")), received)
            assertTrue(repo.finishConnectionCalls.isEmpty())
        }

    @Test
    fun finish_connection_malformed_missing_code_is_ignored() =
        runTest {
            val repo = FakeMetaRepository()
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            val received = collectEvents(viewModel)

            viewModel.finishConnection("metamediasaver://oauth/meta?state=xyz")
            advanceUntilIdle()

            assertTrue(repo.finishConnectionCalls.isEmpty())
            assertTrue(received.isEmpty())
            assertEquals(null, viewModel.state.value.error)
            assertEquals(false, viewModel.state.value.isConnecting)
        }

    @Test
    fun disconnect_selected_triggers_reload() =
        runTest {
            val repo = FakeMetaRepository(accounts = listOf(account("a"), account("b")))
            val viewModel = HomeViewModel(user(), repo, FakeDownloadRepository())
            advanceUntilIdle()

            viewModel.disconnectSelected()
            advanceUntilIdle()

            assertEquals(listOf("a"), repo.disconnectCalls)
            assertEquals(2, repo.listConnectedAccountsCalls)
        }

    private fun TestScope.collectEvents(viewModel: HomeViewModel): MutableList<HomeEvent> {
        val received = mutableListOf<HomeEvent>()
        backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
            start = CoroutineStart.UNDISPATCHED,
        ) {
            viewModel.events.toList(received)
        }
        return received
    }

    private fun user() = AuthUser("uid", "user@example.com", "user")

    private fun account(id: String) =
        ConnectedAccount(
            id = id,
            displayName = "Account $id",
            username = "user_$id",
            sourceType = SourceType.INSTAGRAM,
            avatarUrl = null,
        )

    private fun media(
        id: String,
        accountId: String,
    ) = MediaItem(
        id = id,
        accountId = accountId,
        caption = null,
        mediaType = MediaType.IMAGE,
        mediaUrl = "https://example.com/$id.jpg",
        thumbnailUrl = null,
        permalink = null,
        sourceType = SourceType.INSTAGRAM,
        timestamp = null,
    )

    private fun page(
        vararg items: MediaItem,
        next: String?,
    ) = PagedMedia(items.toList(), next)
}

private class FakeMetaRepository(
    private val accounts: List<ConnectedAccount> = emptyList(),
    private val mediaPages: Map<String, MutableMap<String?, PagedMedia>> = emptyMap(),
    private val startConnectionUrl: String = "https://example.com/connect",
) : MetaRepository {
    var blockListMedia: Boolean = false
    val listMediaCalls = mutableListOf<Pair<String, String?>>()
    val disconnectCalls = mutableListOf<String>()
    val finishConnectionCalls = mutableListOf<Pair<String, String>>()
    var listConnectedAccountsCalls: Int = 0

    override suspend fun startConnection(): String = startConnectionUrl

    override suspend fun finishConnection(
        code: String,
        state: String,
    ): List<ConnectedAccount> {
        finishConnectionCalls += code to state
        return accounts
    }

    override suspend fun listConnectedAccounts(): List<ConnectedAccount> {
        listConnectedAccountsCalls += 1
        return accounts
    }

    override suspend fun listMedia(
        accountId: String,
        cursor: String?,
    ): PagedMedia {
        listMediaCalls += accountId to cursor
        if (blockListMedia && cursor != null) {
            awaitCancellation()
        }
        return mediaPages[accountId]?.get(cursor) ?: PagedMedia(emptyList(), null)
    }

    override suspend fun refreshMediaUrl(mediaId: String): String? = null

    override suspend fun disconnectMeta(accountId: String) {
        disconnectCalls += accountId
    }
}

private class FakeDownloadRepository : DownloadRepository {
    override fun observeDownloads(): Flow<List<DownloadRecord>> = emptyFlow()

    override suspend fun enqueue(item: MediaItem) = throw UnsupportedOperationException()

    override suspend fun retry(record: DownloadRecord) = throw UnsupportedOperationException()

    override suspend fun cancel(workId: String) = Unit
}
