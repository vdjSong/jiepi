package com.jiepi.player.ui.music

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream

/**
 * ID3 / Vorbis Comment 只读读取器（轻量实现，替代 media3 MetadataRetriever）。
 * 直接 openInputStream 读文件头部，解析：
 *  - MP3：ID3v2（v2.3/v2.4）的 TIT2（歌名）/ TPE1（歌手）帧
 *  - FLAC / OGG：Vorbis Comment 的 TITLE / ARTIST
 * 不创建播放器实例，单首毫秒级；M4A/AAC/WAV/无标签/解析失败一律返回 null，由上层回退文件名。
 * 不做封面、不做任何写修改。
 */
object Id3Reader {

    // 最多读头部字节：ID3v2 标签常见 < 64KB；Vorbis Comment 常见 < 8KB；256KB 兜底覆盖超大标签
    private const val HEAD_LIMIT = 256 * 1024

    /**
     * 读单曲歌名/歌手。
     * 返回 null 表示读不出（非支持的音频/损坏/无标签），调用方回退文件名。
     */
    fun readTitleArtist(ctx: Context, uri: Uri): Pair<String, String>? {
        return try {
            val head = readHead(ctx, uri) ?: return null
            if (head.size < 4) return null
            when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                    parseId3v2(head)
                head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() ->
                    parseFlacVorbis(head)
                head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() && head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte() ->
                    parseOggVorbis(head)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 读取 ----------

    private fun readHead(ctx: Context, uri: Uri): ByteArray? {
        val resolver = ctx.contentResolver
        return resolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 16 * 1024)
            val buf = ByteArray(HEAD_LIMIT)
            var off = 0
            while (off < HEAD_LIMIT) {
                val n = input.read(buf, off, HEAD_LIMIT - off)
                if (n <= 0) break
                off += n
            }
            buf.copyOfRange(0, off)
        }
    }

    // ---------- ID3v2 (MP3) ----------

    private fun parseId3v2(head: ByteArray): Pair<String, String>? {
        val major = head[3].toInt() and 0xff
        // 只支持 v2.3 / v2.4（v2.2 帧头只有 6 字节且极罕见；v1 在文件尾不在此列）
        if (major != 3 && major != 4) return null
        val tagSize = synchsafe(head, 6)
        var title: String? = null
        var artist: String? = null
        var p = 10
        val end = minOf(head.size, 10 + tagSize)
        while (p + 10 <= end) {
            val frameId = String(head, p, 4, Charsets.ISO_8859_1)
            // v2.4 帧大小是 synchsafe；v2.3 是普通大端整数
            val size = if (major == 4) synchsafe(head, p + 4) else readIntBE(head, p + 4)
            val frameEnd = p + 10 + size
            if (frameEnd > end) break
            if (frameId == "TIT2" || frameId == "TPE1") {
                val enc = head[p + 10].toInt() and 0xff
                val text = decodeId3Text(head, p + 11, size - 1, enc)
                if (text != null) {
                    if (frameId == "TIT2" && title == null) title = text
                    if (frameId == "TPE1" && artist == null) artist = text
                }
                if (title != null && artist != null) break
            }
            p = frameEnd
        }
        return if (title == null && artist == null) null else (title ?: "") to (artist ?: "")
    }

    private fun decodeId3Text(bytes: ByteArray, off: Int, len: Int, enc: Int): String? {
        if (len <= 0) return null
        var s = off
        var e = off + len
        when (enc) {
            // UTF-16（with BOM / BE）：有效数据恒为偶数长度，字节层不做终止符裁剪——
            // 末尾的 00 00 可能是字符高字节(LE下 'e'=65 00)+单字节终止符，成对裁剪会吃字。
            // 统一靠解码后的 '\u0000' 截断（见下方 indexOf）。
            // 仅当奇数长度且末字节为 0 时，裁掉多余的单字节终止符。
            1, 2 -> {
                if ((e - s) % 2 == 1 && bytes[e - 1] == 0.toByte()) e--
            }
            // 单字节编码（ISO-8859-1 / UTF-8）：终止符是 1 字节 00
            else -> {
                while (e > s && bytes[e - 1] == 0.toByte()) e--
            }
        }
        if (e <= s) return null
        val raw = when (enc) {
            0 -> String(bytes, s, e - s, Charsets.ISO_8859_1)
            1 -> { // UTF-16 with BOM
                if (e - s < 2) return null
                val b0 = bytes[s].toInt() and 0xff
                val b1 = bytes[s + 1].toInt() and 0xff
                if (b0 == 0xFE && b1 == 0xFF) String(bytes, s + 2, e - s - 2, Charsets.UTF_16BE)
                else if (b0 == 0xFF && b1 == 0xFE) String(bytes, s + 2, e - s - 2, Charsets.UTF_16LE)
                else String(bytes, s, e - s, Charsets.UTF_16)
            }
            // 规范要求 UTF-16BE 不带 BOM，但个别写入器会违规带上，检测到则跳过
            2 -> {
                if (e - s < 2) return null
                val b0 = bytes[s].toInt() and 0xff
                val b1 = bytes[s + 1].toInt() and 0xff
                if (b0 == 0xFE && b1 == 0xFF) String(bytes, s + 2, e - s - 2, Charsets.UTF_16BE)
                else String(bytes, s, e - s, Charsets.UTF_16BE)
            }
            3 -> String(bytes, s, e - s, Charsets.UTF_8)
            else -> return null
        }
        // v2.4 多值用 null 分隔，只取第一段
        val nullIdx = raw.indexOf('\u0000')
        val text = if (nullIdx >= 0) raw.substring(0, nullIdx) else raw
        return text.takeIf { it.isNotBlank() }
    }

    // ---------- Vorbis Comment (FLAC / OGG) ----------

    private fun parseFlacVorbis(head: ByteArray): Pair<String, String>? {
        var p = 4 // 跳过 "fLaC"
        while (p + 4 <= head.size) {
            val typeByte = head[p].toInt() and 0xff
            val isLast = (typeByte and 0x80) != 0
            val type = typeByte and 0x7f
            val size = readInt24BE(head, p + 1)
            val dataStart = p + 4
            if (dataStart + size > head.size) return null
            if (type == 4) { // VORBIS_COMMENT
                return parseVorbisComment(head, dataStart, dataStart + size)
            }
            if (isLast) return null
            p = dataStart + size
        }
        return null
    }

    private fun parseOggVorbis(head: ByteArray): Pair<String, String>? {
        // 找 comment header 模式：0x03 'v' 'o' 'r' 'b' 'i' 's'
        var p = 4
        while (p + 6 < head.size) {
            if ((head[p].toInt() and 0xff) == 0x03 &&
                head[p + 1] == 'v'.code.toByte() && head[p + 2] == 'o'.code.toByte() &&
                head[p + 3] == 'r'.code.toByte() && head[p + 4] == 'b'.code.toByte() &&
                head[p + 5] == 'i'.code.toByte() && head[p + 6] == 's'.code.toByte()
            ) {
                return parseVorbisComment(head, p + 7, head.size)
            }
            p++
        }
        return null
    }

    private fun parseVorbisComment(data: ByteArray, offset: Int, limit: Int): Pair<String, String>? {
        var p = offset
        if (p + 4 > limit) return null
        val vendorLen = readIntLE(data, p); p += 4
        if (p + vendorLen > limit) return null
        p += vendorLen
        if (p + 4 > limit) return null
        val count = readIntLE(data, p); p += 4
        var title: String? = null
        var artist: String? = null
        for (i in 0 until count) {
            if (p + 4 > limit) break
            val len = readIntLE(data, p); p += 4
            if (p + len > limit) break
            val s = String(data, p, len, Charsets.UTF_8)
            p += len
            val eq = s.indexOf('=')
            if (eq > 0) {
                val key = s.substring(0, eq)
                val value = s.substring(eq + 1)
                if (key.equals("TITLE", true) && title == null) title = value
                if (key.equals("ARTIST", true) && artist == null) artist = value
            }
        }
        return if (title == null && artist == null) null else (title ?: "") to (artist ?: "")
    }

    // ---------- 字节工具 ----------

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    private fun readInt24BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 16) or ((b[off + 1].toInt() and 0xff) shl 8) or
            (b[off + 2].toInt() and 0xff)

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    // ID3v2 的 4 字节 synchsafe 整数（每字节只取低 7 位）
    private fun synchsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7f) shl 21) or ((b[off + 1].toInt() and 0x7f) shl 14) or
            ((b[off + 2].toInt() and 0x7f) shl 7) or (b[off + 3].toInt() and 0x7f)
}
