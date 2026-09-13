package local.capturetime.exif

import java.io.File
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

    private fun invalid(): Nothing = throw IOException(ERROR_MESSAGE)
}
