package com.santi.metamediasaver

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test

class MediaStoreSmokeTest {
    @Test
    fun insertsAndDeletesGalleryImageEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "media_store_smoke_test.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Meta Media Saver",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

        val uri = resolver.insert(collection, values)
        assertNotNull(uri)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(byteArrayOf(0x01, 0x02, 0x03))
            }
            resolver.delete(uri, null, null)
        }
    }
}
