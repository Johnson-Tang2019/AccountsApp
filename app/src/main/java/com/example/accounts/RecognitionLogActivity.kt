package com.example.accounts

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
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/${PaymentAccessibilityService::class.java.name}", true) == true
        findViewById<TextView>(R.id.logServiceStatus).text = if (enabled) {
            "系统授权：已开启 · 屏幕右侧应显示粉色“记”悬浮按钮"
        } else {
            "系统授权：未开启 · 点击这里前往无障碍设置"
        }
        val content = RecognitionLogger.read(this)
        findViewById<TextView>(R.id.logContent).text = content.ifBlank {
            "暂无日志。\n\n请开启自动记账后完成一次支付，再返回这里查看排除原因。"
        }
    }
}
