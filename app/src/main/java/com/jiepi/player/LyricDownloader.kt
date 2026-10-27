package com.jiepi.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 歌词自动下载器：LRCLIB 主源 + LrcApi 保底。
 * - LRCLIB（lrclib.net）：免费免 key 免登录，按「歌名+歌手」搜索，返回带时间轴 LRC（syncedLyrics）。
 * - LrcApi（api.lrc.cx）：免费免鉴权开源聚合接口，兜底提升中文歌命中率。
 * 全部走 HttpURLConnection（与 UpdateManager 一致，不引第三方网络库）。
 * 请求头 User-Agent 固定为 JPlayer/1.1.9（LRCLIB 官方要求，避免被限流）。
 */
object LyricDownloader {

    private const val UA = "JPlayer/1.1.9"
    // 单请求超时 8s→5s：双源并发后总耗时≈最慢源，最坏 5s 出头，比原来串行最坏 16s 快很多
    private const val TIMEOUT_MS = 5000
    // 并发轮询等待比单请求超时略宽（给网络栈一点缓冲）
    private const val WAIT_DEADLINE_MS = 6000L
    private const val LRCLIB_SEARCH = "https://lrclib.net/api/search"
    private const val LRCCX_LYRICS = "https://api.lrc.cx/lyrics"

    /**
     * 下载歌词：LRCLIB 与 LrcApi 双源并发请求，谁先返回带时间轴的歌词就用谁（不再串行等）。
     * 两个源都统一过滤：不带时间轴（纯文本/字幕）的歌词直接丢弃——播放器按时间戳推进显示，用不了。
     * 最坏等待 WAIT_DEADLINE_MS；某源先完成且有效则立即返回，不等慢源。
     * @return 带时间轴的 LRC 文本；都失败/超时返回 null
     */
    fun fetch(artist: String, title: String): String? {
        if (title.isBlank()) return null
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val fLib = pool.submit(Callable<String?> { fetchFromLrcLib(artist, title) })
            val fCx = pool.submit(Callable<String?> { fetchFromLrcCx(artist, title) })
            val futures = mutableListOf(fLib, fCx)
            val deadline = System.currentTimeMillis() + WAIT_DEADLINE_MS
            while (futures.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val it = futures.iterator()
                while (it.hasNext()) {
                    val f = it.next()
                    if (f.isDone) {
                        val text = try { f.get() } catch (e: Exception) { null }
                        if (text != null && hasTimeTag(text)) return text
                        it.remove()
                    }
                }
                if (futures.isNotEmpty()) Thread.sleep(40)
            }
            null
        } finally {
            pool.shutdown()
        }
    }

    // 校验是否带时间轴：[分:秒] 或 [分:秒.毫秒]；没有时间标签的歌词播放器没法按进度显示
    private fun hasTimeTag(text: String): Boolean =
        Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(text)

    // LRCLIB：/api/search?q=歌名+歌手，返回 JSON 数组，取第一条 syncedLyrics 非空的
    private fun fetchFromLrcLib(artist: String, title: String): String? {
        val q = buildString {
            append(title)
            if (artist.isNotBlank()) append(' ').append(artist)
        }
        val url = "$LRCLIB_SEARCH?q=${URLEncoder.encode(q, "UTF-8")}"
        val text = httpGet(url) ?: return null
        return try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val synced = obj.optString("syncedLyrics", "")
                if (synced.isNotBlank()) return synced
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // LrcApi：/lyrics?artist=&title=，直接返回 LRC 文本；兼容 JSON 包装
    private fun fetchFromLrcCx(artist: String, title: String): String? {
        val url = "$LRCCX_LYRICS?artist=${URLEncoder.encode(artist, "UTF-8")}" +
                "&title=${URLEncoder.encode(title, "UTF-8")}"
        val text = httpGet(url) ?: return null
        if (text.isBlank()) return null
        // 个别部署返回 JSON 包装（如 {"lyric": "..."}），兼容解析
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            return try {
                val obj = JSONObject(trimmed)
                val lyric = obj.optString("lyric", "")
                    .ifBlank { obj.optString("lrc", "") }
                lyric.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        return text
    }

    // 通用 GET：带 UA + 超时；非 2xx / 网络异常返回 null
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
