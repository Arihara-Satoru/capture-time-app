package local.capturetime.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import local.capturetime.BuildConfig
import local.capturetime.duplicate.DuplicateDeleteProcessor
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var days: EditText
    private lateinit var hours: EditText
    private lateinit var minutes: EditText
    private lateinit var seconds: EditText
    private lateinit var selection: MaterialAutoCompleteTextView
    private lateinit var timeZone: MaterialAutoCompleteTextView
    private lateinit var timeZoneLabels: List<String>
    private lateinit var timeZones: List<ZoneId>
    private val sourceBoxes = linkedMapOf<TimeField, CheckBox>()
    private val destinationBoxes = linkedMapOf<TimeField, CheckBox>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(local.capturetime.R.layout.activity_settings)
        days = findViewById(local.capturetime.R.id.settingDays)
        hours = findViewById(local.capturetime.R.id.settingHours)
        minutes = findViewById(local.capturetime.R.id.settingMinutes)
        seconds = findViewById(local.capturetime.R.id.settingSeconds)
        selection = findViewById(local.capturetime.R.id.timeSelection)
        timeZone = findViewById(local.capturetime.R.id.settingTimeZone)
        selection.setAdapter(ArrayAdapter(this, local.capturetime.R.layout.settings_dropdown_item, TimeSelection.entries.map { it.label }))
        sourceBoxes.putAll(mapOf(
            TimeField.CURRENT_CAPTURE to findViewById(local.capturetime.R.id.sourceCurrentCapture),
            TimeField.EXIF_ORIGINAL to findViewById(local.capturetime.R.id.sourceExifOriginal),
            TimeField.EXIF_DIGITIZED to findViewById(local.capturetime.R.id.sourceExifDigitized),
            TimeField.EXIF_MODIFIED to findViewById(local.capturetime.R.id.sourceExifModified),
            TimeField.MEDIA_DATE_TAKEN to findViewById(local.capturetime.R.id.sourceMediaTaken),
            TimeField.MEDIA_DATE_ADDED to findViewById(local.capturetime.R.id.sourceMediaAdded),
            TimeField.FILENAME to findViewById(local.capturetime.R.id.sourceFilename),
            TimeField.FILE_MODIFIED to findViewById(local.capturetime.R.id.sourceFileModified)
        ))
        destinationBoxes.putAll(mapOf(
            TimeField.EXIF_ORIGINAL to findViewById(local.capturetime.R.id.destinationExifOriginal),
            TimeField.EXIF_DIGITIZED to findViewById(local.capturetime.R.id.destinationExifDigitized),
            TimeField.EXIF_MODIFIED to findViewById(local.capturetime.R.id.destinationExifModified),
            TimeField.FILE_MODIFIED to findViewById(local.capturetime.R.id.destinationFileModified)
        ))
        val rule = TimeRuleConfig.load(this)
        selection.setText(rule.selection.label, false)
        val commonZones = linkedMapOf(
            "Asia/Shanghai" to "北京时间", "Asia/Hong_Kong" to "香港", "Asia/Taipei" to "台北",
            "UTC" to "协调世界时", "Asia/Tokyo" to "东京", "Asia/Seoul" to "首尔",
            "Asia/Singapore" to "新加坡", "Europe/London" to "伦敦", "Europe/Paris" to "巴黎",
            "America/New_York" to "纽约", "America/Los_Angeles" to "洛杉矶", "Australia/Sydney" to "悉尼"
        )
        timeZones = (commonZones.keys + rule.zone.id + ZoneId.getAvailableZoneIds().sorted()).distinct().map(ZoneId::of)
        val now = Instant.now()
        timeZoneLabels = timeZones.map { zone ->
            val label = commonZones[zone.id] ?: "${zone.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)} · ${zone.id}"
            val offset = zone.rules.getOffset(now).id.takeUnless { it == "Z" } ?: "+00:00"
            "$label（UTC$offset）"
        }
        timeZone.setAdapter(ArrayAdapter(this, local.capturetime.R.layout.settings_dropdown_item, timeZoneLabels))
        timeZone.setText(timeZoneLabels[timeZones.indexOf(rule.zone)], false)
        sourceBoxes.forEach { (field, box) -> box.isChecked = field in rule.sourceFields }
        destinationBoxes.forEach { (field, box) -> box.isChecked = field in rule.destinationFields }
        days.setText(prefs.getInt("days", 0).toString())
        hours.setText(prefs.getInt("hours", 0).toString())
        minutes.setText(prefs.getInt("minutes", 0).toString())
        seconds.setText(prefs.getInt("seconds", 0).toString())
        findViewById<Button>(local.capturetime.R.id.saveSettings).setOnClickListener { save() }
        findViewById<Button>(local.capturetime.R.id.permissionButton).setOnClickListener { requestStorageAccess() }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(local.capturetime.R.id.settingsToolbar)
            .setNavigationOnClickListener { finish() }
        findViewById<Button>(local.capturetime.R.id.viewLogs).setOnClickListener { showLogs() }
        findViewById<Button>(local.capturetime.R.id.clearBackups).setOnClickListener { confirmClearBackups() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun updatePermissionState() {
        val granted = hasStorageAccess()
        findViewById<TextView>(local.capturetime.R.id.permissionStatus).apply {
            text = if (granted) "已授权，可以全局扫描和原地修正照片" else "权限不完整，全局扫描与照片修改不可用"
            setTextColor(getColor(if (granted) local.capturetime.R.color.permission_granted else local.capturetime.R.color.permission_missing))
        }
        findViewById<Button>(local.capturetime.R.id.permissionButton).text = if (granted) "管理权限" else "授权所有文件访问"
    }

    private fun requestStorageAccess() {
        val mediaPermissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = mediaPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_MEDIA_PERMISSION)
            return
        }
        openAllFilesSettings()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            openAllFilesSettings()
        } else if (requestCode == REQUEST_MEDIA_PERMISSION) {
            Toast.makeText(this, "照片读取权限未授予", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    private fun hasStorageAccess(): Boolean {
        val mediaGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return Environment.isExternalStorageManager() && mediaGranted
    }

    private fun save() {
        val d = days.value(0..3650) ?: return
        val h = hours.value(0..23) ?: return
        val m = minutes.value(0..59) ?: return
        val s = seconds.value(0..59) ?: return
        val zone = timeZones.getOrNull(timeZoneLabels.indexOf(timeZone.text.toString()))
            ?: return errorText("请选择照片时间时区")
        val timeSelection = TimeSelection.entries.firstOrNull { it.label == selection.text.toString() }
            ?: return errorText("请选择目标时间选取方式")
        val sources = sourceBoxes.filterValues { it.isChecked }.keys
        val destinations = destinationBoxes.filterValues { it.isChecked }.keys
        if (sources.isEmpty()) return errorText("请至少选择一个依据字段")
        if (destinations.isEmpty()) return errorText("请至少选择一个修改字段")
        prefs.edit()
            .putInt("days", d).putInt("hours", h).putInt("minutes", m).putInt("seconds", s)
            .putString("time_selection", timeSelection.name)
            .putString("time_zone", zone.id)
            .putString("source_fields", sources.joinToString(",") { it.name })
            .putString("destination_fields", destinations.joinToString(",") { it.name })
            .apply()
        File(filesDir, "last-scan.json").delete()
        Toast.makeText(this, "时间规则已保存，将刷新当前照片预览", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showLogs() {
        val temp = File(android.os.Environment.getExternalStorageDirectory(), ".temp")
        val sessions = temp.listFiles()?.filter { BackupSessionRules.isCaptureSession(it, BuildConfig.DEBUG) }
            ?.sortedByDescending { it.name }.orEmpty()
        val text = if (sessions.isEmpty()) "暂无会话日志" else sessions.joinToString("\n\n") { session ->
            "${session.name}\n${File(session, "summary.json").takeIf { it.isFile }?.readText().orEmpty()}"
        }
        MaterialAlertDialogBuilder(this).setTitle("会话日志").setMessage(text).setPositiveButton("关闭", null).show()
    }

    private fun confirmClearBackups() {
        if (DuplicateDeleteProcessor.isBackupCleanupBlocked(this)) {
            Toast.makeText(this, "照片操作或相册修复仍在进行或等待核验，不能清除备份", Toast.LENGTH_LONG).show()
            return
        }
        val sessions = backupSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, "没有找到可清除的备份", Toast.LENGTH_SHORT).show()
            return
        }
        val totalBytes = sessions.sumOf { BackupSessionRules.sessionSize(it, BuildConfig.DEBUG) }
        val sizeText = if (totalBytes >= 1024 * 1024) "%.1f MB".format(totalBytes / 1024.0 / 1024.0) else "%.1f KB".format(totalBytes / 1024.0)
        MaterialAlertDialogBuilder(this)
            .setTitle("清除备份？")
            .setMessage("将永久删除 ${sessions.size} 个会话目录，约 $sizeText。\n\n其中包含时间纠正与重复清理的照片备份和 TSV/JSON 日志。清除后无法使用这些备份恢复照片。\n\n只会删除当前安装版本创建的 capture-time-app 或 duplicate-cleanup 会话，不会删除另一安装版本或 .temp 下其他内容。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ -> clearBackups(sessions) }
            .show()
    }

    private fun clearBackups(sessions: List<File>) {
        var removed = 0
        var failed = 0
        val allowed = DuplicateDeleteProcessor.runBackupCleanupIfAllowed(this) {
            sessions.forEach {
                if (BackupSessionRules.deleteManagedSession(it, BuildConfig.DEBUG)) removed++ else failed++
            }
        }
        if (!allowed) {
            Toast.makeText(this, "照片操作状态已变化，未清除任何备份", Toast.LENGTH_LONG).show()
            return
        }
        val message = if (failed == 0) "已清除 $removed 个备份会话目录" else "已清除 $removed 个目录，$failed 个目录清除失败"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun backupSessions(): List<File> = File(android.os.Environment.getExternalStorageDirectory(), ".temp")
        .listFiles()
        ?.filter { BackupSessionRules.isManagedSession(it, BuildConfig.DEBUG) }
        ?.sortedByDescending { it.name }
        .orEmpty()

    private fun EditText.value(range: IntRange): Int? {
        val value = text.toString().toIntOrNull()
        if (value == null || value !in range) {
            errorText("请输入 ${range.first} 到 ${range.last} 的整数")
            return null
        }
        return value
    }

    private fun errorText(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_MEDIA_PERMISSION = 20
    }
}
