package local.capturetime.operation

import local.capturetime.model.PhotoRecord
import local.capturetime.model.ProcessResult
import local.capturetime.time.CaptureTimeParser
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

class SessionLogger private constructor(val directory: File, records: List<PhotoRecord>) {
    private val changed = File(directory, "changed.tsv")
    private val skipped = File(directory, "skipped.tsv")
    private val restored = File(directory, "restored.tsv")
    private val successCount = AtomicInteger()
    private val skippedCount = AtomicInteger()
    private val restoredCount = AtomicInteger()
    private val failureCount = AtomicInteger()
    private val total = records.size
    private val candidates = records.count { it.candidate }

    init {
        writeHeader(File(directory, "planned.tsv"), "original_path\tformat\tcurrent_capture_time\tcapture_source\tdate_added\tfilename_time\ttarget_capture_time\tcandidate\tsafe_for_trial\treason")
        records.forEach { append(File(directory, "planned.tsv"), plannedRow(it)) }
        writeHeader(changed, "original_path\tbackup_path\told_capture_time\ttarget_capture_time\texif_verification\tmediastore_verification")
        writeHeader(skipped, "original_path\tformat\treason")
        writeHeader(restored, "original_path\tbackup_path\ttarget_capture_time\treason\texif_verification\tmediastore_verification\trestore_verification")
        updateSummary()
    }

    @Synchronized fun logSkipped(record: PhotoRecord, reason: String) {
        append(skipped, listOf(record.file.absolutePath, record.format.label, reason).joinToString("\t", transform = ::cell))
        skippedCount.incrementAndGet()
        updateSummary()
    }

    @Synchronized fun logResult(result: ProcessResult) {
        val record = result.record
        if (result.success) {
            append(changed, listOf(
                record.file.absolutePath, result.backupPath.orEmpty(), CaptureTimeParser.formatDisplay(record.currentCaptureTime),
                CaptureTimeParser.formatDisplay(record.targetCaptureTime), result.exifVerification, result.mediaStoreVerification
            ).joinToString("\t", transform = ::cell))
            successCount.incrementAndGet()
        } else if (result.restored) {
            append(restored, listOf(
                record.file.absolutePath, result.backupPath.orEmpty(), CaptureTimeParser.formatDisplay(record.targetCaptureTime),
                result.reason, result.exifVerification, result.mediaStoreVerification, result.restoreVerification
            ).joinToString("\t", transform = ::cell))
            restoredCount.incrementAndGet()
            failureCount.incrementAndGet()
        } else {
            append(skipped, listOf(record.file.absolutePath, record.format.label, result.reason).joinToString("\t", transform = ::cell))
            skippedCount.incrementAndGet()
            if (result.failure) failureCount.incrementAndGet()
        }
        updateSummary()
    }

    @Synchronized private fun updateSummary() {
        val json = JSONObject()
            .put("totalScanned", total)
            .put("candidateCount", candidates)
            .put("successCount", successCount.get())
            .put("skippedCount", skippedCount.get())
            .put("restoredCount", restoredCount.get())
            .put("failureCount", failureCount.get())
            .put("sessionPath", directory.absolutePath)
        File(directory, "summary.json").writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun plannedRow(record: PhotoRecord) = listOf(
        record.file.absolutePath, record.format.label, CaptureTimeParser.formatDisplay(record.currentCaptureTime),
        record.captureSource?.label.orEmpty(), CaptureTimeParser.formatDisplay(record.media?.dateAdded),
        CaptureTimeParser.formatDisplay(record.filenameTime), CaptureTimeParser.formatDisplay(record.targetCaptureTime),
        record.candidate.toString(), record.safeForTrial.toString(), record.reason
    ).joinToString("\t", transform = ::cell)

    private fun writeHeader(file: File, value: String) = file.writeText("$value\n", Charsets.UTF_8)

    private fun append(file: File, row: String) {
        FileOutputStream(file, true).use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.append(row).append('\n')
                writer.flush()
                stream.fd.sync()
            }
        }
    }

    private fun cell(value: String): String = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    companion object {
        private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun create(storageRoot: File, records: List<PhotoRecord>): SessionLogger {
            val temp = File(storageRoot, ".temp")
            require(temp.isDirectory || (!temp.exists() && temp.mkdir())) { "无法创建 /sdcard/.temp" }
            repeat(3) {
                val name = "capture-time-app-${formatter.format(ZonedDateTime.now(CaptureTimeParser.zone))}"
                val directory = File(temp, name)
                if (directory.mkdir()) return SessionLogger(directory, records)
                Thread.sleep(1100)
            }
            error("无法创建唯一会话目录")
        }
    }
}
