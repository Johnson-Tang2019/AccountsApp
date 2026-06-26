package com.abyssredemption.accounts

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class StatisticsActivity : AppCompatActivity() {
    private lateinit var db: AccountDb
    private val prefs by lazy { getSharedPreferences("budget_settings", MODE_PRIVATE) }
    private val money = DecimalFormat("0.00")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_statistics)
        db = AccountDb(this)
        applyTopInset(findViewById(R.id.statsHeader))
        findViewById<View>(R.id.statsBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun applyTopInset(header: View) {
        val initialTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingLeft, initialTop + top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)
    }

    private fun refresh() {
        val calendar = Calendar.getInstance()
        val todayDay = calendar.get(Calendar.DAY_OF_MONTH)
        findViewById<TextView>(R.id.statsMonth).text = SimpleDateFormat("yyyy 年 M 月", Locale.CHINA).format(calendar.time)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis
        val monthEndCalendar = calendar.clone() as Calendar
        monthEndCalendar.add(Calendar.MONTH, 1)
        val monthEnd = monthEndCalendar.timeInMillis

        val dailyByKey = db.dailyAmounts(monthStart, monthEnd).associateBy { it.dayKey }
        val baseBalance = prefs.getFloat("current_balance", 0f).toDouble()
        val balanceAsOf = prefs.getLong("balance_as_of", System.currentTimeMillis())
        val currentBalance = baseBalance + db.netChangeSince(balanceAsOf)
        var runningBalance = currentBalance - db.netChangeSince(monthStart)
        var totalExpense = 0.0
        var totalIncome = 0.0
        val points = mutableListOf<ChartPoint>()
        val rows = mutableListOf<ChartPoint>()
        val dayFormat = SimpleDateFormat("MM-dd", Locale.CHINA)
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

        for (day in 1..todayDay) {
            calendar.set(Calendar.DAY_OF_MONTH, day)
            val key = keyFormat.format(calendar.time)
            val amount = dailyByKey[key]
            val income = amount?.income ?: 0.0
            val expense = amount?.expense ?: 0.0
            runningBalance += income - expense
            totalIncome += income
            totalExpense += expense
            val point = ChartPoint(dayFormat.format(calendar.time), expense, runningBalance)
            points += point
            if (income > 0.0 || expense > 0.0 || day == todayDay) rows += point
        }

        findViewById<TextView>(R.id.statsExpense).text = "¥${money.format(totalExpense)}"
        findViewById<TextView>(R.id.statsIncome).text = "¥${money.format(totalIncome)}"
        findViewById<TextView>(R.id.statsBalance).text = "¥${money.format(currentBalance)}"
        findViewById<LineChartView>(R.id.statsChart).setPoints(points)
        renderRows(rows.asReversed())
    }

    private fun renderRows(rows: List<ChartPoint>) {
        val container = findViewById<LinearLayout>(R.id.statsRows)
        container.removeAllViews()
        rows.forEach { point ->
            val row = layoutInflater.inflate(R.layout.item_daily_stat, container, false)
            row.findViewById<TextView>(R.id.dayLabel).text = point.label
            row.findViewById<TextView>(R.id.dayExpense).text = "支出 ¥${money.format(point.expense)}"
            row.findViewById<TextView>(R.id.dayBalance).text = "余额 ¥${money.format(point.balance)}"
            container.addView(row)
        }
    }
}
