package local.capturetime.operation

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Large sequential I/O buffers reduce shared-storage calls without dropping hash checks. */
internal object VerifiedPhotoBackup {
    private const val BUFFER_SIZE = 256 * 1024
    private val isAndroidRuntime = System.getProperty("java.runtime.name") == "Android Runtime"

    fun copyAndVerify(
        source: File,
        destination: File,
        sourceRoot: File = requireNotNull(source.parentFile),
        destinationRoot: File = requireNotNull(destination.parentFile),
        expectedDestinationIdentity: FileIdentity? = null
    ) {
        if (isAndroidRuntime) {
            copyAndroid(source, destination, sourceRoot, destinationRoot, createNew = false, expectedDestinationIdentity)
        } else {
            copyNio(source, destination, sourceRoot, destinationRoot, createNew = false, expectedDestinationIdentity)
        }
        verifySameContent(source, destination, sourceRoot, destinationRoot)
    }

    fun copyNewAndVerify(source: File, destination: File, sessionRoot: File): BackupReceipt {
        val identity = if (isAndroidRuntime) {
            copyAndroid(source, destination, requireNotNull(source.parentFile), sessionRoot, createNew = true)
        } else {
            copyNio(source, destination, requireNotNull(source.parentFile), sessionRoot, createNew = true)
        }
        verifySameContent(source, destination, requireNotNull(source.parentFile), sessionRoot)
        return BackupReceipt(
            identity,
            sha256Hex(destination, sessionRoot, requireSingleLink = true)
        )
    }

    fun <T> withVerifiedWritableFile(
        file: File,
        root: File,
        expectedIdentity: FileIdentity,
        action: (FileDescriptor, File) -> T
    ): T {
        check(isAndroidRuntime) { "仅 Android 运行时支持安全文件写入" }
        val descriptor = openAndroidExisting(
            file,
            root,
            OsConstants.O_RDWR,
            expectedIdentity,
            requireSingleLink = true
        )
        val parcel = try {
            ParcelFileDescriptor.dup(descriptor)
        } finally {
            closeQuietly(descriptor)
        }
        return parcel.use {
            val procFile = File("/proc/self/fd/${it.fd}")
            val result = action(it.fileDescriptor, procFile)
            validateOpenedDescriptor(it.fileDescriptor, file, root, expectedIdentity, requireSingleLink = true)
            result
        }
    }

    fun sha256Hex(file: File, root: File, requireSingleLink: Boolean = false): String =
        fileState(file, root, requireSingleLink).hash.joinToString("") { "%02x".format(it) }

    fun contentMatches(
        source: File,
        destination: File,
        sourceRoot: File,
        destinationRoot: File,
        expectedSha256: String? = null
    ): Boolean = runCatching {
        val sourceState = fileState(source, sourceRoot)
        val destinationState = fileState(destination, destinationRoot, requireSingleLink = true)
        val independent = if (isAndroidRuntime) {
            sourceState.identity != destinationState.identity
        } else {
            !Files.isSameFile(source.toPath(), destination.toPath())
        }
        independent &&
            sourceState.size == destinationState.size &&
            sourceState.hash.contentEquals(destinationState.hash) &&
            (expectedSha256 == null || expectedSha256.equals(
                destinationState.hash.joinToString("") { "%02x".format(it) },
                ignoreCase = true
            ))
    }.getOrDefault(false)

    fun hasIdentity(file: File, root: File, expectedIdentity: FileIdentity): Boolean = runCatching {
        if (isAndroidRuntime) {
            val descriptor = openAndroidExisting(file, root, OsConstants.O_RDONLY, expectedIdentity)
            closeQuietly(descriptor)
        } else {
            require(nioIdentity(file) == expectedIdentity) { "文件身份已变化" }
        }
        true
    }.getOrDefault(false)

    private fun copyAndroid(
        source: File,
        destination: File,
        sourceRoot: File,
        destinationRoot: File,
        createNew: Boolean,
        expectedDestinationIdentity: FileIdentity? = null
    ): FileIdentity {
        val input = openAndroidExisting(source, sourceRoot, OsConstants.O_RDONLY)
        val output = try {
            if (createNew) openAndroidNew(destination, destinationRoot)
            else openAndroidExisting(
                destination,
                destinationRoot,
                OsConstants.O_WRONLY,
                expectedDestinationIdentity,
                requireSingleLink = true
            )
        } catch (error: Throwable) {
            closeQuietly(input)
            throw error
        }
        try {
            val inputIdentity = identity(Os.fstat(input))
            val outputStat = Os.fstat(output)
            require(inputIdentity != identity(outputStat)) { "源文件与目标文件指向同一实体" }
            require(outputStat.st_nlink == 1L) { "目标文件存在硬链接，拒绝覆盖" }
            if (!createNew) Os.ftruncate(output, 0)
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = Os.read(input, buffer, 0, buffer.size)
                if (count == 0) break
                var offset = 0
                while (offset < count) offset += Os.write(output, buffer, offset, count - offset)
            }
            Os.fsync(output)
            return inputIdentity
        } finally {
            closeQuietly(output)
            closeQuietly(input)
        }
    }

    private fun copyNio(
        source: File,
        destination: File,
        sourceRoot: File,
        destinationRoot: File,
        createNew: Boolean,
        expectedDestinationIdentity: FileIdentity? = null
    ): FileIdentity {
        validateExistingFile(source, sourceRoot)
        var existingDestinationIdentity: FileIdentity? = null
        if (createNew) validateNewDestination(destination, destinationRoot)
        else {
            validateExistingFile(destination, destinationRoot)
            val observedIdentity = nioIdentity(destination)
            require(expectedDestinationIdentity == null || observedIdentity == expectedDestinationIdentity) {
                "文件身份已变化"
            }
            existingDestinationIdentity = expectedDestinationIdentity ?: observedIdentity
            requireSingleLinkNio(destination)
            require(!Files.isSameFile(source.toPath(), destination.toPath())) { "源文件与目标文件指向同一实体" }
        }
        val sourceIdentity = nioIdentity(source)
        FileChannel.open(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val options: Array<OpenOption> = if (createNew) {
                arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            } else {
                arrayOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            }
            FileChannel.open(destination.toPath(), *options).use { output ->
                if (createNew) validateCreatedDestination(destination, destinationRoot)
                else {
                    validateExistingFile(destination, destinationRoot)
                    require(nioIdentity(destination) == existingDestinationIdentity) {
                        "文件身份已变化"
                    }
                    requireSingleLinkNio(destination)
                }
                if (!createNew) output.truncate(0)
                output.position(0)
                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                while (input.read(buffer) >= 0) {
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    buffer.clear()
                }
                output.force(true)
            }
        }
        return sourceIdentity
    }

    private fun openAndroidExisting(
        file: File,
        root: File,
        access: Int,
        expectedIdentity: FileIdentity? = null,
        requireSingleLink: Boolean = false
    ): FileDescriptor {
        validateExistingFile(file, root)
        val descriptor = Os.open(
            file.absolutePath,
            access or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0
        )
        return validateOpenedDescriptor(descriptor, file, root, expectedIdentity, requireSingleLink)
    }

    private fun openAndroidNew(destination: File, root: File): FileDescriptor {
        validateNewDestination(destination, root)
        val descriptor = Os.open(
            destination.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0x180
        )
        return validateOpenedDescriptor(descriptor, destination, root, requireSingleLink = true)
    }

    private fun validateOpenedDescriptor(
        descriptor: FileDescriptor,
        expected: File,
        root: File,
        expectedIdentity: FileIdentity? = null,
        requireSingleLink: Boolean = false
    ): FileDescriptor {
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(stat.st_mode)) { "打开的路径不是普通文件" }
            if (requireSingleLink) require(stat.st_nlink == 1L) { "文件存在硬链接，拒绝写入" }
            if (expectedIdentity != null) require(identity(stat) == expectedIdentity) { "文件身份已变化" }
            val target = ParcelFileDescriptor.dup(descriptor).use { duplicate ->
                Os.readlink("/proc/self/fd/${duplicate.fd}")
            }
            require(!target.endsWith(" (deleted)")) { "文件在打开后已被替换" }
            val opened = File(target).canonicalFile
            require(opened == expected.canonicalFile) { "打开的文件与预期路径不一致" }
            require(opened.toPath().startsWith(safeRoot(root).toPath())) { "打开的文件超出允许目录" }
            return descriptor
        } catch (error: Throwable) {
            closeQuietly(descriptor)
            throw error
        }
    }

    private fun verifySameContent(source: File, destination: File, sourceRoot: File, destinationRoot: File) {
        val sourceState = fileState(source, sourceRoot)
        val destinationState = fileState(destination, destinationRoot, requireSingleLink = true)
        require(sourceState.identity != destinationState.identity &&
            sourceState.size == destinationState.size && sourceState.hash.contentEquals(destinationState.hash)) {
            "文件副本完整性校验失败"
        }
    }

    private fun fileState(file: File, root: File, requireSingleLink: Boolean = false): FileState {
        if (isAndroidRuntime) {
            val descriptor = openAndroidExisting(
                file,
                root,
                OsConstants.O_RDONLY,
                requireSingleLink = requireSingleLink
            )
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = Os.read(descriptor, buffer, 0, buffer.size)
                    if (count == 0) break
                    digest.update(buffer, 0, count)
                }
                val stat = Os.fstat(descriptor)
                return FileState(stat.st_size, digest.digest(), identity(stat))
            } finally {
                closeQuietly(descriptor)
            }
        }
        validateExistingFile(file, root)
        if (requireSingleLink) requireSingleLinkNio(file)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
            return FileState(channel.size(), digest.digest(), nioIdentity(file))
        }
    }

    private fun validateExistingFile(file: File, root: File) {
        val safeRoot = safeRoot(root)
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "文件不存在或是符号链接" }
        val canonical = file.canonicalFile
        require(file.absoluteFile.path == canonical.path) { "文件路径发生跳转" }
        require(canonical.toPath().startsWith(safeRoot.toPath())) { "文件超出允许目录" }
        validateParents(file, safeRoot)
    }

    private fun validateNewDestination(destination: File, root: File) {
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "备份路径已存在或是符号链接" }
        validateParents(destination, safeRoot(root))
    }

    private fun validateCreatedDestination(destination: File, root: File) {
        validateExistingFile(destination, root)
    }

    private fun validateParents(file: File, root: File) {
        var parent = file.parentFile ?: error("父目录无效")
        while (true) {
            require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "父目录不安全" }
            require(parent.absoluteFile.path == parent.canonicalFile.path) { "父目录发生路径跳转" }
            if (parent.canonicalFile == root) break
            require(parent.canonicalFile.toPath().startsWith(root.toPath())) { "路径超出允许目录" }
            parent = parent.parentFile ?: error("路径超出允许目录")
        }
    }

    private fun safeRoot(root: File): File {
        val canonical = root.canonicalFile
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "允许目录不安全" }
        require(root.absoluteFile.path == canonical.path) { "允许目录发生路径跳转" }
        return canonical
    }

    private fun closeQuietly(descriptor: FileDescriptor) {
        runCatching { Os.close(descriptor) }
    }

    private fun requireSingleLinkNio(file: File) {
        val links = runCatching {
            (Files.getAttribute(file.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        }.getOrNull() ?: return
        require(links == 1L) { "文件存在硬链接，拒绝写入" }
    }

    private fun identity(stat: android.system.StructStat) = FileIdentity("${stat.st_dev}:${stat.st_ino}")

    private fun nioIdentity(file: File): FileIdentity {
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        return FileIdentity(attributes.fileKey()?.toString() ?: file.canonicalPath)
    }

    data class FileIdentity(internal val key: String)
    data class BackupReceipt(val sourceIdentity: FileIdentity, val backupSha256: String)
    private data class FileState(val size: Long, val hash: ByteArray, val identity: FileIdentity)
}
