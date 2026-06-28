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
    val notes: String,
    val source: UpdateSource
)

enum class UpdateSource(val value: String, val label: String) {
    AUTO("auto", "自动选择"), GITHUB("github", "GitHub"), GITEE("gitee", "Gitee");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

object UpdateManager {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/Johnson-Tang2019/AccountsApp/releases/latest"
    private const val GITEE_VERSION_URL = "https://gitee.com/JohnsonTang2019/AccountsApp/raw/main/releases/latest/README.md"
    private const val GITEE_APK_URL = "https://gitee.com/JohnsonTang2019/AccountsApp/raw/main/releases/latest/app-release.apk"
    private const val GITEE_PAGE_URL = "https://gitee.com/JohnsonTang2019/AccountsApp/tree/main/releases/latest"

    fun check(currentVersion: String, source: UpdateSource, callback: (Result<ReleaseInfo?>) -> Unit) {
        Thread {
            callback(when (source) {
                UpdateSource.GITHUB -> runCatching { checkGitHub(currentVersion) }
                UpdateSource.GITEE -> runCatching { checkGitee(currentVersion) }
                UpdateSource.AUTO -> runCatching { checkGitHub(currentVersion) }
                    .recoverCatching { checkGitee(currentVersion) }
            })
        }.start()
    }

    private fun checkGitHub(currentVersion: String): ReleaseInfo? {
        val response = openConnection(LATEST_RELEASE_API, "GitHub").inputStream.bufferedReader().use { it.readText() }
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
        return if (apkUrl.isBlank() || compareVersions(latest, currentVersion) <= 0) null else ReleaseInfo(
            latest, apkUrl, json.getString("html_url"), json.optString("body"), UpdateSource.GITHUB
        )
    }

    private fun checkGitee(currentVersion: String): ReleaseInfo? {
        val text = openConnection(GITEE_VERSION_URL, "Gitee").inputStream.bufferedReader().use { it.readText() }
        val latest = parseGiteeVersion(text) ?: error("Gitee 版本信息无效")
        return if (compareVersions(latest, currentVersion) <= 0) null else ReleaseInfo(
            latest, GITEE_APK_URL, GITEE_PAGE_URL, "从 Gitee 镜像下载最新正式版本。", UpdateSource.GITEE
        )
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
                val host = URL(release.downloadUrl).host.lowercase()
                require(host == "github.com" || host == "gitee.com") { "下载地址不受信任" }
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "AccountsApp-${release.version}.apk")
                val connection = openConnection(release.downloadUrl, release.source.label)
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

    private fun openConnection(url: String, source: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", if (source == "GitHub") "application/vnd.github+json" else "*/*")
        setRequestProperty("User-Agent", "AccountsApp-Android")
        check(responseCode in 200..299) { "$source 请求失败：HTTP $responseCode" }
    }

    internal fun parseGiteeVersion(text: String): String? =
        Regex("(?:Latest version:\\s*)?v?(\\d+(?:\\.\\d+){1,3})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)

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
