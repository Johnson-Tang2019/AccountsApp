package com.abyssredemption.accounts

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DuplicatePaymentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_duplicate_payment)
        val merchant = intent.getStringExtra("merchant") ?: "支付商户"
        val amount = intent.getStringExtra("amount") ?: return finish()
        val source = intent.getStringExtra("source") ?: "自动记账"
        val paidAt = intent.getLongExtra("paidAt", System.currentTimeMillis())
        val fingerprint = intent.getStringExtra("fingerprint") ?: return finish()
        val value = amount.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return finish()
        findViewById<TextView>(R.id.duplicateMessage).text = "上一笔账单也是 ¥$amount。\n是否记录本次 $source · $merchant？"
        findViewById<android.view.View>(R.id.cancelDuplicate).setOnClickListener {
            RecognitionLogger.event(this, "screen", "confirmation_declined",
                intent.getStringExtra("observedPackage"), mapOf("attemptId" to fingerprint))
            finish()
        }
        findViewById<android.view.View>(R.id.confirmDuplicate).setOnClickListener {
            val result = AccountDb(this).use {
                it.recordAutomaticPayment(merchant, value, source, paidAt, "screen", fingerprint)
            }
            RecognitionLogger.event(this, "screen", result.name.lowercase(),
                intent.getStringExtra("observedPackage"), mapOf("attemptId" to fingerprint))
            if (result == AutoPaymentResult.RECORDED) sendBroadcast(android.content.Intent(PaymentAccessibilityService.ACTION_PAYMENT_CONFIRMED).apply {
                setPackage(packageName)
                putExtra("merchant", merchant)
                putExtra("amount", amount)
                putExtra("source", source)
            })
            finish()
        }
        setFinishOnTouchOutside(false)
    }
}
