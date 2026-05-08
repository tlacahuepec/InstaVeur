package com.santi.metamediasaver.data.download

import androidx.lifecycle.MutableLiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.santi.metamediasaver.data.model.DownloadRecord
import com.santi.metamediasaver.data.model.DownloadState
import com.santi.metamediasaver.data.model.MediaItem
import com.santi.metamediasaver.data.model.MediaType
import com.santi.metamediasaver.data.model.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID
import kotlin.test.assertFailsWith

class WorkManagerDownloadRepositoryTest {
    @Test
    fun enqueue_valid_media_creates_work() = runTest {
        val scheduler = FakeDownloadWorkScheduler()
        val repository = WorkManagerDownloadRepository(scheduler)

        val workId = repository.enqueue(validMediaItem())

        assertNotNull(workId)
        assertEquals(1, scheduler.enqueued.size)
        assertEquals("download-media-1", scheduler.enqueued.single().name)
        assertEquals(ExistingWorkPolicy.REPLACE, scheduler.enqueued.single().policy)
    }

    @Test
    fun retry_without_url_returns_null() = runTest {
        val scheduler = FakeDownloadWorkScheduler()
        val repository = WorkManagerDownloadRepository(scheduler)

        val result = repository.retry(
            DownloadRecord(
                workId = UUID.randomUUID().toString(),
                mediaId = "media-1",
                title = "Title",
                state = DownloadState.FAILED,
                progress = 0,
                localUri = null,
                error = "network",
                retryUrl = null,
                retryMediaType = MediaType.IMAGE
            )
        )

        assertNull(result)
        assertEquals(0, scheduler.enqueued.size)
    }

    @Test
    fun retry_with_url_enqueues_work() = runTest {
        val scheduler = FakeDownloadWorkScheduler()
        val repository = WorkManagerDownloadRepository(scheduler)

        val result = repository.retry(
            DownloadRecord(
                workId = UUID.randomUUID().toString(),
                mediaId = "media-2",
                title = "Retry Title",
                state = DownloadState.FAILED,
                progress = 0,
                localUri = null,
                error = "network",
                retryUrl = "https://example.com/retry.jpg",
                retryMediaType = MediaType.IMAGE
            )
        )

        assertNotNull(result)
        assertEquals(1, scheduler.enqueued.size)
        assertEquals("download-media-2", scheduler.enqueued.single().name)
    }

    @Test
    fun cancel_calls_workmanager() = runTest {
        val scheduler = FakeDownloadWorkScheduler()
        val repository = WorkManagerDownloadRepository(scheduler)
        val workId = UUID.randomUUID()

        repository.cancel(workId.toString())

        assertEquals(listOf(workId), scheduler.cancelled)
    }

    @Test
    fun enqueue_without_media_url_throws() = runTest {
        val scheduler = FakeDownloadWorkScheduler()
        val repository = WorkManagerDownloadRepository(scheduler)

        assertFailsWith<IllegalStateException> {
            repository.enqueue(validMediaItem().copy(mediaUrl = null))
        }
        assertEquals(0, scheduler.enqueued.size)
    }

    private fun validMediaItem() = MediaItem(
        id = "media-1",
        accountId = "account-1",
        caption = null,
        mediaType = MediaType.IMAGE,
        mediaUrl = "https://example.com/image.jpg",
        thumbnailUrl = null,
        permalink = null,
        sourceType = SourceType.INSTAGRAM,
        timestamp = null
    )
}

private class FakeDownloadWorkScheduler : DownloadWorkScheduler {
    data class EnqueuedWork(
        val name: String,
        val policy: ExistingWorkPolicy,
        val request: OneTimeWorkRequest
    )

    val enqueued = mutableListOf<EnqueuedWork>()
    val cancelled = mutableListOf<UUID>()

    override fun getWorkInfosByTagLiveData(tag: String) = MutableLiveData<List<WorkInfo>>(emptyList())

    override fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        enqueued += EnqueuedWork(name, policy, request)
    }

    override fun cancelWorkById(id: UUID) {
        cancelled += id
    }
}
