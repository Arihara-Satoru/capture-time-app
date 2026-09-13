package local.capturetime.operation

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Large sequential I/O buffers reduce shared-storage calls without dropping hash checks. */
internal object VerifiedPhotoBackup {
    private const val BUFFER_SIZE = 256 * 1024

    fun copyAndVerify(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output, BUFFER_SIZE)
                output.fd.sync()
            }
        }
        require(source.length() == destination.length() && sha256(source).contentEquals(sha256(destination))) {
            "文件副本完整性校验失败"
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
