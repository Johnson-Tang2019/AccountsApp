package com.abyssredemption.accounts

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecognitionLogger {
    private const val FILE_NAME = "recognition.log"
    private const val MAX_LINES = 200
    private val recentKeys = mutableMapOf<String, Long>()

    @Synchronized
    fun log(context: Context, key: String, message: String, throttleMs: Long = 3000) {
        val now = System.currentTimeMillis()
        val last = recentKeys[key] ?: 0L
        if (now - last < throttleMs) return
        recentKeys[key] = now
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(now))
        val old = read(context).lineSequence().filter { it.isNotBlank() }.toMutableList()
        old += "$time  $message"
        val kept = old.takeLast(MAX_LINES)
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use {
            it.write(kept.joinToString("\n"))
        }
    }

    @Synchronized
    fun read(context: Context): String = try {
        context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }
    } catch (_: Exception) { "" }

    @Synchronized
    fun clear(context: Context) {
        context.deleteFile(FILE_NAME)
        recentKeys.clear()
    }
}
