package local.capturetime.duplicate

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

object FileVerification {
    fun sameJpegExceptExif(first: File, second: File): Boolean {
        val left = jpegWithoutExifHash(first) ?: return false
        return left.contentEquals(jpegWithoutExifHash(second) ?: return false)
    }

    private fun jpegWithoutExifHash(file: File): ByteArray? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 4 || input.read() != 0xff || input.read() != 0xd8) return null
        input.seek(input.length() - 2)
        if (input.read() != 0xff || input.read() != 0xd9) return null
        input.seek(2)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        fun addBytes(start: Long, end: Long) {
            input.seek(start)
            var remaining = end - start
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count <= 0) throw java.io.EOFException()
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        while (input.filePointer < input.length()) {
            val start = input.filePointer
            if (input.read() != 0xff) return null
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker < 0 || marker == 0 || marker == 0xd8 || marker == 0xd9 || marker in 0xd0..0xd7) return null
            if (marker == 0xda) {
                addBytes(start, input.length())
                return digest.digest()
            }
            if (marker == 0x01) { addBytes(start, input.filePointer); continue }
            val high = input.read()
            val low = input.read()
            if (high < 0 || low < 0) return null
            val length = (high shl 8) or low
            val end = input.filePointer + length - 2
            if (length < 2 || end > input.length()) return null
            val exif = ByteArray(6)
            val skipExif = if (marker == 0xe1 && length >= 8) {
                input.readFully(exif)
                exif.contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))
            } else false
            // ponytail: only EXIF APP1 may differ; all image data and other metadata must match.
            if (!skipExif) addBytes(start, end)
            input.seek(end)
        }
        null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun contentEquals(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        FileInputStream(first).buffered().use { left ->
            FileInputStream(second).buffered().use { right ->
                val leftBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val rightBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val leftCount = left.read(leftBuffer)
                    val rightCount = right.read(rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount < 0) return true
                    if (!leftBuffer.copyOf(leftCount).contentEquals(rightBuffer.copyOf(rightCount))) return false
                }
            }
        }
    }
}
