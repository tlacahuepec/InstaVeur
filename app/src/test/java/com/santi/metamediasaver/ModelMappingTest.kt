package com.santi.metamediasaver

import com.santi.metamediasaver.data.download.DownloadFileNamer
import com.santi.metamediasaver.data.model.MediaItem
import com.santi.metamediasaver.data.model.MediaType
import com.santi.metamediasaver.data.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMappingTest {
    @Test
    fun mediaTypeMapsMetaValues() {
        assertEquals(MediaType.IMAGE, MediaType.fromWire("IMAGE"))
        assertEquals(MediaType.VIDEO, MediaType.fromWire("video"))
        assertEquals(MediaType.CAROUSEL, MediaType.fromWire("CAROUSEL_ALBUM"))
        assertEquals(MediaType.UNKNOWN, MediaType.fromWire(null))
    }

    @Test
    fun sourceTypeMapsBackendValues() {
        assertEquals(SourceType.INSTAGRAM, SourceType.fromWire("instagram"))
        assertEquals(SourceType.FACEBOOK_PAGE, SourceType.fromWire("facebook_page"))
        assertEquals(SourceType.UNKNOWN, SourceType.fromWire("something_else"))
    }

    @Test
    fun fileNamesAreSanitized() {
        val item =
            MediaItem(
                id = "1789:bad/id",
                accountId = "account",
                caption = null,
                mediaType = MediaType.VIDEO,
                mediaUrl = "https://example.com/video.mp4",
                thumbnailUrl = null,
                permalink = null,
                sourceType = SourceType.INSTAGRAM,
                timestamp = null,
            )

        val name = DownloadFileNamer.suggestedFileName(item)

        assertTrue(name.startsWith("meta_1789_bad_id"))
        assertTrue(name.endsWith(".mp4"))
    }
}
