package com.abyssredemption.accounts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

class PaymentNotificationListenerService : NotificationListenerService() {
    private val sources = mapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信"
    )
    private val successMarkers = listOf("支付成功", "付款成功", "交易成功", "已支付", "扣款成功", "支付完成")

    override fun onListenerConnected() {
        super.onListenerConnected()
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
            .putBoolean("message_listener_connected", true)
            .putLong("last_message_listener_connected", System.currentTimeMillis())
            .apply()
        RecognitionLogger.log(this, "message_listener_connected", "消息辅助识别已连接，等待微信或支付宝支付通知", 0)
    }

    override fun onListenerDisconnected() {
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
            .putBoolean("message_listener_connected", false)
            .apply()
        RecognitionLogger.log(this, "message_listener_disconnected", "消息辅助识别已断开，请检查通知使用权", 0)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val rules = RecognitionRules.load(this)
        val builtInSource = sources[sbn.packageName]
        if (builtInSource == null && !rules.hasRulesFor("notification", sbn.packageName)) return
        val source = builtInSource ?: "第三方支付"
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
            .putLong("last_message_event", System.currentTimeMillis())
            .putString("last_message_source", source)
            .apply()
        RecognitionLogger.log(this, "message_event_${sbn.packageName}", "收到${source}通知，开始消息辅助识别", 1500)
        if (!getSharedPreferences("budget_settings", MODE_PRIVATE)
                .getBoolean("message_recognition_enabled", true)) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val receivedAt = System.currentTimeMillis()
        val extras = sbn.notification.extras
        val latestMessage = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)?.messages?.maxByOrNull { it.timestamp }
        val postedAt = latestMessage?.timestamp?.takeIf { it > 0 }
            ?: sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime.takeIf { it > 0 } ?: receivedAt
        if (receivedAt - postedAt !in -10_000L..120_000L) {
            RecognitionLogger.event(this, "notification", "stale_message", sbn.packageName, throttleMs = 1500)
            return
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val sender = latestMessage?.person?.name?.toString().orEmpty().trim()
        val message = latestMessage?.text?.toString()
            ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull())?.toString().orEmpty()
        val parts = listOf(title, sender, message).map(String::trim).filter(String::isNotEmpty).distinct()
        if (message.isBlank()) {
            RecognitionLogger.event(this, "notification", "message_empty", sbn.packageName, throttleMs = 1500)
            return
        }
        if (listOf("收款到账", "到账通知", "已收款", "退款", "支付失败", "付款失败", "待支付", "请支付").any(message::contains)) {
            RecognitionLogger.event(this, "notification", "non_expense_message", sbn.packageName, throttleMs = 1500)
            return
        }
        val rule = rules.evaluate("notification", sbn.packageName, parts)
        val hasSuccess = successMarkers.any { it in message || title == it }
        val knownWechatSender = if (latestMessage == null) title == "微信支付" else
            sender == "微信支付" || (sender.isEmpty() && title == "微信支付")
        if (builtInSource == "微信" && !knownWechatSender) {
            RecognitionLogger.event(this, "notification", "unverified_wechat_sender", sbn.packageName, throttleMs = 1500)
            return
        }
        val builtInAccepted = hasSuccess && when (builtInSource) {
            "微信" -> knownWechatSender
            "支付宝" -> true
            else -> false
        }
        val amount = rule?.amount ?: PaymentEvidence.amount(message)
        val evidence = mutableMapOf<String, Any>("successMarker" to hasSuccess, "amountPresent" to (amount != null))
        rule?.let { evidence["ruleId"] = it.ruleId }
        if (!(rule?.let { it.action == "accept" } ?: builtInAccepted)) {
            RecognitionLogger.event(this, "notification", if (rule == null) "no_payment_marker" else "rule_rejected",
                sbn.packageName, evidence, 1500)
            return
        }
        val value = amount?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        if (value == null) {
            RecognitionLogger.event(this, "notification", "amount_missing", sbn.packageName, evidence, 1500)
            return
        }
        val merchant = rule?.merchant ?: findMerchant(listOf(title, message), source)
        val fingerprint = sha256("notification|${sbn.packageName}|${sbn.key}|$postedAt|$value|${message.trim()}")
        val result = AccountDb(this).use {
            it.recordAutomaticPayment(merchant, value, source, postedAt, "notification", fingerprint)
        }
        evidence["attemptId"] = fingerprint
        RecognitionLogger.event(this, "notification", result.name.lowercase(), sbn.packageName, evidence, 1500)
        if (result == AutoPaymentResult.RECORDED) {
            PaymentNotifications.recorded(this, source, merchant, requireNotNull(amount))
            sendBroadcast(Intent(PaymentAccessibilityService.ACTION_MESSAGE_PAYMENT_RECORDED)
                .setPackage(packageName).putExtra("observedPackage", sbn.packageName))
        }
    }

    private fun findMerchant(parts: List<String>, source: String): String {
        val joined = parts.joinToString(" ")
        listOf(
            Regex("向(.{2,40}?)付款"),
            Regex("商户[：:]?\\s*(.{2,40}?)(?:\\s|¥|￥|$)"),
            Regex("付款给(.{2,40}?)(?:\\s|¥|￥|$)")
        ).forEach { regex -> regex.find(joined)?.groupValues?.get(1)?.trim()?.let { return it } }
        return parts.firstOrNull {
            it.length in 2..40 && it != source && it !in listOf("微信支付", "支付宝") &&
                successMarkers.none(it::contains) && PaymentEvidence.amount(it) == null
        } ?: "${source}消息记账"
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

object PaymentNotifications {
    private const val SUCCESS_CHANNEL = "auto_record_success"
    private const val READ_ALERT_CHANNEL = "screen_read_alert"

    fun recorded(context: android.content.Context, source: String, merchant: String, amount: String) {
        if (!context.getSharedPreferences("budget_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("record_notification_enabled", true)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SUCCESS_CHANNEL, "自动记账结果", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), Notification.Builder(context, SUCCESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("已通过支付消息记账 ¥$amount")
            .setContentText("$source · $merchant")
            .setColor(Color.rgb(233, 130, 158))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build())
    }

    fun screenUnreadable(context: android.content.Context, source: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(READ_ALERT_CHANNEL, "支付识别提醒", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(31036, Notification.Builder(context, READ_ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("无法读取${source}支付页面")
            .setContentText("请检查无障碍权限；也可在设置中开启“读取支付消息记账”。")
            .setColor(Color.rgb(233, 130, 158))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build())
    }

    private fun openApp(context: android.content.Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
