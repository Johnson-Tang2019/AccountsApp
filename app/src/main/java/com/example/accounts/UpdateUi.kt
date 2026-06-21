package com.example.accounts

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object UpdateUi {
    fun checkForUpdates(activity: AppCompatActivity, manual: Boolean) {
        val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        val checking = if (manual) AlertDialog.Builder(activity)
            .setTitle("检查更新")
            .setMessage("正在连接 GitHub…")
            .setCancelable(false)
            .create().also(AlertDialog::show) else null

        UpdateManager.check(currentVersion) { result ->
            activity.runOnUiThread {
                checking?.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { release ->
                    if (release == null) {
                        if (manual) Toast.makeText(activity, "当前已是最新版本 $currentVersion", Toast.LENGTH_SHORT).show()
                    } else showUpdateDialog(activity, currentVersion, release)
                }.onFailure { error ->
                    if (manual) Toast.makeText(activity, "检查失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUpdateDialog(activity: AppCompatActivity, currentVersion: String, release: ReleaseInfo) {
        val summary = release.notes.lineSequence().filter { it.isNotBlank() }.take(5).joinToString("\n").take(500)
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${release.version}")
            .setMessage("当前版本：$currentVersion\n\n${summary.ifBlank { "GitHub 已发布新版本。" }}")
            .setNegativeButton("稍后", null)
            .setNeutralButton("查看发布页") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
            .setPositiveButton("下载更新") { _, _ -> download(activity, release) }
            .show()
    }

    private fun download(activity: AppCompatActivity, release: ReleaseInfo) {
        val density = activity.resources.displayMetrics.density
        val padding = (22 * density).toInt()
        val percent = TextView(activity).apply { text = "0%"; gravity = Gravity.CENTER; textSize = 13f }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(progressBar, LinearLayout.LayoutParams.MATCH_PARENT, (12 * density).toInt())
            addView(percent, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 ${release.version}")
            .setView(content)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .create()
        dialog.show()

        UpdateManager.download(activity, release, cancelled::get, { value ->
            activity.runOnUiThread { progressBar.progress = value; percent.text = "$value%" }
        }) { result ->
            activity.runOnUiThread {
                dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { install(activity, it) }
                    .onFailure { error -> if (!cancelled.get()) Toast.makeText(activity, "下载失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun install(activity: AppCompatActivity, apk: File) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "请允许此应用安装更新，然后再次点击检查更新", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
