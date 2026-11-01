package com.jiepi.player.ui.play

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jiepi.player.ui.NeonSlider
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.LyricDownloader
import com.jiepi.player.PlayerManager
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentPlayBinding
import com.jiepi.player.ui.NeonCard
import com.jiepi.player.ui.music.Id3Reader
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PlayFragment : Fragment() {

    private var _binding: FragmentPlayBinding? = null
    private val binding get() = _binding!!

    // 歌词：时间戳(毫秒) + 文本
    private data class LyricLine(val timeMs: Long, val text: String)
    private var lyrics = listOf<LyricLine>()
    private var lastLyricIndex = -1
    // 正在提前渐显的下一句索引（-1 无）
    private var pendingFadeIn = -1
    // 歌词渐显/渐隐时长（ms）
    private val fadeInMs = 300L
    private val fadeOutMs = 250L
    // 歌词检索中（拦截下载）
    private var isLyricSearching = false
    // 歌词动态时钟（锚定基准，增量乘速度）
    private var lyricAnchorPos = 0   // 听感 position 锚点
    private var lyricAnchorMs = 0    // 锚点处歌词原曲进度
    private var lastLyricMs = 0      // 最近歌词进度（变速锚定用）
    private var lastSpeed = PlayerManager.speed
    // 当前显示歌曲索引
    private var lastSongIndex = -1
    // 播放图标上次状态（变化才重绘）
    private var lastPlaying: Boolean? = null
    // 歌词加载代次（防切歌串台）
    private var lyricLoadSeq = 0
    // 歌词已就绪的歌索引（Fragment 实例跨视图重建存活，防同首歌离开再返回重扫）
    private var lyricReadyIndex = -1
    // 当前显示歌词对应的用户文件夹指纹（length|lastModified；""=用户文件夹无该 .lrc）
    private var lyricReadyFp = ""

    // 歌词缓存目录（App 私有）
    companion object {
        private const val LRC_CACHE_DIR = "jiepi_lyrics_cache"
    }

    // 循环模式/定时档位在 PlayerManager

    // 定时刷新进度的"闹钟"
    private val handler = Handler(Looper.getMainLooper())

    // 进度轮询
    private val progressRunnable = object : Runnable {
        override fun run() {
            refreshProgress()
            handler.postDelayed(this, 250)
        }
    }

    // 系统返回键：回列表页
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!findNavController().popBackStack()) {
                findNavController().navigate(R.id.navigation_music)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 注册系统返回键拦截
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // 页面返回键
        binding.btnBack.setOnClickListener {
            if (!findNavController().popBackStack()) {
                findNavController().navigate(R.id.navigation_music)
            }
        }

        // 歌词下载按钮
        binding.btnDownloadLyric.setOnClickListener { downloadLyric() }

        // 变速按钮
        binding.btnSpeed.setOnClickListener { showSpeedDialog() }

        // 升降调按钮
        binding.btnPitch.setOnClickListener { showPitchDialog() }

        // EQ 按钮
        binding.btnEqualizer.setOnClickListener {
            findNavController().navigate(R.id.action_play_to_eq)
        }

        // 定时关闭按钮
        binding.btnTimer.setOnClickListener { showTimerDialog() }

        // 播放模式按钮（四图标轮切）
        val loopIcons = intArrayOf(
            R.drawable.ic_play_repeat,
            R.drawable.ic_play_repeat1,
            R.drawable.ic_play_sequent,
            R.drawable.ic_play_shuffle
        )
        binding.btnLoop.setImageResource(loopIcons[PlayerManager.loopMode])
        binding.btnLoop.setOnClickListener {
            PlayerManager.setLoopMode((PlayerManager.loopMode + 1) % loopIcons.size)
            binding.btnLoop.setImageResource(loopIcons[PlayerManager.loopMode])
        }

        // 显示歌名歌手并加载歌词
        updateTitleArea()
        lastSongIndex = PlayerManager.currentIndex
        loadLrc()

        // 清理孤儿歌词缓存
        cleanOrphanLyrics()

        // 隐藏底部悬浮按钮
        hideBottomBar()

        // 上一首按钮
        binding.btnPrevious.setOnClickListener {
            PlayerManager.previous(requireContext())
            updateTitleArea()
            loadLrc()
            Toast.makeText(requireContext(), "上一首", Toast.LENGTH_SHORT).show()
        }

        // 播放/暂停按钮
        binding.btnPlayPause.setOnClickListener {
            PlayerManager.pauseOrResume()
            updatePlayIcon()
        }

        // 下一首按钮
        binding.btnNext.setOnClickListener {
            PlayerManager.next(requireContext())
            updateTitleArea()
            loadLrc()
            Toast.makeText(requireContext(), "下一首", Toast.LENGTH_SHORT).show()
        }

        // 进度条
        binding.seekBar.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
            override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.timeCurrent.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(slider: NeonSlider) {
                // 拖动开始：硬件静音 + processor 归零
                PlayerManager.beginSeek()
            }

            override fun onStopTrackingTouch(slider: NeonSlider) {
                PlayerManager.seekTo(slider.progress)
            }
        })

        // 启动：刷图标 + 起进度轮询
        updatePlayIcon()
        handler.post(progressRunnable)
    }

    // 刷新顶部歌名歌手
    private fun updateTitleArea() {
        val display = PlayerManager.currentDisplayName()
        binding.songTitle.text = display.ifBlank { "还没有播放歌曲" }
        val artist = PlayerManager.currentArtist()
        // 无歌/无歌手时副标题留空
        binding.artistTitle.text = if (display.isBlank() || artist.isNullOrBlank()) "" else artist
    }

    // 回页可见：隐藏悬浮按钮 + 刷新播放图标
    override fun onResume() {
        super.onResume()
        hideBottomBar()
        if (_binding != null) {
            lastPlaying = null
            updatePlayIcon()
        }
    }

    // 页面不可见：星光隐去
    override fun onPause() {
        super.onPause()
        _binding?.starField?.setPlaying(false)
    }

    // 刷新进度
    private fun refreshProgress() {
        // 自动切歌时同步歌名歌词
        if (PlayerManager.currentIndex != lastSongIndex) {
            lastSongIndex = PlayerManager.currentIndex
            updateTitleArea()
            loadLrc()
        }
        val duration = PlayerManager.duration()
        val position = PlayerManager.currentPosition()
        if (duration > 0) {
            binding.seekBar.max = duration
        }
        binding.seekBar.progress = position
        binding.timeCurrent.text = formatTime(position)
        binding.timeTotal.text = formatTime(duration)

        // 跟进度换歌词（动态时钟）
        advanceLyric(position)

        // 定时暂停后刷图标
        updatePlayIcon()
    }

    // 播放/暂停图标（变化才重绘）
    private fun updatePlayIcon() {
        val playing = PlayerManager.isPlaying
        if (playing != lastPlaying) {
            lastPlaying = playing
            binding.btnPlayPause.setImageResource(
                if (playing) R.drawable.ic_play_pause else R.drawable.ic_play_play
            )
        }
        // 星光随播放状态
        binding.starField.setPlaying(playing)
    }

    // 毫秒转 分:秒
    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    // 找歌词并解析（缓存 → 内嵌/同名 .lrc 并发）
    private fun loadLrc() {
        val idx = PlayerManager.currentIndex
        // 同一首歌歌词已就绪：直接恢复显示，不重扫、不重弹提示；后台悄悄校验单文件
        if (idx in PlayerManager.songNames.indices && idx == lyricReadyIndex && lyrics.isNotEmpty()) {
            binding.lyricLoading.visibility = View.GONE
            showLyricBox()
            verifyLyricFileAsync(idx)
            return
        }
        lyrics = emptyList()
        lastLyricIndex = -1
        // 歌词时钟归零
        lyricAnchorPos = 0
        lyricAnchorMs = 0
        lastLyricMs = 0
        lastSpeed = PlayerManager.speed
        if (idx !in PlayerManager.songNames.indices) {
            binding.lyricText.text = "还没有播放歌曲"
            showLyricHint()
            return
        }
        // 检索中拦截下载
        isLyricSearching = true
        // 歌词文件名（歌名去扩展名 + .lrc）
        val lrcName = PlayerManager.songNames[idx].substringBeforeLast('.') + ".lrc"
        val songPath = PlayerManager.songPaths.getOrNull(idx) ?: ""
        val dirUri = PlayerManager.currentDir()
        // 先显示查找中
        binding.lyricText.text = "正在查找歌词…"
        binding.lyricLoading.visibility = View.VISIBLE
        showLyricHint()
        // 用应用级 context
        val ctx = requireContext().applicationContext
        // 加载代次防串台
        val loadSeq = ++lyricLoadSeq
        val startMs = System.currentTimeMillis()

        Thread {
            var raw: String? = null

            // 1. 本地缓存
            val cacheDir = File(ctx.filesDir, LRC_CACHE_DIR)
            val cacheFile = File(cacheDir, lyricCacheName(songPath))
            if (cacheFile.exists()) {
                raw = cacheFile.readText()
            }

            if (raw == null && songPath.isNotEmpty()) {
                // 2. 内嵌 + 同名 .lrc 并发
                val embeddedRef = AtomicReference<String?>()
                val safRef = AtomicReference<String?>()
                val latch = CountDownLatch(2)

                Thread {
                    try {
                        val embedded = Id3Reader.readLyrics(ctx, Uri.parse(songPath))
                        if (embedded != null && embedded.isNotBlank()) embeddedRef.set(embedded)
                    } catch (e: Exception) {
                        // 读失败忽略
                    }
                    latch.countDown()
                }.start()

                Thread {
                    try {
                        if (dirUri.isNotEmpty()) {
                            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri))
                            // 规范 .lrc 优先，历史 .lrc.txt 顺手改名
                            var lrcFile = dirDoc?.findFile(lrcName)
                            if (lrcFile == null || !lrcFile.isFile) {
                                val legacy = dirDoc?.findFile(lrcName + ".txt")
                                if (legacy != null && legacy.isFile) {
                                    legacy.renameTo(lrcName)
                                    lrcFile = dirDoc?.findFile(lrcName) ?: legacy
                                }
                            }
                            if (lrcFile != null && lrcFile.isFile) {
                                val bytes = ctx.contentResolver.openInputStream(lrcFile.uri)
                                    ?.use { it.readBytes() }
                                if (bytes != null && bytes.isNotEmpty()) {
                                    var text = String(bytes, Charsets.UTF_8)
                                    val timeTag = Regex("""\[\d{1,2}:\d{2}""")
                                    if (!timeTag.containsMatchIn(text)) {
                                        text = String(bytes, java.nio.charset.Charset.forName("GBK"))
                                    }
                                    safRef.set(text)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 读失败忽略
                    }
                    latch.countDown()
                }.start()

                // 3. 并发最多等 1.5s
                latch.await(1500, TimeUnit.MILLISECONDS)
                raw = embeddedRef.get() ?: safRef.get()

                if (raw != null) {
                    // 写缓存 + 来源标记（file=用户文件夹 .lrc，embed=内嵌）
                    try {
                        cacheDir.mkdirs()
                        cacheFile.writeText(raw)
                        val src = if (embeddedRef.get() != null) "embed" else "file"
                        File(cacheDir, lyricCacheName(songPath) + ".src").writeText(src)
                    } catch (e: Exception) {
                        // 写失败忽略
                    }
                } else if (latch.count > 0) {
                    // 超时：先出暂无歌词，后台续等
                    val seq = loadSeq
                    handler.post {
                        if (!isAdded || _binding == null || seq != lyricLoadSeq) return@post
                        binding.lyricLoading.visibility = View.GONE
                        // 放开下载
                        isLyricSearching = false
                        lyrics = emptyList()
                        binding.lyricText.text = "暂无歌词"
                        showLyricHint()
                    }
                    Thread {
                        latch.await()
                        val late = embeddedRef.get() ?: safRef.get()
                        if (late != null) {
                            try {
                                cacheDir.mkdirs()
                                cacheFile.writeText(late)
                                val src = if (embeddedRef.get() != null) "embed" else "file"
                                File(cacheDir, lyricCacheName(songPath) + ".src").writeText(src)
                            } catch (e: Exception) {
                                // 写失败忽略
                            }
                            val finalLate = late
                            handler.post {
                                if (!isAdded || _binding == null || seq != lyricLoadSeq) return@post
                                applyLyricResult(finalLate, seq, startMs)
                            }
                        }
                    }.start()
                    return@Thread
                }
            }

            // 4. 汇总出结果（缓存命中直接显示歌词，不弹就绪提示：没实际扫就不提示就绪）
            val finalRaw = raw
            val fromCache = raw != null && cacheFile.exists()
            handler.post {
                if (!isAdded || _binding == null || loadSeq != lyricLoadSeq) return@post
                applyLyricResult(finalRaw, loadSeq, startMs, silent = fromCache)
            }
        }.start()
    }

    // 歌词就绪并显示（silent=true 为后台同步刷新：不弹就绪提示）
    private fun applyLyricResult(raw: String?, loadSeq: Int, startMs: Long, silent: Boolean = false) {
        if (!isAdded || _binding == null || loadSeq != lyricLoadSeq) return
        binding.lyricLoading.visibility = View.GONE
        // 放开下载按钮
        isLyricSearching = false
        if (raw == null) {
            lyrics = emptyList()
            binding.lyricText.text = "暂无歌词"
            showLyricHint()
            return
        }
        val parsed = parseLrc(raw)
        lyrics = parsed
        if (parsed.isEmpty()) {
            binding.lyricText.text = "歌词文件里没有时间标签"
            showLyricHint()
        } else {
            // 记录就绪索引（同首歌返回直接恢复，不重扫不重弹）
            lyricReadyIndex = PlayerManager.currentIndex
            lyricReadyFp = ""
            if (silent) {
                // 后台同步刷新：直接切歌词框，不弹提示
                showLyricBox()
            } else {
                // 就绪提示后切歌词框
                val elapsedMs = System.currentTimeMillis() - startMs
                binding.lyricText.text = "歌词就绪 · " + String.format("%.1f", elapsedMs / 1000f) + "s"
                showLyricHint()
                handler.postDelayed({
                    if (!isAdded || _binding == null || loadSeq != lyricLoadSeq) return@postDelayed
                    showLyricBox()
                }, 800)
            }
            // 后台记录用户文件夹指纹并校验单文件（改则同步、删则清缓存）
            verifyLyricFileAsync(PlayerManager.currentIndex)
        }
    }

    // 后台校验用户文件夹该 .lrc：删除→清缓存重新查；修改→读新内容同步刷新
    // 指纹基准 lyricReadyFp 为"上次加载完成时用户文件夹指纹"（""=无文件）
    private fun verifyLyricFileAsync(idx: Int) {
        val ctx = requireContext().applicationContext
        val lrcName = PlayerManager.songNames[idx].substringBeforeLast('.') + ".lrc"
        val songPath = PlayerManager.songPaths.getOrNull(idx) ?: ""
        val dirUri = PlayerManager.currentDir()
        val cacheDir = File(ctx.filesDir, LRC_CACHE_DIR)
        val cacheFile = File(cacheDir, lyricCacheName(songPath))
        val srcFile = File(cacheDir, lyricCacheName(songPath) + ".src")
        val loadSeqSnapshot = lyricLoadSeq
        Thread {
            try {
                val fp = lyricFingerprint(ctx, dirUri, lrcName)
                val baseFp = lyricReadyFp
                if (baseFp == "") {
                    // 基准未初始化：直接记录当前指纹（不触发刷新）
                    if (idx == lyricReadyIndex) lyricReadyFp = fp
                    return@Thread
                }
                if (fp == baseFp) return@Thread // 没变
                val src = try { if (srcFile.exists()) srcFile.readText().trim() else "" } catch (e: Exception) { "" }
                if (fp.isEmpty()) {
                    // 用户文件夹 .lrc 被删除：缓存若来自用户文件则清（内嵌歌词可重新找到）
                    if (src == "file") {
                        cacheFile.delete()
                        srcFile.delete()
                        handler.post {
                            if (!isAdded || _binding == null) return@post
                            if (PlayerManager.currentIndex != idx || lyricLoadSeq != loadSeqSnapshot) return@post
                            loadLrc()
                        }
                    }
                } else if (src == "file") {
                    // 用户文件夹 .lrc 被修改：读新内容更新缓存并静默刷新
                    val newRaw = readUserLrc(ctx, dirUri, lrcName)
                    if (newRaw != null && newRaw.isNotBlank()) {
                        try {
                            cacheDir.mkdirs()
                            cacheFile.writeText(newRaw)
                            srcFile.writeText("file")
                        } catch (e: Exception) {
                            // 写失败忽略
                        }
                        val fresh = newRaw
                        handler.post {
                            if (!isAdded || _binding == null) return@post
                            if (PlayerManager.currentIndex != idx || lyricLoadSeq != loadSeqSnapshot) return@post
                            applyLyricResult(fresh, lyricLoadSeq, System.currentTimeMillis(), silent = true)
                        }
                    }
                }
                // 更新基准指纹（仅当仍是同一首歌）
                if (idx == lyricReadyIndex) lyricReadyFp = fp
            } catch (e: Exception) {
                // 校验失败忽略
            }
        }.start()
    }

    // 用户文件夹该 .lrc 的指纹（length|lastModified；""=不存在）
    private fun lyricFingerprint(ctx: Context, dirUri: String, lrcName: String): String {
        return try {
            if (dirUri.isEmpty()) return ""
            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri))
            var lrcFile = dirDoc?.findFile(lrcName)
            if (lrcFile == null || !lrcFile.isFile) {
                val legacy = dirDoc?.findFile(lrcName + ".txt")
                if (legacy != null && legacy.isFile) lrcFile = legacy
            }
            if (lrcFile == null || !lrcFile.isFile) return ""
            val len = lrcFile.length()
            val mod = lrcFile.lastModified()
            "$len|$mod"
        } catch (e: Exception) {
            ""
        }
    }

    // 读用户文件夹同名 .lrc 内容（UTF-8，无时间标签则 GBK 兜底）
    private fun readUserLrc(ctx: Context, dirUri: String, lrcName: String): String? {
        return try {
            if (dirUri.isEmpty()) return null
            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri))
            var lrcFile = dirDoc?.findFile(lrcName)
            if (lrcFile == null || !lrcFile.isFile) {
                val legacy = dirDoc?.findFile(lrcName + ".txt")
                if (legacy != null && legacy.isFile) {
                    legacy.renameTo(lrcName)
                    lrcFile = dirDoc?.findFile(lrcName) ?: legacy
                }
            }
            if (lrcFile == null || !lrcFile.isFile) return null
            val bytes = ctx.contentResolver.openInputStream(lrcFile.uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) return null
            var text = String(bytes, Charsets.UTF_8)
            val timeTag = Regex("""\[\d{1,2}:\d{2}""")
            if (!timeTag.containsMatchIn(text)) {
                text = String(bytes, java.nio.charset.Charset.forName("GBK"))
            }
            text
        } catch (e: Exception) {
            null
        }
    }

    // 下载歌词（LRCLIB + LrcApi 保底）
    private fun downloadLyric() {
        // 已有歌词则不重复下载
        if (lyrics.isNotEmpty() || binding.lyricText.text == "歌词文件里没有时间标签") return
        // 检索中不下载
        if (isLyricSearching) {
            Toast.makeText(requireContext(), "正在查找歌词，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = PlayerManager.currentIndex
        if (idx !in PlayerManager.songNames.indices) return
        // 检索词：ID3 优先，缺失按歌名拆歌手-歌名
        val songRaw = PlayerManager.songNames.getOrNull(idx).orEmpty()
        val songBase = songRaw.substringBeforeLast('.').trim()
        val id3Title = PlayerManager.id3Titles.getOrNull(idx)?.trim().orEmpty()
        val id3Artist = PlayerManager.id3Artists.getOrNull(idx)?.trim().orEmpty()
        val noRealId3 = id3Title.isBlank() || id3Title == songRaw || id3Title == songBase
        val (searchTitle, searchArtist) = if (noRealId3) {
            val sep = songBase.indexOfFirst { it == '-' || it == '—' || it == '–' }
            if (sep > 0) {
                songBase.substring(sep + 1).trim() to songBase.substring(0, sep).trim()
            } else {
                songBase to ""
            }
        } else {
            id3Title to id3Artist
        }
        if (searchTitle.isBlank()) return
        val songPath = PlayerManager.songPaths.getOrNull(idx) ?: ""
        val lrcName = PlayerManager.songNames[idx].substringBeforeLast('.') + ".lrc"
        val dirUri = PlayerManager.currentDir()
        val ctx = requireContext().applicationContext
        Toast.makeText(requireContext(), "正在适配歌词", Toast.LENGTH_SHORT).show()
        binding.btnDownloadLyric.isEnabled = false
        val cacheDir = File(ctx.filesDir, LRC_CACHE_DIR)
        val cacheFile = File(cacheDir, lyricCacheName(songPath))
        Thread {
            val downloaded = try {
                LyricDownloader.fetch(searchArtist, searchTitle)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (!isAdded || _binding == null) return@post
                binding.btnDownloadLyric.isEnabled = true
                if (downloaded.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "适配失败", Toast.LENGTH_SHORT).show()
                    return@post
                }
                try {
                    cacheDir.mkdirs()
                    cacheFile.writeText(downloaded)
                    // 下载的歌词会写入歌曲文件夹，来源标记 file
                    File(cacheDir, lyricCacheName(songPath) + ".src").writeText("file")
                } catch (e: Exception) {
                    // 缓存写失败不致命
                }
                // 歌词写入歌曲文件夹（SAF）
                writeLrcToSongDir(ctx, dirUri, lrcName, downloaded)
                // 重载歌词
                loadLrc()
            }
        }.start()
    }

    // 歌词缓存名（歌曲地址 MD5）
    private fun lyricCacheName(songPath: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(songPath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".lrc"
    }

    // 歌词写入歌曲文件夹同名 .lrc（SAF），失败返回 false
    private fun writeLrcToSongDir(ctx: Context, dirUri: String, lrcName: String, content: String): Boolean {
        if (dirUri.isBlank()) return false
        return try {
            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri)) ?: return false
            // 1. 复用已有 .lrc
            var lrcFile = dirDoc.findFile(lrcName)
            // 2. 历史 .lrc.txt 改名
            if (lrcFile == null || !lrcFile.isFile) {
                val legacy = dirDoc.findFile(lrcName + ".txt")
                if (legacy != null && legacy.isFile) {
                    legacy.renameTo(lrcName)
                    lrcFile = dirDoc.findFile(lrcName) ?: legacy
                }
            }
            // 3. 新建（SAF 追加 .txt 则改回）
            if (lrcFile == null || !lrcFile.isFile) {
                lrcFile = dirDoc.createFile("text/plain", lrcName) ?: return false
                if (lrcFile.name != lrcName) {
                    lrcFile.renameTo(lrcName)
                    lrcFile = dirDoc.findFile(lrcName) ?: lrcFile
                }
            }
            if (lrcFile == null || !lrcFile.isFile) return false
            val out = ctx.contentResolver.openOutputStream(lrcFile.uri)
            if (out == null) return false
            out.use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            true
        } catch (e: Exception) {
            // 权限异常
            false
        }
    }

    // 清理孤儿歌词缓存（对照歌曲清单）
    private fun cleanOrphanLyrics() {
        val c = requireContext().applicationContext
        Thread {
            try {
                val songsFile = File(c.filesDir, "jiepi_songs_cache.json")
                if (!songsFile.exists()) return@Thread
                val arr = JSONArray(songsFile.readText())
                val valid = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    valid.add(lyricCacheName(arr.getJSONObject(i).getString("path")))
                }
                val dir = File(c.filesDir, LRC_CACHE_DIR)
                if (!dir.exists()) return@Thread
                dir.listFiles()?.forEach { f ->
                    // 孤儿 .lrc 连同来源标记一起清
                    if (f.name.endsWith(".lrc") && f.name !in valid) {
                        File(dir, f.name + ".src").delete()
                        f.delete()
                    }
                    // 孤儿 .src 标记也清（对应 .lrc 已不在清单）
                    if (f.name.endsWith(".src")) {
                        val lrcName = f.name.removeSuffix(".src")
                        if (lrcName !in valid) f.delete()
                    }
                }
            } catch (e: Exception) {
                // 清理失败忽略
            }
        }.start()
    }

    // 解析 LRC：时间标签 + 剥词级时间戳/元数据标签
    private fun parseLrc(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
        val wordTag = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")
        val metaTag = Regex("""\[[A-Za-z][^\[\]]*\]""")
        for (line in raw.split('\n')) {
            // 1. 剥词级时间戳
            var s = line.replace(wordTag, "")
            // 2. 剥元数据标签
            var prev: String
            do {
                prev = s
                s = metaTag.replaceFirst(s, "")
            } while (s != prev)
            // 3. 找时间标签
            val tags = timeTag.findAll(s).toList()
            if (tags.isEmpty()) continue
            // 4. 切片段归属标签
            data class Frag(val timeMs: Long, val text: String)
            val frags = mutableListOf<Frag>()
            var lastTime = -1L
            var prevEnd = 0
            for (m in tags) {
                val text = s.substring(prevEnd, m.range.first).trim()
                if (text.isNotEmpty() && lastTime >= 0) frags.add(Frag(lastTime, text))
                lastTime = tagToMs(m.groupValues)
                prevEnd = m.range.last + 1
            }
            val tail = s.substring(prevEnd).trim()
            if (tail.isNotEmpty() && lastTime >= 0) frags.add(Frag(lastTime, tail))
            if (frags.isEmpty()) continue
            // 5. 短片段合并整句
            if (frags.size >= 4 && frags.all { it.text.length <= 4 && ' ' !in it.text }) {
                result.add(LyricLine(frags.first().timeMs, frags.joinToString("") { it.text }))
            } else {
                for (f in frags) result.add(LyricLine(f.timeMs, f.text))
            }
        }
        return result.sortedBy { it.timeMs }
    }

    // 时间标签转毫秒（兼容 1/2 位毫秒）
    private fun tagToMs(g: List<String>): Long {
        val min = g[1].toLongOrNull() ?: 0L
        val sec = g[2].toLongOrNull() ?: 0L
        val msPart = if (g.size >= 4) g[3] else ""
        val ms = when (msPart.length) {
            0 -> 0L
            1 -> msPart.toLong() * 100
            2 -> msPart.toLong() * 10
            else -> msPart.take(3).toLong()
        }
        return min * 60000 + sec * 1000 + ms
    }

    // 显示四行歌词
    private fun showLyricBox() {
        binding.lyricBox.visibility = View.VISIBLE
        binding.lyricText.visibility = View.GONE
        resetZigZagLines()
    }

    // 显示歌词提示
    private fun showLyricHint() {
        binding.lyricBox.visibility = View.GONE
        binding.lyricText.visibility = View.VISIBLE
    }

    // 歌词时钟推进（增量乘速度）
    private fun advanceLyric(positionMs: Int) {
        if (lyrics.isEmpty()) return
        val speed = PlayerManager.speed
        // seek/切歌跳变：直接锚定新位置
        val seekJump = positionMs < lyricAnchorPos || positionMs - lyricAnchorPos > 3000
        if (lastSpeed != speed) {
            // 变速：锚定当前进度
            lyricAnchorMs = lastLyricMs
            lyricAnchorPos = positionMs
            lastSpeed = speed
        } else if (seekJump) {
            lyricAnchorMs = positionMs
            lyricAnchorPos = positionMs
        }
        lastLyricMs = lyricAnchorMs + ((positionMs - lyricAnchorPos) * speed).toInt()
        updateLyric(lastLyricMs)
    }

    // 更新当前歌词行
    private fun updateLyric(positionMs: Int) {
        if (lyrics.isEmpty()) return
        var idx = 0
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= positionMs) idx = i else break
        }
        // 未唱状态
        if (lyrics[0].timeMs > positionMs) idx = -1
        // 刷新四行
        updateZigZag(idx, positionMs)
    }

    // 四行之字形轮播（idx%4 选行）
    private fun updateZigZag(idx: Int, positionMs: Int) {
        if (idx < 0) {
            // 未开唱：清空四行
            resetZigZagLines()
            pendingFadeIn = -1
            return
        }

        // 跳变：清空四行重建
        val isJump = idx < lastLyricIndex || idx - lastLyricIndex > 1
        if (isJump) {
            resetZigZagLines()
            pendingFadeIn = -1
        }

        val curLine = zigZagLine(idx)
        // 当前句文本就位
        if (curLine.text != lyricAt(idx)) curLine.text = lyricAt(idx)

        if (idx != lastLyricIndex) {
            // 切句：上一句延后淡出
            val prevIdx = idx - 1
            if (prevIdx >= 0 && !isJump) {
                val prevLine = zigZagLine(prevIdx)
                prevLine.animate().cancel()
                prevLine.animate().alpha(0f).setDuration(fadeOutMs).start()
            }
            lastLyricIndex = idx
            pendingFadeIn = -1
        }

        // 提前渐显下一句
        val nextIdx = idx + 1
        if (nextIdx < lyrics.size && nextIdx != pendingFadeIn) {
            val remain = lyrics[nextIdx].timeMs - positionMs
            if (remain in 1..fadeInMs) {
                val nextLine = zigZagLine(nextIdx)
                nextLine.text = lyricAt(nextIdx)
                nextLine.animate().cancel()
                nextLine.alpha = 0f
                nextLine.animate().alpha(1f).setDuration(remain).start()
                pendingFadeIn = nextIdx
            }
        }

        // 当前句强制全显
        val curAlpha = curLine.alpha
        if (curAlpha < 1f && lyrics[idx].timeMs <= positionMs) {
            curLine.animate().cancel()
            curLine.alpha = 1f
        }
    }

    // 歌词句对应行
    private fun zigZagLine(idx: Int): TextView = when (idx % 4) {
        0 -> binding.lyricLine1
        1 -> binding.lyricLine2
        2 -> binding.lyricLine3
        else -> binding.lyricLine4
    }

    // 清空四行
    private fun resetZigZagLines() {
        val lines = listOf(
            binding.lyricLine1, binding.lyricLine2, binding.lyricLine3, binding.lyricLine4
        )
        for (tv in lines) {
            tv.animate().cancel()
            tv.alpha = 0f
            tv.text = ""
        }
    }

    // 取歌词文本（越界空）
    private fun lyricAt(i: Int): String =
        if (i >= 0 && i < lyrics.size) lyrics[i].text else ""

    // 弹窗套霓虹卡片背景
    private fun styleNeonDialog(dialog: Dialog) {
        val content = dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0)
        root?.background = NeonCard.cardBackground(requireContext())
    }

    // 变速弹窗（0.5x~2.0x）
    private fun showSpeedDialog() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_speed)
        styleNeonDialog(dialog)
        // 去系统白底
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val seek = dialog.findViewById<NeonSlider>(R.id.speed_seek)
        val value = dialog.findViewById<TextView>(R.id.speed_value)
        val s0 = PlayerManager.speed
        seek.progress = if (s0 <= 1.0f) ((s0 - 0.5f) / 0.05f).toInt().coerceIn(0, 10)
        else (10 + ((s0 - 1.0f) / 0.1f).toInt()).coerceIn(10, 20)
        value.text = formatSpeed(seek.progress)
        seek.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
            override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = formatSpeed(progress)
                // 变速不变调
                PlayerManager.setSpeed(speedValue(progress))
                vibrateLight()
            }

            override fun onStartTrackingTouch(slider: NeonSlider) {}

            override fun onStopTrackingTouch(slider: NeonSlider) {
                Toast.makeText(requireContext(), "变速 ${value.text}", Toast.LENGTH_SHORT).show()
            }
        })
        dialog.show()
    }

    // 升降调弹窗（±1 半音）
    private fun showPitchDialog() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_pitch)
        styleNeonDialog(dialog)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val value = dialog.findViewById<TextView>(R.id.pitch_value)
        // 弹窗内维护目标值（避免读单例慢一拍）
        var current = PlayerManager.pitchSemitones
        val refresh = { value.text = formatSemitone(current) }
        refresh()
        // 变调不变速，轻震反馈
        dialog.findViewById<View>(R.id.pitch_minus).setOnClickListener {
            val next = (current - 1).coerceIn(-12, 12)
            if (PlayerManager.setPitch(next)) {
                current = next
                refresh()
                vibrateLight()
            }
        }
        dialog.findViewById<View>(R.id.pitch_plus).setOnClickListener {
            val next = (current + 1).coerceIn(-12, 12)
            if (PlayerManager.setPitch(next)) {
                current = next
                refresh()
                vibrateLight()
            }
        }
        dialog.show()
    }

    // 进度档位 → 速度值
    private fun speedValue(progress: Int): Float =
        if (progress <= 10) 0.5f + progress * 0.05f else 1.0f + (progress - 10) * 0.1f

    private fun formatSpeed(progress: Int): String {
        val v = speedValue(progress)
        // 低档奇数格两位小数
        return if (progress <= 10 && progress % 2 == 1) String.format("%.2fx", v)
        else String.format("%.1fx", v)
    }

    private fun formatSemitone(semis: Int): String =
        if (semis > 0) "+$semis" else semis.toString()

    // 轻震动提示
    private fun vibrateLight() {
        val vib = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(6, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(6)
        }
    }

    // 定时关闭弹窗（6 档）
    private fun showTimerDialog() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_timer)
        styleNeonDialog(dialog)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val options = listOf(
            dialog.findViewById<TextView>(R.id.timer_15),
            dialog.findViewById<TextView>(R.id.timer_30),
            dialog.findViewById<TextView>(R.id.timer_45),
            dialog.findViewById<TextView>(R.id.timer_60),
            dialog.findViewById<TextView>(R.id.timer_after_current),
            dialog.findViewById<TextView>(R.id.timer_off)
        )
        // 当前档位高亮
        fun applyHighlight() {
            val mode = PlayerManager.timerMode.coerceIn(0, 5)
            for (i in options.indices) {
                options[i].isSelected = (i == mode)
                options[i].setTextColor(if (i == mode) NeonCard.DARK_TEXT else NeonCard.GRAY_TEXT)
            }
        }
        applyHighlight()
        for (tv in options) {
            tv.setOnClickListener {
                PlayerManager.applyTimerMode(options.indexOf(tv))
                applyHighlight()
                Toast.makeText(requireContext(), "定时 ${tv.text}", Toast.LENGTH_SHORT).show()
            }
        }
        // 弹窗期间周期同步档位高亮
        val timerSyncRunnable = object : Runnable {
            override fun run() {
                if (dialog.isShowing) {
                    applyHighlight()
                    handler.postDelayed(this, 250)
                }
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(timerSyncRunnable) }
        dialog.show()
        handler.post(timerSyncRunnable)
    }

    // 隐藏底部悬浮按钮
    private fun hideBottomBar() {
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            val btn = requireActivity().findViewById<View>(id) ?: continue
            btn.animate().cancel()
            btn.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(progressRunnable)
        // 星光释放
        _binding?.starField?.release()
        // 底部按钮/状态栏由各页自行恢复
        _binding = null
    }
}
