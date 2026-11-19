package com.abyssredemption.accounts

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RecognitionLogActivity : AppCompatActivity() {
    private data class ReportRange(val fromMs: Long, val toMs: Long)

    private var pendingExport: ReportRange? = null
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
        DiagnosticRangePicker.show(this, title, action) { fromMs, toMs ->
            onSelection(ReportRange(fromMs, toMs))
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
