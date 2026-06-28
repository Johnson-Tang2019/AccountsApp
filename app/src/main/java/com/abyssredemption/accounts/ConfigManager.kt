package com.abyssredemption.accounts

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

data class AppConfig(
    val monthlyBudget: Float,
    val currentBalance: Float,
    val remainingRatio: Int,
    val overlayVisible: Boolean,
    val overlayAnimation: Boolean,
    val recordNotification: Boolean,
    val messageRecognition: Boolean,
    val updateSource: String
)

object ConfigManager {
    fun export(output: OutputStream, config: AppConfig) {
        val json = JSONObject().apply {
            put("format", "AccountsAppConfig")
            put("version", 1)
            put("monthlyBudget", config.monthlyBudget.toDouble())
            put("currentBalance", config.currentBalance.toDouble())
            put("remainingRatio", config.remainingRatio)
            put("overlayVisible", config.overlayVisible)
            put("overlayAnimation", config.overlayAnimation)
            put("recordNotification", config.recordNotification)
            put("messageRecognition", config.messageRecognition)
            put("updateSource", config.updateSource)
        }
        output.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString(2)) }
    }

    fun import(input: InputStream): AppConfig {
        val bytes = input.readBytes()
        require(bytes.size <= 64 * 1024) { "配置文件过大" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.optString("format") == "AccountsAppConfig") { "不是本应用导出的配置文件" }
        require(json.optInt("version") == 1) { "不支持的配置文件版本" }
        val budget = json.getDouble("monthlyBudget").toFloat()
        val balance = json.getDouble("currentBalance").toFloat()
        val ratio = json.getInt("remainingRatio")
        require(budget.isFinite() && budget > 0f) { "月度预算无效" }
        require(balance.isFinite()) { "当前余额无效" }
        require(ratio in 0..90) { "期望剩余比例无效" }
        return AppConfig(
            budget, balance, ratio,
            json.optBoolean("overlayVisible", true),
            json.optBoolean("overlayAnimation", true),
            json.optBoolean("recordNotification", true),
            json.optBoolean("messageRecognition", true),
            json.optString("updateSource", UpdateSource.AUTO.value).takeIf { value ->
                UpdateSource.entries.any { it.value == value }
            } ?: UpdateSource.AUTO.value
        )
    }
}
