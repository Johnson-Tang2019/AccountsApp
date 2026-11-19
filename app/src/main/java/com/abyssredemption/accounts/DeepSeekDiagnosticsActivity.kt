package com.abyssredemption.accounts

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class DeepSeekDiagnosticsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FROM_MS = "fromMs"
        const val EXTRA_TO_MS = "toMs"
    }

    private var fromMs = 0L
    private var toMs = 0L
    private var draft: String? = null
    private var pendingAnalysis: String? = null
    private val preferences by lazy { getSharedPreferences("diagnostics_ai", MODE_PRIVATE) }
    private val exportAnalysis = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val contents = pendingAnalysis
        pendingAnalysis = null
        if (uri == null || contents == null) return@registerForActivityResult
        runJob("正在保存分析文件…", "无法保存分析文件。", {
            val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开目标文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(contents) }
        }) { showStatus("分析文件已保存。在 DSH Go 上传此文件即可开始分析。") }
    }

    private val exportDraft = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val contents = draft
        if (uri == null || contents == null) return@registerForActivityResult
        runJob("正在写入规则草案…", "无法导出规则草案", {
            val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开目标文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(contents) }
        }) { showStatus("规则草案已导出。若已有自定义规则，请先合并草案，再导入完整规则文件。") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_deepseek_diagnostics)
        val header = findViewById<View>(R.id.deepSeekHeader)
        val scroll = findViewById<ScrollView>(R.id.deepSeekScroll)
        val top = header.paddingTop
        val bottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, top + safeTop, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safeBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + safeBottom)
            insets
        }

        toMs = savedInstanceState?.getLong(EXTRA_TO_MS) ?: intent.getLongExtra(EXTRA_TO_MS, System.currentTimeMillis())
        fromMs = savedInstanceState?.getLong(EXTRA_FROM_MS) ?: intent.getLongExtra(EXTRA_FROM_MS, toMs - 3_600_000L)
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        findViewById<TextView>(R.id.deepSeekRange).text = "${formatter.format(Date(fromMs))} 至 ${formatter.format(Date((toMs - 1).coerceAtLeast(fromMs)))}"

        findViewById<EditText>(R.id.deepSeekEndpoint).setText(preferences.getString("endpoint", DeepSeekDiagnostics.DEFAULT_ENDPOINT))
        findViewById<EditText>(R.id.deepSeekModel).setText(preferences.getString("model", DeepSeekDiagnostics.DEFAULT_MODEL))
        findViewById<View>(R.id.deepSeekBack).setOnClickListener { finish() }
        pendingAnalysis = savedInstanceState?.getString("pending_analysis")
        findViewById<View>(R.id.exportDshGoAnalysis).setOnClickListener {
            preview("导出分析文件", "保存文件", DeepSeekDiagnostics::analysisDocument) { _, contents ->
                pendingAnalysis = contents
                val suffix = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(fromMs))
                exportAnalysis.launch("Accounts-analysis-$suffix.md")
            }
        }
        findViewById<View>(R.id.previewForDeepSeekApi).setOnClickListener { previewApiRequest() }
        findViewById<View>(R.id.exportDeepSeekDraft).setOnClickListener { exportDraft.launch("accounts-rule-draft.json") }
        PinkDialogs.styleProgress(findViewById(R.id.deepSeekProgress))
        savedInstanceState?.getString("answer")?.let { showAnswer(JSONObject(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(EXTRA_FROM_MS, fromMs)
        outState.putLong(EXTRA_TO_MS, toMs)
        pendingAnalysis?.let { outState.putString("pending_analysis", it) }
        val answer = findViewById<TextView>(R.id.deepSeekOutput)
        if (answer.visibility == View.VISIBLE) outState.putString("answer", answer.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun previewApiRequest() {
        val endpointInput = findViewById<EditText>(R.id.deepSeekEndpoint)
        val modelInput = findViewById<EditText>(R.id.deepSeekModel)
        val keyInput = findViewById<EditText>(R.id.deepSeekKey)
        val endpoint = runCatching { DeepSeekDiagnostics.endpoint(endpointInput.text.toString()) }.getOrElse {
            endpointInput.error = "请输入以 /chat/completions 结尾的 HTTPS 地址。"
            return
        }
        val model = modelInput.text.toString().trim()
        val key = keyInput.text.toString().trim()
        if (model.isBlank() || model.any(Char::isWhitespace)) {
            modelInput.error = "请输入有效的模型名称。"
            return
        }
        if (key.isBlank() || key.any { it <= ' ' || it >= '\u007f' }) {
            keyInput.error = "请输入有效的 API Key。"
            return
        }
        preview("确认发送诊断数据", "发送并分析", { json ->
            "接收方 ${endpoint.authority}\n\n${DeepSeekDiagnostics.INSTRUCTIONS}\n\n$json"
        }) { report, _ ->
            preferences.edit().putString("endpoint", endpoint.toExternalForm()).putString("model", model).apply()
            keyInput.text?.clear()
            runJob("正在请求 DeepSeek 分析…", "DeepSeek 分析失败，请检查网络和接口配置。", {
                DeepSeekDiagnostics.analyze(endpoint, model, key, report)
            }, ::showAnswer)
        }
    }

    private fun preview(
        title: String,
        action: String,
        formatPreview: (String) -> String,
        submit: (json: String, rendered: String) -> Unit
    ) {
        runJob("正在生成脱敏预览…", "无法读取诊断记录。", {
            val report = RecognitionLogger.report(applicationContext, fromMs, toMs, DeepSeekDiagnostics.EVENT_LIMIT)
            DeepSeekDiagnostics.projectReport(report, fromMs, toMs)
        }) { projection ->
            val events = projection.getJSONArray("events").length()
            if (events == 0) {
                showStatus("此时间范围内未保存诊断事件。")
                return@runJob
            }
            showStatus("")
            val json = projection.toString(2)
            val rendered = formatPreview(json)
            val content = TextView(this).apply {
                val padding = (22 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, padding / 2)
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                text = rendered
                setTextIsSelectable(true)
            }
            PinkDialogs.show(AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ScrollView(this).apply { addView(content) })
                .setNegativeButton("取消", null)
                .setPositiveButton(action) { _, _ -> submit(json, rendered) }
                .create())
        }
    }

    private fun showAnswer(answer: JSONObject) {
        findViewById<TextView>(R.id.deepSeekOutput).apply {
            text = answer.toString(2)
            visibility = View.VISIBLE
        }
        val suggested = answer.optJSONObject("rulesConfig")
        draft = suggested?.let { runCatching { RecognitionRules.parse(it.toString()).toJson() }.getOrNull() }
        findViewById<View>(R.id.exportDeepSeekDraft).visibility = if (draft == null) View.GONE else View.VISIBLE
        showStatus(when {
            draft != null -> "分析完成。已生成可导出的规则草案。"
            suggested != null -> "分析完成。规则草案未通过格式校验。"
            else -> "分析完成。"
        })
    }

    private fun <T> runJob(progress: String, failure: String, task: () -> T, onSuccess: (T) -> Unit) {
        val controls = listOf(R.id.exportDshGoAnalysis, R.id.previewForDeepSeekApi, R.id.exportDeepSeekDraft)
        controls.forEach { findViewById<View>(it).isEnabled = false }
        val spinner = findViewById<ProgressBar>(R.id.deepSeekProgress)
        spinner.visibility = View.VISIBLE
        showStatus(progress)
        Thread {
            val outcome = runCatching(task)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                spinner.visibility = View.GONE
                controls.forEach { findViewById<View>(it).isEnabled = true }
                outcome.onSuccess(onSuccess).onFailure { error ->
                    val detail = (error as? IllegalStateException)?.message
                    showStatus(detail?.takeIf { it.startsWith("DeepSeek") } ?: failure)
                }
            }
        }.start()
    }

    private fun showStatus(message: String) {
        findViewById<TextView>(R.id.deepSeekStatus).text = message
    }
}
