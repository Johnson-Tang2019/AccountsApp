package com.abyssredemption.accounts

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

class RecognitionLogActivity : AppCompatActivity() {
    private data class ReportRange(val fromMs: Long, val toMs: Long)

    private var pendingExport: ReportRange? = null
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val range = pendingExport
        pendingExport = null
        if (uri != null && range != null) {
            val app = applicationContext
            Thread {
                val result = runCatching {
                    val report = RecognitionLogger.report(app, range.fromMs, range.toMs)
                    val stream = app.contentResolver.openOutputStream(uri) ?: throw IOException("No output stream")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(report.toString(2)) }
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        val message = if (result.isSuccess) "已导出诊断记录" else "导出失败，请重新选择保存位置"
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("report_from") == true) {
            pendingExport = ReportRange(savedInstanceState.getLong("report_from"), savedInstanceState.getLong("report_to"))
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_recognition_log)
        val header = findViewById<View>(R.id.logHeader)
        val initialTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, initialTop + top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)
        val body = findViewById<View>(R.id.logBody)
        val initialBottom = body.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(body) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialBottom + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(body)
        findViewById<View>(R.id.logBack).setOnClickListener { finish() }
        findViewById<View>(R.id.openAccessibilityFromLog).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.clearLog).setOnClickListener {
            RecognitionLogger.clear(this)
            refresh()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.exportDiagnostics).setOnClickListener {
            chooseRange("导出诊断记录", "导出 JSON") { range ->
                pendingExport = range
                val suffix = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(range.fromMs))
                exportPicker.launch("Accounts-diagnostics-$suffix.json")
            }
        }
        findViewById<View>(R.id.analyzeDiagnostics).setOnClickListener {
            chooseRange("DeepSeek 分析", "继续") { range ->
                startActivity(Intent(this, DeepSeekDiagnosticsActivity::class.java)
                    .putExtra(DeepSeekDiagnosticsActivity.EXTRA_FROM_MS, range.fromMs)
                    .putExtra(DeepSeekDiagnosticsActivity.EXTRA_TO_MS, range.toMs))
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExport?.let {
            outState.putLong("report_from", it.fromMs)
            outState.putLong("report_to", it.toMs)
        }
    }

    private fun chooseRange(title: String, action: String, onSelection: (ReportRange) -> Unit) {
        val zone = ZoneId.systemDefault()
        val localNow = LocalDateTime.now(zone)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (resources.displayMetrics.density * 24).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(TextView(this@RecognitionLogActivity).apply {
                text = "本地时间 · ${zone.id}\nyyyy-MM-dd HH:mm:ss"
                setTextColor(getColor(R.color.text_secondary))
            })
        }
        fun field(label: String, value: LocalDateTime): EditText {
            form.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_primary))
            })
            return EditText(this).apply {
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                contentDescription = label
                setText(dateFormat.format(value))
                form.addView(this)
            }
        }
        val start = field("开始时间", localNow.minusHours(1))
        val end = field("截止时间（含）", localNow)
        fun parse(field: EditText): Long? = runCatching {
            LocalDateTime.parse(field.text.toString().trim(), dateFormat).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()?.takeIf { it >= 0L }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton(action, null)
            .create()
        PinkDialogs.show(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val from = parse(start)
            val until = parse(end)
            if (from == null) {
                start.error = "请输入有效的开始时间"
            } else if (until == null || until < from) {
                end.error = "请输入不早于开始时间的截止时间"
            } else {
                dialog.dismiss()
                onSelection(ReportRange(from, until + 1000))
            }
        }
    }

    private fun refresh() {
        val enabled = AccessibilityStatus.isServiceEnabled(this)
        val heartbeat = getSharedPreferences("service_state", MODE_PRIVATE).getLong("last_heartbeat", 0)
        val running = System.currentTimeMillis() - heartbeat < 15_000
        findViewById<TextView>(R.id.logServiceStatus).text = if (enabled) {
            "系统授权：已开启 · 屏幕右侧应显示粉色“记”悬浮按钮"
        } else if (running) {
            "系统授权：检测不到 · 但服务心跳正常，支付后请查看下方排除原因"
        } else {
            "系统授权：未开启 · 点击这里前往无障碍设置"
        }
        val content = RecognitionLogger.read(this)
        findViewById<TextView>(R.id.logContent).text = content.ifBlank {
            "暂无日志。\n\n请开启自动记账后完成一次支付，再返回这里查看排除原因。"
        }
    }
}
