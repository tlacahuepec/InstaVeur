package com.santi.metamediasaver.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.google.firebase.functions.FirebaseFunctions
import com.santi.metamediasaver.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File

class MediaDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val mediaId = inputData.getString(KEY_MEDIA_ID).orEmpty()
            val originalUrl = inputData.getString(KEY_MEDIA_URL).orEmpty()
            val fileName = inputData.getString(KEY_FILE_NAME).orEmpty()
            val mediaType = MediaType.fromWire(inputData.getString(KEY_MEDIA_TYPE))

            if (mediaId.isBlank() || originalUrl.isBlank() || fileName.isBlank()) {
                return@withContext Result.failure(errorData("Missing download input."))
            }

            setProgress(progressData(0))

            try {
                val localUri = tryDownload(originalUrl, mediaType, fileName)
                Result.success(
                    outputData(
                        localUri = localUri.toString(),
                        mediaId = mediaId,
                        mediaUrl = originalUrl,
                        mediaType = mediaType,
                        fileName = fileName,
                    ),
                )
            } catch (httpError: HttpDownloadException) {
                val refreshedUrl =
                    if (httpError.statusCode == 401 || httpError.statusCode == 403) {
                        refreshMediaUrl(mediaId)
                    } else {
                        null
                    }

                if (!refreshedUrl.isNullOrBlank() && refreshedUrl != originalUrl) {
                    try {
                        val localUri = tryDownload(refreshedUrl, mediaType, fileName)
                        return@withContext Result.success(
                            outputData(
                                localUri = localUri.toString(),
                                mediaId = mediaId,
                                mediaUrl = refreshedUrl,
                                mediaType = mediaType,
                                fileName = fileName,
                            ),
                        )
                    } catch (retryError: Exception) {
                        return@withContext Result.failure(
                            errorData(
                                retryError.message ?: "Download failed after refreshing the URL.",
                                mediaId,
                                refreshedUrl,
                                mediaType,
                                fileName,
                            ),
                        )
                    }
                }

                val message = "Download failed with HTTP ${httpError.statusCode}."
                if (httpError.statusCode in 500..599 && runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.failure(errorData(message, mediaId, originalUrl, mediaType, fileName))
                }
            } catch (error: Exception) {
                Result.failure(
                    errorData(
                        error.message ?: "Download failed.",
                        mediaId,
                        originalUrl,
                        mediaType,
                        fileName,
                    ),
                )
            }
        }

    private suspend fun tryDownload(
        mediaUrl: String,
        mediaType: MediaType,
        fileName: String,
    ): Uri {
        val response =
            client.newCall(
                Request.Builder()
                    .url(mediaUrl)
                    .get()
                    .build(),
            ).execute()

        response.use { openedResponse ->
            if (!openedResponse.isSuccessful) {
                throw HttpDownloadException(openedResponse.code)
            }

            val body = openedResponse.body ?: error("Server returned an empty response.")
            return writeToMediaStore(
                response = openedResponse,
                mediaType = mediaType,
                fileName = fileName,
                contentLength = body.contentLength(),
            )
        }
    }

    private suspend fun writeToMediaStore(
        response: Response,
        mediaType: MediaType,
        fileName: String,
        contentLength: Long,
    ): Uri {
        val resolver = applicationContext.contentResolver
        val collectionUri = collectionUri(mediaType)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mediaType.mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(mediaType))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    put(MediaStore.MediaColumns.DATA, legacyFile(mediaType, fileName).absolutePath)
                }
            }

        val uri =
            checkNotNull(resolver.insert(collectionUri, values)) {
                "Could not create a gallery entry."
            }

        try {
            val body = checkNotNull(response.body) { "Server returned an empty response." }
            resolver.openOutputStream(uri)?.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var read = input.read(buffer)

                    while (read >= 0) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0) {
                            val percent =
                                ((downloaded * 100) / contentLength)
                                    .coerceIn(0, 100)
                                    .toInt()
                            setProgress(progressData(percent))
                        }
                        read = input.read(buffer)
                    }
                }
            } ?: error("Could not open gallery file for writing.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            setProgress(progressData(100))
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun collectionUri(mediaType: MediaType): Uri {
        val volume =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            } else {
                "external"
            }

        return when (mediaType) {
            MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            else -> MediaStore.Images.Media.getContentUri(volume)
        }
    }

    private fun relativePath(mediaType: MediaType): String =
        when (mediaType) {
            MediaType.VIDEO -> "${Environment.DIRECTORY_MOVIES}/Meta Media Saver"
            else -> "${Environment.DIRECTORY_PICTURES}/Meta Media Saver"
        }

    @Suppress("DEPRECATION")
    private fun legacyFile(
        mediaType: MediaType,
        fileName: String,
    ): File {
        val root =
            when (mediaType) {
                MediaType.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
        val directory = File(root, "Meta Media Saver")
        directory.mkdirs()
        return File(directory, fileName)
    }

    private suspend fun refreshMediaUrl(mediaId: String): String? =
        runCatching {
            val result =
                FirebaseFunctions.getInstance()
                    .getHttpsCallable("refreshMediaUrl")
                    .call(mapOf(KEY_MEDIA_ID to mediaId))
                    .await()
                    .data

            val map = result as? Map<*, *>
            map?.get("mediaUrl")?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun progressData(progress: Int): Data = Data.Builder().putInt(KEY_PROGRESS, progress).build()

    private fun outputData(
        localUri: String,
        mediaId: String,
        mediaUrl: String,
        mediaType: MediaType,
        fileName: String,
    ): Data =
        Data.Builder()
            .putString(KEY_LOCAL_URI, localUri)
            .putString(KEY_MEDIA_ID, mediaId)
            .putString(KEY_MEDIA_URL, mediaUrl)
            .putString(KEY_MEDIA_TYPE, mediaType.name)
            .putString(KEY_FILE_NAME, fileName)
            .putInt(KEY_PROGRESS, 100)
            .build()

    private fun errorData(
        message: String,
        mediaId: String? = inputData.getString(KEY_MEDIA_ID),
        mediaUrl: String? = inputData.getString(KEY_MEDIA_URL),
        mediaType: MediaType = MediaType.fromWire(inputData.getString(KEY_MEDIA_TYPE)),
        fileName: String? = inputData.getString(KEY_FILE_NAME),
    ): Data =
        Data.Builder()
            .putString(KEY_ERROR, message)
            .putString(KEY_MEDIA_ID, mediaId)
            .putString(KEY_MEDIA_URL, mediaUrl)
            .putString(KEY_MEDIA_TYPE, mediaType.name)
            .putString(KEY_FILE_NAME, fileName)
            .build()

    private class HttpDownloadException(val statusCode: Int) : RuntimeException()

    companion object {
        const val DOWNLOAD_TAG = "meta-media-download"
        const val TAG_MEDIA_ID = "media-id:"
        const val TAG_TITLE = "title:"
        const val TAG_TYPE = "type:"

        const val KEY_MEDIA_ID = "mediaId"
        const val KEY_MEDIA_URL = "mediaUrl"
        const val KEY_MEDIA_TYPE = "mediaType"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_LOCAL_URI = "localUri"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
    }
}
