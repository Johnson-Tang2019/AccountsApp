package com.abyssredemption.accounts

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal class DiagnosticRangePicker private constructor(
    private val context: Context,
    private val zone: ZoneId,
    now: LocalDateTime
) {
    private val dates = arrayOf(now.minusHours(1), now)
    private var selected = 0
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
    private val start = endpoint()
    private val end = endpoint()
    private val year = wheel("年", 1970, now.year)
    private val month = wheel("月", 1, 12)
    private val day = wheel("日", 1, 31)
    private val hour = wheel("时", 0, 23)
    private val minute = wheel("分", 0, 59)
    private val second = wheel("秒", 0, 59)
    private val error = label("", R.color.pink_dark).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun show(title: String, action: String, onSelection: (Long, Long) -> Unit) {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(label("上下滑动选择日期和时间 · ${zone.id}"))
            addView(RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                addView(start)
                addView(end)
                check(start.id)
                setOnCheckedChangeListener { _, checkedId ->
                    selected = if (checkedId == end.id) 1 else 0
                    bind(dates[selected])
                    error.visibility = View.GONE
                }
            })
            addView(row("年" to year, "月" to month, "日" to day))
            addView(row("时" to hour, "分" to minute, "秒" to second))
            addView(error)
        }
        bind(dates[selected])
        refreshLabels()
        for (picker in listOf(year, month, day, hour, minute, second)) {
            picker.setOnValueChangedListener { _, _, _ ->
                updateDays()
                dates[selected] = LocalDateTime.of(year.value, month.value, day.value, hour.value, minute.value, second.value)
                refreshLabels()
                error.visibility = View.GONE
            }
        }
        val dialog = PinkDialogs.show(AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton(action, null)
            .create())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val fromMs = epochMillis(dates[0])
            val endMs = epochMillis(dates[1])
            if (fromMs == null || endMs == null || endMs < fromMs) {
                error.text = if (fromMs == null || endMs == null) "所选时间无效，请调整时间。" else "截止时间不能早于开始时间。"
                error.visibility = View.VISIBLE
                error.post { error.requestRectangleOnScreen(android.graphics.Rect(0, 0, error.width, error.height), false) }
                return@setOnClickListener
            }
            dialog.dismiss()
            onSelection(fromMs, endMs + 1000)
        }
    }

    private fun epochMillis(value: LocalDateTime): Long? {
        if (zone.rules.getValidOffsets(value).isEmpty()) return null
        return value.atZone(zone).toInstant().toEpochMilli().takeIf { it >= 0 }
    }

    private fun refreshLabels() {
        start.text = "开始时间  ${formatter.format(dates[0])}"
        end.text = "截止时间（含）  ${formatter.format(dates[1])}"
    }

    private fun bind(value: LocalDateTime) {
        year.value = value.year
        month.value = value.monthValue
        updateDays()
        day.value = value.dayOfMonth
        hour.value = value.hour
        minute.value = value.minute
        second.value = value.second
    }

    private fun updateDays() {
        val max = YearMonth.of(year.value, month.value).lengthOfMonth()
        if (day.maxValue != max) day.maxValue = max
    }

    private fun row(vararg columns: Pair<String, NumberPicker>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((unit, picker) in columns) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label(unit))
                addView(picker, ViewGroup.LayoutParams.MATCH_PARENT, dp(90))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun wheel(unit: String, min: Int, max: Int) = NumberPicker(context).apply {
        minValue = min
        maxValue = max
        wrapSelectorWheel = unit != "年"
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = unit
        if (unit != "年") setFormatter { String.format(Locale.ROOT, "%02d", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTextColor(context.getColor(R.color.text_primary))
            setSelectionDividerHeight(dp(1))
        }
    }

    private fun endpoint() = RadioButton(context).apply {
        id = View.generateViewId()
        setTextColor(context.getColor(R.color.text_primary))
        buttonTintList = ColorStateList.valueOf(context.getColor(R.color.pink_dark))
        minimumHeight = dp(44)
        textSize = 13f
    }

    private fun label(value: String, @ColorRes color: Int = R.color.text_secondary) = TextView(context).apply {
        text = value
        textSize = 12f
        setTextColor(context.getColor(color))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        fun show(context: Context, title: String, action: String, onSelection: (Long, Long) -> Unit) {
            val zone = ZoneId.systemDefault()
            DiagnosticRangePicker(context, zone, LocalDateTime.now(zone).withNano(0)).show(title, action, onSelection)
        }
    }
}
