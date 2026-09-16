package local.capturetime.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import local.capturetime.model.MediaSnapshot
import local.capturetime.duplicate.MediaDetails
import local.capturetime.duplicate.MediaKind
import java.io.File
import java.time.Instant
import java.util.HashMap
import java.util.Locale
import kotlin.math.abs

class MediaStoreGateway(private val context: Context) {
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun query(file: File): MediaSnapshot? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val cursor = context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.DATA} = ?",
            arrayOf(file.absolutePath),
            null
        )
        cursor?.use { rows ->
            if (rows.moveToFirst()) {
                val id = rows.getLong(0)
                val taken = rows.takeUnless { it.isNull(2) }?.getLong(2)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                val addedSeconds = rows.takeUnless { it.isNull(3) }?.getLong(3)?.takeIf { it > 0 }
                return MediaSnapshot(id, taken, addedSeconds?.let(Instant::ofEpochSecond), addedSeconds)
            }
        }
        // Some Android builds expose the same file through /sdcard and
        // /storage/emulated/0. Fall back to the normalized path comparison.
        context.contentResolver.query(collection, projection, null, null, null)?.use { rows ->
            val expectedPath = pathKey(file)
            while (rows.moveToNext()) {
                if (pathKey(rows.getString(1) ?: continue) != expectedPath) continue
                val id = rows.getLong(0)
                val taken = rows.takeUnless { it.isNull(2) }?.getLong(2)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                val addedSeconds = rows.takeUnless { it.isNull(3) }?.getLong(3)?.takeIf { it > 0 }
                return MediaSnapshot(id, taken, addedSeconds?.let(Instant::ofEpochSecond), addedSeconds)
            }
        }
        return null
    }

    fun query(uri: Uri): MediaSnapshot? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val addedSeconds = cursor.takeUnless { it.isNull(2) }?.getLong(2)?.takeIf { it > 0 }
            return MediaSnapshot(
                cursor.getLong(0),
                cursor.takeUnless { it.isNull(1) }?.getLong(1)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                addedSeconds?.let(Instant::ofEpochSecond),
                addedSeconds
            )
        }
        return null
    }

    fun query(uri: Uri, expectedFile: File): MediaSnapshot? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val indexedPath = cursor.getString(1) ?: return null
            if (pathKey(indexedPath) != pathKey(expectedFile)) return null
            val addedSeconds = cursor.takeUnless { it.isNull(3) }?.getLong(3)?.takeIf { it > 0 }
            return MediaSnapshot(
                cursor.getLong(0),
                cursor.takeUnless { it.isNull(2) }?.getLong(2)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                addedSeconds?.let(Instant::ofEpochSecond),
                addedSeconds
            )
        }
        return null
    }

    fun resolveFile(uri: Uri): File? = runCatching {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)?.let(::File)?.takeIf { it.isFile }
        }
    }.getOrNull()

    fun queryAll(): Map<String, MediaSnapshot> {
        val result = HashMap<String, MediaSnapshot>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(1) ?: continue
                val addedSeconds = cursor.takeUnless { it.isNull(3) }?.getLong(3)?.takeIf { it > 0 }
                result[pathKey(path)] = MediaSnapshot(
                    cursor.getLong(0),
                    cursor.takeUnless { it.isNull(2) }?.getLong(2)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                    addedSeconds?.let(Instant::ofEpochSecond),
                    addedSeconds
                )
            }
        }
        return result
    }

    fun uri(snapshot: MediaSnapshot): Uri = ContentUris.withAppendedId(collection, snapshot.id)

    fun pathKey(file: File): String = pathKey(file.absolutePath)

    fun queryDuplicateDetails(files: List<File>): Map<String, MediaDetails> {
        val paths = files.mapTo(hashSetOf(), ::pathKey)
        val result = HashMap<String, MediaDetails>()
        queryDetails(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaKind.IMAGE, paths, result)
        queryDetails(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaKind.VIDEO, paths, result)
        return result
    }

    fun queryDuplicateDetails(file: File, kind: MediaKind): MediaDetails? {
        val result = HashMap<String, MediaDetails>()
        val target = setOf(pathKey(file))
        val uri = if (kind == MediaKind.IMAGE) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        queryDetails(uri, kind, target, result)
        return result[pathKey(file)]
    }

    fun containsDuplicatePath(file: File, kind: MediaKind): Boolean {
        val uri = if (kind == MediaKind.IMAGE) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        // Do not filter on dimensions: even an incomplete row means cleanup is unconfirmed.
        val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?: error("无法查询系统媒体库，尚未确认清理结果")
        val expectedPath = pathKey(file)
        return cursor.use { rows ->
            while (rows.moveToNext()) {
                if (pathKey(rows.getString(0) ?: continue) == expectedPath) return@use true
            }
            false
        }
    }

    private fun queryDetails(uri: Uri, kind: MediaKind, paths: Set<String>, result: MutableMap<String, MediaDetails>) {
        val projection = mutableListOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        if (kind == MediaKind.IMAGE) projection += MediaStore.Images.ImageColumns.ORIENTATION
        if (kind == MediaKind.VIDEO) projection += MediaStore.Video.Media.DURATION
        context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)?.let(::pathKey) ?: continue
                if (path !in paths) continue
                val width = cursor.getInt(1)
                val height = cursor.getInt(2)
                val orientation = if (kind == MediaKind.IMAGE && !cursor.isNull(3)) cursor.getInt(3) else 0
                val durationIndex = if (kind == MediaKind.VIDEO) 3 else -1
                val duration = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) cursor.getLong(durationIndex) else 0
                val rotated = abs(orientation) % 180 == 90
                val displayWidth = if (rotated) height else width
                val displayHeight = if (rotated) width else height
                if (displayWidth > 0 && displayHeight > 0) {
                    result[path] = MediaDetails(kind, displayWidth, displayHeight, duration)
                }
            }
        }
    }

    private fun pathKey(path: String): String = runCatching {
        File(path).canonicalPath.lowercase(Locale.ROOT)
    }.getOrDefault(path.lowercase(Locale.ROOT))
}
