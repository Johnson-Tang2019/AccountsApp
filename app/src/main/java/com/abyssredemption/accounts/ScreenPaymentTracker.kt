package com.abyssredemption.accounts

import java.util.UUID

/** Links the payment app's result with the first matching result after returning to a merchant. */
internal class ScreenPaymentTracker {
    data class Window(val packageName: String, val id: Int)
    data class Observation(
        val amount: Double,
        val source: String,
        val merchant: String,
        val paidAtMs: Long,
        val fingerprint: String = UUID.randomUUID().toString(),
        var lastSeenElapsedMs: Long,
        var replayLogged: Boolean = false
    )

    sealed interface Result {
        data class New(val observation: Observation) : Result
        data class Replay(val observation: Observation) : Result
        data class MerchantReturn(val provider: Observation) : Result
    }

    private data class ProviderReceipt(
        val window: Window,
        val observation: Observation,
        val savedElapsedMs: Long,
        var merchantWindow: Window? = null
    )

    private val recentWindows = linkedMapOf<Window, Observation>()
    private var receipt: ProviderReceipt? = null

    fun startPayment(window: Window) {
        recentWindows.keys.removeAll { it.packageName == window.packageName }
        receipt = null
    }

    fun providerRecorded(window: Window, observation: Observation, elapsedMs: Long) {
        receipt = ProviderReceipt(window, observation, elapsedMs)
    }

    fun providerConfirmed(packageName: String, fingerprint: String, elapsedMs: Long) {
        recentWindows.entries.lastOrNull { (window, observation) ->
            window.packageName == packageName && observation.fingerprint == fingerprint
        }?.let { (window, observation) -> providerRecorded(window, observation, elapsedMs) }
    }

    fun observe(
        window: Window,
        providerApp: Boolean,
        amount: Double,
        source: String,
        merchant: String,
        wallTimeMs: Long,
        elapsedMs: Long
    ): Result {
        recentWindows[window]?.takeIf { it.amount == amount }?.let {
            it.lastSeenElapsedMs = elapsedMs
            return Result.Replay(it)
        }
        if (!providerApp) {
            receipt?.takeIf {
                it.window.packageName != window.packageName && it.observation.amount == amount &&
                    it.merchantWindow == null && elapsedMs - it.savedElapsedMs in 0L..12_000L
            }?.let {
                it.merchantWindow = window
                remember(window, it.observation)
                return Result.MerchantReturn(it.observation)
            }
        }
        val previous = recentWindows.entries.lastOrNull { (key, value) ->
            key.packageName == window.packageName && value.amount == amount && value.source == source &&
                elapsedMs - value.lastSeenElapsedMs in 0L..45_000L &&
                PaymentEvidence.sameMerchant(value.merchant, merchant, source)
        }?.value
        if (previous != null) {
            previous.lastSeenElapsedMs = elapsedMs
            remember(window, previous)
            return Result.Replay(previous)
        }
        val created = Observation(amount, source, merchant, wallTimeMs, lastSeenElapsedMs = elapsedMs)
        remember(window, created)
        return Result.New(created)
    }

    private fun remember(window: Window, observation: Observation) {
        recentWindows.remove(window)
        recentWindows[window] = observation
        if (recentWindows.size > 24) recentWindows.remove(recentWindows.keys.first())
    }
}
