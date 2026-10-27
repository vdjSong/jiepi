package com.jiepi.player.ui.play

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
    // 歌词动态时钟：position（听感轴）直接 × speed 会让非零基准跟着放大，
    // 变速/seek 瞬间歌词狂跳。用锚点锁定基准，只对 position 增量乘速度。
    private var lyricAnchorPos = 0   // 听感 position 锚点
    private var lyricAnchorMs = 0    // 锚点处歌词原曲进度
    private var lastLyricMs = 0      // 最近歌词进度（变速锚定用）
    private var lastSpeed = PlayerManager.speed
    // 详情页当前显示的是第几首歌（自动切歌/手动切歌都靠它检测）
    private var lastSongIndex = -1
    // 播放/暂停图标上次刷的状态（只在真正变化时才重绘，避免周期刷新闪跳）
    private var lastPlaying: Boolean? = null
    // 歌词加载代次：每次找歌词 +1，旧代次的回调（延迟显示/就绪提示）一律丢弃，防止切歌后串台
    private var lyricLoadSeq = 0

    // 歌词本地缓存目录（App 私有目录，不需要任何权限）
    companion object {
        private const val LRC_CACHE_DIR = "jiepi_lyrics_cache"
    }

    // 循环模式 / 定时档位已挪进 PlayerManager（全局状态，换页/切歌不丢）

    // 定时刷新进度的"闹钟"
    private val handler = Handler(Looper.getMainLooper())

    // 每半秒刷新一次进度
    private val progressRunnable = object : Runnable {
        override fun run() {
            refreshProgress()
            handler.postDelayed(this, 250)
        }
    }

    // 系统返回键拦截：跟页面返回键一个行为，回列表页，不乱退桌面
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

        // 拦截系统返回键：跟着详情页一起销毁，不单独移除
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // 页面返回键：能退就退，退不了就回列表页
        binding.btnBack.setOnClickListener {
            if (!findNavController().popBackStack()) {
                findNavController().navigate(R.id.navigation_music)
            }
        }

        // 右上角歌词图标：本地没有歌词时出现，点击下载歌词（下载权交给用户）
        binding.btnDownloadLyric.setOnClickListener { downloadLyric() }

        // 变速：弹圆角小窗选速度（先看UI，真实变速后接）
        binding.btnSpeed.setOnClickListener { showSpeedDialog() }

        // 升降调：弹圆角小窗，加减按钮 ±12 半音（先看UI）
        binding.btnPitch.setOnClickListener { showPitchDialog() }

        // EQ：跳独立页（先看UI）
        binding.btnEqualizer.setOnClickListener {
            findNavController().navigate(R.id.action_play_to_eq)
        }

        // 定时关闭：点一下弹档位卡片
        binding.btnTimer.setOnClickListener { showTimerDialog() }

        // 播放模式：4 个图标轮着切，当前显示哪个就是哪个模式
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

        // 状态栏：全 app 统一黑底白字，MainActivity 已全局设置，这里不再各自保存/恢复

        // 显示当前歌名 + 歌手 + 加载歌词（后台读，不卡界面）
        updateTitleArea()
        lastSongIndex = PlayerManager.currentIndex
        loadLrc()

        // 清理孤儿歌词缓存：歌曲已经没了的，歌词缓存跟着删（后台跑，不影响界面）
        cleanOrphanLyrics()

        // 隐藏底部三个悬浮按钮（不挡详情页控件）
        hideBottomBar()

        // 上一首
        binding.btnPrevious.setOnClickListener {
            PlayerManager.previous(requireContext())
            updateTitleArea()
            loadLrc()
            Toast.makeText(requireContext(), "上一首", Toast.LENGTH_SHORT).show()
        }

        // 播放/暂停：图标随状态变（三角=停着点它播；双竖=播着点它停）
        binding.btnPlayPause.setOnClickListener {
            PlayerManager.pauseOrResume()
            updatePlayIcon()
        }

        // 下一首
        binding.btnNext.setOnClickListener {
            PlayerManager.next(requireContext())
            updateTitleArea()
            loadLrc()
            Toast.makeText(requireContext(), "下一首", Toast.LENGTH_SHORT).show()
        }

        // 进度条：能看能拖
        binding.seekBar.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
            override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.timeCurrent.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(slider: NeonSlider) {
                // 网上标准：拖动开始即硬件静音（setVolume(0) 罩住 buffer 已有数据）+ processor 归零
                PlayerManager.beginSeek()
            }

            override fun onStopTrackingTouch(slider: NeonSlider) {
                PlayerManager.seekTo(slider.progress)
            }
        })

        // 启动：图标摆正 + 进度开始走
        updatePlayIcon()
        handler.post(progressRunnable)
    }

    // 顶部歌名+歌手刷新：详情页统一入口（初始/上下首/自动切歌都走这）
    private fun updateTitleArea() {
        val display = PlayerManager.currentDisplayName()
        binding.songTitle.text = display.ifBlank { "还没有播放歌曲" }
        val artist = PlayerManager.currentArtist()
        // 没歌时副标题留空：避免把"还没有播放歌曲"当歌名、再凑出"未知歌手"；
        // 有歌但读不到歌手时才显示"未知歌手"占位（与列表页口径一致）
        binding.artistTitle.text = if (display.isBlank() || artist.isNullOrBlank()) "" else artist
    }

    // 页面重新可见（从 EQ 页返回/切回来/后台回前台）：强制隐藏悬浮按钮 + 刷一次播放暂停图标。
    // 原因：view 重建后 ImageView src 会回落为 XML 默认的"播放三角"，
    // 而 lastPlaying 缓存恰好等于当前状态时不触发重绘，按钮显示与实际播放状态不符。
    // 按钮：只在 onViewCreated 隐藏一次不够——切页/前后台回来时旧实例可能已被恢复成 VISIBLE，
    // 必须每次可见都强制隐藏（照抄 EqFragment 的兜底写法），别让三悬浮按钮挡主控键。
    override fun onResume() {
        super.onResume()
        hideBottomBar()
        if (_binding != null) {
            lastPlaying = null
            updatePlayIcon()
        }
    }

    // 刷新进度条和时间
    private fun refreshProgress() {
        // 自动切歌（循环/随机/顺序播）时歌名和歌词也要跟着换，不只手动点按钮
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

        // 跟着进度换歌词：走歌词动态时钟（锚定基准，只对增量乘变速系数）
        advanceLyric(position)

        // 定时到点 pause() 后图标要跟着变（只在状态真正变化时重绘，不闪跳）
        updatePlayIcon()
    }

    // 播放/暂停图标：在播显示"双竖杠"，停了显示"三角"
    // 只在状态真正变化时重绘：progressRunnable 每 250ms 也会调它，避免周期刷新闪跳
    private fun updatePlayIcon() {
        val playing = PlayerManager.isPlaying
        if (playing != lastPlaying) {
            lastPlaying = playing
            binding.btnPlayPause.setImageResource(
                if (playing) R.drawable.ic_play_pause else R.drawable.ic_play_play
            )
        }
    }

    // 毫秒转 分:秒
    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    // 找歌词并解析（后台线程读，不卡界面）：
    // 1. 先看本地缓存（App 私有目录，秒读）
    // 2. 无缓存：并发读音频文件内嵌歌词（MP3 USLT / FLAC·OGG LYRICS）+ SAF 找同名 .lrc，谁先出用谁
    // 3. SAF 线性遍历慢：最多等 1.5s，超时先出"暂无歌词"，后台继续等，真找到了再悄悄刷新
    // 4. 每次播放都完整找一遍（不写持久 miss 标记）：下次播放重新扫，用户刚放的歌词文件立刻能发现
    private fun loadLrc() {
        lyrics = emptyList()
        lastLyricIndex = -1
        // 切歌/重载歌词：歌词时钟归零，从新歌起点重新积分
        lyricAnchorPos = 0
        lyricAnchorMs = 0
        lastLyricMs = 0
        lastSpeed = PlayerManager.speed
        val idx = PlayerManager.currentIndex
        if (idx !in PlayerManager.songNames.indices) {
            binding.lyricText.text = "还没有播放歌曲"
            showLyricHint()
            return
        }
        // 歌词文件名 = 歌名去掉扩展名 + .lrc（歌名叫 xxx.mp3 → xxx.lrc）
        val lrcName = PlayerManager.songNames[idx].substringBeforeLast('.') + ".lrc"
        val songPath = PlayerManager.songPaths.getOrNull(idx) ?: ""
        val dirUri = PlayerManager.currentDir()
        // 先显示"正在查找歌词…" + 转圈，后台慢慢读，界面不卡
        binding.lyricText.text = "正在查找歌词…"
        binding.lyricLoading.visibility = View.VISIBLE
        showLyricHint()
        // 用应用级 context 读文件，避免页面关了还拿着旧界面
        val ctx = requireContext().applicationContext
        // 加载代次：防止切歌后旧线程的结果/延迟提示覆盖新歌
        val loadSeq = ++lyricLoadSeq
        val startMs = System.currentTimeMillis()

        Thread {
            var raw: String? = null

            // 1. 先看本地缓存（App 私有目录，秒读）
            val cacheDir = File(ctx.filesDir, LRC_CACHE_DIR)
            val cacheFile = File(cacheDir, lyricCacheName(songPath))
            if (cacheFile.exists()) {
                raw = cacheFile.readText()
            }

            if (raw == null && songPath.isNotEmpty()) {
                // 2. 并发：内嵌歌词 + SAF 同名 .lrc 同时找，谁先出结果用谁
                val embeddedRef = AtomicReference<String?>()
                val safRef = AtomicReference<String?>()
                val latch = CountDownLatch(2)

                Thread {
                    try {
                        val embedded = Id3Reader.readLyrics(ctx, Uri.parse(songPath))
                        if (embedded != null && embedded.isNotBlank()) embeddedRef.set(embedded)
                    } catch (e: Exception) {
                        // 读失败就当没有
                    }
                    latch.countDown()
                }.start()

                Thread {
                    try {
                        if (dirUri.isNotEmpty()) {
                            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri))
                            val lrcFile = dirDoc?.findFile(lrcName)
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
                        // 读失败就当没有，稍后再试
                    }
                    latch.countDown()
                }.start()

                // 3. 最多等 1.5s（SAF 线性遍历最慢，超时不傻等；内嵌毫秒级，通常立刻有结果）
                latch.await(1500, TimeUnit.MILLISECONDS)
                raw = embeddedRef.get() ?: safRef.get()

                if (raw != null) {
                    // 找到了：写缓存，下次秒读
                    try {
                        cacheDir.mkdirs()
                        cacheFile.writeText(raw)
                    } catch (e: Exception) {
                        // 缓存写失败不致命
                    }
                } else if (latch.count > 0) {
                    // 超时兜底：先出"暂无歌词"，后台继续等真结果，找到了再悄悄刷新
                    val seq = loadSeq
                    handler.post {
                        if (!isAdded || _binding == null || seq != lyricLoadSeq) return@post
                        binding.lyricLoading.visibility = View.GONE
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
                            } catch (e: Exception) {
                                // 缓存写失败不致命，下次再写
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

            // 4. 缓存命中 / 并发拿到 / 并发确认都没有 → 直接出结果
            val finalRaw = raw
            handler.post {
                if (!isAdded || _binding == null || loadSeq != lyricLoadSeq) return@post
                applyLyricResult(finalRaw, loadSeq, startMs)
            }
        }.start()
    }

    // 把找到的歌词应用到界面：闪"歌词就绪 · x.xs"提示，短暂延迟后切到歌词框
    private fun applyLyricResult(raw: String?, loadSeq: Int, startMs: Long) {
        if (!isAdded || _binding == null || loadSeq != lyricLoadSeq) return
        binding.lyricLoading.visibility = View.GONE
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
            // 就绪提示闪一下，再切歌词框
            val elapsedMs = System.currentTimeMillis() - startMs
            binding.lyricText.text = "歌词就绪 · " + String.format("%.1f", elapsedMs / 1000f) + "s"
            showLyricHint()
            handler.postDelayed({
                if (!isAdded || _binding == null || loadSeq != lyricLoadSeq) return@postDelayed
                showLyricBox()
            }, 800)
        }
    }

    // 右上角图标点击：下载歌词（LRCLIB 主源 + LrcApi 保底）。
    // 流程：提示【正在适配歌词】→ 后台联网 → 成功写缓存 + 歌曲文件夹同名 .lrc 并重载歌词（自然显示，不再提示）；
    // 失败提示【适配失败】。下载期间禁用图标防连点。
    private fun downloadLyric() {
        // 图标常驻：已有可用歌词/本地有 .lrc 但无时间标签时，点击不重复下载（下载了也用不了/没必要）
        if (lyrics.isNotEmpty() || binding.lyricText.text == "歌词文件里没有时间标签") return
        val idx = PlayerManager.currentIndex
        if (idx !in PlayerManager.songNames.indices) return
        val searchTitle = PlayerManager.id3Titles.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: return
        val searchArtist = PlayerManager.id3Artists.getOrNull(idx).orEmpty()
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
                    cacheFile.writeText(downloaded)  // 缓存统一 UTF-8，下次秒读
                } catch (e: Exception) {
                    // 缓存写失败不致命
                }
                // 歌词写进歌曲文件夹（SAF），卸载软件也不丢——这是用户的财产
                writeLrcToSongDir(ctx, dirUri, lrcName, downloaded)
                // 重载歌词：缓存已就位，自然显示，无需额外提示
                loadLrc()
            }
        }.start()
    }

    // 歌词缓存文件名：用歌曲地址的 MD5，避免地址里的特殊符号当文件名
    private fun lyricCacheName(songPath: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(songPath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".lrc"
    }

    // 把下载到的歌词写进歌曲所在文件夹的同名 .lrc（SAF 授权目录）。
    // 歌词是用户流量换来的财产：必须落在歌曲文件夹里，卸载软件也不丢；
    // 写入失败返回 false（此时只有应用私有缓存，卸载会跟着清掉），调用方需告知用户。
    // 播放页按「歌名去扩展名 + .lrc」在同目录找歌词，写这里下次直接命中；
    // 外部播放器/文件管理器也能看到这份歌词。
    private fun writeLrcToSongDir(ctx: Context, dirUri: String, lrcName: String, content: String): Boolean {
        if (dirUri.isBlank()) return false
        return try {
            val dirDoc = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri)) ?: return false
            var lrcFile = dirDoc.findFile(lrcName)
            if (lrcFile == null || !lrcFile.isFile) {
                lrcFile = dirDoc.createFile("text/plain", lrcName) ?: return false
            }
            val out = ctx.contentResolver.openOutputStream(lrcFile.uri)
            if (out == null) return false
            out.use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            true
        } catch (e: Exception) {
            // 目录只读/权限异常
            false
        }
    }

    // 清理孤儿歌词缓存：跟歌曲清单（MusicFragment 写的磁盘缓存）对一下，
    // 歌曲已经没了的，歌词缓存文件删掉（后台跑，不卡界面）
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
                    // 只清歌词缓存文件；找不到的歌曲下次播放重新扫，不留标记
                    if (f.name.endsWith(".lrc") && f.name !in valid) f.delete()
                }
            } catch (e: Exception) {
                // 清理失败不致命，下次再清
            }
        }.start()
    }

    // 手动解析歌词：把 [分:秒] 或 [分:秒.毫秒] 的标签和文字抠出来（不怕粘贴丢符号）。
    // 方案B 增强兼容：
    //  - 增强LRC：剥掉尖括号词级时间戳 <mm:ss.xx>；同一行≥4个短逐字片段合并回整句（避免逐字拆成单字行）
    //  - 元数据标签：[ti:]/[ar:]/[al:]/[by:] 等字母开头标签只跳过、不产生歌词行
    private fun parseLrc(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
        val wordTag = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")
        val metaTag = Regex("""\[[A-Za-z][^\[\]]*\]""")
        for (line in raw.split('\n')) {
            // 1. 剥尖括号词级时间戳（增强LRC：[00:10.00]<00:10.20>你<00:10.40>好 → [00:10.00]你好）
            var s = line.replace(wordTag, "")
            // 2. 剥元数据标签（[ti:]/[ar:]/[al:]/[by:] 等字母开头，循环剥到没有为止）
            var prev: String
            do {
                prev = s
                s = metaTag.replaceFirst(s, "")
            } while (s != prev)
            // 3. 找时间标签
            val tags = timeTag.findAll(s).toList()
            if (tags.isEmpty()) continue
            // 4. 切片段：每个标签前的文字归属上一个标签；行尾文字归属最后一个标签
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
            // 5. 逐字判定：≥4 个短片段（≤4字符且不含空格）→ 合并成整句（取第一个片段时间）
            if (frags.size >= 4 && frags.all { it.text.length <= 4 && ' ' !in it.text }) {
                result.add(LyricLine(frags.first().timeMs, frags.joinToString("") { it.text }))
            } else {
                for (f in frags) result.add(LyricLine(f.timeMs, f.text))
            }
        }
        return result.sortedBy { it.timeMs }
    }

    // [分:秒.毫秒] → 毫秒时间戳；毫秒位 1 位按百分之一秒、2 位按百分之一秒*10 兼容非标准写法
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

    // 歌词正常：显示两行固定歌词（KTV 对角式），隐藏提示
    private fun showLyricBox() {
        binding.lyricBox.visibility = View.VISIBLE
        binding.lyricText.visibility = View.GONE
        // 开场：前两句先摆好，全是灰色（还没唱，不高亮）
        updateTwoLines(-1)
    }

    // 没有歌词/出错：显示提示，隐藏两行
    private fun showLyricHint() {
        binding.lyricBox.visibility = View.GONE
        binding.lyricText.visibility = View.VISIBLE
    }

    // 歌词动态时钟推进：只对 position 增量乘速度，变速瞬间锚定当前位置不跳变
    private fun advanceLyric(positionMs: Int) {
        if (lyrics.isEmpty()) return
        val speed = PlayerManager.speed
        // 前进跳变 >3s 或回退 = seek/切歌：position 就是新位置的原曲进度，直接锚定
        val seekJump = positionMs < lyricAnchorPos || positionMs - lyricAnchorPos > 3000
        if (lastSpeed != speed) {
            // 变速：锚定到当前歌词进度，保持连续（不瞬间跳变）
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

    // 按播放位置更新当前歌词行
    private fun updateLyric(positionMs: Int) {
        if (lyrics.isEmpty()) return
        var idx = 0
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= positionMs) idx = i else break
        }
        // 还没唱第一句：归为"未唱"状态
        if (lyrics[0].timeMs > positionMs) idx = -1
        if (idx != lastLyricIndex) {
            lastLyricIndex = idx
            updateTwoLines(idx)
        }
    }

    // 两行固定对角显示：上一行偏左、下一行偏右，窗口不动、不滚动
    // 规则：偶数句在上一行亮白（下一句在下一行灰等）；奇数句在下一行亮白（下一句在上一行灰等）
    private fun updateTwoLines(idx: Int) {
        if (idx < 0) {
            // 还没开唱：前两句灰色摆好（直接设置，不播动画）
            binding.lyricTop.text = lyricAt(0)
            binding.lyricBottom.text = lyricAt(1)
            setLyricStyleInstant(binding.lyricTop, false)
            setLyricStyleInstant(binding.lyricBottom, false)
            return
        }
        // 偶数句：当前句在上一行；奇数句：当前句在下一行
        val topIsCurrent = idx % 2 == 0
        val topIdx = if (topIsCurrent) idx else idx + 1
        val bottomIdx = if (topIsCurrent) idx + 1 else idx
        binding.lyricTop.text = lyricAt(topIdx)
        binding.lyricBottom.text = lyricAt(bottomIdx)
        // 高亮柔和过渡：颜色和字号一起平滑渐变，不瞬间跳变
        animateLyricStyle(binding.lyricTop, topIsCurrent)
        animateLyricStyle(binding.lyricBottom, !topIsCurrent)
    }

    // 取第几句歌词文字，越界返回空
    private fun lyricAt(i: Int): String =
        if (i >= 0 && i < lyrics.size) lyrics[i].text else ""

    // 柔和过渡：220ms 内颜色 + 字号一起渐变，加粗瞬间切（主流播放器同款手感）
    private fun animateLyricStyle(tv: TextView, isCurrent: Boolean) {
        val targetColor = if (isCurrent) 0xFFE0E0E0.toInt() else 0xFF888888.toInt()
        val targetSize = if (isCurrent) 20f else 14f
        val startColor = tv.currentTextColor
        val startSize = tv.textSize / resources.displayMetrics.scaledDensity

        // 颜色渐变：灰 ↔ 白
        val colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor)
        colorAnim.duration = 220
        colorAnim.addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
        colorAnim.start()

        // 字号渐变：14sp ↔ 20sp
        val sizeAnim = ValueAnimator.ofFloat(startSize, targetSize)
        sizeAnim.duration = 220
        sizeAnim.addUpdateListener { tv.textSize = it.animatedValue as Float }
        sizeAnim.start()

        // 加粗/正常：瞬间切（字体风格没法渐变，只能这样）
        tv.typeface = if (isCurrent) android.graphics.Typeface.DEFAULT_BOLD
        else android.graphics.Typeface.DEFAULT
    }

    // 开场/直接设置：一步到位，不播动画（注意用 typeface 属性，别再传错类型）
    private fun setLyricStyleInstant(tv: TextView, isCurrent: Boolean) {
        tv.setTextColor(if (isCurrent) 0xFFE0E0E0.toInt() else 0xFF888888.toInt())
        tv.textSize = if (isCurrent) 20f else 14f
        tv.typeface = if (isCurrent) android.graphics.Typeface.DEFAULT_BOLD
        else android.graphics.Typeface.DEFAULT
    }

    // 给详情页弹窗的卡片根视图套上霓虹卡片背景（覆盖 layout 里的纯色底，与全局卡片方案一致）
    private fun styleNeonDialog(dialog: Dialog) {
        val content = dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0)
        root?.background = NeonCard.cardBackground(requireContext())
    }

    // 变速弹窗：圆角卡片，NeonSlider 滑动条 0.5x~2.0x 共 20 格，1.0x 正中间（低段 0.05/档、高段 0.1/档），拖动实时变速
    private fun showSpeedDialog() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_speed)
        styleNeonDialog(dialog)
        // 去掉系统默认白底方框，露出自己的圆角卡片
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
                // 变速不变调：pitch 由 PlayerManager 保持
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

    // 升降调弹窗：圆角卡片，「减 / 当前半音 / 加」三件套，每次 ±1 半音，读单例不归零
    private fun showPitchDialog() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_pitch)
        styleNeonDialog(dialog)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val value = dialog.findViewById<TextView>(R.id.pitch_value)
        // 弹窗内维护目标值：setPitch 走 120ms 去抖、字段延迟回写，直接读单例会慢一拍；
        // 这里以本弹窗的目标值为准，接受调度即刷新显示（与最终生效值一致）
        var current = PlayerManager.pitchSemitones
        val refresh = { value.text = formatSemitone(current) }
        refresh()
        // 变调不变速：speed 由 PlayerManager 保持；点一下 ±1 半音，立即生效并轻震
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

    // 档位数字：progress 0..10 → 0.5+0.05p（0.5x~1.0x，尾数0/5），11..20 → 1.0+0.1(p-10)（1.1x~2.0x 尾数0）
    // 0.5→1.0 十格、1.0→2.0 十格，1.0x 恰好居中
    private fun speedValue(progress: Int): Float =
        if (progress <= 10) 0.5f + progress * 0.05f else 1.0f + (progress - 10) * 0.1f

    private fun formatSpeed(progress: Int): String {
        val v = speedValue(progress)
        // 低档段偶数格尾数0、奇数格尾数5；高档段全部尾数0
        return if (progress <= 10 && progress % 2 == 1) String.format("%.2fx", v)
        else String.format("%.1fx", v)
    }

    private fun formatSemitone(semis: Int): String =
        if (semis > 0) "+$semis" else semis.toString()

    // 跨档轻震动提示：5~10ms、力度小，仅支持 Vibrator 的设备生效
    private fun vibrateLight() {
        val vib = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(6, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(6)
        }
    }

    // 定时关闭弹窗：圆角卡片，6 个档位选一个，选中即 PlayerManager.setTimerMode 启动真实倒计时
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
        // 当前档位高亮（每次按 PlayerManager.timerMode 全量重绘，来源唯一）
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
        // 弹窗打开期间周期同步 timerMode：到点后 PlayerManager 已把档位设回 5（关），
        // 这里定时重绘高亮，旧档位灭掉、回到"关"，真实定时行为不受影响
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

    // 把底部三个悬浮按钮藏起来（先取消动画，防止反弹结束后通过 withEndAction 重新弹回来）
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
        // 离开详情页：底部按钮不在这里恢复——切到歌单/我的页时由它们自己 onResume 拉回，
        // 状态栏也不恢复（全 app 统一黑底白字，MainActivity 全局兜底）
        _binding = null
    }
}
