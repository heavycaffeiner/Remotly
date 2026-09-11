package com.remotly.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePasteTest {

    @Test
    fun `imageExtension takes the last segment, lowercased`() {
        assertEquals("png", ImagePaste.imageExtension("photo.PNG"))
        assertEquals("jpeg", ImagePaste.imageExtension("photo.jpeg"))
        assertEquals("gz", ImagePaste.imageExtension("photo.tar.gz"))
    }

    @Test
    fun `imageExtension falls back to png for anything it cannot trust`() {
        assertEquals("png", ImagePaste.imageExtension("photo"))
        assertEquals("png", ImagePaste.imageExtension("photo."))
        assertEquals("png", ImagePaste.imageExtension(""))
        assertEquals("png", ImagePaste.imageExtension("photo.toolongext"))
        assertEquals("png", ImagePaste.imageExtension("photo.j p"))
        assertEquals("png", ImagePaste.imageExtension("photo.j/p"))
    }

    @Test
    fun `pastedImageName stamps the time and keeps the source extension`() {
        assertEquals("paste_1970-01-01_00-00-00-000.png", ImagePaste.pastedImageName(0L))
        assertEquals("paste_1970-01-01_00-00-00-001.jpg", ImagePaste.pastedImageName(1L, "camera.JPG"))
    }

    @Test
    fun `pastedImageName is always a single plain name`() {
        val name = ImagePaste.pastedImageName(System.currentTimeMillis(), "../evil.png")
        assertTrue(isPlainName(name))
    }

    @Test
    fun `sizeRefusal rejects an empty or oversized image`() {
        assertEquals("That image is empty.", ImagePaste.sizeRefusal(0))
        assertEquals("That image is empty.", ImagePaste.sizeRefusal(-1))
        assertEquals(null, ImagePaste.sizeRefusal(1024))
        assertEquals(null, ImagePaste.sizeRefusal(ImagePaste.MAX_IMAGE_BYTES))
        assertEquals(
            "That image is too large to paste.",
            ImagePaste.sizeRefusal(ImagePaste.MAX_IMAGE_BYTES + 1),
        )
    }
}
