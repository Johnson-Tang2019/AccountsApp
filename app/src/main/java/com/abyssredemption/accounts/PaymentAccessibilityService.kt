package com.abyssredemption.accounts

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import kotlin.math.abs

class PaymentAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_SETTINGS_CHANGED = "com.abyssredemption.accounts.SETTINGS_CHANGED"
        const val ACTION_PAYMENT_CONFIRMED = "com.abyssredemption.accounts.PAYMENT_CONFIRMED"
        const val ACTION_MESSAGE_PAYMENT_RECORDED = "com.abyssredemption.accounts.MESSAGE_PAYMENT_RECORDED"
    }
    private val allowedPackages = mapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信"
    )
    private val successWords = listOf(
        "支付成功", "付款成功", "支付完成", "交易成功", "转账成功", "红包已发送"
    )
    private val resultPageWords = listOf("返回商家", "完成", "支付详情", "查看订单", "订单详情")
    private val historyPageWords = listOf("交易记录", "账单历史", "全部订单", "订单列表")
    private val amountRegex = Regex("(?:[¥￥]\\s*|金额[：:]?\\s*)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackage: String? = null
    private val parseTask = Runnable { pendingPackage?.let(::parseCurrentWindow) }
    private var overlayView: ImageView? = null
    private var confirmationPendingUntil = 0L
    private var pendingWechatAmount: String? = null
    private var pendingWechatMerchant: String? = null
    private var pendingWechatUntil = 0L
    private var screenReceiverRegistered = false
    private val unreadableCounts = mutableMapOf<String, Int>()
    private var lastUnreadableAlertAt = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> setOverlayVisible(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> updateOverlayForScreenState()
                ACTION_SETTINGS_CHANGED -> updateOverlayForScreenState()
                ACTION_PAYMENT_CONFIRMED -> {
                    animateOverlaySuccess()
                    sendRecordedNotification(
                        intent.getStringExtra("source") ?: "自动记账",
                        intent.getStringExtra("merchant") ?: "支付商户",
                        intent.getStringExtra("amount") ?: "0.00"
                    )
                }
                ACTION_MESSAGE_PAYMENT_RECORDED -> animateOverlaySuccess()
            }
        }
    }
    private val heartbeatTask = object : Runnable {
        override fun run() {
            getSharedPreferences("service_state", MODE_PRIVATE).edit()
                .putBoolean("service_connected", true)
                .putLong("last_heartbeat", System.currentTimeMillis())
                .apply()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RecognitionLogger.log(this, "service_connected", "无障碍服务已连接，等待支付宝/微信事件", 0)
        handler.removeCallbacks(heartbeatTask)
        heartbeatTask.run()
        registerScreenReceiver()
        showServiceOverlay()
        updateOverlayForScreenState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        if (pkg !in allowedPackages) {
            val eventText = event.text.joinToString(" ")
            val eventLooksLikeResult = successWords.any(eventText::contains) || resultPageWords.any(eventText::contains)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventLooksLikeResult) {
                inspectThirdPartyPaymentWindow(pkg)
                handler.postDelayed({ inspectThirdPartyPaymentWindow(pkg) }, 350)
                handler.postDelayed({ inspectThirdPartyPaymentWindow(pkg) }, 1_200)
            }
            return
        }

        val source = allowedPackages[pkg] ?: pkg
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
            .putLong("last_event", System.currentTimeMillis())
            .putString("last_event_source", source)
            .apply()
        RecognitionLogger.log(this, "event_$pkg", "收到${source}窗口事件（类型 ${event.eventType}）", 1500)

        // 微信支付处理中页面停留很短，立即和短延时各采样一次；最终再等待窗口稳定。
        parseCurrentWindow(pkg)
        handler.postDelayed({ parseCurrentWindow(pkg) }, 120)
        handler.postDelayed({ parseCurrentWindow(pkg) }, 1500)
        handler.postDelayed({ parseCurrentWindow(pkg) }, 2500)
        pendingPackage = pkg
        handler.removeCallbacks(parseTask)
        handler.postDelayed(parseTask, 650)
    }

    private fun inspectThirdPartyPaymentWindow(expectedPackage: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != expectedPackage) return
        val texts = mutableListOf<String>()
        collectVisibleText(root, texts)
        if (texts.isEmpty()) return
        val page = texts.joinToString(" ")
        val hasSuccess = successWords.any(page::contains)
        val hasResultAction = resultPageWords.any(page::contains)
        val hasHistoryMarker = historyPageWords.any(page::contains)
        val amount = findAmount(texts, page)
        if (hasSuccess && hasResultAction && !hasHistoryMarker && amount != null) {
            RecognitionLogger.log(
                this,
                "third_party_candidate_$expectedPackage",
                "发现第三方支付结果页：包名=$expectedPackage，节点=${texts.size}，金额=¥$amount",
                0
            )
            parseCurrentWindow(expectedPackage)
        }
    }

    private fun parseCurrentWindow(expectedPackage: String) {
        val sourceLabel = allowedPackages[expectedPackage] ?: "第三方支付"
        val root = rootInActiveWindow ?: run {
            RecognitionLogger.log(this, "no_root_$expectedPackage", "排除：${sourceLabel}活动窗口内容不可读取，可能是安全窗口或页面尚未完成加载")
            val failures = (unreadableCounts[expectedPackage] ?: 0) + 1
            unreadableCounts[expectedPackage] = failures
            val now = System.currentTimeMillis()
            if (failures >= 3 && now - lastUnreadableAlertAt >= 60_000) {
                lastUnreadableAlertAt = now
                PaymentNotifications.screenUnreadable(this, sourceLabel)
                RecognitionLogger.log(this, "screen_unreadable_alert_$expectedPackage", "提醒：连续无法读取${sourceLabel}页面，已发送系统通知", 0)
            }
            return
        }
        unreadableCounts[expectedPackage] = 0
        val actualPackage = root.packageName?.toString() ?: run {
            RecognitionLogger.log(this, "no_package_$expectedPackage", "排除：窗口没有提供应用包名")
            return
        }
        if (actualPackage != expectedPackage) {
            RecognitionLogger.log(this, "package_changed_$expectedPackage", "排除：采样时窗口已切换到其他应用")
            return
        }
        val texts = mutableListOf<String>()
        collectVisibleText(root, texts)
        val page = texts.joinToString(" ")
        val visibleAmount = findAmount(texts, page)
        val sourceName = allowedPackages[actualPackage] ?: when {
            page.contains("微信支付") -> "微信"
            page.contains("支付宝") -> "支付宝"
            else -> "第三方支付"
        }
        val hasSuccessText = successWords.any(page::contains)
        val prePaymentWords = listOf("确认付款", "立即付款", "输入支付密码", "使用密码", "确认支付")
        val isPrePaymentPage = prePaymentWords.any(page::contains)
        val isWechatBrowsePage = actualPackage == "com.tencent.mm" && listOf(
            "通讯录", "发现", "朋友圈", "我的账单", "支付服务", "摇优惠"
        ).any(page::contains)
        val isWechatConfirmedPayment = actualPackage == "com.tencent.mm" &&
            page.contains("微信支付") &&
            (page.contains("微信红包") || page.contains("转账") || page.contains("收付款"))
        val isWechatSuccessPage = actualPackage == "com.tencent.mm" && hasSuccessText &&
            (page.contains("微信支付") || page.contains("微信红包") || page.contains("转账") || page.contains("收付款"))
        // 微信新版紧凑结果页不再显示“微信支付”字样，只保留状态、商户、金额和“返回商家”。
        val isWechatCompactSuccessPage = actualPackage == "com.tencent.mm" &&
            hasSuccessText && visibleAmount != null && resultPageWords.any(page::contains) &&
            historyPageWords.none(page::contains)
        val now = System.currentTimeMillis()
        val hasRecentWechatPaymentCandidate = actualPackage == "com.tencent.mm" &&
            pendingWechatAmount != null &&
            now <= pendingWechatUntil
        val isWechatFastResultPage = hasRecentWechatPaymentCandidate &&
            page.contains("微信支付") &&
            !isPrePaymentPage
        if (isWechatBrowsePage) {
            RecognitionLogger.log(this, "wechat_browse_page", "排除：当前是微信聊天列表或支付账单历史，不是新的支付结果页")
            return
        }
        if (isPrePaymentPage && !isWechatConfirmedPayment) {
            if (actualPackage == "com.tencent.mm") {
                findAmount(texts, page)?.let { amount ->
                    pendingWechatAmount = amount
                    pendingWechatMerchant = findMerchant(texts)
                    pendingWechatUntil = now + 12_000
                    RecognitionLogger.log(this, "wechat_pending_amount", "暂存：检测到微信付款确认金额 ¥$amount，等待支付结果页", 0)
                }
            }
            RecognitionLogger.log(this, "pre_payment_$actualPackage", "排除：检测到付款确认控件，尚未进入支付成功阶段")
            return
        }
        val accepted = if (actualPackage == "com.tencent.mm") {
            isWechatConfirmedPayment || isWechatSuccessPage || isWechatCompactSuccessPage || isWechatFastResultPage
        } else if (actualPackage !in allowedPackages) {
            hasSuccessText && resultPageWords.any(page::contains) && historyPageWords.none(page::contains)
        } else {
            hasSuccessText
        }
        if (!accepted) {
            val wechatPay = page.contains("微信支付")
            val redPacket = page.contains("微信红包")
            RecognitionLogger.log(
                this,
                "no_marker_$actualPackage",
                "排除：未找到成功或已确认支付标记（节点 ${texts.size}，微信支付=$wechatPay，微信红包=$redPacket，紧凑结果页=$isWechatCompactSuccessPage）"
            )
            return
        }

        val amount = visibleAmount ?: pendingWechatAmount?.takeIf {
            actualPackage == "com.tencent.mm" && isWechatFastResultPage
        } ?: run {
            RecognitionLogger.log(this, "no_amount_$actualPackage", "排除：已找到支付状态，但未读取到金额（节点 ${texts.size}）")
            return
        }
        val merchant = findMerchant(texts)
            ?: pendingWechatMerchant?.takeIf { actualPackage == "com.tencent.mm" && isWechatFastResultPage }
            ?: "${sourceName}商户"
        val database = AccountDb(applicationContext)
        if (database.hasRecentPayment(amount.toDouble(), now - 60_000)) {
            RecognitionLogger.log(this, "recent_duplicate_${sourceName}_$amount", "排除：60 秒内已有相同金额的记录，避免屏幕和消息重复记账", 0)
            return
        }
        val previous = database.recent().firstOrNull()
        if (previous != null && kotlin.math.abs(previous.amount - amount.toDouble()) < 0.001 && now >= confirmationPendingUntil) {
            confirmationPendingUntil = now + 15_000
            val confirmationFingerprint = sha256("confirm|$actualPackage|$merchant|$amount|$now")
            startActivity(Intent(this, DuplicatePaymentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("merchant", merchant)
                putExtra("amount", amount)
                putExtra("source", sourceName)
                putExtra("paidAt", now)
                putExtra("fingerprint", confirmationFingerprint)
            })
            RecognitionLogger.log(this, "same_amount_confirmation", "提示：本次金额与上一笔相同，等待用户确认是否记录", 0)
            return
        }
        val fingerprint = sha256("$actualPackage|$merchant|$amount|${now / 60_000}")
        val inserted = database.insert(merchant, amount, sourceName, now, fingerprint)
        if (inserted) {
            RecognitionLogger.log(this, "inserted_$fingerprint", "成功：已记录 $sourceName · $merchant · ¥$amount", 0)
            clearPendingWechatPayment()
            animateOverlaySuccess()
            sendRecordedNotification(sourceName, merchant, amount)
        } else {
            RecognitionLogger.log(this, "duplicate_$fingerprint", "排除：该支付记录已存在，未重复记账", 0)
            clearPendingWechatPayment()
        }
    }

    private fun clearPendingWechatPayment() {
        pendingWechatAmount = null
        pendingWechatMerchant = null
        pendingWechatUntil = 0L
    }

    private fun findAmount(texts: List<String>, page: String): String? {
        amountRegex.find(page)?.groupValues?.get(1)?.replace(",", "")?.let { return it }
        // 兼容“¥”与“24.88”被拆成相邻无障碍节点的页面。
        for (i in 0 until texts.lastIndex) {
            if (texts[i].trim() in setOf("¥", "￥")) {
                Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?").find(texts[i + 1])?.value
                    ?.replace(",", "")?.let { return it }
            }
        }
        return null
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (!node.isVisibleToUser || node.isPassword || node.isEditable) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collectVisibleText(child, out) }
        }
    }

    private fun findMerchant(texts: List<String>): String? {
        if (texts.any { it.contains("微信红包") }) return "微信红包"
        if (texts.any { it.contains("转账") }) return "微信转账"
        val markers = listOf("商户", "收款方", "付款给", "商品")
        val markerIndex = texts.indexOfFirst { text -> markers.any(text::contains) }
        if (markerIndex >= 0) {
            val inline = texts[markerIndex].substringAfter(':', "").substringAfter('：', "").trim()
            inline.takeIf { it.isNotEmpty() }?.let { return it.take(80) }
            texts.getOrNull(markerIndex + 1)?.takeIf(::isMerchantCandidate)?.let { return it.take(80) }
        }

        // 支付宝新版结果页直接显示公司/店铺名，不再带“商户”标签。
        val businessSuffixes = listOf("有限公司", "公司", "商店", "店铺", "商行", "超市", "餐厅", "科技", "网络")
        return texts.firstOrNull { text -> businessSuffixes.any(text::contains) && isMerchantCandidate(text) }?.take(80)
            ?: texts.firstOrNull(::isMerchantCandidate)?.take(80)
    }

    private fun isMerchantCandidate(text: String): Boolean {
        val value = text.trim()
        if (value.length !in 2..80) return false
        if (successWords.any(value::contains)) return false
        if (value.any(Char::isDigit) || value.contains('¥') || value.contains('￥')) return false
        val excluded = listOf(
            "回首页", "付款方式", "完成", "红包", "积分", "广告", "账单", "一键领", "去领取",
            "关闭", "取消", "确认付款", "立即付款", "确认支付", "使用密码", "输入支付密码"
        )
        return excluded.none(value::contains)
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sendRecordedNotification(source: String, merchant: String, amount: String) {
        if (!getSharedPreferences("budget_settings", MODE_PRIVATE).getBoolean("record_notification_enabled", true)) return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "auto_record_success"
        manager.createNotificationChannel(NotificationChannel(
            channelId,
            "自动记账结果",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "支付信息成功写入账本时提醒"
            enableLights(true)
            lightColor = Color.rgb(233, 130, 158)
        })
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("已自动记账 ¥$amount")
            .setContentText("$source · $merchant")
            .setColor(Color.rgb(233, 130, 158))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun showServiceOverlay() {
        if (overlayView != null) return
        val density = resources.displayMetrics.density
        val size = (42 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val positionPrefs = getSharedPreferences("overlay_position", MODE_PRIVATE)
        val windowManager = getSystemService(WindowManager::class.java)
        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.overlay_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 0, 0, 0)
            alpha = 0.42f
            contentDescription = "自动记账服务运行中，点击查看日志"
            background = null
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positionPrefs.getInt("x", screenWidth - size - (8 * density).toInt()).coerceIn(0, screenWidth - size)
            y = positionPrefs.getInt("y", (screenHeight - size) / 2).coerceIn(0, screenHeight - size)
        }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var moved = false
        val beginDrag = Runnable {
            dragging = true
            bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            bubble.animate().alpha(0.9f).scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
        }
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragging = false; moved = false
                    handler.postDelayed(beginDrag, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, screenWidth - size)
                        params.y = (startY + dy.toInt()).coerceIn(0, screenHeight - size)
                        windowManager.updateViewLayout(bubble, params)
                    } else if (moved) {
                        handler.removeCallbacks(beginDrag)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(beginDrag)
                    if (dragging) {
                        positionPrefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                        bubble.animate().alpha(0.42f).scaleX(1f).scaleY(1f).setDuration(220).start()
                    } else if (!moved) {
                        startActivity(Intent(this@PaymentAccessibilityService, RecognitionLogActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(beginDrag)
                    bubble.animate().alpha(0.42f).scaleX(1f).scaleY(1f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
        try {
            windowManager.addView(bubble, params)
            overlayView = bubble
            RecognitionLogger.log(this, "overlay_visible", "服务悬浮标记已显示，自动记账正在运行", 0)
        } catch (error: Exception) {
            RecognitionLogger.log(this, "overlay_failed", "警告：服务已连接，但悬浮标记显示失败（${error.javaClass.simpleName}）", 0)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_SETTINGS_CHANGED)
            addAction(ACTION_PAYMENT_CONFIRMED)
            addAction(ACTION_MESSAGE_PAYMENT_RECORDED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun updateOverlayForScreenState() {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val enabled = getSharedPreferences("budget_settings", MODE_PRIVATE).getBoolean("overlay_visible_enabled", true)
        setOverlayVisible(enabled && interactive && !locked)
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun animateOverlaySuccess() {
        if (!getSharedPreferences("budget_settings", MODE_PRIVATE).getBoolean("overlay_animation_enabled", true)) return
        val icon = overlayView ?: return
        icon.animate().cancel()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.45f, 0.9f, 1.15f, 1f),
                ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.45f, 0.9f, 1.15f, 1f),
                ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, -14f, 11f, -5f, 0f),
                ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -18f, 0f, -7f, 0f),
                ObjectAnimator.ofFloat(icon, View.ALPHA, 0.42f, 1f, 0.8f, 1f, 0.42f)
            )
            duration = 900
            start()
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(parseTask)
        RecognitionLogger.log(this, "service_interrupted", "无障碍服务已中断", 0)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatTask)
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
            .putBoolean("service_connected", false)
            .putLong("last_heartbeat", 0)
            .apply()
        overlayView?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
            screenReceiverRegistered = false
        }
        RecognitionLogger.log(this, "service_destroyed", "无障碍服务已停止", 0)
        super.onDestroy()
    }
}
