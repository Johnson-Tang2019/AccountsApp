package com.abyssredemption.accounts

internal object PaymentEvidence {
    private const val MONEY = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?"
    private val symbol = Regex("(?<![-−])[¥￥]\\s*($MONEY)(?![0-9.,])")
    private val named = Regex("金额[：:]?\\s*($MONEY)(?![0-9.,])")
    private val yuan = Regex("(?<![0-9.,−-])($MONEY)\\s*元")
    private val markerSeparators = " ：:，,。！!\n\t¥￥"

    fun symbolAmount(text: String): String? = extract(symbol, text)

    fun amount(text: String): String? =
        symbolAmount(text) ?: extract(named, text) ?: extract(yuan, text)

    private fun extract(pattern: Regex, text: String): String? =
        pattern.find(text)?.groupValues?.get(1)?.replace(",", "")

    fun startsWithMarker(text: String, markers: List<String>): Boolean = markers.any {
        text.startsWith(it) && (text.length == it.length || text[it.length].isDigit() || text[it.length] in markerSeparators)
    }

    fun sameMerchant(first: String, second: String, source: String): Boolean {
        val generic = setOf("${source}商户", "${source}消息记账")
        return first.trim().equals(second.trim(), ignoreCase = true) || first in generic || second in generic
    }
}
