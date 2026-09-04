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
