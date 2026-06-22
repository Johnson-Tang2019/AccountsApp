package com.example.accounts

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
        findViewById<TextView>(R.id.duplicateMessage).text = "上一笔账单也是 ¥$amount。\n是否记录本次 $source · $merchant？"
        findViewById<android.view.View>(R.id.cancelDuplicate).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.confirmDuplicate).setOnClickListener {
            val inserted = AccountDb(this).use { it.insert(merchant, amount, source, paidAt, fingerprint) }
            if (inserted) sendBroadcast(android.content.Intent(PaymentAccessibilityService.ACTION_PAYMENT_CONFIRMED).apply {
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
