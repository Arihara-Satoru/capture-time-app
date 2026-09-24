package local.capturetime.duplicate

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import local.capturetime.BuildConfig
import local.capturetime.media.MediaStoreGateway
import local.capturetime.operation.BackupOperationGuard
import local.capturetime.operation.MediaWait
import local.capturetime.operation.VerifiedPhotoBackup
import local.capturetime.security.PathPolicy
import local.capturetime.settings.BackupSessionRules
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

class DuplicateDeleteProcessor(
    private val context: Context,
    private val mediaStore: MediaStoreGateway
) {
    fun prepare(candidates: List<DuplicateCandidate>): DuplicateDeletePreparation {
        require(Environment.isExternalStorageManager()) { "所有文件访问权限已撤销" }
        require(candidates.isNotEmpty()) { "没有选择重复项" }
        require(!DuplicateRules.hasSelectionConflict(candidates)) {
            "所选候选存在保留文件同时被删除的依赖冲突，请减少勾选数量后重试"
        }
        require(candidates.all(DuplicateRules::isEligibleCandidate)) {
            "候选已不符合内容哈希或下划线文件名前缀规则，请重新扫描"
        }
        val storage = Environment.getExternalStorageDirectory()
        val session = BackupOperationGuard.beginDuplicate(context) { createSession(storage) }
        return try {
            val preparedLog = File(session, "prepared.tsv")
            val log = File(session, "deleted.tsv")
            writeLine(preparedLog, "original_path\tretained_path\tbackup_path\tmediastore_uris")
            writeLine(log, "original_path\tretained_path\tbackup_path\tconfirmation")
            val failures = mutableListOf<String>()
            val prepared = mutableListOf<PreparedDuplicateDelete>()
            val urisByAsset = mediaStore.duplicateUris(candidates.map { it.delete.file to it.delete.kind })
            verifyUnchanged(candidates.flatMap { listOf(it.delete, it.retained) })
            require(urisByAsset.values.flatten().distinct().size <= MAX_DELETE_URIS) {
                "系统单批最多删除 $MAX_DELETE_URIS 个媒体记录，请减少勾选数量"
            }

            candidates.forEach { candidate ->
                val uris = urisByAsset[mediaStore.pathKey(candidate.delete.file) to candidate.delete.kind].orEmpty()
                runCatching { prepareOne(candidate, uris, storage, session, preparedLog) }
                    .onSuccess(prepared::add)
                    .onFailure { failures += "${candidate.delete.file.absolutePath}：未请求删除：${it.message ?: "准备失败"}" }
            }

            DuplicateDeletePreparation(session, candidates.size, prepared, failures).also {
                if (it.prepared.isNotEmpty()) {
                    revalidate(it)
                    savePending(it)
                } else {
                    clearActive(session)
                }
            }
        } catch (error: Throwable) {
            clearActive(session)
            throw error
        }
    }

    fun revalidate(preparation: DuplicateDeletePreparation) {
        require(Environment.isExternalStorageManager()) { "所有文件访问权限已撤销" }
        val currentUris = mediaStore.duplicateUris(preparation.prepared.map { it.delete.file to it.delete.kind })
        verifyUnchanged(preparation.prepared.flatMap { listOf(it.delete, it.retained) })
        preparation.prepared.forEach { item ->
            require(item.delete.file.parentFile?.canonicalFile == item.retained.file.parentFile?.canonicalFile) {
                "文件已不在同一物理文件夹"
            }
            require(VerifiedPhotoBackup.contentMatches(
                item.delete.file,
                item.backup,
                Environment.getExternalStorageDirectory(),
                preparation.sessionDirectory,
                item.delete.sha256
            )) {
                "删除确认前原件或备份内容已变化"
            }
            val uris = currentUris[mediaStore.pathKey(item.delete.file) to item.delete.kind].orEmpty()
            require(uris.toSet() == item.mediaUris.toSet()) { "删除确认前 MediaStore 记录已变化，请重新扫描" }
        }
    }

    fun verifyDeletion(preparation: DuplicateDeletePreparation): DuplicateDeleteResult {
        val log = File(preparation.sessionDirectory, "deleted.tsv")
        val failures = preparation.failures.toMutableList()
        var deleted = 0
        var verified = 0
        val loggedPaths = log.takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty()
            .drop(1).mapTo(hashSetOf()) { it.substringBefore('\t') }

        MediaWait.until(android.os.SystemClock::elapsedRealtime, Thread::sleep) {
            preparation.prepared.all { !it.delete.file.exists() }
        }
        val missing = preparation.prepared.filter { !it.delete.file.exists() }
        var remainingMediaPaths = emptySet<Pair<String, MediaKind>>()
        val mediaError = runCatching {
            MediaWait.until(android.os.SystemClock::elapsedRealtime, Thread::sleep) {
                remainingMediaPaths = mediaStore.remainingDuplicatePaths(missing.map { it.delete.file to it.delete.kind })
                remainingMediaPaths.isEmpty()
            }
        }.exceptionOrNull()
        val retainedErrors = HashMap<Pair<String, MediaKind>, Throwable>()
        runCatching { verifyUnchanged(preparation.prepared.map { it.retained }) }
            .onFailure { error ->
                preparation.prepared.forEach { item ->
                    retainedErrors[mediaStore.pathKey(item.retained.file) to item.retained.kind] = error
                }
            }

        preparation.prepared.forEach { item ->
            var removed = false
            val confirmed = preparation.confirmation == DuplicateDeleteConfirmation.CONFIRMED
            val error = runCatching {
                require(!item.delete.file.exists()) {
                    if (confirmed) "系统删除确认已返回，但原文件仍存在" else "只读核验时原文件仍存在"
                }
                removed = true
                deleted++
                if (item.delete.file.absolutePath !in loggedPaths) {
                    writeLine(log, listOf(
                        item.delete.file.absolutePath,
                        item.retained.file.absolutePath,
                        item.backup.absolutePath,
                        preparation.confirmation.name.lowercase()
                    ).joinToString("\t", transform = ::cell))
                }
                mediaError?.let { throw it }
                require(mediaStore.pathKey(item.delete.file) to item.delete.kind !in remainingMediaPaths) {
                    "原路径已消失，但系统媒体库记录仍存在，请稍后重新扫描"
                }
                retainedErrors[mediaStore.pathKey(item.retained.file) to item.retained.kind]?.let { throw it }
                require(item.backup.isFile && item.backup.length() == item.delete.size) { "删除后备份文件不存在或大小已变化" }
                require(VerifiedPhotoBackup.sha256Hex(
                    item.backup,
                    preparation.sessionDirectory,
                    requireSingleLink = true
                ) == item.delete.sha256) { "删除后备份 SHA-256 已变化" }
                require(!item.delete.file.exists()) { "核验期间原路径重新出现，请检查相册同步或其他应用；未再次删除" }
            }.exceptionOrNull()
            if (error == null) verified++ else failures +=
                "${item.delete.file.absolutePath}：${if (removed) "原路径已消失，后续核验或日志失败" else "原路径仍存在"}：${error.message ?: "核验失败"}"
        }

        runCatching {
            val rows = log.readLines(Charsets.UTF_8).drop(1).count { it.isNotBlank() }
            require(rows == deleted) { "记录 $rows，已执行删除 $deleted" }
        }.onFailure { failures += "deleted.tsv 清单行数核验失败：${it.message}" }
        return DuplicateDeleteResult(
            preparation.sessionDirectory,
            deleted,
            preparation.requested - deleted,
            failures,
            verified
        )
    }

    private fun prepareOne(
        candidate: DuplicateCandidate,
        mediaUris: List<Uri>,
        storage: File,
        session: File,
        preparedLog: File
    ): PreparedDuplicateDelete {
        val target = candidate.delete.file
        val retained = candidate.retained.file
        require(PathPolicy.isSafeFile(target, listOf(storage))) { "待删除路径不安全或文件已不存在" }
        require(PathPolicy.isSafeFile(retained, listOf(storage))) { "保留文件路径不安全或文件已不存在" }
        require(target.parentFile?.canonicalFile == retained.parentFile?.canonicalFile) { "文件已不在同一物理文件夹" }
        require(mediaUris.isNotEmpty()) { "找不到待删除文件的精确 MediaStore URI" }

        val relative = PathPolicy.relativeStoragePath(target, storage) ?: error("无法计算原始相对路径")
        val backup = File(session, relative)
        require(!backup.exists()) { "备份路径已存在，拒绝覆盖" }
        require(backup.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建备份目录" }
        VerifiedPhotoBackup.copyNewAndVerify(target, backup, session)
        require(VerifiedPhotoBackup.contentMatches(target, backup, storage, session, candidate.delete.sha256)) {
            "备份与原件的内容或 SHA-256 核验失败"
        }
        writeLine(preparedLog, listOf(
            target.absolutePath,
            retained.absolutePath,
            backup.absolutePath,
            mediaUris.joinToString(",")
        ).joinToString("\t", transform = ::cell))
        return PreparedDuplicateDelete(candidate.delete, candidate.retained, backup, mediaUris)
    }

    fun loadPending(): DuplicateDeletePreparation? = runCatching {
        val root = JSONObject(pendingAtomicFile().openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        val prepared = root.getJSONArray("prepared").let { rows ->
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                PreparedDuplicateDelete(
                    assetFromJson(row.getJSONObject("delete")),
                    assetFromJson(row.getJSONObject("retained")),
                    File(row.getString("backup")),
                    row.getJSONArray("uris").let { values -> List(values.length()) { Uri.parse(values.getString(it)) } }
                )
            }
        }
        DuplicateDeletePreparation(
            File(root.getString("session")),
            root.getInt("requested"),
            prepared,
            root.getJSONArray("failures").let { values -> List(values.length()) { values.getString(it) } },
            root.optString("confirmation", DuplicateDeleteConfirmation.AWAITING_CONFIRMATION.name)
                .let(DuplicateDeleteConfirmation::valueOf)
        )
    }.getOrNull()

    fun hasPendingState(): Boolean = runCatching {
        pendingAtomicFile().openRead().use { }
        true
    }.getOrDefault(false)

    fun hasBlockingState(): Boolean = isBackupCleanupBlocked(context)

    fun updateConfirmation(
        preparation: DuplicateDeletePreparation,
        confirmation: DuplicateDeleteConfirmation
    ): DuplicateDeletePreparation = preparation.copy(confirmation = confirmation).also(::savePending)

    fun clearPending(preparation: DuplicateDeletePreparation? = null) {
        BackupOperationGuard.withStateLock {
            if (preparation == null) {
                pendingAtomicFile().delete()
                activeAtomicFile().delete()
            } else {
                val current = loadPending()
                if (current?.sessionDirectory?.absolutePath == preparation.sessionDirectory.absolutePath) {
                    pendingAtomicFile().delete()
                    clearActive(preparation.sessionDirectory)
                } else if (!hasPendingState()) {
                    clearActive(preparation.sessionDirectory)
                }
            }
        }
    }

    private fun savePending(preparation: DuplicateDeletePreparation) {
        if (preparation.prepared.isEmpty()) return
        val root = JSONObject()
            .put("session", preparation.sessionDirectory.absolutePath)
            .put("requested", preparation.requested)
            .put("confirmation", preparation.confirmation.name)
            .put("failures", JSONArray(preparation.failures))
            .put("prepared", JSONArray(preparation.prepared.map { item ->
                JSONObject()
                    .put("delete", assetToJson(item.delete))
                    .put("retained", assetToJson(item.retained))
                    .put("backup", item.backup.absolutePath)
                    .put("uris", JSONArray(item.mediaUris.map(Uri::toString)))
            }))
        val file = pendingAtomicFile()
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(file::failWrite)
            throw error
        }
    }

    private fun assetToJson(asset: DuplicateAsset) = JSONObject()
        .put("path", asset.file.absolutePath)
        .put("kind", asset.kind.name)
        .put("width", asset.width)
        .put("height", asset.height)
        .put("duration", asset.durationMillis)
        .put("size", asset.size)
        .put("sha256", asset.sha256)

    private fun assetFromJson(value: JSONObject) = DuplicateAsset(
        File(value.getString("path")),
        MediaKind.valueOf(value.getString("kind")),
        value.getInt("width"),
        value.getInt("height"),
        value.getLong("duration"),
        value.getLong("size"),
        value.getString("sha256")
    )

    private fun pendingFile() = File(context.filesDir, "pending-duplicate-delete.json")

    private fun pendingAtomicFile() = AtomicFile(pendingFile())

    private fun activeAtomicFile() = AtomicFile(File(context.filesDir, ACTIVE_FILE_NAME))

    private fun clearActive(session: File) {
        BackupOperationGuard.clearDuplicateActive(context, session)
    }

    private fun verifyUnchanged(assets: List<DuplicateAsset>) {
        val unique = assets.distinctBy { mediaStore.pathKey(it.file) to it.kind }
        val detailsByPath = mediaStore.queryDuplicateDetails(unique.map(DuplicateAsset::file))
        unique.forEach { asset ->
            require(asset.file.isFile && asset.file.length() == asset.size) { "文件大小已变化，请重新扫描" }
            val details = detailsByPath[mediaStore.pathKey(asset.file)] ?: error("MediaStore 记录或有效尺寸已消失")
            require(details.kind == asset.kind && details.width == asset.width && details.height == asset.height) {
                "文件类型或分辨率已变化，请重新扫描"
            }
            if (asset.kind == MediaKind.VIDEO) {
                require(asset.durationMillis > 0 && details.durationMillis > 0 && details.durationMillis == asset.durationMillis) {
                    "视频时长缺失或已变化，请重新扫描"
                }
            }
            require(VerifiedPhotoBackup.sha256Hex(
                asset.file,
                Environment.getExternalStorageDirectory()
            ) == asset.sha256) { "文件 SHA-256 已变化，请重新扫描" }
        }
    }

    @Synchronized private fun writeLine(file: File, value: String) {
        FileOutputStream(file, true).use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.append(value).append('\n')
                writer.flush()
                stream.fd.sync()
            }
        }
    }

    private fun cell(value: String) = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun createSession(storage: File): File {
        repeat(3) {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            val name = BackupSessionRules.duplicateSessionName(stamp, BuildConfig.DEBUG)
            val directory = runCatching { BackupSessionRules.createSessionDirectory(storage, name) }.getOrNull()
            if (directory != null) return directory
            Thread.sleep(1100)
        }
        error("无法创建唯一清理会话")
    }

    companion object {
        private const val ACTIVE_FILE_NAME = "duplicate-delete-active"
        private const val MAX_DELETE_URIS = 2_000

        fun isBackupCleanupBlocked(context: Context): Boolean =
            BackupOperationGuard.isCleanupBlocked(context)

        fun runBackupCleanupIfAllowed(context: Context, action: () -> Unit): Boolean =
            BackupOperationGuard.runCleanupIfAllowed(context, action)
    }
}
