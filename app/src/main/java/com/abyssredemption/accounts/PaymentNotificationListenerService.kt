package com.abyssredemption.accounts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle
import java.security.MessageDigest

class PaymentNotificationListenerService : NotificationListenerService() {
    private val sources = mapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信"
    )
    private val amountRegex = Regex("(?:[¥￥]\\s*|金额[：:]?\\s*)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元")
    private val successMarkers = listOf("支付成功", "付款成功", "交易成功", "已支付", "支付凭证", "扣款成功")

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
        val source = sources[sbn.packageName] ?: return
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
            .putLong("last_message_event", System.currentTimeMillis())
            .putString("last_message_source", source)
            .apply()
        RecognitionLogger.log(this, "message_event_${sbn.packageName}", "收到${source}通知，开始消息辅助识别", 1500)
        if (!getSharedPreferences("budget_settings", MODE_PRIVATE)
                .getBoolean("message_recognition_enabled", true)) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val receivedAt = System.currentTimeMillis()
        val postedAt = sbn.postTime.takeIf { it > 0 } ?: receivedAt
        if (receivedAt - postedAt > 120_000) {
            RecognitionLogger.log(this, "message_stale_${sbn.key}", "消息排除：忽略两分钟前的旧支付通知", 0)
            return
        }

        val extras = sbn.notification.extras
        val parts = buildList<String> {
            add(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it.toString()) }
            collectBundleText(extras, this)
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        val message = parts.joinToString(" ")
        if (message.isBlank()) {
            RecognitionLogger.log(this, "message_empty_${sbn.packageName}", "消息排除：${source}通知未提供可读取文字")
            return
        }
        if (listOf("收款到账", "到账通知", "已收款").any(message::contains)) {
            RecognitionLogger.log(this, "message_income_${sbn.packageName}", "消息排除：检测到收款通知，暂不作为支出记账")
            return
        }
        val match = amountRegex.find(message)
        val amount = match?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.replace(",", "") ?: run {
            RecognitionLogger.log(this, "message_no_amount_${sbn.packageName}", "消息排除：${source}支付通知未读取到金额")
            return
        }
        val trustedPaymentAccount = message.contains("微信支付") ||
            (source == "支付宝" && message.contains("支付宝"))
        if (successMarkers.none(message::contains) && !trustedPaymentAccount) {
            RecognitionLogger.log(this, "message_no_marker_${sbn.packageName}", "消息排除：${source}通知没有明确的支付凭证标记")
            return
        }
        val value = amount.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return
        val now = postedAt
        val db = AccountDb(applicationContext)
        if (db.hasRecentPayment(value, now - 60_000)) {
            RecognitionLogger.log(this, "message_duplicate_${source}_$amount", "消息排除：60 秒内已有相同金额的记录，避免与屏幕识别重复", 0)
            return
        }
        val merchant = findMerchant(parts, source)
        val fingerprint = sha256("notification|${sbn.packageName}|${sbn.key}|$amount|${now / 60_000}")
        if (db.insert(merchant, amount, source, now, fingerprint)) {
            RecognitionLogger.log(this, "message_inserted_$fingerprint", "消息成功：已记录 $source · $merchant · ¥$amount", 0)
            PaymentNotifications.recorded(this, source, merchant, amount)
            sendBroadcast(Intent(PaymentAccessibilityService.ACTION_MESSAGE_PAYMENT_RECORDED).setPackage(packageName))
        }
    }

    @Suppress("DEPRECATION")
    private fun collectBundleText(bundle: Bundle, output: MutableList<String>) {
        bundle.keySet().forEach { key ->
            when (val value = bundle.get(key)) {
                is CharSequence -> output += value.toString()
                is Bundle -> collectBundleText(value, output)
                is Array<*> -> value.forEach { item ->
                    when (item) {
                        is CharSequence -> output += item.toString()
                        is Bundle -> collectBundleText(item, output)
                    }
                }
            }
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
                successMarkers.none(it::contains) && amountRegex.find(it) == null
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
