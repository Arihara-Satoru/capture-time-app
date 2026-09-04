package local.capturetime.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var days: EditText
    private lateinit var hours: EditText
    private lateinit var minutes: EditText
    private lateinit var seconds: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(local.capturetime.R.layout.activity_settings)
        days = findViewById(local.capturetime.R.id.settingDays)
        hours = findViewById(local.capturetime.R.id.settingHours)
        minutes = findViewById(local.capturetime.R.id.settingMinutes)
        seconds = findViewById(local.capturetime.R.id.settingSeconds)
        days.setText(prefs.getInt("days", 0).toString())
        hours.setText(prefs.getInt("hours", 0).toString())
        minutes.setText(prefs.getInt("minutes", 0).toString())
        seconds.setText(prefs.getInt("seconds", 0).toString())
        findViewById<Button>(local.capturetime.R.id.saveSettings).setOnClickListener { save() }
        findViewById<Button>(local.capturetime.R.id.viewLogs).setOnClickListener { showLogs() }
        findViewById<Button>(local.capturetime.R.id.clearBackups).setOnClickListener { confirmClearBackups() }
    }

    private fun save() {
        val d = days.value(0..3650) ?: return
        val h = hours.value(0..23) ?: return
        val m = minutes.value(0..59) ?: return
        val s = seconds.value(0..59) ?: return
        prefs.edit().putInt("days", d).putInt("hours", h).putInt("minutes", m).putInt("seconds", s).apply()
        Toast.makeText(this, "误差已保存，返回主页后将按新规则重新扫描", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showLogs() {
        val temp = File(android.os.Environment.getExternalStorageDirectory(), ".temp")
        val sessions = temp.listFiles()?.filter { it.isDirectory && it.name.startsWith("capture-time-app-") }?.sortedByDescending { it.name }.orEmpty()
        val text = if (sessions.isEmpty()) "暂无会话日志" else sessions.joinToString("\n\n") { session ->
            "${session.name}\n${File(session, "summary.json").takeIf { it.isFile }?.readText().orEmpty()}"
        }
        android.app.AlertDialog.Builder(this).setTitle("会话日志").setMessage(text).setPositiveButton("关闭", null).show()
    }

    private fun confirmClearBackups() {
        val sessions = backupSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, "没有找到可清除的备份", Toast.LENGTH_SHORT).show()
            return
        }
        val totalBytes = sessions.sumOf { it.walkTopDown().filter(File::isFile).sumOf(File::length) }
        val sizeText = if (totalBytes >= 1024 * 1024) "%.1f MB".format(totalBytes / 1024.0 / 1024.0) else "%.1f KB".format(totalBytes / 1024.0)
        android.app.AlertDialog.Builder(this)
            .setTitle("清除备份？")
            .setMessage("将永久删除 ${sessions.size} 个会话目录，约 $sizeText。\n\n其中包含照片原始备份和 TSV/JSON 日志。清除后无法使用这些备份恢复照片。\n\n只会删除名称以 capture-time-app- 开头的目录，不会删除 .temp 下其他内容。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ -> clearBackups(sessions) }
            .show()
    }

    private fun clearBackups(sessions: List<File>) {
        var removed = 0
        var failed = 0
        sessions.forEach { if (it.deleteRecursively()) removed++ else failed++ }
        val message = if (failed == 0) "已清除 $removed 个备份会话目录" else "已清除 $removed 个目录，$failed 个目录清除失败"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun backupSessions(): List<File> = File(android.os.Environment.getExternalStorageDirectory(), ".temp")
        .listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("capture-time-app-") && it.name.length > "capture-time-app-".length }
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
}
