package com.example.accounts

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("budget_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        val header = findViewById<android.view.View>(R.id.settingsHeader)
        val initialTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, initialTop + safeTop, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)

        val budget = findViewById<EditText>(R.id.settingBudget)
        val balance = findViewById<EditText>(R.id.settingBalance)
        val ratio = findViewById<SeekBar>(R.id.remainingRatio)
        val ratioText = findViewById<TextView>(R.id.remainingRatioText)
        val animationSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.overlayAnimationSwitch)
        budget.setText(prefs.getFloat("monthly_budget", 5000f).toString())
        balance.setText(prefs.getFloat("current_balance", 0f).toString())
        ratio.progress = prefs.getInt("remaining_ratio", 20)
        animationSwitch.isChecked = prefs.getBoolean("overlay_animation_enabled", true)

        fun updateRatioLabel(value: Int) {
            ratioText.text = "期望保留 $value% · 月底浅粉进度 ${(100 - value)}%"
        }
        updateRatioLabel(ratio.progress)
        ratio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateRatioLabel(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<android.view.View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.openRecognitionLog).setOnClickListener {
            startActivity(Intent(this, RecognitionLogActivity::class.java))
        }
        findViewById<android.view.View>(R.id.saveSettings).setOnClickListener {
            val budgetValue = budget.text.toString().toFloatOrNull()
            val balanceValue = balance.text.toString().toFloatOrNull()
            if (budgetValue == null || budgetValue <= 0f) { budget.error = "请输入大于 0 的预算"; return@setOnClickListener }
            if (balanceValue == null) { balance.error = "请输入正确余额"; return@setOnClickListener }
            val oldBalance = prefs.getFloat("current_balance", 0f)
            val editor = prefs.edit()
                .putFloat("monthly_budget", budgetValue)
                .putInt("remaining_ratio", ratio.progress)
                .putBoolean("overlay_animation_enabled", animationSwitch.isChecked)
            if (balanceValue != oldBalance) {
                editor.putFloat("current_balance", balanceValue).putLong("balance_as_of", System.currentTimeMillis())
            }
            editor.apply()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        findViewById<TextView>(R.id.versionText).text = "版本 ${packageInfo.versionName}（构建 $buildNumber）"
    }
}
