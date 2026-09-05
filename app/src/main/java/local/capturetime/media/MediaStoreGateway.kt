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

class MediaStoreGateway(private val context: Context) {
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun query(file: File): MediaSnapshot? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.DATA} = ?",
            arrayOf(file.absolutePath),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            val taken = cursor.takeUnless { it.isNull(1) }?.getLong(1)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            val addedSeconds = cursor.takeUnless { it.isNull(2) }?.getLong(2)?.takeIf { it > 0 }
            return MediaSnapshot(id, taken, addedSeconds?.let(Instant::ofEpochSecond), addedSeconds)
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
            val expectedPath = runCatching { expectedFile.canonicalPath }.getOrDefault(expectedFile.absolutePath)
            val actualPath = runCatching { File(indexedPath).canonicalPath }.getOrDefault(indexedPath)
            if (actualPath != expectedPath) return null
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
                result[path.lowercase()] = MediaSnapshot(
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

    fun queryDuplicateDetails(files: List<File>): Map<String, MediaDetails> {
        val paths = files.mapTo(hashSetOf()) { it.absolutePath.lowercase() }
        val result = HashMap<String, MediaDetails>()
        queryDetails(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaKind.IMAGE, paths, result)
        queryDetails(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaKind.VIDEO, paths, result)
        return result
    }

    fun queryDuplicateDetails(file: File, kind: MediaKind): MediaDetails? {
        val result = HashMap<String, MediaDetails>()
        val target = setOf(file.absolutePath.lowercase())
        val uri = if (kind == MediaKind.IMAGE) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        queryDetails(uri, kind, target, result)
        return result[file.absolutePath.lowercase()]
    }

    private fun queryDetails(uri: Uri, kind: MediaKind, paths: Set<String>, result: MutableMap<String, MediaDetails>) {
        val projection = mutableListOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        if (kind == MediaKind.VIDEO) projection += MediaStore.Video.Media.DURATION
        context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)?.lowercase() ?: continue
                if (path !in paths) continue
                val width = cursor.getInt(1)
                val height = cursor.getInt(2)
                val duration = if (kind == MediaKind.VIDEO && !cursor.isNull(3)) cursor.getLong(3) else 0
                if (width > 0 && height > 0) result[path] = MediaDetails(kind, width, height, duration)
            }
        }
    }
}
