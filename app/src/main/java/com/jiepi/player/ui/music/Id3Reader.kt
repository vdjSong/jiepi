package com.jiepi.player.ui.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.MetadataRetriever
import java.util.concurrent.TimeUnit

/**
 * ID3 标签只读读取器（media3 实现）。
 * 用 media3 官方 MetadataRetriever 免播放提取 title/artist，支持 content:// 直读，
 * 不再拷贝临时文件；media3-exoplayer 1.5.1 已包含该能力，无需新增依赖。
 * 任何异常 / 字段空白一律返回 null，由上层回退文件名显示（列表不空行）。
 * 不做封面、不做任何写修改。
 */
object Id3Reader {

    /**
     * 读单曲 ID3 歌名/歌手（TIT2/TPE1）。
     * 返回 null 表示读不出（非音频/损坏/无标签），调用方回退文件名。
     */
    fun readTitleArtist(ctx: Context, uri: Uri): Pair<String, String>? {
        return try {
            // 官方免播放链路（media3 1.5.x）：MetadataRetriever → TrackGroup → Format.metadata → MediaMetadata
            // 支持 content:// 直读，3 秒超时兜底
            val trackGroups = MetadataRetriever.retrieveMetadata(ctx, MediaItem.fromUri(uri))
                .get(3, TimeUnit.SECONDS)
            if (trackGroups.length == 0) return null
            val format = trackGroups.get(0).getFormat(0)
            val metadata = format.metadata ?: return null
            val mm = MediaMetadata.Builder().populateFromMetadata(metadata).build()
            val title = mm.title?.toString()?.takeIf { it.isNotBlank() }
            val artist = mm.artist?.toString()?.takeIf { it.isNotBlank() }
            // 歌名、歌手都空白：等同无 ID3，回退文件名
            if (title == null && artist == null) null else (title ?: "") to (artist ?: "")
        } catch (e: Exception) {
            null
        }
    }
}
