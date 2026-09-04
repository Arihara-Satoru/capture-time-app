package local.capturetime.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import local.capturetime.model.MediaSnapshot
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
}
