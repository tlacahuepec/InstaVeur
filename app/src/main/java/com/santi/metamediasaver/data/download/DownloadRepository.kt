package com.santi.metamediasaver.data.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.santi.metamediasaver.data.model.DownloadRecord
import com.santi.metamediasaver.data.model.DownloadState
import com.santi.metamediasaver.data.model.MediaItem
import com.santi.metamediasaver.data.model.MediaType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadRecord>>
    suspend fun enqueue(item: MediaItem): UUID
    suspend fun retry(record: DownloadRecord): UUID?
    suspend fun cancel(workId: String)
}

class WorkManagerDownloadRepository internal constructor(
    private val scheduler: WorkScheduler
) : DownloadRepository {
    constructor(context: Context) : this(
        WorkManagerScheduler(WorkManager.getInstance(context.applicationContext))
    )

    override fun observeDownloads(): Flow<List<DownloadRecord>> =
        callbackFlow {
            val liveData = scheduler.getWorkInfosByTagLiveData(MediaDownloadWorker.DOWNLOAD_TAG)
            val observer = Observer<List<WorkInfo>> { infos ->
                trySend(
                    infos.map { it.toDownloadRecord() }
                    .sortedWith(compareBy<DownloadRecord> { it.state == DownloadState.SUCCEEDED }
                        .thenBy { it.title })
                )
            }
            val mainHandler = Handler(Looper.getMainLooper())

            mainHandler.post { liveData.observeForever(observer) }
            awaitClose { mainHandler.post { liveData.removeObserver(observer) } }
        }

    override suspend fun enqueue(item: MediaItem): UUID {
        val mediaUrl = item.mediaUrl ?: error("Media item has no downloadable URL.")
        val request = requestBuilder(
            mediaId = item.id,
            mediaUrl = mediaUrl,
            mediaType = item.mediaType,
            fileName = DownloadFileNamer.suggestedFileName(item)
        )

        scheduler.enqueueUniqueWork(
            "download-${item.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    override suspend fun retry(record: DownloadRecord): UUID? {
        val retryUrl = record.retryUrl ?: return null
        val request = requestBuilder(
            mediaId = record.mediaId,
            mediaUrl = retryUrl,
            mediaType = record.retryMediaType,
            fileName = "${record.title}.${record.retryMediaType.extension}"
        )

        scheduler.enqueueUniqueWork(
            "download-${record.mediaId}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    override suspend fun cancel(workId: String) {
        scheduler.cancelWorkById(UUID.fromString(workId))
    }

    private fun requestBuilder(
        mediaId: String,
        mediaUrl: String,
        mediaType: MediaType,
        fileName: String
    ): OneTimeWorkRequest {
        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MEDIA_ID, mediaId)
            .putString(MediaDownloadWorker.KEY_MEDIA_URL, mediaUrl)
            .putString(MediaDownloadWorker.KEY_MEDIA_TYPE, mediaType.name)
            .putString(MediaDownloadWorker.KEY_FILE_NAME, fileName)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag(MediaDownloadWorker.DOWNLOAD_TAG)
            .addTag("${MediaDownloadWorker.TAG_MEDIA_ID}$mediaId")
            .addTag("${MediaDownloadWorker.TAG_TITLE}$fileName")
            .addTag("${MediaDownloadWorker.TAG_TYPE}${mediaType.name}")
            .build()
    }

    private fun WorkInfo.toDownloadRecord(): DownloadRecord {
        val title = tagValue(MediaDownloadWorker.TAG_TITLE)
            ?.let(DownloadFileNamer::displayTitle)
            ?: outputData.getString(MediaDownloadWorker.KEY_FILE_NAME)
            ?: "Download"
        val mediaId = tagValue(MediaDownloadWorker.TAG_MEDIA_ID)
            ?: outputData.getString(MediaDownloadWorker.KEY_MEDIA_ID)
            ?: id.toString()
        val mediaType = tagValue(MediaDownloadWorker.TAG_TYPE)
            ?.let(MediaType::fromWire)
            ?: outputData.getString(MediaDownloadWorker.KEY_MEDIA_TYPE)
                ?.let(MediaType::fromWire)
            ?: MediaType.UNKNOWN

        return DownloadRecord(
            workId = id.toString(),
            mediaId = mediaId,
            title = title,
            state = state.toDownloadState(),
            progress = progress.getInt(MediaDownloadWorker.KEY_PROGRESS, progressForState(state)),
            localUri = outputData.getString(MediaDownloadWorker.KEY_LOCAL_URI),
            error = outputData.getString(MediaDownloadWorker.KEY_ERROR),
            retryUrl = outputData.getString(MediaDownloadWorker.KEY_MEDIA_URL),
            retryMediaType = mediaType
        )
    }

    private fun WorkInfo.tagValue(prefix: String): String? =
        tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun progressForState(state: WorkInfo.State): Int = when (state) {
        WorkInfo.State.SUCCEEDED -> 100
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED -> 0
        else -> 0
    }

    private fun WorkInfo.State.toDownloadState(): DownloadState = when (this) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> DownloadState.QUEUED
        WorkInfo.State.RUNNING -> DownloadState.RUNNING
        WorkInfo.State.SUCCEEDED -> DownloadState.SUCCEEDED
        WorkInfo.State.FAILED -> DownloadState.FAILED
        WorkInfo.State.CANCELLED -> DownloadState.CANCELLED
    }
}

internal interface WorkScheduler {
    fun getWorkInfosByTagLiveData(tag: String): LiveData<List<WorkInfo>>
    fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
    fun cancelWorkById(id: UUID)
}

private class WorkManagerScheduler(
    private val workManager: WorkManager
) : WorkScheduler {
    override fun getWorkInfosByTagLiveData(tag: String) = workManager.getWorkInfosByTagLiveData(tag)

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancelWorkById(id: UUID) {
        workManager.cancelWorkById(id)
    }
}
