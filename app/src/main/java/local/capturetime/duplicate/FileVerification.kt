package local.capturetime.duplicate

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object FileVerification {
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
