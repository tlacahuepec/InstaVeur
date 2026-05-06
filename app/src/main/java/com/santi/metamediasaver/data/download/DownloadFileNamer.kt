package com.santi.metamediasaver.data.download

import com.santi.metamediasaver.data.model.MediaItem

object DownloadFileNamer {
    fun suggestedFileName(item: MediaItem): String {
        val cleanedId = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return "meta_${cleanedId}.${item.mediaType.extension}"
    }

    fun displayTitle(fileName: String): String = fileName.substringBeforeLast('.')
}
