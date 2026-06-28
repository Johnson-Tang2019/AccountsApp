package com.abyssredemption.accounts

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.*
import android.app.AlertDialog
import androidx.activity.OnBackPressedCallback
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
    private lateinit var budgetInput: EditText
    private lateinit var balanceInput: EditText
    private lateinit var ratioInput: SeekBar
    private lateinit var animationSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var overlaySwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var notificationSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var messageSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var updateSourceGroup: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var initialState: SettingsState

    private data class SettingsState(
        val budget: Float?, val balance: Float?, val ratio: Int,
        val animation: Boolean, val overlay: Boolean, val notification: Boolean, val message: Boolean,
        val updateSource: String
    )
    private val configExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val saved = prefs.getFloat("current_balance", 0f).toDouble()
            val asOf = prefs.getLong("balance_as_of", System.currentTimeMillis())
            val live = AccountDb(this).use { saved + it.netChangeSince(asOf) }.toFloat()
            val config = AppConfig(
                prefs.getFloat("monthly_budget", 5000f), live, prefs.getInt("remaining_ratio", 20),
                prefs.getBoolean("overlay_visible_enabled", true),
                prefs.getBoolean("overlay_animation_enabled", true),
                prefs.getBoolean("record_notification_enabled", true),
                prefs.getBoolean("message_recognition_enabled", true),
                prefs.getString("update_source", UpdateSource.AUTO.value) ?: UpdateSource.AUTO.value
            )
            contentResolver.openOutputStream(uri)?.use { ConfigManager.export(it, config) } ?: error("无法创建配置文件")
        }.onSuccess { Toast.makeText(this, "配置已导出", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "导出配置失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    private val configImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val config = contentResolver.openInputStream(uri)?.use(ConfigManager::import) ?: error("无法读取配置文件")
            prefs.edit()
                .putFloat("monthly_budget", config.monthlyBudget)
                .putFloat("current_balance", config.currentBalance)
                .putLong("balance_as_of", System.currentTimeMillis())
                .putInt("remaining_ratio", config.remainingRatio)
                .putBoolean("overlay_visible_enabled", config.overlayVisible)
                .putBoolean("overlay_animation_enabled", config.overlayAnimation)
                .putBoolean("record_notification_enabled", config.recordNotification)
                .putBoolean("message_recognition_enabled", config.messageRecognition)
                .putString("update_source", config.updateSource)
                .apply()
            sendBroadcast(Intent(PaymentAccessibilityService.ACTION_SETTINGS_CHANGED).setPackage(packageName))
        }.onSuccess {
            Toast.makeText(this, "配置已导入并应用", Toast.LENGTH_SHORT).show()
            recreate()
        }.onFailure { Toast.makeText(this, "导入配置失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
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
        val saveBar = findViewById<android.view.View>(R.id.settingsSaveBar)
        val initialSaveBottom = saveBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(saveBar) { view, insets ->
            val safeBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialSaveBottom + safeBottom)
            insets
        }
        ViewCompat.requestApplyInsets(saveBar)

        budgetInput = findViewById(R.id.settingBudget)
        balanceInput = findViewById(R.id.settingBalance)
        ratioInput = findViewById(R.id.remainingRatio)
        val ratioText = findViewById<TextView>(R.id.remainingRatioText)
        animationSwitch = findViewById(R.id.overlayAnimationSwitch)
        overlaySwitch = findViewById(R.id.overlayVisibleSwitch)
        notificationSwitch = findViewById(R.id.recordNotificationSwitch)
        messageSwitch = findViewById(R.id.messageRecognitionSwitch)
        updateSourceGroup = findViewById(R.id.updateSourceGroup)
        budgetInput.setText(prefs.getFloat("monthly_budget", 5000f).toString())
        val savedBalance = prefs.getFloat("current_balance", 0f).toDouble()
        val balanceAsOf = prefs.getLong("balance_as_of", System.currentTimeMillis())
        val liveBalance = AccountDb(this).use { savedBalance + it.netChangeSince(balanceAsOf) }
        balanceInput.setText(java.text.DecimalFormat("0.00").format(liveBalance))
        ratioInput.progress = prefs.getInt("remaining_ratio", 20)
        animationSwitch.isChecked = prefs.getBoolean("overlay_animation_enabled", true)
        overlaySwitch.isChecked = prefs.getBoolean("overlay_visible_enabled", true)
        notificationSwitch.isChecked = prefs.getBoolean("record_notification_enabled", true)
        messageSwitch.isChecked = prefs.getBoolean("message_recognition_enabled", true)
        val savedUpdateSource = UpdateSource.from(prefs.getString("update_source", UpdateSource.AUTO.value))
        updateSourceGroup.check(when (savedUpdateSource) {
            UpdateSource.AUTO -> R.id.updateSourceAuto
            UpdateSource.GITHUB -> R.id.updateSourceGithub
            UpdateSource.GITEE -> R.id.updateSourceGitee
        })
        findViewById<android.view.View>(R.id.notificationAccessCard).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        fun updateRatioLabel(value: Int) {
            ratioText.text = "期望保留 $value% · 月底浅粉进度 ${(100 - value)}%"
        }
        updateRatioLabel(ratioInput.progress)
        ratioInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateRatioLabel(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        initialState = currentState()
        findViewById<android.view.View>(R.id.backButton).setOnClickListener { requestExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit()
        })
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
        findViewById<android.view.View>(R.id.exportConfig).setOnClickListener {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA).format(java.util.Date())
            configExportLauncher.launch("AccountsConfig-$stamp.json")
        }
        findViewById<android.view.View>(R.id.importConfig).setOnClickListener {
            configImportLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<android.view.View>(R.id.manualUpdate).setOnClickListener {
            UpdateUi.checkForUpdates(this, manual = true)
        }
        findViewById<android.view.View>(R.id.saveSettings).setOnClickListener { saveSettingsAndExit() }
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        findViewById<TextView>(R.id.versionText).text = "版本 ${packageInfo.versionName}（构建 $buildNumber）"
    }

    private fun currentState() = SettingsState(
        budgetInput.text.toString().toFloatOrNull(), balanceInput.text.toString().toFloatOrNull(), ratioInput.progress,
        animationSwitch.isChecked, overlaySwitch.isChecked, notificationSwitch.isChecked, messageSwitch.isChecked,
        when (updateSourceGroup.checkedButtonId) {
            R.id.updateSourceGithub -> UpdateSource.GITHUB.value
            R.id.updateSourceGitee -> UpdateSource.GITEE.value
            else -> UpdateSource.AUTO.value
        }
    )

    private fun saveSettingsAndExit(): Boolean {
        val state = currentState()
        val budgetValue = state.budget
        val balanceValue = state.balance
        if (budgetValue == null || budgetValue <= 0f) {
            budgetInput.error = "请输入大于 0 的预算"
            budgetInput.requestFocus()
            return false
        }
        if (balanceValue == null) {
            balanceInput.error = "请输入正确余额"
            balanceInput.requestFocus()
            return false
        }
        prefs.edit()
            .putFloat("monthly_budget", budgetValue)
            .putInt("remaining_ratio", state.ratio)
            .putBoolean("overlay_animation_enabled", state.animation)
            .putBoolean("overlay_visible_enabled", state.overlay)
            .putBoolean("record_notification_enabled", state.notification)
            .putBoolean("message_recognition_enabled", state.message)
            .putString("update_source", state.updateSource)
            .putFloat("current_balance", balanceValue)
            .putLong("balance_as_of", System.currentTimeMillis())
            .apply()
        sendBroadcast(Intent(PaymentAccessibilityService.ACTION_SETTINGS_CHANGED).setPackage(packageName))
        initialState = state
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    private fun requestExit() {
        if (currentState() == initialState) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("保存设置更改？")
            .setMessage("你修改了设置，但还没有保存。")
            .setNegativeButton("继续编辑", null)
            .setNeutralButton("不保存") { _, _ -> finish() }
            .setPositiveButton("保存") { _, _ -> saveSettingsAndExit() }
            .create()
        PinkDialogs.show(dialog)
    }

    override fun onResume() {
        super.onResume()
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        findViewById<TextView>(R.id.notificationAccessStatus)?.apply {
            text = if (enabled) "消息读取权限已开启" else "点击开启消息读取权限  ›"
            setTextColor(getColor(if (enabled) R.color.income_green else R.color.pink_dark))
        }
    }
}
