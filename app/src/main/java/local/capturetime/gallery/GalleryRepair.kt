package local.capturetime.gallery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.util.AtomicFile
import local.capturetime.operation.BackupOperationGuard
import local.capturetime.settings.TimeRuleConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class GalleryRepair(private val context: Context) {
    data class Preview(val rows: List<JSONObject>, val inspected: Int, val cloudOnly: Int)

    fun scan(rule: TimeRuleConfig): Preview {
        check(!BackupOperationGuard.isCleanupBlocked(context)) { "请先完成正在进行或等待核验的照片操作" }
        val result = root("local.capturetime.gallery.GalleryTimeRepair", listOf("plan"))
        val parsed = JSONArray(result.first)
        val rows = (0 until parsed.length()).map(parsed::getJSONObject).filter { row ->
            val target = Instant.ofEpochMilli(row.getLong("target"))
            listOf("dateTaken", "mixedDateTime", "dateModified").any { field ->
                rule.needsChange(if (row.isNull(field)) null else Instant.ofEpochMilli(row.getLong(field)), target)
            }
        }
        return Preview(
            rows,
            Regex("Inspected=(\\d+)").find(result.second)?.groupValues?.get(1)?.toIntOrNull() ?: parsed.length(),
            Regex("CloudOnly=(\\d+)").find(result.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        )
    }

    fun repair(rows: List<JSONObject>, progress: (String) -> Unit): File {
        require(rows.isNotEmpty()) { "请先选择要修复的照片" }
        val session = BackupOperationGuard.beginGallery(context) {
            File(context.filesDir, "gallery-repair/${System.currentTimeMillis()}-${UUID.randomUUID()}").apply {
                check(mkdirs()) { "无法创建相册修复会话" }
                FileOutputStream(File(this, "plan.json")).use { stream ->
                    stream.write(JSONArray(rows).toString(2).toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            }
        }
        root("local.capturetime.gallery.GalleryRootWorker", listOf("repair", session.path), session, progress)
        BackupOperationGuard.endGallery(context, session)
        return session
    }

    fun recover(progress: (String) -> Unit): File {
        val session = File(requireNotNull(BackupOperationGuard.galleryRecoverySession(context)) { "没有待核验的相册会话" })
        root("local.capturetime.gallery.GalleryRootWorker", listOf("recover", session.path), session, progress)
        BackupOperationGuard.endGallery(context, session)
        return session
    }

    private fun root(entry: String, args: List<String>, session: File? = null, progress: (String) -> Unit = {}): Pair<String, String> {
        val log = File(context.cacheDir, "gallery-root-${UUID.randomUUID()}").apply { check(mkdir()) }
        val output = File(log, "stdout")
        val error = File(log, "stderr")
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin $entry " + args.joinToString(" ", transform = ::quote)
        val process = try {
            // App mount isolation hides Gallery's private directory even from uid 0.
            ProcessBuilder("su", "-M", "-c", command).redirectOutput(output).redirectError(error).start()
        } catch (failure: Exception) {
            throw IllegalStateException("无法启动 Root。请确认设备已 Root，并在权限管理器中允许本应用。", failure)
        }
        val started = android.os.SystemClock.elapsedRealtime()
        var previous = ""
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            val message = session?.let { runCatching { JSONObject(File(it, "status.json").readText()).optString("message") }.getOrNull() }
            if (!message.isNullOrBlank() && message != previous) { progress(message); previous = message }
            check(android.os.SystemClock.elapsedRealtime() - started < TimeUnit.MINUTES.toMillis(15)) {
                "Root 操作等待超时，可能仍在后台运行。请使用“核验上次会话”，不要重复执行。"
            }
        }
        val stdout = output.readText()
        val stderr = error.readText()
        check(process.exitValue() == 0) {
            session?.let { runCatching { JSONObject(File(it, "status.json").readText()).takeIf { state -> state.optString("phase") == "needs_check" }?.optString("message") }.getOrNull() }
                ?.takeIf(String::isNotBlank)
                ?: stderr.lineSequence().firstOrNull { "Exception:" in it }?.substringAfter("Exception:")?.trim()
                ?: "Root 未获授权或相册检查失败。请在 Root 管理器中允许本应用。\n${stderr.takeLast(1800)}"
        }
        return stdout to stderr
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

/** Runs from the installed APK in a separate root process, including after UI process death. */
object GalleryRootWorker {
    private val database = File("/data/user/0/com.miui.gallery/databases/gallery.db")

    @JvmStatic fun main(args: Array<String>) {
        try {
            require(android.os.Process.myUid() == 0) { "需要 Root 授权" }
            require(args.size == 2 && args[0] in listOf("repair", "recover")) { "无效的修复命令" }
            val session = File(args[1]).canonicalFile
            val roots = listOf("local.capturetime", "local.capturetime.debug").map {
                File("/data/user/0/$it/files/gallery-repair").canonicalFile
            }
            require(session.isDirectory && requireNotNull(session.parentFile) in roots && session.path == File(args[1]).absolutePath) { "修复会话路径不安全" }
            // A device-wide file lock also excludes overlapping release/debug root workers.
            RandomAccessFile("/data/adb/capture-time-gallery-repair.lock", "rw").use { handle ->
                val lock = handle.channel.tryLock() ?: error("另一项小米相册修复仍在运行，请稍后核验")
                lock.use { execute(session, args[0] == "recover") }
            }
        } catch (error: Throwable) {
            error.printStackTrace(System.err)
            kotlin.system.exitProcess(1)
        }
    }

    private fun execute(session: File, recovery: Boolean) {
        val owner = Os.stat(session.path)
        val plan = File(session, "plan.json")
        require(plan.isFile && plan.canonicalFile.parentFile == session) { "修复清单不存在或路径异常" }
        val rows = JSONArray(plan.readText())
        require(rows.length() > 0) { "修复清单为空" }
        val dbOwner = Os.stat(database.path)
        var stopped = false
        var terminal = false

        fun save(name: String, value: String) {
            val file = AtomicFile(File(session, name))
            val stream = file.startWrite()
            try { stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync(); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
            Os.chown(file.baseFile.path, owner.st_uid, owner.st_gid)
        }
        fun status(phase: String, message: String) = save("status.json", JSONObject().put("phase", phase)
            .put("message", message).put("count", rows.length()).put("updatedAt", System.currentTimeMillis()).toString(2))
        fun hashes(): JSONObject = JSONObject().apply {
            for (i in 0 until rows.length()) {
                val path = rows.getJSONObject(i).getString("localFile")
                require(path.startsWith("/storage/emulated/0/") && File(path).canonicalPath == path) { "照片路径已变化" }
                put(path, GalleryTimeRepair.sha256(File(path)))
            }
        }
        fun checkHashes(expected: JSONObject, actual: JSONObject) {
            expected.keys().forEach { path -> check(expected.getString(path) == actual.getString(path)) { "照片内容已变化：$path" } }
        }
        try {
            status("stopping", if (recovery) "正在核验上次相册修复" else "正在暂停小米相册并准备备份")
            command("am", "force-stop", "com.miui.gallery")
            stopped = true
            if (recovery) {
                val verified = runCatching { GalleryTimeRepair.run(arrayOf("verify", plan.path)) }.isSuccess
                if (!verified) {
                    // SQLite is atomic. Only an entirely unchanged plan is safe to unlock after interruption.
                    SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        for (i in 0 until rows.length()) {
                            val old = rows.getJSONObject(i)
                            val fields = arrayOf("dateTaken", "mixedDateTime", "dateModified", "exifDateTime")
                            db.query("cloud", fields, "_id=?", arrayOf(old.getLong("_id").toString()), null, null, null).use { cursor ->
                                check(cursor.moveToFirst()) { "部分照片记录已经变化，请保留备份并检查会话" }
                                fields.forEachIndexed { index, key ->
                                    val actual = if (cursor.isNull(index)) JSONObject.NULL else cursor.getString(index)
                                    check(actual.toString() == old.get(key).toString()) { "部分时间记录已经变化，请保留备份并检查会话" }
                                }
                            }
                        }
                        db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                            check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "相册数据库完整性核验失败" }
                        }
                    }
                }
                File(session, "photos-before.json").takeIf { it.isFile }?.let { checkHashes(JSONObject(it.readText()), hashes()) }
                status(if (verified) "complete" else "rolled_back", if (verified) "上次修复已完成，时间与照片核验通过" else "已确认整批未提交，原时间记录保持完整，可重新检查")
                terminal = true
            } else {
                status("backup", "正在备份小米相册数据库")
                val sourceFiles = listOf("gallery.db", "gallery_lite_r.db", "gallery_sub.db").flatMap { name ->
                    listOf("", "-wal", "-shm").map { File(database.parentFile, name + it) }.filter(File::isFile)
                }
                check(session.usableSpace > sourceFiles.sumOf(File::length) * 2 + 20 * 1024 * 1024) { "备份空间不足" }
                val sourceHashes = sourceFiles.associate { it.name to GalleryTimeRepair.sha256(it) }
                val archive = File(session, "gallery-before.tar")
                check(!archive.exists()) { "已有备份，禁止重复执行同一会话" }
                command(*listOf("tar", "-cf", archive.path, "-C", database.parent).plus(sourceFiles.map(File::getName)).toTypedArray())
                sourceFiles.forEach { check(sourceHashes[it.name] == GalleryTimeRepair.sha256(it)) { "相册在备份期间变化，请重新检查" } }
                FileOutputStream(archive, true).use { it.fd.sync() }
                save("backup-sha256.json", JSONObject(sourceHashes).put("gallery-before.tar", GalleryTimeRepair.sha256(archive)).toString(2))
                status("hash", "正在记录照片哈希并核对修复依据")
                val before = hashes()
                save("photos-before.json", before.toString(2))
                status("applying", "正在事务中修复 ${rows.length()} 张照片的相册时间")
                GalleryTimeRepair.run(arrayOf("apply", plan.path))
                status("verifying", "正在重读相册时间并核验照片完整性")
                GalleryTimeRepair.run(arrayOf("verify", plan.path))
                val after = hashes()
                checkHashes(before, after)
                save("photos-after.json", after.toString(2))
                status("complete", "已修复 ${rows.length()} 张，相册时间与照片哈希全部核验通过")
                terminal = true
            }
        } catch (error: Throwable) {
            status("needs_check", "${error.message ?: "操作中断"}。请点击“核验上次会话”；备份已保留。")
            throw error
        } finally {
            try {
                listOf("", "-wal", "-shm").map { File(database.path + it) }.filter(File::exists).forEach {
                    Os.chown(it.path, dbOwner.st_uid, dbOwner.st_gid)
                    command("restorecon", it.path)
                }
                session.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == session }.forEach {
                    Os.chown(it.path, owner.st_uid, owner.st_gid)
                }
                if (stopped) command("am", "start", "-n", "com.miui.gallery/.MainActivity")
                if (terminal && JSONObject(File(session, "status.json").readText()).optString("phase") == "complete")
                    GalleryTimeRepair.run(arrayOf("verify", plan.path))
            } catch (error: Throwable) {
                runCatching { status("needs_check", "最终核验未完成：${error.message}。请点击“核验上次会话”。") }
                throw error
            }
        }
    }

    private fun command(vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0 && !(args.first() == "am" && "Error:" in output)) { "${args.first()} 失败：${output.takeLast(1200)}" }
    }
}
