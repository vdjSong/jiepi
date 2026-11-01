package com.jiepi.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import java.nio.ByteOrder

// 全局播放器：全 App 共用一个播放芯（Media3 ExoPlayer + 自研 DSP 管线）
object PlayerManager {

    // 双实例交叉淡入（切歌不硬停旧实例）
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    // 当前出声的实例（交叉淡入期间为新实例）
    internal var activePlayer: ExoPlayer? = null
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    // ========== 音频焦点（AudioFocus） ==========
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    // 因焦点丢失而暂停（GAIN 时自动恢复）
    private var pausedByFocusLoss = false
    // CAN_DUCK 压低状态
    private var ducked = false
    // 压低音量（约 15%）
    private val duckVolume = 0.15f
    // 播放代次（作废过期延迟任务）
    private var playToken = 0
    // 停旧实例的排空等待时长（排空旧缓冲残留）
    private val crossTailMs = 450L
    // 恢复播放的排空确认时长
    private val resumeDrainMs = 120L
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    // 硬件音量渐弱任务序列号（作废过期任务）
    private var volumeFadeSeq = 0

    // ========== 排空等待（事件驱动） ==========
    private fun waitDrain(
        player: ExoPlayer,
        drainMs: Long,
        token: Int,
        valid: (Int) -> Boolean,
        done: () -> Unit,
        forceDoneMs: Long = 700L
    ) {
        val startPos = player.currentPosition
        val startTime = SystemClock.uptimeMillis()
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!valid(token)) return
                // 仅播放态用位置推进判定排空
                val drained = player.isPlaying && player.currentPosition - startPos >= drainMs
                val timedOut = SystemClock.uptimeMillis() - startTime >= forceDoneMs
                if (drained || timedOut) done() else mainHandler.postDelayed(this, 10)
            }
        })
    }

    // 切歌监听（CopyOnWriteArrayList）
    private val songChangeListeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()
    // 播放状态监听（车载 MediaSession 代理用）
    private val playbackListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addSongChangeListener(listener: (Int) -> Unit) {
        if (!songChangeListeners.contains(listener)) songChangeListeners.add(listener)
    }

    fun removeSongChangeListener(listener: (Int) -> Unit) {
        songChangeListeners.remove(listener)
    }

    internal fun addPlaybackListener(listener: () -> Unit) {
        if (!playbackListeners.contains(listener)) playbackListeners.add(listener)
    }

    internal fun removePlaybackListener(listener: () -> Unit) {
        playbackListeners.remove(listener)
    }

    private fun notifyPlaybackChanged() {
        for (l in playbackListeners) l()
    }

    internal fun notifyQueueChanged() {
        notifyPlaybackChanged()
    }

    // DSP 音效管线（每实例一个 processor）
    private val dspA = JiepiAudioProcessor()
    private val dspB = JiepiAudioProcessor()
    // 当前出声实例的 DSP
    private var activeDsp: JiepiAudioProcessor? = null
    // 交叉淡入进行中状态
    private var pendingCrossTarget: ExoPlayer? = null
    private var pendingCrossDsp: JiepiAudioProcessor? = null
    private var pendingCrossOld: ExoPlayer? = null
    private var crossToken = 0
    // 目标实例预滚完成标记
    private var pendingCrossWarmed = false
    // seek 待淡入标记
    private var pendingSeekTarget: Long? = null
    // 冷启动待淡入标记（READY 后延迟执行）
    private var pendingColdFadeInWarmed: Boolean? = null
    // 冷启动 fadeIn 相对 READY 的延迟
    private val coldFadeInDelayMs = 100L
    // 暂停排空代次
    private var pauseToken = 0
    // 正在暂停排空的实例
    private var pauseDrainTarget: ExoPlayer? = null
    // 暂停排空后待停止的旧实例
    private var pendingStopAfterDrain: ExoPlayer? = null
    // 待停止登记时的播放代次
    private var pendingStopAfterDrainToken = 0

    // 歌单（名字+地址+文件夹）
    var songNames = mutableListOf<String>()
    var songPaths = mutableListOf<String>()
    // 每首歌的 SAF 文件夹地址
    var songDirs = mutableListOf<String>()

    // ID3 显示数据（歌名/歌手，与歌单平行同步）
    var id3Titles = mutableListOf<String>()
    var id3Artists = mutableListOf<String>()

    // 当前播到第几首（-1 未播）
    var currentIndex = -1
        private set

    val isPlaying: Boolean
        get() = activePlayer?.isPlaying == true

    // ========== 变速/变调（自研 Sonic 内核） ==========
    var speed = 1f
        private set
    var pitchSemitones = 0
        private set

    // 半音数转 pitch 系数
    private val pitchFactor: Float
        get() = 2f.pow(pitchSemitones / 12f)

    // ---------- 变速/变调去抖 ----------
    private var pendingSpeed: Float? = null
    private var pendingSemitones: Int? = null
    private val applyPlaybackRunnable = Runnable { applyPendingPlayback() }

    // 返回 true 表示已接受调度；播放器未就绪返回 false
    fun setSpeed(value: Float): Boolean {
        val p = activePlayer ?: return false
        val v = value.coerceIn(0.25f, 4f)
        // 同值跳过
        if (v == speed) return true
        pendingSpeed = v
        pendingSemitones = pitchSemitones
        scheduleApply()
        return true
    }

    fun setPitch(semitones: Int): Boolean {
        val p = activePlayer ?: return false
        val s = semitones.coerceIn(-12, 12)
        // 同值跳过
        if (s == pitchSemitones) return true
        pendingSpeed = speed
        pendingSemitones = s
        scheduleApply()
        return true
    }

    private fun scheduleApply() {
        mainHandler.removeCallbacks(applyPlaybackRunnable)
        mainHandler.postDelayed(applyPlaybackRunnable, 120)
    }

    // 去抖结束后真正应用一次；字段只在成功应用后更新，失败不残留
    private fun applyPendingPlayback() {
        val s = pendingSpeed ?: return
        val semis = pendingSemitones ?: return
        pendingSpeed = null
        pendingSemitones = null
        // 合并期间改回原值则放弃
        if (s == speed && semis == pitchSemitones) return
        val p = 2f.pow(semis / 12f)
        // 双 DSP 同步应用变速变调
        dspA.setSpeedPitch(s, p)
        dspB.setSpeedPitch(s, p)
        speed = s
        pitchSemitones = semis
    }

    // ========== 循环模式（0列表循环 1单曲循环 2列表播放 3随机） ==========
    var loopMode = 0
        private set

    // ========== 随机播放：独立洗牌队列 ==========
    private val shuffleQueue = mutableListOf<Int>()

    // 切换循环模式
    fun setLoopMode(mode: Int) {
        val m = mode.coerceIn(0, 3)
        val old = loopMode
        loopMode = m
        if (m == 3 && old != 3) rebuildShuffleQueue()
    }

    // 定时关闭（0-3=15/30/45/60 分钟，4=播完当前，5=关）
    var timerMode = 5
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null
    private var stopAfterCurrent = false

    // 命名避让属性 setter 的 JVM 签名冲突
    fun applyTimerMode(mode: Int) {
        timerMode = mode.coerceIn(0, 5)
        stopTimer()
        stopAfterCurrent = false
        if (timerMode in 0..3) {
            val minutes = intArrayOf(15, 30, 45, 60)[timerMode]
            timerHandler = Handler(Looper.getMainLooper())
            timerRunnable = Runnable {
                stopTimer()
                timerMode = 5 // 到点自动回到"关"，弹窗里不再高亮旧档位
                pause()
            }
            // 刚赋值必非空，用 !! 断言
            timerHandler?.postDelayed(timerRunnable!!, minutes * 60_000L)
        } else if (timerMode == 4) {
            stopAfterCurrent = true
        }
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        timerHandler = null
        timerRunnable = null
    }

    // ========== EQ/音效状态 ==========
    val eqBandLevels = FloatArray(10) // -12 ~ +12 dB，0 = 不动
    var effectDynamic = 0 // 动态推进 0~100
    var effectHifi = 0 // 高保真 0~100
    var effectHigh = 0 // 高音加强 0~100
    var effectLow = 0 // 低音加强 0~100
    var effectSurround = 0 // 环绕 0~100
    var effectReverb = 0 // 混响 0~100

    // ---------- EQ/音效持久化 ----------
    private val FX_PREFS = "jiepi_fx"

    // 初始化并读取上次音效
    fun initFx(context: Context) {
        appContext = context.applicationContext
        val p = appContext!!.getSharedPreferences(FX_PREFS, Context.MODE_PRIVATE)
        prefs = p
        // 10 段 EQ 解析
        try {
            val eqStr = p.getString("eq", "") ?: ""
            if (eqStr.isNotEmpty()) {
                val parts = eqStr.split(",")
                if (parts.size == 10) {
                    for (i in 0 until 10) {
                        eqBandLevels[i] = parts[i].toFloat().coerceIn(-12f, 12f)
                    }
                }
            }
        } catch (_: Exception) {}
        effectDynamic = p.getInt("effectDynamic", 0)
        effectHifi = p.getInt("effectHifi", 0)
        effectHigh = p.getInt("effectHigh", 0)
        effectLow = p.getInt("effectLow", 0)
        effectSurround = p.getInt("effectSurround", 0)
        effectReverb = p.getInt("effectReverb", 0)
        initResume(appContext!!)
    }

    // 写入音效状态
    private fun persistFxState() {
        val p = prefs ?: return
        val eqStr = eqBandLevels.joinToString(",") { "%.1f".format(it) }
        p.edit()
            .putString("eq", eqStr)
            .putInt("effectDynamic", effectDynamic)
            .putInt("effectHifi", effectHifi)
            .putInt("effectHigh", effectHigh)
            .putInt("effectLow", effectLow)
            .putInt("effectSurround", effectSurround)
            .putInt("effectReverb", effectReverb)
            .apply()
    }

    // 启动车载媒体会话服务（幂等）
    private fun ensurePlaybackService(context: Context) {
        try {
            val ctx = context.applicationContext ?: context
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {}
    }

    // ========== 播放前真实信号预滚（IIR 稳态建立） ==========
    private class WarmPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    private fun decodeHeadPcm(context: Context, path: String, maxMs: Int): WarmPcm? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(path), null)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) { trackIndex = i; break }
            }
            if (trackIndex < 0) { Log.i("JiepiWarm", "decodeHeadPcm: NO_AUDIO_TRACK"); return null }
            val format = extractor.getTrackFormat(trackIndex)
            val sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) { Log.i("JiepiWarm", "decodeHeadPcm: NO_MIME"); return null }
            Log.i("JiepiWarm", "decodeHeadPcm: start path=$path maxMs=$maxMs sr=$sr ch=$ch mime=$mime")
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val c = codec // 非空别名
            val maxSamples = (sr * ch * maxMs / 1000).coerceAtLeast(1)
            val out = ShortArray(maxSamples)
            val info = MediaCodec.BufferInfo()
            var written = 0
            var inputEos = false
            var outputEos = false
            // 只喂开头一小段就 EOS
            var inputBudget = (maxSamples * 2).coerceAtLeast(64)
            var idle = 0
            val timeoutUs = 5_000L
            while (written < maxSamples && !outputEos && idle < 200) {
                var progressed = false
                if (!inputEos) {
                    val inIdx = c.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz > 0 && inputBudget > 0) {
                            c.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            inputBudget -= sz
                            extractor.advance()
                            progressed = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                            progressed = true
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = c.getOutputBuffer(outIdx)!!
                        val n = minOf(info.size / 2, maxSamples - written)
                        if (n > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + n * 2)
                            outBuf.order(ByteOrder.nativeOrder()).asShortBuffer().get(out, written, n)
                            written += n
                            progressed = true
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    c.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 以解码器实际格式为准
                    val of = c.outputFormat
                    val sr2 = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sr
                    val ch2 = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else ch
                    if (sr2 != sr || ch2 != ch) {
                        // 格式不一致则放弃预滚
                        Log.i("JiepiWarm", "decodeHeadPcm: FMT_MISMATCH container=$sr/$ch decoder=$sr2/$ch2")
                        c.stop()
                        c.release()
                        codec = null
                        return null
                    }
                    progressed = true
                }
                if (!progressed) idle++ else idle = 0
            }
            if (written == 0) { Log.i("JiepiWarm", "decodeHeadPcm: ZERO_SAMPLES idle=$idle"); return null }
            Log.i("JiepiWarm", "decodeHeadPcm: OK written=$written samples sr=$sr ch=$ch")
            WarmPcm(out.copyOf(written), sr, ch)
        } catch (e: Exception) {
            Log.i("JiepiWarm", "decodeHeadPcm: EXC ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    // 播放第 index 首（startPosMs>0 逐曲记忆续播）
    fun play(index: Int, context: Context, startPosMs: Int = 0) {
        if (index < 0 || index >= songPaths.size) return
        ensurePlaybackService(context)
        // 开播/切歌：请求音频焦点
        requestAudioFocus()
        pausedByFocusLoss = false
        // 切歌前记旧歌进度
        rememberCurrentTrackPosition()
        currentIndex = index
        // 通知切歌监听
        for (l in songChangeListeners) l(index)
        appContext = context.applicationContext
        val path = songPaths[index]
        val token = ++playToken
        try {
            val exo = activePlayer
            if (exo == null) {
                // 冷启动第一首：新建实例直接播放
                val fresh = createPlayer(context, dspA, true)
                playerA = fresh
                activePlayer = fresh
                activeDsp = dspA
                // 播放前真实信号预滚（建立 IIR 稳态）
                val warmed = decodeHeadPcm(context, path, 300)
                Log.i("JiepiWarm", "PLAY_COLD: warmed=${warmed != null}")
                if (warmed != null) dspA.warmUp(warmed.samples, warmed.sampleRate, warmed.channels)
                fresh.setMediaItem(MediaItem.fromUri(path))
                // prefill 静音垫底（防止满音量瞬态写入 buffer）
                dspA.prepareFadeIn()
                fresh.prepare()
                // 逐曲记忆续播（prepare 后立即 seek）
                if (startPosMs > 0) fresh.seekTo(startPosMs.toLong())
                // 开播：极短淡入盖硬件起振
                fresh.play()
                // 登记待淡入标记（READY 后延迟 fadeIn）
                Log.i("JiepiWarm", "PLAY_COLD: armFadeIn(15) warmed=${warmed != null}")
                pendingColdFadeInWarmed = true
                applyAudioFxState()
            } else if (exo.playbackState == Player.STATE_ENDED) {
                // 自动切歌：清 DSP 防状态残留
                activeDsp?.flush()
                // 静音垫底 + 极短淡入
                activeDsp?.prepareFadeIn()
                exo.setMediaItem(MediaItem.fromUri(path))
                exo.prepare()
                if (startPosMs > 0) exo.seekTo(startPosMs.toLong())
                exo.play()
                // 登记延迟淡入（READY 后 fadeIn）
                Log.i("JiepiWarm", "PLAY_AUTO: armFadeIn(15)")
                pendingColdFadeInWarmed = true
                applyAudioFxState()
            } else if (!exo.isPlaying) {
                // 暂停中切歌：双实例交叉淡入
                exo.setVolume(0f)
                crossfadeTo(path, exo, token, startPosMs)
            } else {
                // 手动切歌（播放中）：交叉淡入
                crossfadeTo(path, exo, token, startPosMs)
            }
        } catch (e: Exception) {
            // 异常兜底：全部重建，下次 play 走冷启动路径
            playerA = null
            playerB = null
            activePlayer = null
            activeDsp = null
            pendingCrossTarget = null
        }
        // 切歌即落盘播放记忆
        saveResumeState(appContext ?: context)
        notifyPlaybackChanged()
    }

    // 恢复播放记忆（默认停在暂停；autoPlay 直接开播）
    fun resumeRestored(index: Int, positionMs: Int, context: Context, autoPlay: Boolean = false) {
        if (index < 0 || index >= songPaths.size) return
        if (activePlayer != null) return  // 已有实例不干预
        if (autoPlay) ensurePlaybackService(context)
        currentIndex = index
        for (l in songChangeListeners) l(index)
        appContext = context.applicationContext
        val path = songPaths[index]
        try {
            val fresh = createPlayer(context, dspA, true)
            playerA = fresh
            activePlayer = fresh
            activeDsp = dspA
            // 播放前真实信号预滚
            val warmed = decodeHeadPcm(context, path, 300)
            Log.i("JiepiWarm", "PLAY_RESUME: warmed=${warmed != null}")
            if (warmed != null) dspA.warmUp(warmed.samples, warmed.sampleRate, warmed.channels)
            fresh.setMediaItem(MediaItem.fromUri(path))
            // prefill 静音垫底
            dspA.prepareFadeIn()
            fresh.prepare()
            if (positionMs > 0) fresh.seekTo(positionMs.toLong())
            // 音效参数前置
            applyAudioFxState()
            if (autoPlay) {
                // 自动开播：极短淡入盖硬件起振
                fresh.playWhenReady = true
                // 统一极短淡入
                Log.i("JiepiWarm", "PLAY_RESUME: fadeIn(15) warmed=${warmed != null}")
                dspA.fadeIn(15)
            } else {
                fresh.playWhenReady = false
            }
        } catch (e: Exception) {
            playerA = null
            playerB = null
            activePlayer = null
            activeDsp = null
            pendingCrossTarget = null
        }
        saveResumeState(appContext ?: context)
        notifyPlaybackChanged()
    }

    // 创建带 DSP 管线的播放器实例
    private fun createPlayer(context: Context, dsp: JiepiAudioProcessor, isA: Boolean): ExoPlayer {
        return ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // DSP 管线挂 AudioSink；缓冲压回系统最小值（消除截断杂音）
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(dsp))
                    .setAudioTrackBufferSizeProvider { minBufferSizeInBytes, _, _, _, _, _, _ ->
                        // 使用系统最小缓冲
                        minBufferSizeInBytes
                    }
                    .build()
            }
        }).build().also {
            it.addListener(makeListener(dsp, isA))
        }
    }

    // 取交叉淡入目标实例
    private fun getOrCreateTarget(old: ExoPlayer): Pair<ExoPlayer, JiepiAudioProcessor> {
        return if (old === playerA) {
            if (playerB == null) {
                playerB = createPlayer(appContext!!, dspB, false)
            }
            Pair(playerB!!, dspB)
        } else {
            if (playerA == null) {
                playerA = createPlayer(appContext!!, dspA, true)
            }
            Pair(playerA!!, dspA)
        }
    }

    // 双实例交叉淡入（目标预加载 + 旧歌渐出 + 延迟停旧）
    private fun crossfadeTo(path: String, old: ExoPlayer, token: Int, startPosMs: Int = 0) {
        val (target, targetDsp) = getOrCreateTarget(old)
        // 目标实例复位硬件音量
        setFullVolume(target)
        // 目标实例预滚（建立 IIR 稳态）
        val warmed = decodeHeadPcm(appContext!!, path, 300)
        Log.i("JiepiWarm", "PLAY_CROSS: warmed=${warmed != null}")
        if (warmed != null) targetDsp.warmUp(warmed.samples, warmed.sampleRate, warmed.channels)
        // 变速状态切歌：先踢引擎再预加载
        if (speed != 1f || pitchSemitones != 0) {
            targetDsp.suspendSonic()
        } else {
            // 目标实例预置静音
            targetDsp.prepareFadeIn()
        }
        // 旧歌 processor 渐出（120ms）
        activeDsp?.fadeOut(120)
        // 旧歌硬件音量整体渐弱（300ms）
        fadeOutVolume(old)
        // 作废冷启动待淡入标记
        pendingColdFadeInWarmed = null
        // 登记交叉状态
        pendingCrossTarget = target
        pendingCrossDsp = targetDsp
        pendingCrossOld = old
        crossToken = token
        pendingCrossWarmed = warmed != null
        target.setMediaItem(MediaItem.fromUri(path))
        target.prepare()
        // 逐曲记忆续播：prepare 后立即 seek
        if (startPosMs > 0) target.seekTo(startPosMs.toLong())
    }

    // 实例专属监听器
    private fun makeListener(dsp: JiepiAudioProcessor, isA: Boolean): Player.Listener {
        // 淡入完成开闸事件（恢复硬件音量）
        dsp.onFadeInDone = {
            mainHandler.post {
                val cur = if (isA) playerA else playerB
                // 校验当前出声实例与淡入完成状态
                if (activePlayer === cur && activeDsp === dsp && dsp.isFadeInDone()) {
                    cur?.let { setFullVolume(it) }
                }
            }
        }
        return object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // seek 完成：新位置渐入
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                val mine = if (isA) playerA else playerB
                if (mine !== activePlayer) return
                if (pendingSeekTarget != null) {
                    // seek 完成：恢复音量并淡入
                    val sonicPath = dsp.isSonicActive() || speed != 1f || pitchSemitones != 0
                    if (!sonicPath) {
                        mine?.let { setFullVolume(it) }
                        // 非变速：恢复音量 + 120ms 淡入
                        activeDsp?.fadeIn(120)
                    } else {
                        // 变速：恢复引擎参数，音量由淡入完成事件开闸
                        activeDsp?.restoreSonic(speed, pitchFactor)
                    }
                    pendingSeekTarget = null
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val mine = if (isA) playerA else playerB
                if (playbackState == Player.STATE_ENDED) {
                    if (mine === activePlayer) handleSongEnded()
                } else if (playbackState == Player.STATE_IDLE) {
                    // 进入 IDLE：清空 DSP 防状态残留
                    dsp.flush()
                } else if (playbackState == Player.STATE_READY && pendingColdFadeInWarmed != null
                    && mine === activePlayer) {
                    // 冷启动 READY：延迟 fadeIn 至硬件真正 start 后
                    val warmed = pendingColdFadeInWarmed ?: false
                    pendingColdFadeInWarmed = null
                    // mine === activePlayer 已校验，activeDsp 即该实例挂的 DSP
                    val dsp = activeDsp
                    val coldToken = playToken
                    mainHandler.postDelayed({
                        // 播放代次变化则作废
                        if (coldToken != playToken) return@postDelayed
                        // 统一极短淡入
                        Log.i("JiepiWarm", "PLAY_COLD: fadeIn(15)@READY+$coldFadeInDelayMs warmed=$warmed")
                        dsp?.fadeIn(15)
                    }, coldFadeInDelayMs)
                } else if (playbackState == Player.STATE_READY && pendingCrossTarget === mine) {
                    // 交叉淡入：目标 READY，静音启动 + 淡入淡出
                    pendingCrossTarget = null
                    if (crossToken != playToken) return
                    val old = pendingCrossOld
                    // READY：恢复变速引擎参数
                    if (speed != 1f || pitchSemitones != 0) {
                        pendingCrossDsp?.restoreSonic(speed, pitchFactor)
                    }
                    // 本回调必然来自该实例
                    mine!!.play()
                    // 非变速：play 后延迟 fadeIn
                    if (speed == 1f && pitchSemitones == 0) {
                        // 手动切歌：延迟 fadeIn 至硬件 start 后
                        val warmed = pendingCrossWarmed
                        val dsp = pendingCrossDsp
                        val crossTokenAtReady = crossToken
                        val playTokenAtReady = playToken
                        mainHandler.postDelayed({
                            // 代次变化则作废
                            if (crossTokenAtReady != crossToken) return@postDelayed
                            if (playTokenAtReady != playToken) return@postDelayed
                            // 统一极短淡入
                            Log.i("JiepiWarm", "PLAY_CROSS: fadeIn(15)@READY+$coldFadeInDelayMs warmed=$warmed")
                            dsp?.fadeIn(15)
                        }, coldFadeInDelayMs)
                    }
                    // 旧歌渐出由 crossfadeTo 已启动的流程接管
                    activePlayer = mine
                    activeDsp = pendingCrossDsp
                    val oldToken = crossToken
                    // 排空旧缓冲后安全停旧实例
                    val oldPlayer = old
                    if (oldPlayer != null) {
                        if (oldPlayer.isPlaying) {
                            // 播放中切歌：等排空完成再停
                            waitDrain(oldPlayer, crossTailMs, oldToken, { it == playToken }, done = {
                                if (oldToken == playToken && oldPlayer !== activePlayer) {
                                    oldPlayer.setVolume(0f) // 排空后保持静音再停
                                    oldPlayer.stop()
                                }
                            })
                        } else {
                            // 暂停切歌：空管直接停，排空中登记待停
                            if (oldPlayer.isPlaying && pauseDrainTarget === oldPlayer) {
                                pendingStopAfterDrain = oldPlayer
                                pendingStopAfterDrainToken = playToken
                            } else {
                                // 已真正暂停：防护通过后直接停
                                if (oldToken == playToken && oldPlayer !== activePlayer &&
                                    pendingCrossTarget !== oldPlayer
                                ) {
                                    oldPlayer.setVolume(0f)
                                    oldPlayer.stop()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 暂停/继续切换
    fun pauseOrResume() {
        val p = activePlayer ?: return
        if (p.isPlaying) pauseDrain() else resume()
    }

    // 暂停（主动排空 + 让出焦点）
    fun pause() {
        // 让出音频焦点并清标志
        pausedByFocusLoss = false
        ducked = false
        abandonAudioFocus()
        pauseByFocus()
    }

    // 暂停统一动作（排空+落盘+广播）
    private fun pauseByFocus() {
        val p = activePlayer ?: return
        if (p.isPlaying) pauseDrain()
        // 暂停即落盘当前位置（恢复续播从这里继续）
        appContext?.let { saveResumeState(it) }
        notifyPlaybackChanged()
    }

    // ========== 音频焦点实现 ==========

    // 懒初始化 AudioFocusRequest（长时 GAIN）
    private fun ensureAudioFocusSetup() {
        if (audioFocusRequest != null) return
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
            .build()
    }

    // 请求音频焦点（幂等）
    private fun requestAudioFocus() {
        ensureAudioFocusSetup()
        val am = audioManager ?: return
        val req = audioFocusRequest ?: return
        val result = try {
            am.requestAudioFocus(req)
        } catch (_: Exception) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    // 让出音频焦点
    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        val req = audioFocusRequest ?: return
        try {
            am.abandonAudioFocusRequest(req)
        } catch (_: Exception) {}
    }

    // 统一音量出口（含 duck 压低）
    private fun setFullVolume(p: ExoPlayer) {
        p.setVolume(if (ducked) duckVolume else 1f)
    }

    // 旧歌硬件音量整体渐弱（50ms 步进，序列号作废）
    private fun fadeOutVolume(player: ExoPlayer, durationMs: Int = 300) {
        val seq = ++volumeFadeSeq
        val start = player.volume
        val steps = durationMs / 50 + 1 // 300ms → 7 档，6 个 50ms 间隔
        var step = 0
        fun tick() {
            if (seq != volumeFadeSeq) return // 有更新任务则作废
            if (player.playbackState == Player.STATE_IDLE) return // 实例已停则作废
            step++
            if (step >= steps) {
                player.setVolume(0f)
                return
            }
            player.setVolume(start * (1f - step.toFloat() / steps))
            mainHandler.postDelayed({ tick() }, 50)
        }
        tick()
    }

    // 焦点变化回调（四态分发）
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusGain()
            AudioManager.AUDIOFOCUS_LOSS -> onFocusLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusTransient()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusDuck()
        }
    }

    // 获得焦点：恢复音量，必要时自动恢复播放
    private fun onFocusGain() {
        val wasDucked = ducked
        ducked = false
        if (wasDucked) {
            val p = activePlayer
            // 静音罩期间不碰音量
            if (p != null && pauseDrainTarget !== p && pendingSeekTarget == null) {
                setFullVolume(p)
            }
        }
        if (pausedByFocusLoss) {
            pausedByFocusLoss = false
            resume()
        }
    }

    // 永久失去焦点：暂停并让出焦点
    private fun onFocusLoss() {
        pausedByFocusLoss = true
        abandonAudioFocus()
        pauseByFocus()
    }

    // 短暂失去焦点：暂停、保留焦点声明
    private fun onFocusTransient() {
        pausedByFocusLoss = true
        pauseByFocus()
    }

    // 可压低：降音量不暂停
    private fun onFocusDuck() {
        ducked = true
        val p = activePlayer
        // 静音罩期间不碰音量
        if (p != null && pauseDrainTarget !== p && pendingSeekTarget == null) {
            setFullVolume(p)
        }
    }

    // 暂停排空（静音→排空→干净待命）
    private fun pauseDrain() {
        val p = activePlayer ?: return
        if (!p.isPlaying) return        // 已暂停无需重复排空
        if (pauseDrainTarget === p) return // 已在排空中
        val tk = ++pauseToken
        pauseDrainTarget = p
        p.setVolume(0f)                 // 立即静音
        activeDsp?.prepareFadeIn()      // 上游停真实数据
        // 保持播放态排空旧缓冲
        waitDrain(p, crossTailMs, tk, {
            it == pauseToken && (p === activePlayer || pendingStopAfterDrain === p)
        }, done = {
            if (pauseDrainTarget === p) pauseDrainTarget = null
            val waitingStop = pendingStopAfterDrain
            if (waitingStop === p) {
                pendingStopAfterDrain = null
                if (pendingStopAfterDrainToken == playToken &&
                    p !== activePlayer && pendingCrossTarget !== p
                ) {
                    // 切歌在等：安全 stop
                    p.setVolume(0f)
                    p.stop()
                } else {
                    // 防护不过：退化为干净暂停
                    p.pause()
                }
            } else if (p === activePlayer && tk == pauseToken) {
                // 正常暂停：空管真正暂停
                p.pause()
                notifyPlaybackChanged() // 真正暂停才广播
            }
        })
    }

    // 恢复播放（静音启动 + 排空确认 + 长渐变接入）
    fun resume() {
        val p = activePlayer ?: return
        if (p.isPlaying) return
        appContext?.let { ensurePlaybackService(it) }
        // 清焦点暂停标志并重新请求焦点
        pausedByFocusLoss = false
        requestAudioFocus()
        val tk = ++pauseToken        // 作废过期暂停排空
        pauseDrainTarget = null
        p.setVolume(0f)              // 静音起步
        activeDsp?.prepareFadeIn()   // 压 0 起步
        p.play()                     // 静音数据下稳定启动
        waitDrain(p, resumeDrainMs, tk, { it == pauseToken && pendingSeekTarget == null }, done = {
            if (tk == pauseToken && p === activePlayer) {
                setFullVolume(p)         // 排空后恢复音量
                activeDsp?.fadeIn(150)   // 150ms 渐变接入
            }
        })
        notifyPlaybackChanged()
    }

    // 下一首（随机模式走洗牌队列）
    fun next(context: Context) {
        if (songPaths.isEmpty()) return
        if (loopMode == 3) {
            shuffleNext(context)
            return
        }
        play((currentIndex + 1) % songPaths.size, context)
    }

    // 上一首（随机模式走洗牌队列）
    fun previous(context: Context) {
        if (songPaths.isEmpty()) return
        if (loopMode == 3) {
            shufflePrevious(context)
            return
        }
        play((currentIndex - 1 + songPaths.size) % songPaths.size, context)
    }

    // 随机模式下一首：重洗取第一首
    private fun shuffleNext(context: Context) {
        if (songPaths.size < 2) {
            if (songPaths.size == 1) play(0, context)
            return
        }
        play(reshuffledTarget(), context)
    }

    // 随机模式上一首：重洗取第一首
    private fun shufflePrevious(context: Context) {
        if (songPaths.size < 2) {
            if (songPaths.size == 1) play(0, context)
            return
        }
        play(reshuffledTarget(), context)
    }

    // 重洗队列取目标（避免原地重播）
    private fun reshuffledTarget(): Int {
        rebuildShuffleQueue()
        var target = shuffleQueue.first()
        if (target == currentIndex && shuffleQueue.size > 1) {
            target = shuffleQueue[1]
        }
        return target
    }

    // 整队重新洗牌
    private fun rebuildShuffleQueue() {
        shuffleQueue.clear()
        for (i in 0 until songPaths.size) shuffleQueue.add(i)
        shuffleQueue.shuffle()
    }

    // ========== 队列删除/重定位 ==========

    // 从队列剔除一首歌（五数组同步 + 下标校正）
    fun removeSongFromQueue(path: String) {
        val idx = songPaths.indexOf(path)
        if (idx < 0) return
        songNames.removeAt(idx)
        songPaths.removeAt(idx)
        songDirs.removeAt(idx)
        id3Titles.removeAt(idx)
        id3Artists.removeAt(idx)
        if (idx < currentIndex) {
            currentIndex--
        } else if (idx == currentIndex && currentIndex >= songPaths.size) {
            currentIndex = songPaths.size - 1
        }
        notifyPlaybackChanged()
    }

    // 队列新增一首（去重，append 队尾）
    fun addSongToQueue(path: String, name: String, dir: String, id3Title: String?, id3Artist: String?) {
        if (songPaths.contains(path)) return
        songNames.add(name)
        songPaths.add(path)
        songDirs.add(dir)
        id3Titles.add(id3Title ?: name)
        id3Artists.add(id3Artist ?: "")
        notifyPlaybackChanged()
    }

    // 队列变化后重定位 currentIndex（对齐 UI 与实播）
    fun realignCurrentIndex(context: Context? = null) {
        val playingPath = activePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        val newIdx = if (playingPath.isNullOrEmpty()) -1 else songPaths.indexOf(playingPath)
        if (newIdx >= 0) {
            currentIndex = newIdx
            return
        }
        if (songPaths.isEmpty()) {
            currentIndex = -1
            // 队列空：停掉播放器
            if (activePlayer != null) pause()
            return
        }
        if (currentIndex >= songPaths.size) currentIndex = songPaths.size - 1
        if (context != null && activePlayer != null && currentIndex >= 0) {
            // 实例停在已删的歌上：切到原下一首
            val wasPlaying = activePlayer?.isPlaying == true
            play(currentIndex, context)
            if (!wasPlaying) pause()
        }
    }

    // 当前歌名
    fun currentName(): String =
        if (currentIndex in songNames.indices) songNames[currentIndex] else ""

    // 详情页显示用歌名（ID3 优先，回退文件名）
    fun currentDisplayName(): String =
        if (currentIndex in id3Titles.indices && id3Titles[currentIndex].isNotBlank()) id3Titles[currentIndex]
        else currentName()

    // 详情页显示用歌手（空串=未读到）
    fun currentArtist(): String =
        if (currentIndex in id3Artists.indices) id3Artists[currentIndex] else ""

    // 当前歌所在文件夹
    fun currentDir(): String =
        if (currentIndex in songDirs.indices) songDirs[currentIndex] else ""

    // 播放位置（毫秒）
    fun currentPosition(): Int = activePlayer?.currentPosition?.toInt() ?: 0
    fun duration(): Int {
        val d = activePlayer?.duration ?: return 0
        return if (d == C.TIME_UNSET || d <= 0) 0 else d.toInt()
    }

    // 拖动开始（拖动期间静音）
    fun beginSeek() {
        val p = activePlayer ?: return
        if (!p.isPlaying) return
        p.setVolume(0f)
        if (speed != 1f || pitchSemitones != 0) {
            // 变速拖动：先踢引擎
            activeDsp?.suspendSonic()
        } else {
            activeDsp?.prepareFadeIn()
        }
    }

    // 拖动进度条
    fun seekTo(posMs: Int) {
        val p = activePlayer ?: return
        if (p.isPlaying) {
            // 播放中 seek：静音罩 + 归零后 seek
            p.setVolume(0f)
            pendingSeekTarget = posMs.toLong()
            // 变速 seek：先踢引擎
            if (speed != 1f || pitchSemitones != 0) {
                activeDsp?.suspendSonic()
            } else {
                activeDsp?.prepareFadeIn() // 归零：seek 后写入静音数据
            }
            p.seekTo(posMs.toLong())
        } else {
            // 暂停中 seek：直接 seek
            p.seekTo(posMs.toLong())
        }
        // 落盘当前位置
        appContext?.let { saveResumeState(it) }
        notifyPlaybackChanged()
    }

    // ========== 播放记忆（队列+位置+来源） ==========
    var resumeSource = "all"
    var resumePlaylistName: String? = null
    var resumeFolderUri: String? = null

    // 播放记忆数据
    data class ResumeData(
        val source: String,
        val playlistName: String?,
        val folderUri: String?,
        val queue: List<String>,
        val currentIndex: Int,
        val currentPath: String,
        val position: Int
    )

    private val RESUME_PREFS = "jiepi_resume"
    private const val RESUME_KEY = "state"
    private const val PER_TRACK_KEY = "perTrack"
    private var resumePrefs: SharedPreferences? = null
    private var resumeHeartbeatStarted = false

    // 初始化播放记忆（含位置心跳）
    private fun initResume(context: Context) {
        resumePrefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        if (resumeHeartbeatStarted) return
        resumeHeartbeatStarted = true
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isPlaying) persistResumeNow()
                mainHandler.postDelayed(this, 10_000)
            }
        })
    }

    // 播放记忆总开关（jiepi_data/sw_resume）
    fun isResumeEnabled(context: Context): Boolean =
        context.getSharedPreferences("jiepi_data", Context.MODE_PRIVATE).getBoolean("sw_resume", false)

    // 启动自动播放开关（jiepi_data/sw_autoplay）
    fun isAutoPlayEnabled(context: Context): Boolean =
        context.getSharedPreferences("jiepi_data", Context.MODE_PRIVATE).getBoolean("sw_autoplay", false)

    // 保存播放记忆
    fun saveResumeState(context: Context) {
        if (!isResumeEnabled(context)) return
        if (songPaths.isEmpty()) return
        persistResumeNow()
    }

    // 落盘队列与播放状态（JSON）
    private fun persistResumeNow() {
        val p = resumePrefs ?: return
        try {
            // 逐曲记忆：当前歌进度单独落盘
            rememberCurrentTrackPosition()
            val arr = JSONArray()
            for (path in songPaths) arr.put(path)
            val o = JSONObject()
            o.put("source", resumeSource)
            o.put("playlistName", resumePlaylistName ?: "")
            o.put("folderUri", resumeFolderUri ?: "")
            o.put("queue", arr)
            o.put("index", currentIndex)
            o.put("currentPath", songPaths.getOrNull(currentIndex) ?: "")
            o.put("pos", currentPosition())
            p.edit().putString(RESUME_KEY, o.toString()).apply()
        } catch (_: Exception) {}
    }

    // 逐曲记忆：当前歌进度单独落盘
    private fun rememberCurrentTrackPosition() {
        val p = resumePrefs ?: return
        val ctx = appContext ?: return
        if (!isResumeEnabled(ctx)) return
        if (currentIndex < 0 || currentIndex >= songPaths.size) return
        val path = songPaths[currentIndex]
        val playingUri = activePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (playingUri != null && playingUri != path) return
        val pos = currentPosition()
        if (pos <= 0) return
        try {
            val raw = p.getString(PER_TRACK_KEY, null)
            val map = if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
            map.put(path, pos)
            p.edit().putString(PER_TRACK_KEY, map.toString()).apply()
        } catch (_: Exception) {}
    }

    // 读取单曲上次播放位置
    fun readTrackPosition(context: Context, path: String): Int {
        return try {
            val p = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            val raw = p.getString(PER_TRACK_KEY, null) ?: return 0
            JSONObject(raw).optInt(path, 0)
        } catch (_: Exception) {
            0
        }
    }

    // 读取播放记忆
    fun loadResume(context: Context): ResumeData? {
        return try {
            val p = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            val raw = p.getString(RESUME_KEY, null) ?: return null
            val o = JSONObject(raw)
            val arr = o.optJSONArray("queue") ?: return null
            val queue = mutableListOf<String>()
            for (i in 0 until arr.length()) queue.add(arr.optString(i, ""))
            ResumeData(
                source = o.optString("source", "all"),
                playlistName = o.optString("playlistName", "").ifEmpty { null },
                folderUri = o.optString("folderUri", "").ifEmpty { null },
                queue = queue,
                currentIndex = o.optInt("index", -1),
                currentPath = o.optString("currentPath", ""),
                position = o.optInt("pos", 0)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ========== 播放完成循环控制 ==========

    private fun handleSongEnded() {
        // 播完当前：暂停
        if (stopAfterCurrent) {
            stopAfterCurrent = false
            timerMode = 5
            stopTimer()
            pause()
            return
        }
        val ctx = appContext
        when (loopMode) {
            1 -> { // 单曲循环：清 DSP 后重播当前
                activeDsp?.flush()
                activePlayer?.seekTo(0)
                activePlayer?.play()
            }
            2 -> { // 列表播放：顺序播放，到末尾就停
                if (currentIndex >= songPaths.size - 1) {
                    pause()
                } else if (ctx != null) {
                    next(ctx)
                }
            }
            3 -> { // 随机：重洗队列取第一首
                if (ctx != null && songPaths.size > 1) {
                    shuffleNext(ctx)
                } else if (ctx != null) {
                    play(0, ctx)
                }
            }
            else -> { // 列表循环：顺序播，到尾回第一首
                if (ctx != null) next(ctx)
            }
        }
    }

    // ========== DSP 音效管理 ==========

    // 刷新音效参数到双 DSP 并落盘
    fun applyAudioFxState() {
        dspA.setEqLevels(eqBandLevels)
        dspB.setEqLevels(eqBandLevels)
        dspA.setEffects(
            effectDynamic, effectHifi,
            effectHigh, effectLow, effectSurround, effectReverb
        )
        dspB.setEffects(
            effectDynamic, effectHifi,
            effectHigh, effectLow, effectSurround, effectReverb
        )
        persistFxState()
    }
}
