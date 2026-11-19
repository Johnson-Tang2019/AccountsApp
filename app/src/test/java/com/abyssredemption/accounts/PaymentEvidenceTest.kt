package com.abyssredemption.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentEvidenceTest {
    @Test fun parsesCurrencyWithoutAcceptingPartialOrUnmarkedNumbers() {
        assertEquals("24.88", PaymentEvidence.amount("支付成功 ￥ 24.88"))
        assertEquals("1234.56", PaymentEvidence.amount("已支付1,234.56元"))
        assertEquals("20.80", PaymentEvidence.amount("实付 ¥20.80 · 优惠 5元"))
        assertNull(PaymentEvidence.amount("¥20.888"))
        assertNull(PaymentEvidence.amount("-¥20.80"))
        assertNull(PaymentEvidence.amount("订单号 2026111901"))
    }

    @Test fun requiresAResultHeadingAndDistinguishesNamedMerchants() {
        assertTrue(PaymentEvidence.startsWithMarker("支付成功 · ¥20", listOf("支付成功")))
        assertTrue(PaymentEvidence.startsWithMarker("支付成功20.80元", listOf("支付成功")))
        assertFalse(PaymentEvidence.startsWithMarker("对方说支付成功", listOf("支付成功")))
        assertFalse(PaymentEvidence.sameMerchant("商家甲", "商家乙", "微信"))
        assertTrue(PaymentEvidence.sameMerchant("商家甲", "微信消息记账", "微信"))
    }
}
