package com.abyssredemption.accounts

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class RecognitionRulesActivity : AppCompatActivity() {
    private val exportRules = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runFileOperation {
            val json = RecognitionRules.load(this).toJson()
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                ?: error("无法写入规则文件")
            val onComplete: () -> Unit = { showMessage("规则已导出", "可用文本编辑器或智能体修改 JSON，修改后重新导入。") }
            onComplete
        }
    }

    private val importRules = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runFileOperation {
            val config = contentResolver.openInputStream(uri)?.use(RecognitionRules::read)
                ?: error("无法读取规则文件")
            val onComplete: () -> Unit = { confirmImport(config) }
            onComplete
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_recognition_rules)
        val header = findViewById<View>(R.id.rulesHeader)
        val headerTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, headerTop + top, view.paddingRight, view.paddingBottom)
            insets
        }
        val content = findViewById<View>(R.id.rulesScroll)
        val contentBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, contentBottom + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)
        ViewCompat.requestApplyInsets(content)
        findViewById<View>(R.id.rulesBack).setOnClickListener { finish() }
        findViewById<View>(R.id.exportRecognitionRules).setOnClickListener {
            exportRules.launch("Accounts-RecognitionRules.json")
        }
        findViewById<View>(R.id.importRecognitionRules).setOnClickListener {
            importRules.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        showRuleCount()
    }

    private fun showRuleCount() {
        val config = RecognitionRules.load(this)
        findViewById<TextView>(R.id.rulesCount).text = "启用 ${config.enabledCount} 条自定义规则，共 ${config.ruleCount} 条"
    }

    private fun confirmImport(config: RecognitionRules) {
        PinkDialogs.show(AlertDialog.Builder(this)
            .setTitle("应用识别规则？")
            .setMessage("文件包含 ${config.ruleCount} 条规则，其中 ${config.enabledCount} 条已启用。应用后将替换本机的自定义规则。")
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                runFileOperation {
                    RecognitionRules.save(this, config)
                    val onComplete: () -> Unit = { showRuleCount() }
                    onComplete
                }
            }.create())
    }

    private fun runFileOperation(operation: () -> (() -> Unit)) {
        val importButton = findViewById<View>(R.id.importRecognitionRules)
        val exportButton = findViewById<View>(R.id.exportRecognitionRules)
        importButton.isEnabled = false
        exportButton.isEnabled = false
        Thread {
            val result = runCatching(operation)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                importButton.isEnabled = true
                exportButton.isEnabled = true
                result.onSuccess { it() }.onFailure {
                    showMessage("无法处理规则文件", it.message ?: "请检查文件格式后重试。")
                }
            }
        }.start()
    }

    private fun showMessage(title: String, message: String) {
        PinkDialogs.show(AlertDialog.Builder(this)
            .setTitle(title).setMessage(message).setPositiveButton("确定", null).create())
    }
}
