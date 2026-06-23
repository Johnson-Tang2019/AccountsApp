package com.abyssredemption.accounts

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class RecognitionLogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_recognition_log)
        val header = findViewById<android.view.View>(R.id.logHeader)
        val initialTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, initialTop + top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)
        findViewById<android.view.View>(R.id.logBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.openAccessibilityFromLog).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<android.view.View>(R.id.clearLog).setOnClickListener {
            RecognitionLogger.clear(this)
            refresh()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

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
