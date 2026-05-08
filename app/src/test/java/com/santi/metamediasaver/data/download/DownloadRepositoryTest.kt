package com.santi.metamediasaver.data.download

import androidx.lifecycle.LiveData
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

class DownloadRepositoryTest {
    @Test
    fun enqueue_valid_media_creates_work() = runTest {
        val fakeScheduler = FakeWorkScheduler()
        val repository = WorkManagerDownloadRepository(fakeScheduler)
        val item = mediaItem(mediaUrl = "https://cdn.example.com/media.jpg")

        val workId = repository.enqueue(item)

        assertNotNull(workId)
        assertEquals(1, fakeScheduler.enqueued.size)
        assertEquals("download-${item.id}", fakeScheduler.enqueued.first().name)
    }

    @Test(expected = IllegalStateException::class)
    fun enqueue_invalid_media_throws() = runTest {
        val fakeScheduler = FakeWorkScheduler()
        val repository = WorkManagerDownloadRepository(fakeScheduler)

        repository.enqueue(mediaItem(mediaUrl = null))
    }

    @Test
    fun retry_without_url_returns_null() = runTest {
        val fakeScheduler = FakeWorkScheduler()
        val repository = WorkManagerDownloadRepository(fakeScheduler)
        val record = downloadRecord(retryUrl = null)

        val workId = repository.retry(record)

        assertNull(workId)
        assertEquals(0, fakeScheduler.enqueued.size)
    }

    @Test
    fun cancel_calls_workmanager() = runTest {
        val fakeScheduler = FakeWorkScheduler()
        val repository = WorkManagerDownloadRepository(fakeScheduler)
        val workId = UUID.randomUUID()

        repository.cancel(workId.toString())

        assertEquals(workId, fakeScheduler.cancelled.single())
    }

    private fun mediaItem(mediaUrl: String?) = MediaItem(
        id = "media-1",
        accountId = "account-1",
        caption = "caption",
        mediaType = MediaType.IMAGE,
        mediaUrl = mediaUrl,
        thumbnailUrl = null,
        permalink = null,
        sourceType = SourceType.INSTAGRAM,
        timestamp = null
    )

    private fun downloadRecord(retryUrl: String?) = DownloadRecord(
        workId = UUID.randomUUID().toString(),
        mediaId = "media-1",
        title = "media-title",
        state = DownloadState.FAILED,
        progress = 0,
        localUri = null,
        error = "error",
        retryUrl = retryUrl,
        retryMediaType = MediaType.IMAGE
    )
}

private data class EnqueuedWork(
    val name: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest
)

private class FakeWorkScheduler : WorkScheduler {
    val enqueued = mutableListOf<EnqueuedWork>()
    val cancelled = mutableListOf<UUID>()

    override fun getWorkInfosByTagLiveData(tag: String): LiveData<List<WorkInfo>> =
        MutableLiveData(emptyList())

    override fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        enqueued += EnqueuedWork(name, policy, request)
    }

    override fun cancelWorkById(id: UUID) {
        cancelled += id
    }
}
