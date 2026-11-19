package com.abyssredemption.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPaymentTrackerTest {
    private val wechat = ScreenPaymentTracker.Window("com.tencent.mm", 13431)
    private val jd = ScreenPaymentTracker.Window("com.jingdong.app.mall", 13434)
    private val tracker = ScreenPaymentTracker()

    @Test fun jdReturnReusesWechatAttemptEvenWhenMerchantTextChanges() {
        val payment = result(wechat, 0) as ScreenPaymentTracker.Result.New
        tracker.providerRecorded(wechat, payment.observation, 0)
        assertTrue(result(wechat, 11) is ScreenPaymentTracker.Result.Replay)
        val returned = result(jd, 3_688, "京东") as ScreenPaymentTracker.Result.MerchantReturn
        assertEquals(payment.observation.fingerprint, returned.provider.fingerprint)
        val replay = result(jd, 28_821, "商品详情") as ScreenPaymentTracker.Result.Replay
        assertEquals(payment.observation.fingerprint, replay.observation.fingerprint)
    }

    @Test fun realNewCheckoutRearmsSameWindowAndAmount() {
        val first = result(wechat, 0) as ScreenPaymentTracker.Result.New
        tracker.providerRecorded(wechat, first.observation, 0)
        assertTrue(result(jd, 3_688) is ScreenPaymentTracker.Result.MerchantReturn)
        tracker.startPayment(jd)
        assertTrue(result(jd, 6_000) is ScreenPaymentTracker.Result.New)
        tracker.startPayment(wechat)
        assertTrue(result(wechat, 7_000) is ScreenPaymentTracker.Result.New)
    }

    @Test fun unrelatedOrLateResultDoesNotClaimProviderReceipt() {
        val payment = result(wechat, 0) as ScreenPaymentTracker.Result.New
        tracker.providerRecorded(wechat, payment.observation, 0)
        assertTrue(result(jd, 6_000, amount = 17.0) is ScreenPaymentTracker.Result.New)
        assertTrue(result(jd, 12_001) is ScreenPaymentTracker.Result.New)
    }

    private fun result(
        window: ScreenPaymentTracker.Window, elapsed: Long, merchant: String = "测试商家", amount: Double = 20.8
    ): ScreenPaymentTracker.Result {
        val provider = window == wechat
        return tracker.observe(window, provider, amount, if (provider) "微信" else "第三方支付", merchant, elapsed, elapsed)
    }
}
