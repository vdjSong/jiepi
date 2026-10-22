package com.jiepi.player.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 更新管理器（联网检查更新）：
 * 检查 GitHub Releases 最新版 → 比版本号 → 有新版本弹窗 → 下载 APK → 调系统安装器覆盖安装。
 * INTERNET 权限仅用于检查更新，检查完即静默，不滥用。
 */
object UpdateManager {

    private const val RELEASE_API = "https://api.github.com/repos/vdjSong/jiepi/releases/latest"
    private const val APK_DIR = "update" // 缓存子目录，对应 res/xml/file_paths.xml 的 cache-path

    /** 检查更新 API 候选：官方直连 + 国内加速代理，依次尝试，成功即停 */
    private val apiCandidates = listOf(
        RELEASE_API,
        "https://github.akams.cn/$RELEASE_API",
        "https://gh-proxy.com/$RELEASE_API",
        "https://ghfast.top/$RELEASE_API"
    )

    /** APK 下载候选：国内加速镜像（前缀代理）+ 官方直连兜底，依次尝试，成功即停 */
    private fun downloadCandidates(rawUrl: String): List<String> = listOf(
        "https://gh-proxy.com/$rawUrl",
        "https://ghfast.top/$rawUrl",
        "https://ghproxy.net/$rawUrl",
        "https://github.akams.cn/$rawUrl",
        "https://ghproxy.homeboyc.cn/$rawUrl",
        rawUrl // 官方直连兜底
    )

    /**
     * 在后台线程查最新版本；回调在主线程。
     * onResult(latestTag, apkUrl, error)：latestTag 不带 v 前缀；error 非空表示检查失败。
     */
    fun checkLatest(
        context: Context,
        onResult: (latestTag: String?, apkUrl: String?, error: String?) -> Unit
    ) {
        Thread {
            var tag: String? = null
            var apkUrl: String? = null
            var err: String? = null
            try {
                for (api in apiCandidates) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(api).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        conn.setRequestProperty("Accept", "application/vnd.github+json")
                        if (conn.responseCode == 200) {
                            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val json = JSONObject(text)
                            val t = json.optString("tag_name", "").removePrefix("v")
                            val assets = json.optJSONArray("assets")
                            var u: String? = null
                            for (i in 0 until (assets?.length() ?: 0)) {
                                val a = assets!!.getJSONObject(i)
                                if (a.optString("name", "").endsWith(".apk")) {
                                    u = a.optString("browser_download_url")
                                    break
                                }
                            }
                            if (t.isNotEmpty() && !u.isNullOrEmpty()) {
                                tag = t
                                apkUrl = u
                                err = null
                                break
                            }
                            err = "服务器返回格式异常"
                        } else {
                            err = "服务器响应异常（HTTP ${conn.responseCode}）"
                        }
                    } catch (e: Exception) {
                        err = "网络异常，检查失败：${e.message ?: "未知错误"}"
                    } finally {
                        conn?.disconnect()
                    }
                }
            } catch (e: Exception) {
                err = "网络异常，检查失败：${e.message ?: "未知错误"}"
            }
            val t = tag
            val u = apkUrl
            val e = err
            (context as? Activity)?.runOnUiThread { onResult(t, u, e) }
        }.start()
    }

    /** 版本号比较：1.1.0 > 1.0.0。返回 true 表示 remote 比当前新 */
    fun isNewer(remote: String?, current: String): Boolean {
        if (remote.isNullOrBlank()) return false
        val r = remote.trim().split(".").mapNotNull { it.toIntOrNull() }
        val c = current.trim().split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /**
     * 后台下载 APK 到应用缓存目录，完成后回调（主线程）。
     * onDone(file, error)：file 为下载好的 APK；error 非空表示失败。
     */
    fun downloadApk(
        context: Context,
        url: String,
        onDone: (file: File?, error: String?) -> Unit
    ) {
        Thread {
            var file: File? = null
            var err: String? = null
            try {
                val dir = File(context.cacheDir, APK_DIR)
                dir.mkdirs()
                file = File(dir, "jiepi-update.apk")
                // 依次尝试国内加速镜像，最后官方直连兜底；任一源成功即停
                for (candidate in downloadCandidates(url)) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(candidate).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("Accept", "application/octet-stream")
                        if (conn.responseCode == 200) {
                            conn.inputStream.use { input ->
                                FileOutputStream(file).use { out -> input.copyTo(out) }
                            }
                            err = null
                            break
                        } else {
                            err = "下载失败（HTTP ${conn.responseCode}）"
                        }
                    } catch (e: Exception) {
                        err = "下载失败：${e.message ?: "未知错误"}"
                    } finally {
                        conn?.disconnect()
                    }
                    // 当前源失败：清掉半截文件再试下一个源
                    if (file.exists()) file.delete()
                }
                if (err != null) file = null
            } catch (e: Exception) {
                err = "下载失败：${e.message ?: "未知错误"}"
                file = null
            }
            val f = file
            val e = err
            (context as? Activity)?.runOnUiThread { onDone(f, e) }
        }.start()
    }

    /** 调系统安装器安装 APK（Android 8+ 会引导用户允许"安装未知应用"） */
    fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开安装器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
