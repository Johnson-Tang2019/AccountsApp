package com.abyssredemption.accounts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekDiagnosticsTest {
    @Test
    fun projectionKeepsDiagnosticSignalsAndDropsPrivateData() {
        val from = 1_777_777_700_000L
        fun event(offset: Long, packageName: String) = JSONObject()
            .put("timestampMs", from + offset)
            .put("channel", "screen")
            .put("code", "no_marker")
            .put("packageName", packageName)
            .put("evidence", JSONObject()
                .put("nodeCount", 4)
                .put("attemptId", "original-attempt-id")
                .put("merchant", "private-merchant")
                .put("amount", 18.17)
                .put("ruleId", "private-rule-id"))

        val report = JSONObject().put("events", JSONArray()
            .put(event(100, "com.tencent.mm"))
            .put(event(200, "example.private.app")))
        val projected = DeepSeekDiagnostics.projectReport(report, from, from + 300)
        val events = projected.getJSONArray("events")
        val first = events.getJSONObject(0)
        val second = events.getJSONObject(1)
        assertEquals(100L, first.getLong("offsetMs"))
        assertEquals("com.tencent.mm", first.getString("packageName"))
        assertEquals(4, first.getJSONObject("evidence").getInt("nodeCount"))
        assertEquals(1, first.getJSONObject("evidence").getInt("attemptGroup"))
        assertEquals(1, second.getJSONObject("evidence").getInt("attemptGroup"))
        assertFalse(first.has("timestampMs"))
        assertFalse(second.has("packageName"))
        val payload = projected.toString()
        listOf("private-merchant", "18.17", "private-rule-id", "original-attempt-id").forEach {
            assertFalse(payload.contains(it))
        }
    }

    @Test
    fun acceptsHttpsCompletionsAndRejectsCredentialBearingOrInsecureUrls() {
        assertEquals("https://api.deepseek.com/chat/completions", DeepSeekDiagnostics.endpoint(DeepSeekDiagnostics.DEFAULT_ENDPOINT).toString())
        assertEquals("api.example.com", DeepSeekDiagnostics.endpoint("https://api.example.com/v1/chat/completions").host)
        listOf(
            "http://example.com/chat/completions",
            "https://name:password@example.com/chat/completions",
            "https://example.com/chat/completions?key=password",
            "https://example.com/chat/completions#ignored",
            "https://example.com/other"
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { DeepSeekDiagnostics.endpoint(invalid) } }
    }
}
