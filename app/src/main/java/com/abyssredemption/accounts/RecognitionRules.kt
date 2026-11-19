package com.abyssredemption.accounts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal

data class RecognitionRuleMatch(
    val action: String,
    val ruleId: String,
    val amount: String? = null,
    val merchant: String? = null
)

class RecognitionRules private constructor(private val rules: List<Rule>) {
    private data class Rule(
        val id: String,
        val enabled: Boolean,
        val channel: String,
        val packageName: String,
        val action: String,
        val containsAll: List<String>,
        val containsAny: List<String>,
        val containsNone: List<String>,
        val completionPhrase: String?,
        val amountAnchor: String?,
        val merchant: String?
    )

    val ruleCount: Int get() = rules.size
    val enabledCount: Int get() = rules.count { it.enabled }

    fun hasRulesFor(channel: String, packageName: String): Boolean =
        rules.any { it.enabled && it.channel == channel && it.packageName == packageName }

    fun evaluate(channel: String, packageName: String, parts: List<String>): RecognitionRuleMatch? {
        if (!hasRulesFor(channel, packageName)) return null
        val input = parts.asSequence().take(160).map { it.trim().take(1024) }
            .filter { it.isNotEmpty() }.joinToString("\n").take(8192)
        val text = input.replace('\n', ' ')
        if (text.isEmpty()) return null
        for (rule in rules) {
            if (!rule.enabled || rule.channel != channel || rule.packageName != packageName) continue
            if (!rule.containsAll.all(text::contains)) continue
            if (rule.containsAny.isNotEmpty() && rule.containsAny.none(text::contains)) continue
            if (rule.containsNone.any(text::contains)) continue
            if (rule.action == "reject") return RecognitionRuleMatch(rule.action, rule.id)
            val completionPhrase = requireNotNull(rule.completionPhrase)
            if (input.lineSequence().none { hasCompletionPhrase(it.trimStart(), completionPhrase) }) continue
            val amountText = if (rule.amountAnchor == null) text else {
                val index = text.indexOf(rule.amountAnchor)
                if (index < 0) continue
                text.substring(index + rule.amountAnchor.length).trimStart()
                    .trimStart(':', '：').trimStart().take(32)
            }
            val amount = parseAmount(amountText, anchored = rule.amountAnchor != null) ?: continue
            return RecognitionRuleMatch(rule.action, rule.id, amount, rule.merchant)
        }
        return null
    }

    private fun hasCompletionPhrase(line: String, phrase: String): Boolean {
        if (!line.startsWith(phrase)) return false
        val next = line.getOrNull(phrase.length) ?: return true
        return next.isWhitespace() || next.isDigit() || next in "：:，,。！!¥￥"
    }

    fun toJson(): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("rules", JSONArray().apply {
            rules.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("enabled", rule.enabled)
                    put("channel", rule.channel)
                    put("packageName", rule.packageName)
                    put("action", rule.action)
                    put("containsAll", JSONArray(rule.containsAll))
                    put("containsAny", JSONArray(rule.containsAny))
                    put("containsNone", JSONArray(rule.containsNone))
                    rule.completionPhrase?.let { put("completionPhrase", it) }
                    rule.amountAnchor?.let { put("amountAnchor", it) }
                    rule.merchant?.let { put("merchant", it) }
                })
            }
        })
    }.toString(2)

    companion object {
        private const val FORMAT = "AccountsRecognitionRules"
        private const val VERSION = 1
        private const val MAX_BYTES = 64 * 1024
        private const val MAX_RULES = 32
        private const val PREFS = "recognition_rules"
        private const val KEY = "rules_v1"
        private val ruleFields = setOf(
            "id", "enabled", "channel", "packageName", "action", "containsAll", "containsAny",
            "containsNone", "completionPhrase", "amountAnchor", "merchant"
        )
        private val idPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}")
        private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        private const val NUMBER = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,9})(?:\\.[0-9]{1,2})?"
        private val currencyAmount = Regex("(?<![-−])[¥￥]\\s*($NUMBER)(?![0-9.,])|(?<![0-9.,¥￥−-])($NUMBER)\\s*元")
        private val firstAmount = Regex("^(?:[¥￥]\\s*)?($NUMBER)(?![0-9.,])")
        private val template = RecognitionRules(listOf(Rule(
            id = "example-wechat-receipt",
            enabled = false,
            channel = "screen",
            packageName = "com.tencent.mm",
            action = "accept",
            containsAll = listOf("返回商家"),
            containsAny = emptyList(),
            containsNone = listOf("交易记录", "我的账单"),
            completionPhrase = "支付成功",
            amountAnchor = null,
            merchant = null
        )))

        @Volatile private var cachedConfig: Pair<String, RecognitionRules>? = null

        fun load(context: Context): RecognitionRules {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return template
            cachedConfig?.let { if (it.first == json) return it.second }
            return runCatching { parse(json) }.getOrDefault(template).also { cachedConfig = json to it }
        }

        fun save(context: Context, config: RecognitionRules) {
            val json = config.toJson()
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            check(preferences.edit().putString(KEY, json).commit()) { "无法保存识别规则" }
            cachedConfig = json to config
        }

        fun read(input: InputStream): RecognitionRules {
            val buffer = ByteArray(4096)
            val bytes = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(bytes.size() + count <= MAX_BYTES) { "识别规则文件不能超过 64 KB" }
                bytes.write(buffer, 0, count)
            }
            return parse(bytes.toString(Charsets.UTF_8.name()))
        }

        fun parse(raw: String): RecognitionRules {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "识别规则文件不能超过 64 KB" }
            val text = raw.removePrefix("\uFEFF")
            requireReasonableDepth(text)
            val tokenizer = JSONTokener(text)
            val root = tokenizer.nextValue() as? JSONObject ?: error("识别规则必须是 JSON 对象")
            require(tokenizer.nextClean() == '\u0000') { "JSON 对象后有多余内容" }
            require(root.keys().asSequence().all { it in setOf("format", "version", "rules", "\$schema") }) { "存在未知的配置字段" }
            require(!root.has("\$schema") || root.opt("\$schema") is String) { "\$schema 必须是字符串" }
            require(root.opt("format") == FORMAT && root.opt("version") == VERSION) { "不支持的识别规则格式或版本" }
            val array = root.optJSONArray("rules") ?: error("rules 必须是数组")
            require(array.length() <= MAX_RULES) { "最多允许 32 条识别规则" }
            val ids = mutableSetOf<String>()
            val config = RecognitionRules(List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: error("第 ${index + 1} 条规则必须是 JSON 对象")
                val rule = parseRule(item, index + 1)
                require(ids.add(rule.id)) { "规则 ID 重复：${rule.id}" }
                rule
            })
            require(config.toJson().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "展开后的规则配置不能超过 64 KB" }
            return config
        }

        private fun parseRule(item: JSONObject, index: Int): Rule {
            val label = "第 $index 条规则"
            require(item.keys().asSequence().all { it in ruleFields }) { "$label 存在未知字段" }
            val id = item.opt("id") as? String
            require(id != null && idPattern.matches(id)) { "$label 的 id 无效" }
            val enabled = if (item.has("enabled")) item.opt("enabled") as? Boolean else true
            require(enabled != null) { "$id 的 enabled 必须是布尔值" }
            val channel = item.opt("channel") as? String
            require(channel != null && (channel == "screen" || channel == "notification")) { "$id 的 channel 无效" }
            val packageName = item.opt("packageName") as? String
            require(packageName != null && packageName.length <= 160 && packagePattern.matches(packageName)) { "$id 的 packageName 无效" }
            val action = item.opt("action") as? String
            require(action != null && (action == "accept" || action == "reject")) { "$id 的 action 无效" }
            fun literal(field: String, value: Any?): String {
                require(value is String && value.length in 1..80 && value == value.trim() && value.none { it.isISOControl() }) {
                    "$id 的 $field 必须是 1–80 个字符的非空文字"
                }
                return value
            }
            fun optional(field: String) = if (item.has(field)) literal(field, item.opt(field)) else null
            fun terms(field: String): List<String> {
                if (!item.has(field)) return emptyList()
                val values = item.optJSONArray(field) ?: error("$id 的 $field 必须是数组")
                require(values.length() <= 12) { "$id 的 $field 最多允许 12 项" }
                return List(values.length()) { literal(field, values.opt(it)) }
            }
            val all = terms("containsAll")
            val any = terms("containsAny")
            val none = terms("containsNone")
            val completion = optional("completionPhrase")
            val anchor = optional("amountAnchor")
            val merchant = optional("merchant")
            if (action == "accept") {
                require(completion != null && completion.count(Char::isLetter) >= 3) {
                    "$id 的 completionPhrase 必须是明确的支付完成文案"
                }
            } else {
                require(all.isNotEmpty() || any.isNotEmpty()) { "$id 的拒绝规则至少需要一个正向匹配条件" }
                require(completion == null && anchor == null && merchant == null) { "$id 的拒绝规则不能指定入账字段" }
            }
            return Rule(id, enabled, channel, packageName, action, all, any, none, completion, anchor, merchant)
        }

        private fun parseAmount(text: String, anchored: Boolean): String? {
            val values = if (anchored) listOfNotNull(firstAmount.find(text)?.groupValues?.get(1)) else {
                currencyAmount.findAll(text).map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }.toList()
            }
            val amounts = values.mapNotNull { value ->
                value.replace(",", "").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("999999999.99") }
                    ?.stripTrailingZeros()?.toPlainString()
            }.distinct()
            return amounts.singleOrNull()
        }

        private fun requireReasonableDepth(text: String) {
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (char in text) {
                if (quote != null) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == quote) quote = null
                } else when (char) {
                    '"' -> quote = char
                    '\'', '#', '/' -> error("规则文件请使用标准 JSON 格式")
                    '[', '{' -> require(++depth <= 8) { "JSON 嵌套过深" }
                    ']', '}' -> require(--depth >= 0) { "JSON 结构无效" }
                }
            }
            require(depth == 0 && quote == null) { "JSON 结构不完整" }
        }
    }
}
