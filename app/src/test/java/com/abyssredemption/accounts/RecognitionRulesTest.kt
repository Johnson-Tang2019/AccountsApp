package com.abyssredemption.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecognitionRulesTest {
    private val paymentRule = """
        {
          "id":"wechat-shop", "channel":"screen", "packageName":"com.tencent.mm", "action":"accept",
          "completionPhrase":"支付成功", "containsAll":["返回商家"], "containsNone":["退款成功"]
        }
    """.trimIndent()

    private fun config(vararg rules: String) = RecognitionRules.parse(
        """{"format":"AccountsRecognitionRules", "version":1, "rules":[${rules.joinToString(",")}]}"""
    )

    @Test
    fun acceptsOnlyScopedSuccessfulPaymentsWithOneDistinctAmount() {
        val config = config(paymentRule)
        val receipt = listOf("支付成功", "¥", "20.80", "已付 20.8 元", "返回商家")
        assertEquals("20.8", config.evaluate("screen", "com.tencent.mm", receipt)?.amount)
        assertNull(config.evaluate("screen", "com.example.shop", receipt))
        assertNull(config.evaluate("notification", "com.tencent.mm", receipt))
        assertNull(config.evaluate("screen", "com.tencent.mm", receipt + "优惠 ¥2.00"))
        assertNull(config.evaluate("screen", "com.tencent.mm", receipt + "退款成功"))
        assertNull(config.evaluate("screen", "com.tencent.mm", receipt - "支付成功"))
        assertNull(config.evaluate("screen", "com.tencent.mm", receipt - "支付成功" + "未支付成功"))
        assertNull(config.evaluate("screen", "com.tencent.mm", receipt - "支付成功" + "支付成功后将通知你"))
    }

    @Test
    fun honorsOrderedRejectionAndAmountAnchorAfterRoundTrip() {
        val reject = """
            {"id":"history", "channel":"screen", "packageName":"com.tencent.mm", "action":"reject", "containsAny":["交易记录"]}
        """.trimIndent()
        val anchoredPayment = paymentRule.replace("\"completionPhrase\"", "\"amountAnchor\":\"实付\",\"completionPhrase\"")
        val config = RecognitionRules.parse(config(reject, anchoredPayment).toJson())
        val receipt = listOf("支付成功", "原价 ￥1,500.90", "实付：", "1,250.90 元", "返回商家")
        assertEquals("reject", config.evaluate("screen", "com.tencent.mm", receipt + "交易记录")?.action)
        assertEquals("1250.9", config.evaluate("screen", "com.tencent.mm", receipt)?.amount)
    }

    @Test
    fun rejectsUnsafeOrInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { config(paymentRule, paymentRule) }
        assertThrows(IllegalArgumentException::class.java) { config(paymentRule.replace("支付成功", "*")) }
        assertThrows(IllegalArgumentException::class.java) { config(paymentRule.replace("containsAll", "containsALL")) }
    }
}
