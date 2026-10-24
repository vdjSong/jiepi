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
import com.jiepi.player.PlayerManager
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentPlayBinding
import com.jiepi.player.ui.NeonCard
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

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

        // 变速：弹圆角小窗选速度（先看UI，真实变速后接）
        binding.btnSpeed.setOnClickListener { showSpeedDialog() }

        // 升降调：弹圆角小窗，加减按钮 ±12 半音（先看UI）
        binding.btnPitch.setOnClickListener { showPitchDialog() }

        // EQ：跳独立页（先看UI）
        binding.btnEqualizer.setOnClickListener {
            findNavController().navigate(R.id.action_play_to_eq)
        }

        // 定时关闭：点一下弹档位卡片（原更多卡片入口，直接上排）
        binding.btnTimer.setOnClickListener { showTimerDialog() }

        // 播放模式：4 个图标轮着切（原更多卡片入口，直接上排），当前显示哪个就是哪个模式
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
    // 2. 没有缓存：走 SAF 从歌所在文件夹找同名 .lrc，找到就写本地缓存，下次秒读
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
        // 先显示"加载中"，后台慢慢读，界面不卡
        binding.lyricText.text = "歌词加载中…"
        showLyricHint()
        // 用应用级 context 读文件，避免页面关了还拿着旧界面
        val ctx = requireContext().applicationContext

        Thread {
            var raw: String? = null

            // 1. 先看本地缓存（App 私有目录，秒读）
            val cacheDir = File(ctx.filesDir, LRC_CACHE_DIR)
            val cacheFile = File(cacheDir, lyricCacheName(songPath))
            if (cacheFile.exists()) {
                raw = cacheFile.readText()
            }

            // 2. 没有缓存：走 SAF 从歌所在文件夹找同名 .lrc（后台读），找到就写本地缓存
            if (raw == null && dirUri.isNotEmpty()) {
                try {
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
                            raw = text
                            try {
                                cacheDir.mkdirs()
                                cacheFile.writeText(text)  // 统一按 UTF-8 存，下次直接秒读
                            } catch (e: Exception) {
                                // 缓存写失败不致命，下次再写
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 读失败就当没有，稍后再试
                }
            }

            val finalRaw = raw
            // 回主线程更新界面（页面可能已经关了，先检查再动）
            handler.post {
                if (!isAdded || _binding == null) return@post
                if (finalRaw == null) {
                    lyrics = emptyList()
                    binding.lyricText.text = "暂无歌词"
                    showLyricHint()
                    return@post
                }
                val parsed = parseLrc(finalRaw)
                lyrics = parsed
                if (parsed.isEmpty()) {
                    binding.lyricText.text = "歌词文件里没有时间标签"
                    showLyricHint()
                } else {
                    showLyricBox()
                }
            }
        }.start()
    }

    // 歌词缓存文件名：用歌曲地址的 MD5，避免地址里的特殊符号当文件名
    private fun lyricCacheName(songPath: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(songPath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".lrc"
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
                    if (f.name !in valid) f.delete()
                }
            } catch (e: Exception) {
                // 清理失败不致命，下次再清
            }
        }.start()
    }

    // 手动解析歌词：把 [分:秒] 或 [分:秒.毫秒] 的标签和文字抠出来（不怕粘贴丢符号）
    private fun parseLrc(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        var i = 0
        while (i < raw.length) {
            val start = raw.indexOf('[', i)
            if (start < 0) break
            val end = raw.indexOf(']', start)
            if (end < 0) break
            val tag = raw.substring(start + 1, end)
            val parts = tag.split(':', '.')
            if (parts.size >= 2) {
                val min = parts[0].toLongOrNull()
                val sec = parts[1].toLongOrNull()
                if (min != null && sec != null) {
                    val msPart = if (parts.size >= 3) parts[2] else ""
                    val ms = when (msPart.length) {
                        0 -> 0L
                        1 -> msPart.toLong() * 100
                        2 -> msPart.toLong() * 10
                        else -> msPart.take(3).toLong()
                    }
                    val text = raw.substring(end + 1).substringBefore('[').trim()
                    result.add(LyricLine(min * 60000 + sec * 1000 + ms, text))
                }
            }
            i = end + 1
        }
        return result
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
