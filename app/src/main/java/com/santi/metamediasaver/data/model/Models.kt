package com.santi.metamediasaver.data.model

data class AuthUser(
    val uid: String,
    val email: String,
    val username: String,
)

data class ConnectedAccount(
    val id: String,
    val displayName: String,
    val username: String?,
    val sourceType: SourceType,
    val avatarUrl: String?,
) {
    val label: String
        get() = username?.takeIf { it.isNotBlank() } ?: displayName
}

data class MediaItem(
    val id: String,
    val accountId: String,
    val caption: String?,
    val mediaType: MediaType,
    val mediaUrl: String?,
    val thumbnailUrl: String?,
    val permalink: String?,
    val sourceType: SourceType,
    val timestamp: String?,
    val downloadable: Boolean = !mediaUrl.isNullOrBlank(),
) {
    val previewUrl: String?
        get() = thumbnailUrl ?: mediaUrl
}

data class DownloadRecord(
    val workId: String,
    val mediaId: String,
    val title: String,
    val state: DownloadState,
    val progress: Int,
    val localUri: String?,
    val error: String?,
    val retryUrl: String?,
    val retryMediaType: MediaType,
)

enum class SourceType {
    INSTAGRAM,
    FACEBOOK,
    FACEBOOK_PAGE,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): SourceType =
            when (value?.lowercase()) {
                "instagram" -> INSTAGRAM
                "facebook" -> FACEBOOK
                "facebook_user" -> FACEBOOK
                "facebook_page" -> FACEBOOK_PAGE
                else -> UNKNOWN
            }
    }
}

enum class MediaType {
    IMAGE,
    VIDEO,
    CAROUSEL,
    UNKNOWN,
    ;

    val mimeType: String
        get() =
            when (this) {
                IMAGE, CAROUSEL -> "image/jpeg"
                VIDEO -> "video/mp4"
                UNKNOWN -> "application/octet-stream"
            }

    val extension: String
        get() =
            when (this) {
                IMAGE, CAROUSEL -> "jpg"
                VIDEO -> "mp4"
                UNKNOWN -> "bin"
            }

    companion object {
        fun fromWire(value: String?): MediaType =
            when (value?.uppercase()) {
                "IMAGE", "PHOTO" -> IMAGE
                "VIDEO", "REELS" -> VIDEO
                "CAROUSEL", "CAROUSEL_ALBUM" -> CAROUSEL
                else -> UNKNOWN
            }
    }
}

enum class DownloadState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class PagedMedia(
    val items: List<MediaItem>,
    val nextCursor: String?,
)
