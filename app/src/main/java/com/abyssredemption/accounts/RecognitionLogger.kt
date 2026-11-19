package com.abyssredemption.accounts

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object RecognitionLogger {
    const val RETENTION_DAYS = 14
    const val MAX_EVENTS = 2000
    private const val FILE_NAME = "recognition-v1.jsonl"
    private const val LEGACY_FILE_NAME = "recognition.log"
    private const val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000L
    private const val HOUR_MS = 60 * 60 * 1000L
    private val token = Regex("[a-zA-Z0-9_-]{1,64}")
    private val fingerprintToken = Regex("[a-f0-9]{64}")
    private val packageToken = Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")
    private val channels = setOf("screen", "notification", "system")
    private val numberFields = setOf("nodeCount", "windowId", "eventType", "dedupWindowMs", "elapsedMs", "attempt", "candidateCount")
    private val booleanFields = setOf("amountPresent", "successMarker", "channelMatch", "amountMatch", "merchantMatch", "supportedPackage", "appLock", "prePayment", "resultAction", "historyPage")
    private val tokenFields = setOf("ruleId", "attemptId")
    private val recentKeys = linkedMapOf<String, Long>()
    private var eventCount = 0
    private var lastCompaction: Long? = null

    private val displayLabels = mapOf(
        "screen_unreadable_alert" to "支付窗口持续不可读",
        "same_amount_confirmation" to "等待确认相同金额记录",
        "third_party_candidate" to "发现第三方支付结果页",
        "message_listener_connected" to "通知监听已连接",
        "message_listener_disconnected" to "通知监听已断开",
        "message_no_amount" to "通知未提取到金额",
        "message_no_marker" to "通知未匹配支付凭证",
        "message_duplicate" to "通知与已有支付记录重复",
        "message_inserted" to "已通过通知记账",
        "message_stale" to "忽略过期通知",
        "message_empty" to "通知不含可读内容",
        "message_income" to "忽略收款通知",
        "message_event" to "收到支付应用通知",
        "app_lock" to "忽略应用锁页面",
        "wechat_pending_amount" to "等待微信支付结果",
        "wechat_browse_page" to "忽略微信聊天列表或账单历史",
        "pre_payment" to "尚在付款确认阶段",
        "package_changed" to "采样时应用窗口已切换",
        "no_package" to "窗口未提供包名",
        "no_marker" to "未匹配支付成功标记",
        "no_amount" to "窗口未提取到金额",
        "no_root" to "窗口内容暂不可读",
        "recent_duplicate" to "最近已有相同支付记录",
        "inserted" to "已通过无障碍记账",
        "duplicate" to "已忽略重复的支付记录",
        "service_connected" to "无障碍服务已连接",
        "service_interrupted" to "无障碍服务已中断",
        "service_destroyed" to "无障碍服务已停止",
        "overlay_visible" to "服务悬浮标记已显示",
        "overlay_failed" to "服务悬浮标记显示失败",
        "manual_check" to "手动检测了无障碍状态",
        "event" to "收到支付应用窗口事件",
        "window_unavailable" to "支付窗口暂不可读",
        "history_page" to "忽略账单历史页面",
        "no_success_marker" to "未匹配支付成功标记",
        "rule_rejected" to "识别规则已排除该次采样",
        "amount_missing" to "未提取到有效金额",
        "recorded" to "已自动记账",
        "replay" to "忽略重复上报的支付结果",
        "cross_channel_duplicate" to "已合并无障碍和通知的重复记录",
        "confirmation_required" to "等待确认相同金额记录",
        "stale_message" to "忽略过期通知",
        "non_expense_message" to "忽略非支出通知",
        "unverified_wechat_sender" to "未确认通知来自微信支付",
        "no_payment_marker" to "通知未匹配支付凭证",
        "confirmation_declined" to "已取消这笔待确认记录",
        "unspecified" to "识别诊断事件"
    )
    private val nodeCountPattern = Regex("节点 (\\d{1,6})")
    private val eventTypePattern = Regex("类型 (\\d{1,6})")

    fun log(context: Context, key: String, message: String, throttleMs: Long = 3000) {
        val code = displayLabels.keys.firstOrNull { key == it || key.startsWith("${it}_") }
            ?: "unspecified"
        val channel = when {
            code.startsWith("message_") -> "notification"
            code.startsWith("service_") || code.startsWith("overlay_") || code == "manual_check" -> "system"
            else -> "screen"
        }
        val packageName = listOf("com.tencent.mm", "com.eg.android.AlipayGphone").firstOrNull { key.endsWith("_$it") }
        val evidence = mutableMapOf<String, Any>()
        nodeCountPattern.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { evidence["nodeCount"] = it }
        eventTypePattern.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { evidence["eventType"] = it }
        if (code.contains("no_amount")) evidence["amountPresent"] = false
        if (code.contains("no_marker")) evidence["successMarker"] = false
        event(context, channel, code, packageName, evidence, throttleMs)
    }

    @Synchronized
    fun event(
        context: Context,
        channel: String,
        code: String,
        packageName: String? = null,
        evidence: Map<String, Any> = emptyMap(),
        throttleMs: Long = 0
    ) {
        require(channel in channels && token.matches(code)) { "Invalid diagnostic event" }
        val safePackage = packageName?.takeIf { it.length <= 120 && packageToken.matches(it) }
        val elapsed = SystemClock.elapsedRealtime()
        val attemptId = (evidence["attemptId"] as? String)?.takeIf(token::matches)
        val throttleKey = "$channel/$code/$safePackage/$attemptId"
        if (throttleMs > 0) {
            val last = recentKeys[throttleKey]
            if (last != null && elapsed - last < throttleMs) return
            if (recentKeys.size >= 256) recentKeys.keys.firstOrNull()?.let(recentKeys::remove)
            recentKeys[throttleKey] = elapsed
        }

        val details = JSONObject()
        for ((name, value) in evidence) {
            when {
                name in numberFields && value is Int -> details.put(name, value)
                name in numberFields && value is Long -> details.put(name, value)
                name in booleanFields && value is Boolean -> details.put(name, value)
                name == "attemptId" && value is String && fingerprintToken.matches(value) ->
                    details.put(name, aliasFingerprint(context, value))
                name in tokenFields && value is String && token.matches(value) -> details.put(name, value)
            }
        }
        val now = System.currentTimeMillis()
        val item = JSONObject()
            .put("timestampMs", now)
            .put("channel", channel)
            .put("code", code)
            .put("evidence", details)
        if (safePackage != null) item.put("packageName", safePackage)
        val file = File(context.filesDir, FILE_NAME)
        try {
            if (eventCount >= MAX_EVENTS || lastCompaction?.let { elapsed - it >= HOUR_MS } != false) {
                compact(file, now)
                lastCompaction = elapsed
            }
            file.appendText("$item\n", Charsets.UTF_8)
            eventCount++
        } catch (_: IOException) {
            // A diagnostics write failure must not interrupt payment recognition.
        }
    }

    private fun aliasFingerprint(context: Context, fingerprint: String): String {
        val prefs = context.getSharedPreferences("recognition_diagnostics", Context.MODE_PRIVATE)
        val salt = prefs.getString("fingerprint_salt", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("fingerprint_salt", it).apply()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest((salt + fingerprint).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Returns chronological, privacy-filtered events for [fromMs, toMs). */
    @Synchronized
    fun report(context: Context, fromMs: Long, toMs: Long, limit: Int = MAX_EVENTS): JSONObject {
        require(fromMs >= 0L && toMs > fromMs) { "Invalid diagnostic time range" }
        require(limit in 1..MAX_EVENTS) { "Invalid diagnostic event limit" }
        val retained = load(File(context.filesDir, FILE_NAME), System.currentTimeMillis())
        val matching = retained.filter { it.optLong("timestampMs") in fromMs until toMs }
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("format", "accounts-recognition-diagnostics")
            .put("schemaVersion", 1)
            .put("appVersion", packageInfo.versionName.orEmpty())
            .put("appBuild", PackageInfoCompat.getLongVersionCode(packageInfo))
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("timeZone", TimeZone.getDefault().id)
            .put("range", JSONObject().put("fromMsInclusive", fromMs).put("toMsExclusive", toMs))
            .put("retention", JSONObject()
                .put("days", RETENTION_DAYS)
                .put("maxEvents", MAX_EVENTS)
                .put("oldestAvailableMs", retained.firstOrNull()?.optLong("timestampMs") ?: JSONObject.NULL))
            .put("matchingEventCount", matching.size)
            .put("truncated", matching.size > limit)
            .put("events", JSONArray(matching.takeLast(limit)))
    }

    @Synchronized
    fun read(context: Context): String {
        val displayTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        return load(File(context.filesDir, FILE_NAME), System.currentTimeMillis()).takeLast(200)
            .joinToString("\n") { item ->
                val code = item.optString("code")
                val time = displayTime.format(Date(item.optLong("timestampMs")))
                val app = item.optString("packageName")
                val appPrefix = when (app) {
                    "com.tencent.mm" -> "微信 · "
                    "com.eg.android.AlipayGphone" -> "支付宝 · "
                    "" -> ""
                    else -> "$app · "
                }
                "$time  $appPrefix${displayLabels[code] ?: code}"
            }
    }

    @Synchronized
    fun clear(context: Context) {
        AtomicFile(File(context.filesDir, FILE_NAME)).delete()
        context.deleteFile(LEGACY_FILE_NAME)
        recentKeys.clear()
        eventCount = 0
        lastCompaction = null
    }

    private fun load(file: File, now: Long): List<JSONObject> = try {
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { it.optLong("timestampMs") >= now - RETENTION_MS }
                .toList()
                .sortedBy { it.optLong("timestampMs") }
                .takeLast(MAX_EVENTS)
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun compact(file: File, now: Long) {
        val retained = load(file, now)
        // Leave space for a batch of events before rewriting at the cap again.
        val remaining = if (retained.size >= MAX_EVENTS) retained.takeLast(MAX_EVENTS - 100) else retained
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            for (item in remaining) {
                writer.write(item.toString())
                writer.newLine()
            }
            writer.flush()
            atomic.finishWrite(stream)
            eventCount = remaining.size
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
