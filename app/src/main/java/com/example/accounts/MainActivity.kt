package com.example.accounts

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var db: AccountDb
    private val prefs by lazy { getSharedPreferences("budget_settings", MODE_PRIVATE) }
    private var filter: String? = null
    private val money = java.text.DecimalFormat("0.00")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applyTopInset(findViewById(R.id.mainHeader))
        requestNotificationPermissionIfNeeded()
        db = AccountDb(this)
        if (!prefs.contains("balance_as_of")) {
            prefs.edit().putLong("balance_as_of", System.currentTimeMillis()).apply()
        }
        findViewById<View>(R.id.autoCard).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<View>(R.id.checkAccessibility).setOnClickListener { showAccessibilityCheck() }
        findViewById<View>(R.id.addRecord).setOnClickListener { showRecordDialog(null) }
        findViewById<View>(R.id.openSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.filterAll).setOnClickListener { filter = null; refresh() }
        findViewById<View>(R.id.filterExpense).setOnClickListener { filter = "expense"; refresh() }
        findViewById<View>(R.id.filterIncome).setOnClickListener { filter = "income"; refresh() }
        UpdateUi.checkForUpdates(this, manual = false)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun applyTopInset(header: View) {
        val initialTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, initialTop + safeTop, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        updateFilterSelection()
        val calendar = Calendar.getInstance()
        findViewById<TextView>(R.id.monthTitle).text = SimpleDateFormat("yyyy 年 M 月", Locale.CHINA).format(calendar.time)
        calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis; calendar.add(Calendar.MONTH, 1)
        val end = calendar.timeInMillis
        val summary = db.monthSummary(start, end)
        val monthlyBudget = prefs.getFloat("monthly_budget", 5000f).toDouble()
        val balanceBase = prefs.getFloat("current_balance", 0f).toDouble()
        val balanceAsOf = prefs.getLong("balance_as_of", System.currentTimeMillis())
        val currentBalance = balanceBase + db.netChangeSince(balanceAsOf)
        val budgetStats = db.budgetStats(monthlyBudget, currentBalance, start)
        val budgetUsedAmount = (monthlyBudget - currentBalance).coerceAtLeast(0.0)
        val today = Calendar.getInstance()
        val elapsedRatio = today.get(Calendar.DAY_OF_MONTH).toDouble() / today.getActualMaximum(Calendar.DAY_OF_MONTH).toDouble()
        val remainingRatio = prefs.getInt("remaining_ratio", 20) / 100.0
        val spendableBudget = monthlyBudget * (1.0 - remainingRatio)
        val expectedUsed = spendableBudget * elapsedRatio
        val expectedProgress = if (monthlyBudget <= 0) 0 else ((expectedUsed / monthlyBudget) * 1000).toInt().coerceIn(0, 1000)
        findViewById<TextView>(R.id.balance).text = "¥ ${money.format(currentBalance)}"
        findViewById<TextView>(R.id.income).text = "¥${money.format(summary.income)}"
        findViewById<TextView>(R.id.expense).text = "¥${money.format(summary.expense)}"
        findViewById<TextView>(R.id.currentBalance).text = "¥${money.format(summary.income - summary.expense)}"
        findViewById<TextView>(R.id.budgetUsed).text = "已用 ¥${money.format(budgetUsedAmount)} / ¥${money.format(monthlyBudget)}"
        findViewById<TextView>(R.id.budgetPace).text = "今日建议 ${(expectedProgress / 10.0)}% · 预留 ${(remainingRatio * 100).toInt()}% · ¥${money.format(expectedUsed)}"
        findViewById<TextView>(R.id.monthRemaining).text = "¥${money.format(budgetStats.monthRemaining)}"
        findViewById<TextView>(R.id.accumulatedRemaining).text = "¥${money.format(budgetStats.accumulatedRemaining)}"
        findViewById<ProgressBar>(R.id.budgetProgress).apply {
            max = 1000
            progress = if (monthlyBudget <= 0) 0 else ((budgetUsedAmount / monthlyBudget) * 1000).toInt().coerceIn(0, 1000)
        }
        findViewById<ProgressBar>(R.id.budgetExpectedProgress).apply {
            max = 1000
            progress = expectedProgress
        }

        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/${PaymentAccessibilityService::class.java.name}", true) == true
        val statePrefs = getSharedPreferences("service_state", MODE_PRIVATE)
        val heartbeatFresh = System.currentTimeMillis() - statePrefs.getLong("last_heartbeat", 0) < 15_000
        findViewById<TextView>(R.id.autoStatus).text = when {
            enabled && heartbeatFresh -> "自动记账运行中"
            enabled -> "已授权但服务未运行，点击检测"
            else -> "未开启，点击授权"
        }
        findViewById<TextView>(R.id.autoDot).setTextColor(Color.parseColor(if (enabled && heartbeatFresh) "#4FAE82" else "#C9A6AE"))

        val rows = db.recent(filter)
        findViewById<TextView>(R.id.emptyView).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        findViewById<ListView>(R.id.records).adapter = RecordAdapter(rows)
        findViewById<ListView>(R.id.records).setOnItemClickListener { _, _, position, _ ->
            showRecordDialog(rows[position])
        }
        findViewById<ListView>(R.id.records).setOnItemLongClickListener { _, _, position, _ ->
            val deleteDialog = AlertDialog.Builder(this).setTitle("删除这笔记录？").setMessage("删除后无法恢复。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> db.delete(rows[position].id); refresh() }.create()
            PinkDialogs.show(deleteDialog)
            true
        }
    }

    private fun updateFilterSelection() {
        listOf(
            R.id.filterAll to (filter == null),
            R.id.filterExpense to (filter == "expense"),
            R.id.filterIncome to (filter == "income")
        ).forEach { (id, selected) ->
            findViewById<TextView>(id).apply {
                setBackgroundResource(if (selected) R.drawable.bg_filter_selected else R.drawable.bg_category)
                setTextColor(getColor(if (selected) R.color.white else R.color.text_secondary))
            }
        }
    }

    private fun showAccessibilityCheck() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/${PaymentAccessibilityService::class.java.name}", true) == true
        val state = getSharedPreferences("service_state", MODE_PRIVATE)
        val heartbeat = state.getLong("last_heartbeat", 0)
        val running = System.currentTimeMillis() - heartbeat < 15_000
        val lastEvent = state.getLong("last_event", 0)
        val lastSource = state.getString("last_event_source", "暂无") ?: "暂无"
        val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        val message = buildString {
            append("系统授权：${if (enabled) "已开启" else "未开启"}\n")
            append("服务心跳：${if (running) "正常" else "未检测到"}\n")
            append("悬浮标记：${if (running) "服务已请求显示" else "服务未运行"}\n")
            append("最后事件：")
            if (lastEvent > 0) append("$lastSource · ${timeFormat.format(Date(lastEvent))}") else append("尚未收到微信/支付宝事件")
            if (enabled && !running) append("\n\n系统显示已授权，但服务没有运行。请关闭该无障碍服务后重新开启，并允许应用后台运行。")
        }
        RecognitionLogger.log(this, "manual_check", "手动检测：授权=$enabled，心跳=$running，最后事件=$lastSource", 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("无障碍功能检测")
            .setMessage(message)
            .setNegativeButton("关闭", null)
            .setPositiveButton("打开无障碍设置") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .create()
        dialog.show()
        roundDialog(dialog)
    }

    private fun showRecordDialog(record: PaymentRecord?) {
        val box = layoutInflater.inflate(R.layout.dialog_record, null)
        val title = box.findViewById<TextView>(R.id.dialogRecordTitle)
        val subtitle = box.findViewById<TextView>(R.id.dialogRecordSubtitle)
        val merchant = box.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.recordMerchant)
        val amount = box.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.recordAmount)
        val kinds = box.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.recordKindToggle)
        val categories = box.findViewById<AutoCompleteTextView>(R.id.recordCategory)
        val note = box.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.recordNote)
        title.text = if (record == null) "记一笔" else "修改记录"
        subtitle.text = if (record == null) "记录此刻的收支" else "调整后余额和统计会同步更新"
        merchant.setText(record?.merchant.orEmpty())
        if (record != null) amount.setText(money.format(record.amount))
        kinds.check(if (record?.kind == "income") R.id.recordIncome else R.id.recordExpense)
        val categoryValues = listOf("餐饮", "购物", "交通", "居住", "娱乐", "医疗", "工资", "红包", "自动记账", "其他")
        categories.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categoryValues))
        categories.setText(record?.category?.takeIf(categoryValues::contains) ?: "其他", false)
        note.setText(record?.note.orEmpty())
        val builder = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
        if (record != null) builder.setNeutralButton("删除记录", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = amount.text.toString().toDoubleOrNull()
                if (value == null || value <= 0) { amount.error = "请输入正确金额"; return@setOnClickListener }
                val kind = if (kinds.checkedButtonId == R.id.recordIncome) "income" else "expense"
                val name = merchant.text.toString().trim()
                val category = categories.text.toString().takeIf(categoryValues::contains) ?: "其他"
                val memo = note.text.toString().trim()
                if (record == null) db.insertManual(name, value, kind, category, memo)
                else db.update(record.id, name, value, kind, category, memo)
                dialog.dismiss(); refresh()
            }
            if (record != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                    setTextColor(Color.parseColor("#C94F68"))
                    setOnClickListener {
                        val confirm = AlertDialog.Builder(this@MainActivity)
                            .setTitle("删除这笔记录？")
                            .setMessage("删除后无法恢复，余额和统计会立即重新计算。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                db.delete(record.id)
                                dialog.dismiss()
                                refresh()
                            }
                            .create()
                        confirm.show()
                        roundDialog(confirm)
                    }
                }
            }
        }
        dialog.show()
        roundDialog(dialog)
    }

    private fun roundDialog(dialog: AlertDialog) {
        PinkDialogs.apply(dialog)
    }

    private inner class RecordAdapter(private val data: List<PaymentRecord>) : BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = data[position].id
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val item = data[position]
            val row = convertView ?: layoutInflater.inflate(R.layout.item_record, parent, false)
            row.findViewById<TextView>(R.id.categoryIcon).text = categoryIcon(item.category)
            row.findViewById<TextView>(R.id.recordTitle).text = item.merchant
            row.findViewById<TextView>(R.id.recordMeta).text = "${item.category} · ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.paidAt))}"
            row.findViewById<TextView>(R.id.recordAmount).apply {
                text = (if (item.kind == "income") "+" else "−") + "¥${money.format(item.amount)}"
                setTextColor(Color.parseColor(if (item.kind == "income") "#4FAE82" else "#3D3437"))
            }
            return row
        }
    }

    private fun categoryIcon(category: String) = when (category) {
        "餐饮" -> "餐"; "购物" -> "购"; "交通" -> "行"; "居住" -> "家"; "娱乐" -> "乐"
        "医疗" -> "医"; "工资" -> "薪"; "红包" -> "礼"; "自动记账" -> "自"; else -> "记"
    }
}
