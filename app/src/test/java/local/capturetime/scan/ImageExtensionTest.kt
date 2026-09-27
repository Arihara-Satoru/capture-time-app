package local.capturetime.scan

import local.capturetime.model.ImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImageExtensionTest {
    @Test fun correctsJpegNamedPngWithoutOverwriting() {
        val directory = Files.createTempDirectory("extension-correction").toFile()
        try {
            val source = directory.resolve("1696840326919.png")
            source.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()) + ByteArray(12))
            assertEquals("jpg", ImageExtension.inspect(source).correction)
            val target = directory.resolve("1696840326919.jpg")
            target.writeText("keep")
            assertThrows(IllegalArgumentException::class.java) { ImageExtension.correct(source, "jpg", directory) }
            assertEquals("keep", target.readText())
            target.delete()
            val originalBytes = source.readBytes()
            assertEquals(target, ImageExtension.correct(source, "jpg", directory))
            assertFalse(source.exists())
            assertArrayEquals(originalBytes, target.readBytes())
            assertEquals(ImageFormat.JPEG, ImageExtension.inspect(target).format)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun batchContinuesPastExistingTarget() {
        val directory = Files.createTempDirectory("extension-batch").toFile()
        try {
            val first = directory.resolve("first.png")
            val second = directory.resolve("second.png")
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(14)
            first.writeBytes(jpeg)
            second.writeBytes(jpeg)
            directory.resolve("first.jpg").writeText("keep")
            val result = ImageExtension.correctAll(listOf(first to "jpg", second to "jpg"), directory)
            assertEquals(1, result.renamed.size)
            assertEquals(directory.resolve("second.jpg"), result.renamed[second])
            assertEquals(first, result.failures.single().first)
            assertTrue(first.exists())
            assertEquals("keep", directory.resolve("first.jpg").readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
