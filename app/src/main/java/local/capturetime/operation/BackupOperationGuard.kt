package local.capturetime.operation

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

object BackupOperationGuard {
    private const val CAPTURE_ACTIVE_FILE = "capture-time-active"
    private const val DUPLICATE_ACTIVE_FILE = "duplicate-delete-active"
    private const val DUPLICATE_PENDING_FILE = "pending-duplicate-delete.json"
    private val lock = Any()

    @Volatile private var captureActiveInProcess = false

    fun <T> withStateLock(action: () -> T): T = synchronized(lock) { action() }

    fun <T> beginCapture(context: Context, createSession: () -> T, sessionDirectory: (T) -> File): T =
        synchronized(lock) {
            check(!captureActiveInProcess) { "另一项拍摄时间操作仍在进行" }
            check(!atomicFileExists(captureMarker(context))) { "存在上次中断的拍摄时间会话，请先检查备份并解除锁定" }
            check(!hasDuplicateState(context)) { "重复删除仍在准备、确认或核验" }
            val session = createSession()
            writeMarker(captureMarker(context), sessionDirectory(session).absolutePath)
            captureActiveInProcess = true
            session
        }

    fun endCapture(context: Context, sessionDirectory: File, completed: Boolean) = synchronized(lock) {
        if (completed) clearMatchingMarker(captureMarker(context), sessionDirectory)
        captureActiveInProcess = false
    }

    fun hasCaptureRecoveryState(context: Context): Boolean = synchronized(lock) {
        atomicFileExists(captureMarker(context))
    }

    fun captureRecoverySession(context: Context): String? = synchronized(lock) {
        readMarker(captureMarker(context))
    }

    fun acknowledgeCaptureRecovery(context: Context): Boolean = synchronized(lock) {
        if (captureActiveInProcess) false else {
            captureMarker(context).delete()
            true
        }
    }

    fun beginDuplicate(context: Context, createSession: () -> File): File = synchronized(lock) {
        check(!captureActiveInProcess && !atomicFileExists(captureMarker(context))) {
            "拍摄时间操作仍在进行或存在中断恢复会话"
        }
        check(!hasDuplicateState(context)) { "另一项重复删除仍在进行" }
        val session = createSession()
        writeMarker(duplicateMarker(context), session.absolutePath)
        session
    }

    fun clearDuplicateActive(context: Context, sessionDirectory: File) = synchronized(lock) {
        clearMatchingMarker(duplicateMarker(context), sessionDirectory)
    }

    fun hasDuplicateState(context: Context): Boolean = synchronized(lock) {
        atomicFileExists(duplicatePending(context)) || atomicFileExists(duplicateMarker(context))
    }

    fun isCleanupBlocked(context: Context): Boolean = synchronized(lock) {
        captureActiveInProcess || atomicFileExists(captureMarker(context)) ||
            atomicFileExists(duplicatePending(context)) || atomicFileExists(duplicateMarker(context))
    }

    fun runCleanupIfAllowed(context: Context, action: () -> Unit): Boolean = synchronized(lock) {
        if (isCleanupBlocked(context)) false else {
            action()
            true
        }
    }

    private fun captureMarker(context: Context) = AtomicFile(File(context.filesDir, CAPTURE_ACTIVE_FILE))
    private fun duplicateMarker(context: Context) = AtomicFile(File(context.filesDir, DUPLICATE_ACTIVE_FILE))
    private fun duplicatePending(context: Context) = AtomicFile(File(context.filesDir, DUPLICATE_PENDING_FILE))

    private fun writeMarker(file: AtomicFile, value: String) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(file::failWrite)
            throw error
        }
    }

    private fun clearMatchingMarker(file: AtomicFile, sessionDirectory: File) {
        if (readMarker(file) == sessionDirectory.absolutePath) file.delete()
    }

    private fun readMarker(file: AtomicFile): String? = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private fun atomicFileExists(file: AtomicFile): Boolean = runCatching {
        val base = file.baseFile
        base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()
    }.getOrDefault(true)
}
