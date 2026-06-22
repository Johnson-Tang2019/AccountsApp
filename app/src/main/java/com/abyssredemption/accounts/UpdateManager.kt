package com.abyssredemption.accounts

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val pageUrl: String,
    val notes: String
)

object UpdateManager {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/Johnson-Tang2019/AccountsApp/releases/latest"

    fun check(currentVersion: String, callback: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            callback(runCatching {
                val connection = openConnection(LATEST_RELEASE_API)
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val latest = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                var apkUrl = ""
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    if (asset.getString("name").endsWith(".apk", true)) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isBlank() || compareVersions(latest, currentVersion) <= 0) null else ReleaseInfo(
                    version = latest,
                    downloadUrl = apkUrl,
                    pageUrl = json.getString("html_url"),
                    notes = json.optString("body")
                )
            })
        }.start()
    }

    fun download(
        context: Context,
        release: ReleaseInfo,
        cancelled: () -> Boolean,
        progress: (Int) -> Unit,
        callback: (Result<File>) -> Unit
    ) {
        Thread {
            callback(runCatching {
                require(URL(release.downloadUrl).host.equals("github.com", true)) { "下载地址不是 GitHub" }
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "AccountsApp-${release.version}.apk")
                val connection = openConnection(release.downloadUrl)
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            if (cancelled()) error("下载已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (total > 0) progress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                require(target.length() > 100_000) { "下载的 APK 文件无效" }
                progress(100)
                target
            })
        }.start()
    }

    private fun openConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "AccountsApp-Android")
        check(responseCode in 200..299) { "GitHub 请求失败：HTTP $responseCode" }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}
