package com.abyssredemption.accounts

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
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
        val checking = if (manual) {
            val density = activity.resources.displayMetrics.density
            val spinner = ProgressBar(activity).also(PinkDialogs::styleProgress)
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padding = (22 * density).toInt()
                setPadding(padding, (8 * density).toInt(), padding, (8 * density).toInt())
                addView(spinner, (34 * density).toInt(), (34 * density).toInt())
                addView(TextView(activity).apply {
                    text = "正在连接 GitHub…"
                    textSize = 14f
                    setTextColor(android.graphics.Color.rgb(61, 52, 55))
                    setPadding((14 * density).toInt(), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            AlertDialog.Builder(activity).setTitle("检查更新").setView(content).setCancelable(false).create()
                .also(PinkDialogs::show)
        } else null

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
        val density = activity.resources.displayMetrics.density
        val summary = release.notes.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }
            .filter { it.isNotBlank() }.take(5).joinToString("\n").take(500)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (22 * density).toInt()
            setPadding(padding, (4 * density).toInt(), padding, 0)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.app_icon)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, (58 * density).toInt(), (58 * density).toInt())
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * density).toInt(), 0, 0, 0)
                    addView(TextView(activity).apply {
                        text = "发现新版本"
                        textSize = 20f
                        setTextColor(android.graphics.Color.rgb(61, 52, 55))
                    })
                    addView(TextView(activity).apply {
                        text = "v$currentVersion  →  v${release.version}"
                        textSize = 13f
                        setTextColor(android.graphics.Color.rgb(201, 79, 104))
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(TextView(activity).apply {
                text = summary.ifBlank { "GitHub 已发布新版本。"
                }
                textSize = 13f
                setTextColor(android.graphics.Color.rgb(97, 80, 86))
                setLineSpacing(0f, 1.2f)
                setPadding(0, (18 * density).toInt(), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setNegativeButton("稍后", null)
            .setNeutralButton("查看发布页") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
            .setPositiveButton("下载更新") { _, _ -> download(activity, release) }
            .create()
        PinkDialogs.show(dialog)
    }

    private fun download(activity: AppCompatActivity, release: ReleaseInfo) {
        val density = activity.resources.displayMetrics.density
        val padding = (22 * density).toInt()
        val percent = TextView(activity).apply { text = "0%"; gravity = Gravity.CENTER; textSize = 13f }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        PinkDialogs.styleProgress(progressBar)
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
        PinkDialogs.show(dialog)

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
