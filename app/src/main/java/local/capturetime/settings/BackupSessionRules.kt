package local.capturetime.settings

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files

internal object BackupSessionRules {
    private const val CAPTURE_PREFIX = "capture-time-app"
    private const val DUPLICATE_PREFIX = "duplicate-cleanup"
    private val timestamp = Regex("\\d{8}-\\d{6}")
    private val isAndroidRuntime = System.getProperty("java.runtime.name") == "Android Runtime"

    fun captureSessionName(stamp: String, debug: Boolean): String = sessionName(CAPTURE_PREFIX, stamp, debug)
    fun duplicateSessionName(stamp: String, debug: Boolean): String = sessionName(DUPLICATE_PREFIX, stamp, debug)

    fun isCaptureSession(directory: File, debug: Boolean): Boolean =
        isPlainDirectory(directory) && isPlainDirectory(directory.parentFile) &&
            directory.canonicalFile.parentFile == directory.parentFile?.canonicalFile &&
            directory.parentFile?.name == ".temp" && matches(directory.name, CAPTURE_PREFIX, debug)

    fun isManagedSession(directory: File, debug: Boolean): Boolean =
        isPlainDirectory(directory) && isPlainDirectory(directory.parentFile) &&
            directory.canonicalFile.parentFile == directory.parentFile?.canonicalFile &&
            directory.parentFile?.name == ".temp" && isManagedName(directory.name, debug)

    fun createSessionDirectory(storageRoot: File, name: String): File {
        val temp = backupRoot(storageRoot)
        val directory = File(temp, name)
        require(!directory.exists() && directory.mkdir()) { "无法创建唯一会话目录" }
        require(isPlainDirectory(directory) && directory.canonicalFile.parentFile == temp.canonicalFile) {
            directory.delete()
            "会话目录路径不安全"
        }
        return directory
    }

    fun sessionSize(directory: File, debug: Boolean): Long {
        if (!isManagedSession(directory, debug)) return 0
        return noFollowFiles(directory).sumOf(File::length)
    }

    fun deleteManagedSession(directory: File, debug: Boolean): Boolean {
        if (isAndroidRuntime) return deleteManagedSessionAndroid(directory, debug)
        if (!isManagedSession(directory, debug)) return false
        return deleteNoFollow(directory, directory.canonicalFile)
    }

    internal fun isManagedName(name: String, debug: Boolean): Boolean =
        matches(name, CAPTURE_PREFIX, debug) || matches(name, DUPLICATE_PREFIX, debug)

    private fun sessionName(prefix: String, stamp: String, debug: Boolean): String =
        "$prefix${if (debug) "-debug" else ""}-$stamp"

    private fun matches(name: String, prefix: String, debug: Boolean): Boolean {
        val expectedPrefix = "$prefix${if (debug) "-debug" else ""}-"
        return name.startsWith(expectedPrefix) && timestamp.matches(name.removePrefix(expectedPrefix))
    }

    private fun backupRoot(storageRoot: File): File {
        require(isPlainDirectory(storageRoot)) { "共享存储根目录路径不安全" }
        val temp = File(storageRoot, ".temp")
        require(temp.isDirectory || (!temp.exists() && temp.mkdir())) { "无法创建 /sdcard/.temp" }
        require(isPlainDirectory(temp) && temp.canonicalFile.parentFile == storageRoot.canonicalFile) {
            "备份根目录路径不安全"
        }
        return temp
    }

    private fun isPlainDirectory(directory: File?): Boolean = directory != null && runCatching {
        directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            directory.absoluteFile.path == directory.canonicalFile.path
    }.getOrDefault(false)

    private fun noFollowFiles(root: File): Sequence<File> = sequence {
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            current.listFiles().orEmpty().forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                if (child.isDirectory) pending.add(child) else if (child.isFile) yield(child)
            }
        }
    }

    private fun deleteNoFollow(file: File, root: File): Boolean {
        if (Files.isSymbolicLink(file.toPath())) return file.delete()
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (canonical != root && !canonical.toPath().startsWith(root.toPath())) return false
        if (file.isDirectory && file.listFiles().orEmpty().any { !deleteNoFollow(it, root) }) return false
        return file.delete()
    }

    private fun deleteManagedSessionAndroid(directory: File, debug: Boolean): Boolean = runCatching {
        if (!isManagedName(directory.name, debug)) return false
        val parent = directory.parentFile ?: return false
        if (parent.name != ".temp" || !isPlainDirectory(parent)) return false
        openDirectory(parent).use { parentHandle ->
            val anchoredSession = File("/proc/self/fd/${parentHandle.fd}", directory.name)
            val before = Os.lstat(anchoredSession.path)
            if (!OsConstants.S_ISDIR(before.st_mode) || OsConstants.S_ISLNK(before.st_mode)) return false
            openDirectory(anchoredSession).use { sessionHandle ->
                val opened = Os.fstat(sessionHandle.fileDescriptor)
                if (!sameIdentity(before, opened) || !deleteDirectoryContents(sessionHandle)) return false
                val after = Os.lstat(anchoredSession.path)
                if (!sameIdentity(opened, after) || !OsConstants.S_ISDIR(after.st_mode)) return false
                Os.remove(anchoredSession.path)
                true
            }
        }
    }.getOrDefault(false)

    private fun deleteDirectoryContents(directory: ParcelFileDescriptor): Boolean {
        val anchored = File("/proc/self/fd/${directory.fd}")
        val children = anchored.listFiles() ?: return false
        return children.all { child ->
            runCatching {
                val before = Os.lstat(child.path)
                if (OsConstants.S_ISDIR(before.st_mode) && !OsConstants.S_ISLNK(before.st_mode)) {
                    openDirectory(child).use { childHandle ->
                        val opened = Os.fstat(childHandle.fileDescriptor)
                        if (!sameIdentity(before, opened) || !deleteDirectoryContents(childHandle)) return@runCatching false
                        val after = Os.lstat(child.path)
                        if (!sameIdentity(opened, after) || !OsConstants.S_ISDIR(after.st_mode)) return@runCatching false
                        Os.remove(child.path)
                    }
                } else {
                    val after = Os.lstat(child.path)
                    if (!sameIdentity(before, after) || before.st_mode != after.st_mode) return@runCatching false
                    Os.remove(child.path)
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun openDirectory(directory: File): ParcelFileDescriptor {
        val raw = Os.open(
            directory.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0
        )
        try {
            require(OsConstants.S_ISDIR(Os.fstat(raw).st_mode)) { "路径不是目录" }
            val handle = ParcelFileDescriptor.dup(raw)
            try {
                val target = Os.readlink("/proc/self/fd/${handle.fd}")
                require(!target.endsWith(" (deleted)") && File(target).canonicalFile == directory.canonicalFile) {
                    "目录身份已变化"
                }
                return handle
            } catch (error: Throwable) {
                handle.close()
                throw error
            }
        } finally {
            closeQuietly(raw)
        }
    }

    private fun sameIdentity(first: android.system.StructStat, second: android.system.StructStat): Boolean =
        first.st_dev == second.st_dev && first.st_ino == second.st_ino

    private fun closeQuietly(descriptor: FileDescriptor) {
        runCatching { Os.close(descriptor) }
    }
}
