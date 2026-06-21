package com.example.accounts

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    private val prefs by lazy { getSharedPreferences("budget_settings", MODE_PRIVATE) }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            runCatching {
                val records = AccountDb(this).use { it.allRecords() }
                contentResolver.openOutputStream(uri)?.use { XlsxManager.export(it, records) }
                    ?: error("无法打开导出文件")
                records.size
            }.onSuccess { count ->
                runOnUiThread { Toast.makeText(this, "已导出 $count 条账单", Toast.LENGTH_SHORT).show() }
            }.onFailure { error ->
                runOnUiThread { Toast.makeText(this, "导出失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            runCatching {
                val records = contentResolver.openInputStream(uri)?.use(XlsxManager::import)
                    ?: error("无法读取导入文件")
                var added = 0
                AccountDb(this).use { database -> records.forEach { if (database.insertImported(it)) added++ } }
                added to records.size
            }.onSuccess { (added, total) ->
                runOnUiThread { Toast.makeText(this, "导入完成：新增 $added 条，跳过 ${total - added} 条", Toast.LENGTH_LONG).show() }
            }.onFailure { error ->
                runOnUiThread { Toast.makeText(this, "导入失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

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
        findViewById<android.view.View>(R.id.exportXlsx).setOnClickListener {
            val name = "Accounts-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA).format(java.util.Date())}.xlsx"
            exportLauncher.launch(name)
        }
        findViewById<android.view.View>(R.id.importXlsx).setOnClickListener {
            importLauncher.launch(arrayOf(XLSX_MIME, "application/zip"))
        }
        findViewById<android.view.View>(R.id.manualUpdate).setOnClickListener {
            UpdateUi.checkForUpdates(this, manual = true)
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
