package local.capturetime.exif

import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile

/** Checks metadata segment boundaries up to the first scan; does not decode pixels. */
internal object JpegStructure {
    const val ERROR_MESSAGE = "JPEG 结构异常，无法写入"

    fun validate(file: File) {
        RandomAccessFile(file, "r").use { input ->
            if (input.read() != 0xff || input.read() != 0xd8) return
            while (true) {
                if (input.read() != 0xff) invalid()
                var marker = input.read()
                while (marker == 0xff) marker = input.read()
                if (marker < 0 || marker == 0 || marker == 0xd8 || marker == 0xd9 || marker in 0xd0..0xd7) invalid()
                if (marker == 0x01) continue
                val high = input.read()
                val low = input.read()
                if (high < 0 || low < 0) invalid()
                val length = (high shl 8) or low
                val end = input.filePointer + length - 2
                if (length < 2 || end > input.length()) invalid()
                if (marker == 0xda) {
                    val components = input.read()
                    if (components !in 1..4 || length != 6 + 2 * components || end >= input.length()) invalid()
                    return
                }
                input.seek(end)
            }
        }
    }

    fun validate(descriptor: FileDescriptor) {
        val size = Os.fstat(descriptor).st_size
        var position = 0L
        fun readByte(): Int {
            val value = ByteArray(1)
            if (Os.pread(descriptor, value, 0, 1, position) != 1) return -1
            position++
            return value[0].toInt() and 0xff
        }
        if (readByte() != 0xff || readByte() != 0xd8) return
        while (true) {
            if (readByte() != 0xff) invalid()
            var marker = readByte()
            while (marker == 0xff) marker = readByte()
            if (marker < 0 || marker == 0 || marker == 0xd8 || marker == 0xd9 || marker in 0xd0..0xd7) invalid()
            if (marker == 0x01) continue
            val high = readByte()
            val low = readByte()
            if (high < 0 || low < 0) invalid()
            val length = (high shl 8) or low
            val end = position + length - 2
            if (length < 2 || end > size) invalid()
            if (marker == 0xda) {
                val components = readByte()
                if (components !in 1..4 || length != 6 + 2 * components || end >= size) invalid()
                return
            }
            position = end
        }
    }

    private fun invalid(): Nothing = throw IOException(ERROR_MESSAGE)
}
