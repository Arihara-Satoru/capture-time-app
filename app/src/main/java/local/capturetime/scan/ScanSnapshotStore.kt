package local.capturetime.scan

import android.content.Context
import local.capturetime.model.CaptureSource
import local.capturetime.model.ImageFormat
import local.capturetime.model.MediaSnapshot
import local.capturetime.model.PhotoRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class ScanSnapshotStore(context: Context) {
    private val file = File(context.filesDir, "last-scan.json")

    fun save(records: List<PhotoRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("path", record.file.absolutePath)
                put("format", record.format.name)
                put("exifOriginal", record.exifOriginal?.toEpochMilli() ?: JSONObject.NULL)
                put("mediaId", record.media?.id ?: JSONObject.NULL)
                put("dateTaken", record.media?.dateTaken?.toEpochMilli() ?: JSONObject.NULL)
                put("dateAdded", record.media?.dateAdded?.epochSecond ?: JSONObject.NULL)
                put("filenameTime", record.filenameTime?.toEpochMilli() ?: JSONObject.NULL)
                put("current", record.currentCaptureTime?.toEpochMilli() ?: JSONObject.NULL)
                put("source", record.captureSource?.name ?: JSONObject.NULL)
                put("target", record.targetCaptureTime?.toEpochMilli() ?: JSONObject.NULL)
                put("candidate", record.candidate)
                put("safe", record.safeForTrial)
                put("reason", record.reason)
            })
        }
        file.writeText(array.toString(), Charsets.UTF_8)
    }

    fun load(): List<PhotoRecord> = runCatching {
        val array = JSONArray(file.readText(Charsets.UTF_8))
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val mediaId = item.longOrNull("mediaId")
                val added = item.longOrNull("dateAdded")
                val media = mediaId?.let {
                    MediaSnapshot(it, item.longOrNull("dateTaken")?.let(Instant::ofEpochMilli),
                        added?.let(Instant::ofEpochSecond), added)
                }
                add(PhotoRecord(
                    File(item.getString("path")), ImageFormat.valueOf(item.getString("format")),
                    item.longOrNull("exifOriginal")?.let(Instant::ofEpochMilli), media,
                    item.longOrNull("filenameTime")?.let(Instant::ofEpochMilli),
                    item.longOrNull("current")?.let(Instant::ofEpochMilli),
                    item.stringOrNull("source")?.let(CaptureSource::valueOf),
                    item.longOrNull("target")?.let(Instant::ofEpochMilli),
                    item.optBoolean("candidate"), item.optBoolean("safe"), item.optString("reason")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key).takeIf { it != 0L }
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
