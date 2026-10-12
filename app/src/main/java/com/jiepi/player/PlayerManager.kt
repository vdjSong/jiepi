package com.jiepi.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

// 全局播放器：全 App 共用一个播放芯，换页面不打断
// 内核：Media3 ExoPlayer + 自研 JiepiAudioProcessor DSP 管线（绕开系统 audiofx，任何 ROM 生效）
object PlayerManager {

    // 双实例交叉淡入（根治切歌残留 pop）：
    // 手动切歌不再硬停当前 AudioTrack（stop 会拦腰截断 buffer 里 fade 前写入的满音量 PCM，
    // 这就是 25/45ms 淡出都盖不住的残留杂音根因），而是用第二个实例预加载新歌、READY 后
    // 静音启动再交叉淡入，旧实例保持播放把残留缓冲自然排空后才停，全程无波形截断。
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    // 当前出声的实例（交叉淡入期间为新实例）
    internal var activePlayer: ExoPlayer? = null
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    // ========== 音频焦点（AudioFocus）：后台播放被其他音频打断的标准处理 ==========
    // 策略：请求长时 GAIN 焦点（音乐播放器标准）；来电/语音等短暂打断 → 暂停（走 pauseDrain
    // 排空流程，无爆音），焦点夺回自动恢复；导航等 CAN_DUCK 打断 → 压低音量不暂停；其他
    // 播放器接管（LOSS）→ 暂停并让出焦点，等用户重新点播。所有焦点回调走主线程 handler，
    // 与 ExoPlayer/PlayerManager 的调用线程一致，无并发问题。
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    // 因焦点丢失而暂停（GAIN 时据此自动恢复）；用户手动 pause/resume 会清掉，防止误恢复
    private var pausedByFocusLoss = false
    // CAN_DUCK 压低状态：目标音量从 1f 降到 duckVolume，GAIN 时恢复
    private var ducked = false
    // 压低音量（导航/语音打断时的标准 duck 音量，约 15%）
    private val duckVolume = 0.15f
    // 播放代次：防止延迟任务把旧状态带到新歌上
    private var playToken = 0
    // 交叉淡入完成后延迟多久才停旧实例（让旧 AudioTrack buffer 里 fade 前的满音量残留自然播完）
    // 450ms（排水阀开到最大）：Media3 会把 AudioTrack 缓冲自行放大到
    // 实际 100~300ms，且部分 ROM/采样率下更深——300ms 仍可能差一口气。450ms 覆盖实测
    // 见过的最深缓冲，暂停排空/切歌停旧实例一律排到见底。多排的是静音数据，无任何可闻影响；
    // 代价只是暂停/切歌的排空等待略长（事件驱动位置推进，正常几十 ms 即完成，不会真等满）。
    private val crossTailMs = 450L
    // 恢复播放的排空确认时长：waitDrain 是"位置推进"事件驱动，此处 120ms 只证明硬件已
    // 消费掉暂停残留（真实排空验证，非固定时钟等待），无需等满缓冲时长。
    private val resumeDrainMs = 120L
    // Handler 在主线程执行
    private val mainHandler = Handler(Looper.getMainLooper())

    // ========== 排空等待（事件驱动，不猜固定 ms） ==========
    // 思路：不知道 buffer 什么时候排空，就等它排空"放话"再继续，期间保持排空状态老实待着。
    // 实现：AudioTrack 播放时 currentPosition 持续推进 = 硬件正在消费数据；从起始位置推进
    // drainMs 毫秒 = 起始前写入的旧数据已全部被硬件消费完毕（排空完成）。位置推进就是
    // "排空完成"信号，比固定 ms 等待可靠——buffer 深度因 ROM/采样率波动时固定时间必然漏。
    // 排空期间外部动作（stop/seek）先按住不动，done 回调里才继续；token 失效则整个等待作废
    //（用户又切歌/拖条，旧任务不执行，避免把新状态破坏掉）。
    // forceDoneMs 兜底：极少数场景位置不推进（如暂停场景 play() 被系统打断、解码 stall），
    // 超时强制继续，防无限卡死；正常几十 ms 即完成，不会走到兜底。
    // 700ms（排水阀开到最大）：必须大于 crossTailMs(450ms)，否则兜底会在排空
    // 完成前强制截断，排空时长形同虚设——"排到见底"的保证就不能断在兜底这一环。
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
                // 位置推进只在播放态可信：暂停时 currentPosition 是系统时钟外推（虚涨），
                // 推进 ≠ 数据被硬件消费。必须 isPlaying 才用位置判定排空，防"假排空"。
                val drained = player.isPlaying && player.currentPosition - startPos >= drainMs
                val timedOut = SystemClock.uptimeMillis() - startTime >= forceDoneMs
                if (drained || timedOut) done() else mainHandler.postDelayed(this, 10)
            }
        })
    }

    // 切歌监听：currentIndex 变化时逐个通知（列表/播放页用来跟随刷新"当前播放"标识）
    // CopyOnWriteArrayList：允许播放器线程发通知的同时 UI 线程注册/注销，不抛并发异常
    private val songChangeListeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()
    // 播放状态监听（车载 MediaSession 代理用）：切歌/暂停/恢复/seek 等出声状态变化时逐个通知，
    // 代理收到后 invalidateState() 重新拉真实状态广播给车载/通知栏
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

    // 应用内 DSP 音效管线：每个播放器实例一个 processor（kotlin-footguns 生产经验：
    // "One instance per player, share the parameter not the processor"——processor 带格式/缓冲
    // 状态，共享会串音；EQ/变速等全局参数在两个 processor 间同步推送）
    private val dspA = JiepiAudioProcessor()
    private val dspB = JiepiAudioProcessor()
    // 当前出声实例对应的 DSP（交叉淡入时用来对旧实例 fadeOut）
    private var activeDsp: JiepiAudioProcessor? = null
    // 交叉淡入进行中的状态（listener 在目标实例 READY 后接管）
    private var pendingCrossTarget: ExoPlayer? = null
    private var pendingCrossDsp: JiepiAudioProcessor? = null
    private var pendingCrossOld: ExoPlayer? = null
    private var crossToken = 0
    // 播放中 seek 的待淡入标记：seek 完成回调（onSeekProcessed）里执行 fadeIn
    private var pendingSeekTarget: Long? = null
    // 暂停排空代次：作废过期暂停排空回调（用户快速播放/切歌时旧任务不动作）
    private var pauseToken = 0
    // 当前正在执行"暂停排空"的实例（主动排水+验证中）；排空完成或取消后清空
    private var pauseDrainTarget: ExoPlayer? = null
    // 暂停排空中被切歌等待的旧实例：暂停排空完成回调里安全 stop（不抢跑静音落地）
    private var pendingStopAfterDrain: ExoPlayer? = null
    // 登记 pendingStopAfterDrain 时的播放代次：用户又切歌（playToken 变）则放弃 stop
    private var pendingStopAfterDrainToken = 0

    // 歌单（名字 + 播放地址 + 所在文件夹）
    var songNames = mutableListOf<String>()
    var songPaths = mutableListOf<String>()
    // 每首歌所在的 SAF 文件夹地址（content://），播放页找歌词要用
    var songDirs = mutableListOf<String>()

    // ID3 显示数据：与 songNames/songPaths/songDirs 平行同步（列表页 push 时带入）
    // id3Titles 存"最终显示用歌名"（ID3 歌名优先，回退去尾巴文件名）；id3Artists 空串=未读到歌手
    var id3Titles = mutableListOf<String>()
    var id3Artists = mutableListOf<String>()

    // 当前播到第几首（-1 表示还没播）
    var currentIndex = -1
        private set

    val isPlaying: Boolean
        get() = activePlayer?.isPlaying == true

    // ========== 变速 / 变调：自研 DSP 管线 Sonic 内核（纯 Kotlin，0.25~4x 全支持） ==========
    var speed = 1f
        private set
    var pitchSemitones = 0
        private set

    // 半音数转 pitch 系数：+12 → 2.0，-12 → 0.5
    private val pitchFactor: Float
        get() = 2f.pow(pitchSemitones / 12f)

    // ---------- 变速/变调去抖 ----------
    // 旧路线 PlaybackParameters 会让 Media3 Sonic + 整条 DSP 链 flush 重置，产生"卡一下"；
    // 新路线直接调 DSP 里的自研 Sonic 内核：DSP 内部做 80ms 平滑渐变，这里保留 120ms 去抖
    // 只是合并用户连点，避免无谓的渐变抖动。
    private var pendingSpeed: Float? = null
    private var pendingSemitones: Int? = null
    private val applyPlaybackRunnable = Runnable { applyPendingPlayback() }

    // 返回 true 表示已接受调度；播放器未就绪返回 false
    fun setSpeed(value: Float): Boolean {
        val p = activePlayer ?: return false
        val v = value.coerceIn(0.25f, 4f)
        // 同值跳过：和当前生效倍率一样就不重复调度
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
        // 合并期间用户又改回原值：直接放弃
        if (s == speed && semis == pitchSemitones) return
        val p = 2f.pow(semis / 12f)
        // 直接推进 DSP 管线里的变速引擎：参数平滑渐变，无 ExoPlayer flush，不爆音不卡顿。
        // 双实例架构下两个 processor 都要同步（未来交叉淡入时另一个实例随时可能接棒）
        dspA.setSpeedPitch(s, p)
        dspB.setSpeedPitch(s, p)
        speed = s
        pitchSemitones = semis
    }

    // ========== 循环模式：0列表循环 1单曲循环 2列表播放 3随机 ==========
    var loopMode = 0
        private set

    // ========== 随机播放：独立洗牌队列（仅随机模式使用，原列表完全不动、不参与显示） ==========
    // 队列存的是"当前实际播放列表（songPaths）"的索引；每次切歌都把整队重新洗一遍、取第一首为目标：
    // 每一步都随机、结果不可预测（不再预存完整排列顺序"攥在手里"）
    private val shuffleQueue = mutableListOf<Int>()

    // 切换循环模式入口：进入随机模式立刻洗牌一次；退出随机模式不影响队列（下次进入再洗）
    fun setLoopMode(mode: Int) {
        val m = mode.coerceIn(0, 3)
        val old = loopMode
        loopMode = m
        if (m == 3 && old != 3) rebuildShuffleQueue()
    }

    // ========== 定时关闭：0-3 = 15/30/45/60 分钟，4 = 播完当前，5 = 关 ==========
    var timerMode = 5
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null
    private var stopAfterCurrent = false

    // 与 var timerMode 的属性 setter 重名会 JVM 签名冲突，故命名为 applyTimerMode
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
            // mutable 属性在 lambda/回调里智能转换不了，刚赋值必非空，用 !! 断言
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

    // ========== EQ / 音效状态（全局保存，切歌不丢，杀进程也不丢） ==========
    val eqBandLevels = FloatArray(10) // -12 ~ +12 dB，0 = 不动
    var vocalSuppress = 0 // 伴奏人声抑制 0~100
    var effectDynamic = 0 // 动态推进 0~100
    var effectHifi = 0 // 高保真 0~100
    var effectHigh = 0 // 高音加强 0~100
    var effectLow = 0 // 低音加强 0~100
    var effectSurround = 0 // 环绕 0~100
    var effectReverb = 0 // 混响 0~100

    // ---------- EQ/音效持久化：App 重启后恢复上次调好的音效 ----------
    private val FX_PREFS = "jiepi_fx"

    // 初始化并读取上次保存的音效（应用启动时调用一次）
    fun initFx(context: Context) {
        appContext = context.applicationContext
        val p = appContext!!.getSharedPreferences(FX_PREFS, Context.MODE_PRIVATE)
        prefs = p
        // 10 段 EQ：逗号分隔的字符串，解析失败就当全 0（首次安装没有 key）
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
        vocalSuppress = p.getInt("vocalSuppress", 0)
        effectDynamic = p.getInt("effectDynamic", 0)
        effectHifi = p.getInt("effectHifi", 0)
        effectHigh = p.getInt("effectHigh", 0)
        effectLow = p.getInt("effectLow", 0)
        effectSurround = p.getInt("effectSurround", 0)
        effectReverb = p.getInt("effectReverb", 0)
        initResume(appContext!!)
    }

    // 把当前音效状态写进 SharedPreferences（任何参数变化后调用）
    private fun persistFxState() {
        val p = prefs ?: return
        val eqStr = eqBandLevels.joinToString(",") { "%.1f".format(it) }
        p.edit()
            .putString("eq", eqStr)
            .putInt("vocalSuppress", vocalSuppress)
            .putInt("effectDynamic", effectDynamic)
            .putInt("effectHifi", effectHifi)
            .putInt("effectHigh", effectHigh)
            .putInt("effectLow", effectLow)
            .putInt("effectSurround", effectSurround)
            .putInt("effectReverb", effectReverb)
            .apply()
    }

    // 启动车载媒体会话服务（MediaSessionService）：出声前拉起，方向盘/蓝牙/通知栏才能控制。
    // 幂等：服务已在运行则 onStartCommand 再次调用无副作用；后台启动受限时 try-catch 兜底
    //（播放不受影响，只是那次没有媒体会话，用户前台操作时必能正常拉起）。
    private fun ensurePlaybackService(context: Context) {
        try {
            val ctx = context.applicationContext ?: context
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {}
    }

    // 播放第 index 首（SAF 的 content:// 地址由 ExoPlayer 原生支持，无需手开 FileDescriptor）
    // startPosMs>0 表示逐曲记忆续播：新歌目标实例在 prepare 后立即 seek（交叉淡入时
    // activePlayer 尚未切到新实例，外部 seekTo 会打错对象，必须由 play 内部对目标实例 seek）
    fun play(index: Int, context: Context, startPosMs: Int = 0) {
        if (index < 0 || index >= songPaths.size) return
        ensurePlaybackService(context)
        // 开播/切歌：请求音频焦点（幂等），并清除"因焦点丢失而暂停"标志——用户主动点播
        // 意味着重新接管播放，焦点夺回后不应再自动恢复旧状态
        requestAudioFocus()
        pausedByFocusLoss = false
        // 切歌前先把旧歌进度记进逐曲记忆（切回来时还能从上次位置续上）
        rememberCurrentTrackPosition()
        currentIndex = index
        // 通知切歌监听：列表"正在播放"绿字要跟着走（回调可能在播放器线程，UI 侧自己切主线程）
        for (l in songChangeListeners) l(index)
        appContext = context.applicationContext
        val path = songPaths[index]
        val token = ++playToken
        try {
            val exo = activePlayer
            if (exo == null) {
                // 冷启动第一首：新建 A 实例，挂 DSP 管线与切歌监听，直接播放（无上一首，无截断）
                val fresh = createPlayer(context, dspA, true)
                playerA = fresh
                activePlayer = fresh
                activeDsp = dspA
                fresh.setMediaItem(MediaItem.fromUri(path))
                fresh.prepare()
                // 逐曲记忆续播：prepare 后立即 seek（Media3 对未 READY 的 seek 会记住待应用）
                if (startPosMs > 0) fresh.seekTo(startPosMs.toLong())
                fresh.play()
                applyAudioFxState()
            } else if (exo.playbackState == Player.STATE_ENDED) {
                // 自动切歌：上一首已自然播完，没有"播放中硬停"，直接切换即可
                exo.setMediaItem(MediaItem.fromUri(path))
                exo.prepare()
                if (startPosMs > 0) exo.seekTo(startPosMs.toLong())
                exo.play()
                applyAudioFxState()
            } else if (!exo.isPlaying) {
                // 暂停中切歌：暂停时已走"主动排空+双重验证+干净待命"（pauseDrain）——
                // 旧实例要么已真正 pause（空管，READY 后直接 stop 无 pop），要么仍在
                // 排空中（volume=0 + processor 静音，由排空完成信号负责安全 stop）。
                // 这里 setVolume(0) 是排空流程已有静音的兜底，不 play() 旧歌（play 会
                // 重启 AudioTrack 硬件产生 start 咔哒），直接双实例交叉淡入；READY 回调
                // 按旧实例形态决定"直接 stop"还是"登记等待排空完成"。
                exo.setVolume(0f)
                crossfadeTo(path, exo, token, startPosMs)
            } else {
                // 手动切歌（播放中）：双实例交叉淡入，旧实例不硬停
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
        // 切歌即落盘：队列 + 当前位置 + 来源（含播放位置，冷启动恢复续播用）
        saveResumeState(appContext ?: context)
        notifyPlaybackChanged()
    }

    // 播放记忆恢复：冷启动恢复队列后"停在暂停"（不自动响，等用户按播放键才开播）
    // 只处理 activePlayer==null 的冷启动形态；播放位置从上次记忆接上（Media3 对未 READY 的 seek 会记住待应用）
    // autoPlay=true 时（启动自动播放接管）恢复位置后直接开播：playWhenReady=true 等价 play()
    fun resumeRestored(index: Int, positionMs: Int, context: Context, autoPlay: Boolean = false) {
        if (index < 0 || index >= songPaths.size) return
        if (activePlayer != null) return  // 已有播放实例：不干预，走正常 play
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
            fresh.setMediaItem(MediaItem.fromUri(path))
            fresh.prepare()
            fresh.playWhenReady = autoPlay
            if (positionMs > 0) fresh.seekTo(positionMs.toLong())
            applyAudioFxState()
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

    // 创建带 DSP 管线的播放器实例；listener 按实例绑定自己的 processor
    private fun createPlayer(context: Context, dsp: JiepiAudioProcessor, isA: Boolean): ExoPlayer {
        return ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // DSP 管线必须在 AudioSink 构建时挂上（应用层音效，任何 ROM 生效）
                // 机制级根治切歌/seek 爆音：ExoPlayer 默认把 AudioTrack buffer 放大到
                // 数百 ms，stop/flush 截断的总是深缓冲里已写满的满音量 PCM → pop 杂音。
                // 用 AudioTrackBufferSizeProvider 把缓冲压回系统最小值（约 30~50ms）：
                // 排空快、截断残余短，配合实例级 setVolume(0) 静音兜底，从机制上消除
                // 截断杂音，不再依赖"等排空/等静音落地"的固定长延迟。
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(dsp))
                    .setAudioTrackBufferSizeProvider { minBufferSizeInBytes, _, _, _, _, _, _ ->
                        minBufferSizeInBytes
                    }
                    .build()
            }
        }).build().also {
            it.addListener(makeListener(dsp, isA))
        }
    }

    // 取交叉淡入的目标实例（非活动那个），未创建则创建
    private fun getOrCreateTarget(old: ExoPlayer): Pair<ExoPlayer, JiepiAudioProcessor> {
        return if (old === playerA) {
            if (playerB == null) playerB = createPlayer(appContext!!, dspB, false)
            Pair(playerB!!, dspB)
        } else {
            if (playerA == null) playerA = createPlayer(appContext!!, dspA, true)
            Pair(playerA!!, dspA)
        }
    }

    // 手动切歌（播放中/暂停恢复后）：目标实例预加载 + 旧歌渐出，READY 后由 listener 接管交叉淡入
    // 旧实例一律在 READY 后延迟 stop：暂停切歌先由 play() 入口 setVolume(0) 硬件静音，
    // 残余在无声中排空；播放中切歌的旧实例 fadeOut 归零后 buffer 残留自然播完。
    // stop 前再 setVolume(0) 兜底：AudioTrack 硬件混音对 buffer 里已有数据也立即生效，
    // stop 截断的必然是静音数据，不会出 pop。
    // startPosMs>0：逐曲记忆续播位置，prepare 后立即对 target seek（未 READY 的 seek
    // Media3 会记住待应用，READY 后 play() 直接从该位置开始）
    private fun crossfadeTo(path: String, old: ExoPlayer, token: Int, startPosMs: Int = 0) {
        val (target, targetDsp) = getOrCreateTarget(old)
        // 目标实例复位硬件音量：该实例上次被 stop 前可能被 setVolume(0) 静音兜底，
        // 此刻旧内容已排空干净、无输出，复位到目标音量供新歌使用（duck 期间为压低音量）
        setFullVolume(target)
        // 切歌踢引擎：变速/变调状态下切歌，目标实例先踢掉引擎（参数归 1 +
        // 清缓冲 → 新歌以原速直通起步，与"未变速切歌"完全同一路径），READY 后由
        // restoreSonic 恢复变速重新武装——杜绝引擎窗口重建起振瞬态在切歌瞬间干扰
        //（变速切歌爆音/无声的源头）。非变速照旧 prepareFadeIn。
        if (speed != 1f || pitchSemitones != 0) {
            targetDsp.suspendSonic()
        } else {
            // 目标实例预置静音：防止 prepare 期间满音量 PCM 提前写进 AudioTrack buffer，
            // 等 play() start 瞬间轰出 start 咔哒（双实例交叉淡入的关键细节）
            targetDsp.prepareFadeIn()
        }
        // 旧歌立刻开始渐出：processor 归零后 AudioTrack 继续自然排空 buffer 里的满音量残留
        activeDsp?.fadeOut()
        // 登记交叉状态，listener 在 target READY 后接管
        pendingCrossTarget = target
        pendingCrossDsp = targetDsp
        pendingCrossOld = old
        crossToken = token
        target.setMediaItem(MediaItem.fromUri(path))
        target.prepare()
        // 逐曲记忆续播：prepare 后立即对目标实例 seek（交叉淡入期间 activePlayer 还是旧
        // 实例，外部 seekTo 会打错对象，必须在 READY/play 之前把位置定在目标实例上）
        if (startPosMs > 0) target.seekTo(startPosMs.toLong())
    }

    // 实例专属 listener：IDLE 时 flush 自己的 DSP；自然播完时仅当自己是当前出声实例才处理切歌
    private fun makeListener(dsp: JiepiAudioProcessor, isA: Boolean): Player.Listener {
        // 变速/变调 seek 的硬件音量开闸事件：processor 播放线程 fadeGain 爬到 1（淡入真正
        // 完成、引擎预热伪影已平滑接入）时触发。seek 完成回调（onPositionDiscontinuity）里
        // 变速路径不抢跑 setVolume(1f)，改由这里在淡入完成后恢复——RubberBand 引擎 seek 后
        // 窗口重建→稳定期→500ms 淡入的预热全程保持硬件静音，杜绝"从渐变中段轰出抖动瞬态"
        //（变速/变调拖条杂音的根因）。回调在播放线程（processFrames 内）触发，音量切回主线程。
        // 注意：不能在这里取 playerA/playerB 快照——createPlayer 是在 playerA/B 赋值前调用的，
        // 快照恒为 null（曾导致变速 seek 后 onFadeInDone 校验永远失败、硬件音量永不恢复 =
        // "歌还在放但不出声"）。回调内动态取当前实例。
        dsp.onFadeInDone = {
            mainHandler.post {
                val cur = if (isA) playerA else playerB
                // 校验：当前出声实例没变、淡入确实完成且门控未被新一轮 seek 复位
                //（isFadeInDone 含 fadeTarget==1，防"事件触发后用户又拖条"竞态把新静音罩捅破）
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
                // 播放中 seek 完成（Media3 无 onSeekProcessed，用 discontinuity SEEK 回调）：
                // 新位置数据开始流过 processor，从 0 渐入盖相位跳变。
                // 变速/变调时 Sonic 时间拉伸有内部预缓冲窗口，seek 后首批输出要等窗口建立才
                // 出现。窗口由 processor 数据流门控（armSonicGate）接管：Sonic 恢复输出那一刻
                // 自动淡入，不再用固定时钟等待（250ms 盖不住更深窗口 → 首批数据以满增益轰出）。
                // 非变速直通模式无窗口延迟，立即淡入即可。
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                val mine = if (isA) playerA else playerB
                if (mine !== activePlayer) return
                if (pendingSeekTarget != null) {
                    // seek 完成（网上标准）：拖动期间已硬件静音（beginSeek setVolume(0)），
                    // Media3 flush 截断在无声中完成；此刻新位置数据已开始流过 processor
                    //（fadeGain 仍为 0，输出静音）。
                    // 变速/变调（含恢复原速但渐变未完成的"传染"态）：不在此恢复硬件音量，
                    // 由 processor 的 onFadeInDone 事件（fadeGain 爬到 1、引擎预热伪影已平滑
                    // 接入）开闸——seek 完成回调抢跑 setVolume(1f) 会把预热中段抖动瞬态从
                    // 渐变里轰出。判定用 processor 实际状态（isSonicActive 含引擎渐变未回
                    // 原速），不是 PlayerManager 目标值——恢复原速瞬间目标已 1 但引擎还在
                    // 渐变，若误判走非变速快速淡入 = "恢复正常速度后传染正常拖条"的根因。
                    // 非变速直通：无 Sonic 窗口，seek 后新位置数据立即流过，立即恢复 + 淡入。
                    // 用 activePlayer/activeDsp：延迟回调执行时用户可能已切歌，淡入永远
                    // 落在当前出声实例上，不会把音量状态留给新歌。
                    val sonicPath = dsp.isSonicActive() || speed != 1f || pitchSemitones != 0
                    if (!sonicPath) {
                        mine?.let { setFullVolume(it) }
                        activeDsp?.fadeIn()
                    } else {
                        // 拖条踢引擎：seek 期间引擎已被 suspendSonic 踢出处理链
                        //（直通，与"未变速未变调拖条"同一路径，引擎起振瞬态无法干扰），
                        // 此刻恢复变速/变调参数重新武装引擎（resetBuffers + 从 1 渐变到目标），
                        // 预热期由门控+淡入接管；硬件音量恢复由 onFadeInDone 事件驱动
                        //（淡入真正完成才开闸，不在此抢跑 setVolume(1f)）
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
                    // 复用实例切歌时 stop() 完成会进入 IDLE，但 Media3 只在输出格式变化时才自动
                    // flush AudioProcessor；两首歌采样率/声道相同时不会触发，上一首的 EQ 滤波状态、
                    // 混响尾巴、Sonic 缓冲会残留混进新歌开头（表现为"切歌短暂声音"）。
                    // 必须在播放线程（本回调）手动清空 DSP，且与 queueInput 天然串行、无竞态。
                    dsp.flush()
                } else if (playbackState == Player.STATE_READY && pendingCrossTarget === mine) {
                    // 交叉淡入：目标实例预加载完成，静音启动 + 并行淡入淡出
                    pendingCrossTarget = null
                    if (crossToken != playToken) return
                    val old = pendingCrossOld
                    // 切歌踢引擎：切歌期间引擎已被 suspendSonic 踢出处理链
                    //（新歌以原速直通起步，与"未变速未变调切歌"同一路径，引擎窗口重建
                    // 起振瞬态无法干扰），READY 此刻恢复变速/变调参数重新武装引擎
                    //（resetBuffers + 从 1 渐变到目标），预热期由门控（armSonicGate 内含
                    // 于 restoreSonic）+淡入接管，音量开闸由 onFadeInDone 事件负责
                    if (speed != 1f || pitchSemitones != 0) {
                        pendingCrossDsp?.restoreSonic(speed, pitchFactor)
                    }
                    // 本回调必然来自该实例（事件源），非空断言安全
                    mine!!.play()
                    // 非变速：play 之后才 fadeIn（resume 同款教训——play 前 fadeIn 的渐变
                    // 爬升期间数据就已满增益灌进 AudioTrack buffer，硬件 start 瞬间轰满音量
                    // → 切歌咔哒。resume 之所以干净，就是它是唯一"play 后淡入"的路径；
                    // 切歌路径一直在 play 前 fadeIn，这正是"只有暂停播放干净、其余哪哪都响"
                    // 的根因）。play 让硬件在静音数据（prepareFadeIn 已置 fadeGain=0）下稳定
                    // 启动后，再 500ms 长渐变接入（时长顶到最大）。
                    if (speed == 1f && pitchSemitones == 0) {
                        pendingCrossDsp?.fadeIn(500)
                    }
                    activeDsp?.fadeOut(500) // 旧歌对称渐出（processor 归零后 AudioTrack 自然排空）
                    activePlayer = mine
                    activeDsp = pendingCrossDsp
                    val oldToken = crossToken
                    // 排空尾巴后停旧实例（事件驱动，不猜固定 ms）：等旧实例位置推进
                    // crossTailMs = 旧 buffer 里 fade 前写入的满音量残留已被硬件消费排空，
                    // "排空完成"信号成立再停。位置推进是硬件消费数据的真实事件，buffer
                    // 深度因 ROM/采样率波动也不怕；此刻新歌已满音量、旧 processor 已
                    // fadeOut 归零，后续写入的必是静音数据，stop 截断的只剩空/静音 → 无 pop。
                    // 暂停切歌的关键：old 若处于暂停状态，AudioTrack 不消费 buffer、位置
                    // 不推进，排空永远不会发生。必须先 play() 让残余在静音下被消费排空
                    //（用户听不到，volume=0 无 start 咔哒），位置开始推进后"排空完成"才成立。
                    // 播放中切歌 old 本就在播，play() 无副作用。
                    // token 变了说明用户又切歌，可能正复用旧实例，等待直接作废（valid=false），
                    // 排空期间保持静音状态老实待着，不动作；排空完成才 setVolume(0)+stop。
                    val oldPlayer = old
                    if (oldPlayer != null) {
                        if (oldPlayer.isPlaying) {
                            // 播放中切歌：等真排空再停旧实例。waitDrain 已加播放态门槛——
                            // 只在 isPlaying 时信位置推进，暂停虚涨的假信号进不来。
                            waitDrain(oldPlayer, crossTailMs, oldToken, { it == playToken }, done = {
                                if (oldToken == playToken && oldPlayer !== activePlayer) {
                                    oldPlayer.setVolume(0f) // 兜底：排空后保持静音，stop 截断静音
                                    oldPlayer.stop()
                                }
                            })
                        } else {
                            // 暂停切歌：旧实例此时只有两种形态——
                            // 1) 已排空干净（暂停排空已完成 → 已真正 pause，空管）：
                            //    直接 stop，截断的必是空/静音 → 无 pop，新歌秒出。
                            // 2) 仍在暂停排空中（volume=0 + processor 静音，位置正在推进
                            //    消费残留，静音 HAL 可能尚未落地）：不能立即 stop（抢跑会
                            //    截断满音量残留 → pop），登记 pendingStopAfterDrain，由暂停
                            //    排空完成回调（waitDrain 验证空管后）执行安全 stop。
                            // 不再用"setVolume(0)+猜 120ms"：排空验证是硬件位置推进的真实
                            // 事件，比固定时钟可靠，且与暂停排空共用同一信号，无重复等待。
                            if (oldPlayer.isPlaying && pauseDrainTarget === oldPlayer) {
                                pendingStopAfterDrain = oldPlayer
                                pendingStopAfterDrainToken = playToken
                            } else {
                                // 已真正暂停（空管）或从未排空但暂停已久（buffer 已空）：
                                // 三重防护后直接 stop
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

    // 暂停 / 继续
    fun pauseOrResume() {
        val p = activePlayer ?: return
        if (p.isPlaying) pauseDrain() else resume()
    }

    // 定时到点 / 播完当前：暂停播放（同样走"主动排空+验证+干净待命"）
    fun pause() {
        // 用户主动暂停：让出音频焦点，并清除 duck/焦点暂停标志（焦点夺回不再自动恢复，
        // 避免"用户已暂停、别家播完又来抢播"的误恢复）
        pausedByFocusLoss = false
        ducked = false
        abandonAudioFocus()
        pauseByFocus()
    }

    // 暂停统一动作：暂停排空 + 落盘 + 广播。焦点回调路径也复用此方法，但不清标志、不让出
    // 焦点——短暂失焦（来电/语音）要保留焦点声明，等 GAIN 到来自动恢复播放
    private fun pauseByFocus() {
        val p = activePlayer ?: return
        if (p.isPlaying) pauseDrain()
        // 暂停即落盘当前位置（恢复续播从这里继续）
        appContext?.let { saveResumeState(it) }
        notifyPlaybackChanged()
    }

    // ========== 音频焦点实现 ==========

    // 懒初始化：首次请求焦点时用 appContext 取 AudioManager 并构建 AudioFocusRequest。
    // 长时 GAIN（音乐播放器标准）：别的播放器开始发声会触发我们 LOSS，导航/语音按对方
    // 请求类型收 TRANSIENT 或 CAN_DUCK。回调挂在主线程 handler，与播放调用同线程。
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

    // 请求音频焦点（幂等）：开播/恢复/切歌时调用；失败（对方拒绝让渡）不阻塞播放，
    // 照常出声，仅记录 focusHeld=false
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

    // 让出音频焦点：用户手动暂停时调用（暂停 = 彻底让出声明，别家可安心发声）。
    // 短暂失焦（TRANSIENT）路径不调用——保留声明才能收到 GAIN 自动恢复
    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        val req = audioFocusRequest ?: return
        try {
            am.abandonAudioFocusRequest(req)
        } catch (_: Exception) {}
    }

    // 统一音量出口：正常满音量 1f；CAN_DUCK 压低期间为 duckVolume。
    // 所有"恢复出声音量"的调用点（resume/seek/交叉淡入/淡入完成）都走这里，
    // 保证 duck 状态不被任何恢复路径误覆盖回满音量
    private fun setFullVolume(p: ExoPlayer) {
        p.setVolume(if (ducked) duckVolume else 1f)
    }

    // 焦点变化回调（主线程）：按 Android 标准四态处理
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusGain()
            AudioManager.AUDIOFOCUS_LOSS -> onFocusLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusTransient()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusDuck()
        }
    }

    // 获得/夺回焦点：恢复被 duck 压低的音量；若此前因失焦而暂停且用户没有手动干预
    //（手动 pause/resume 会清标志），则自动恢复播放
    private fun onFocusGain() {
        val wasDucked = ducked
        ducked = false
        if (wasDucked) {
            val p = activePlayer
            // 排空/seek 静音罩期间不碰音量（setVolume(0) 的静音优先，避免被 duck 音量捅破）
            if (p != null && pauseDrainTarget !== p && pendingSeekTarget == null) {
                setFullVolume(p)
            }
        }
        if (pausedByFocusLoss) {
            pausedByFocusLoss = false
            resume()
        }
    }

    // 永久失去焦点（其他播放器接管）：暂停 + 让出焦点，等用户重新点播时再请求
    private fun onFocusLoss() {
        pausedByFocusLoss = true
        abandonAudioFocus()
        pauseByFocus()
    }

    // 短暂失去焦点（来电/语音助手等）：暂停但不让出焦点声明，夺回时自动恢复
    private fun onFocusTransient() {
        pausedByFocusLoss = true
        pauseByFocus()
    }

    // 可压低（导航提示等）：不暂停，把音量降到 duckVolume；结束收到 GAIN 再恢复
    private fun onFocusDuck() {
        ducked = true
        val p = activePlayer
        // 排空/seek 静音罩期间不碰音量（静音优先，duck 音量待恢复音量路径统一接管）
        if (p != null && pauseDrainTarget !== p && pendingSeekTarget == null) {
            setFullVolume(p)
        }
    }

    // 暂停 = 主动排空 + 双重验证 + 干净待命
    // 点暂停：setVolume(0) 立即静音（用户感知暂停）→ 上游停止放真实数据（processor 归零，
    // 管子里后续只进静音帧）→ 保持播放态让硬件把管子里已有的水消费掉 → waitDrain 验证
    // 空管（位置推进 = 硬件真实消费，isPlaying 门槛防假排空）→ 真正 pause。
    // 达成"干净待命"后：切歌直接 stop 无 pop；播放重新放水+fadeIn，声音晚 30~50ms 无缝正确。
    // 若排空期间用户切歌（activePlayer 已换新实例）：排空完成信号仍负责把旧实例安全 stop
    //（pendingStopAfterDrain 登记），截断的必是空/静音 → 无 pop。
    private fun pauseDrain() {
        val p = activePlayer ?: return
        if (!p.isPlaying) return        // 已暂停（干净待命态），无需重复排空
        if (pauseDrainTarget === p) return // 该实例已在排空中，不重复发起
        val tk = ++pauseToken
        pauseDrainTarget = p
        p.setVolume(0f)                 // 立即静音：按下暂停瞬间听不到任何东西
        activeDsp?.prepareFadeIn()      // 上游停真实数据：processor 归零，管子里后续只进静音帧
        // 保持播放态让硬件消费管子里已有的水；位置推进 crossTailMs = 旧数据已全部被硬件
        // 消费（排空完成）。期间 token 失效，或实例既不是 active 也没有切歌在等 → 作废。
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
                    // 切歌在等且未被复用：排空完成，安全 stop（截断的必是空/静音）
                    p.setVolume(0f)
                    p.stop()
                } else {
                    // 防护不过（用户又切歌/实例被复用为新 target）：退化为干净暂停，不硬停
                    p.pause()
                }
            } else if (p === activePlayer && tk == pauseToken) {
                // 正常暂停：空管真正暂停（无残留可截，无 pop）
                p.pause()
                notifyPlaybackChanged() // 真正落地暂停才广播（排空期间 playWhenReady 仍为 true）
            }
        })
    }

    // 恢复播放：从"干净待命"态重新放水。pauseDrain 已保证实例真正暂停、buffer 空管，
    // 但 Media3 实际缓冲比理论最小值深（100~300ms），暂停时刻的旧残留可能没排完；
    // 若在满音量下直接 play，残留会被硬件 start 瞬态轰出 → "先播一下→断一下→接上小尾巴"。
    // 双保险：
    // 1) 静音启动：保持 pauseDrain 的 setVolume(0) 硬件静音，先 play() 让硬件在无声中
    //    消费/排空残留（processor fadeGain=0，后续写入的也是静音数据，无 start 咔哒）。
    // 2) 事件驱动确认排空：waitDrain 等位置推进约 120ms（硬件真实消费，非固定时钟），
    //    确认残留排空后才 setVolume(1f)（此刻 buffer 空、输出静音，无 0→1 可闻台阶）
    //    + fadeIn(150) 150ms 长渐变平滑接入。
    // token 失效（用户又暂停/切歌）或进入拖动 seek（pendingSeekTarget 非空）则整个等待
    // 作废，不碰音量不淡入——音量恢复交给 seek 完成回调，避免破坏 seek 静音罩。
    fun resume() {
        val p = activePlayer ?: return
        if (p.isPlaying) return
        appContext?.let { ensurePlaybackService(it) }
        // 用户手动恢复：清除"因焦点丢失而暂停"标志（焦点夺回不再自动恢复的判定依据），
        // 并重新请求音频焦点（若之前暂停时已让出）
        pausedByFocusLoss = false
        requestAudioFocus()
        val tk = ++pauseToken        // 作废可能仍在进行中的暂停排空（用户快速点播放）
        pauseDrainTarget = null
        p.setVolume(0f)              // 静音起步：残留即使存在也在无声中排空
        activeDsp?.prepareFadeIn()   // 显式压 0 起步（防御 pauseDrain 外路径残留增益）
        p.play()                     // 硬件在静音数据下稳定启动，无 start 瞬态
        waitDrain(p, resumeDrainMs, tk, { it == pauseToken && pendingSeekTarget == null }, done = {
            if (tk == pauseToken && p === activePlayer) {
                setFullVolume(p)         // 此刻 buffer 已排空、输出静音，无 0→1 硬台阶
                activeDsp?.fadeIn(150)   // 150ms 长渐变盖硬件恢复期，平滑接入
            }
        })
        notifyPlaybackChanged()
    }

    // 下一首（到末尾就回到第一首；随机模式走独立洗牌队列）
    fun next(context: Context) {
        if (songPaths.isEmpty()) return
        if (loopMode == 3) {
            shuffleNext(context)
            return
        }
        play((currentIndex + 1) % songPaths.size, context)
    }

    // 上一首（到头就跳最后一首；随机模式走独立洗牌队列）
    fun previous(context: Context) {
        if (songPaths.isEmpty()) return
        if (loopMode == 3) {
            shufflePrevious(context)
            return
        }
        play((currentIndex - 1 + songPaths.size) % songPaths.size, context)
    }

    // 随机模式：整队重新洗牌一次，取第一首为目标（每一步都随机，结果不可预测）
    private fun shuffleNext(context: Context) {
        if (songPaths.size < 2) {
            if (songPaths.size == 1) play(0, context)
            return
        }
        play(reshuffledTarget(), context)
    }

    // 随机模式：同样整队重新洗牌取第一首（上一首也随机，不按历史回退）
    private fun shufflePrevious(context: Context) {
        if (songPaths.size < 2) {
            if (songPaths.size == 1) play(0, context)
            return
        }
        play(reshuffledTarget(), context)
    }

    // 基于当前实际播放列表整队重洗，取第一首为目标；若恰为当前正在播的歌则顺延下一格（避免"下一首原地重播"）
    private fun reshuffledTarget(): Int {
        rebuildShuffleQueue()
        var target = shuffleQueue.first()
        if (target == currentIndex && shuffleQueue.size > 1) {
            target = shuffleQueue[1]
        }
        return target
    }

    // 基于当前实际播放列表重新洗牌（Fisher-Yates 由标准库 shuffle 保证），原列表不动
    private fun rebuildShuffleQueue() {
        shuffleQueue.clear()
        for (i in 0 until songPaths.size) shuffleQueue.add(i)
        shuffleQueue.shuffle()
    }

    // ========== 队列删除/重定位：删歌、扫描等队列变化后，保证 currentIndex 与 UI、实播一致 ==========

    // 从播放器队列中剔除一首歌（歌单/文件夹上下文删歌时用，只动队列不动播放实例）：
    // 五数组同步 removeAt + currentIndex 基础校正（被删下标 < currentIndex 则前移一格）。
    // 被删的正是当前播放曲（下标相等）时保持原下标——新数组该位置就是原下一首；
    // 若原下标是末位（删后越界），收尾到新数组末位。队列里没有这首歌则什么都不做。
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
    }

    // 队列被整体替换（pushToPlayer/扫描/删除）后，按"当前实际播放的媒体 path"重定位 currentIndex：
    // - 当前播放的歌仍在队列 → 精确指到它（避免下标前移导致的 UI 与实播错位）
    // - 已不在队列（被删/外部文件消失）→ 指向新数组原位置（= 原下一首）；越界则收尾；队列空则 -1
    // - 传入 context 且当前确实在播被删的歌时，主动 play() 切到原下一首，让 UI 与实播一致
    //   （play 走成熟的交叉淡入切歌路径，无爆音；空队列或未开播时不切）
    fun realignCurrentIndex(context: Context? = null) {
        val playingPath = activePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        val newIdx = if (playingPath.isNullOrEmpty()) -1 else songPaths.indexOf(playingPath)
        if (newIdx >= 0) {
            currentIndex = newIdx
            return
        }
        if (songPaths.isEmpty()) {
            currentIndex = -1
            return
        }
        if (currentIndex >= songPaths.size) currentIndex = songPaths.size - 1
        if (context != null && activePlayer != null && currentIndex >= 0) {
            // 当前播放实例还停在已删的歌上：把实例切到新队列原位置（= 原下一首）
            // 播放中 → 交叉淡入切歌继续响；暂停中 → 切过去后立即恢复暂停（不打破暂停态）
            val wasPlaying = activePlayer?.isPlaying == true
            play(currentIndex, context)
            if (!wasPlaying) pause()
        }
    }

    // 当前歌名
    fun currentName(): String =
        if (currentIndex in songNames.indices) songNames[currentIndex] else ""

    // 详情页显示用歌名：ID3 歌名优先（列表页已算好回退），没同步到时回退文件名
    fun currentDisplayName(): String =
        if (currentIndex in id3Titles.indices && id3Titles[currentIndex].isNotBlank()) id3Titles[currentIndex]
        else currentName()

    // 详情页显示用歌手：空串=未读到，UI 层转「未知歌手」
    fun currentArtist(): String =
        if (currentIndex in id3Artists.indices) id3Artists[currentIndex] else ""

    // 当前歌所在文件夹（找不到返回空串）
    fun currentDir(): String =
        if (currentIndex in songDirs.indices) songDirs[currentIndex] else ""

    // 播放位置（毫秒）
    fun currentPosition(): Int = activePlayer?.currentPosition?.toInt() ?: 0
    fun duration(): Int {
        val d = activePlayer?.duration ?: return 0
        return if (d == C.TIME_UNSET || d <= 0) 0 else d.toInt()
    }

    // 拖动进度条开始（网上标准：拖动期间静音，松手才真正 seek）：
    // setVolume(0) 作用于 AudioTrack 输出端，buffer 里已写入的满音量旧数据也瞬间静音，
    // 拖动全程无声；processor 同步归零（后续帧静音，数据流连续防 underrun），松手 seek 时
    // Media3 flush 截断必在无声中进行。暂停中拖动无需静音（seek 无输出）。
    fun beginSeek() {
        val p = activePlayer ?: return
        if (!p.isPlaying) return
        p.setVolume(0f)
        if (speed != 1f || pitchSemitones != 0) {
            // 拖条踢引擎：变速/变调状态下拖动进度条，先把引擎踢出处理链
            //（参数归 1 + 清缓冲 → processor 直通，与"未变速未变调拖条"完全同一路径），
            // 松手 seek 完成后再由 restoreSonic 恢复参数重新武装，杜绝引擎窗口重建起振
            // 瞬态在变速拖条期间干扰
            activeDsp?.suspendSonic()
        } else {
            activeDsp?.prepareFadeIn()
        }
    }

    // 拖动进度条
    fun seekTo(posMs: Int) {
        val p = activePlayer ?: return
        if (p.isPlaying) {
            // 网上标准：拖动期间 beginSeek 已硬件静音（setVolume(0) 立即罩住 AudioTrack
            // buffer 里已写入的满音量旧数据）+ processor 归零（后续帧静音），拖动全程无声。
            // 松手 seek 直接调 Media3：内部 flush+prefill，截断发生在硬件静音中，听不到爆音；
            // 不再 waitDrain 猜排空时长——硬件音量 0 本身就是最可靠的静音罩，排空与否无所谓。
            // 这里 setVolume(0)+归零是 beginSeek 的兜底（非 UI 入口调用 seekTo 也安全）。
            p.setVolume(0f)
            pendingSeekTarget = posMs.toLong()
            // 拖条踢引擎：变速/变调 seek 前先把引擎踢出处理链（同 beginSeek），
            // seek 期间 processor 与"未变速未变调"完全同一直通链；seek 完成回调里
            // restoreSonic 恢复参数重新武装；非变速用 prepareFadeIn 同步归零。
            if (speed != 1f || pitchSemitones != 0) {
                activeDsp?.suspendSonic()
            } else {
                activeDsp?.prepareFadeIn() // 同步强制归零：seek 后写入 buffer 的必须是静音数据
            }
            p.seekTo(posMs.toLong())
        } else {
            // 暂停中 seek：无输出，直接 seek
            p.seekTo(posMs.toLong())
        }
        // 拖动/跳转即落盘当前位置（恢复续播从这里继续）
        appContext?.let { saveResumeState(it) }
        notifyPlaybackChanged()
    }

    // ========== 播放记忆：SharedPreferences 全量存队列 + 位置 + 来源，冷启动恢复续播 ==========
    // 队列来源由 MusicFragment 同步（主列表=all，歌单=playlist，文件夹=folder）
    var resumeSource = "all"
    var resumePlaylistName: String? = null
    var resumeFolderUri: String? = null

    // 播放记忆数据（loadResume 解析出的结构，供 MusicFragment 恢复编排用）
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

    // 初始化播放记忆：缓存 prefs + 启动位置心跳（每 10s 落盘一次，杀进程也不丢太多进度）
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

    // 播放记忆总开关：与设置页"播放记忆"开关同源（jiepi_data/sw_resume，默认关）
    // 默认关闭：播放记忆是可选功能，开关没开就按老样子放歌（不续播、不恢复队列）
    fun isResumeEnabled(context: Context): Boolean =
        context.getSharedPreferences("jiepi_data", Context.MODE_PRIVATE).getBoolean("sw_resume", false)

    // 启动自动播放开关：与设置页"启动自动播放"开关同源（jiepi_data/sw_autoplay，默认关）
    // 独立于播放记忆：开=冷启动无条件直接开播（记忆能接就接上次位置，接不上播全量第一首）
    fun isAutoPlayEnabled(context: Context): Boolean =
        context.getSharedPreferences("jiepi_data", Context.MODE_PRIVATE).getBoolean("sw_autoplay", false)

    // 保存播放记忆：切歌/暂停/拖动/心跳时调用（开关关闭时不写，重开时恢复的是关闭前状态）
    fun saveResumeState(context: Context) {
        if (!isResumeEnabled(context)) return
        if (songPaths.isEmpty()) return
        persistResumeNow()
    }

    // 落盘：队列全量 path + 当前位置 + 播放位置 + 来源（队列可能几百首，JSON 字符串存 SharedPreferences，apply 异步无感）
    private fun persistResumeNow() {
        val p = resumePrefs ?: return
        try {
            // 逐曲记忆：当前歌的进度单独落盘（切歌跑路再回来，从上次位置续上）
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

    // 逐曲记忆：当前歌的进度单独落盘（path -> 上次播放位置 ms）。
    // 心跳/暂停/拖动/切歌都会更新当前歌；点歌时读出来 seekTo 续上，切歌互不影响。
    // 受播放记忆开关约束：开关关闭不写（读取侧同样受约束，开关关掉点歌从头放）。
    // 防错位：外部替换 songPaths 后 currentIndex 还是旧值，此时 activePlayer 的媒体
    // 与 songPaths[currentIndex] 不一致（歌单/文件夹点歌场景），跳过不写，避免污染别首歌。
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

    // 读取某首歌上次的播放位置（ms，0=无记忆）；MusicFragment 点歌前调用，超过最小阈值才续播
    fun readTrackPosition(context: Context, path: String): Int {
        return try {
            val p = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            val raw = p.getString(PER_TRACK_KEY, null) ?: return 0
            JSONObject(raw).optInt(path, 0)
        } catch (_: Exception) {
            0
        }
    }

    // 读取播放记忆（无数据/解析失败返回 null）
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

    // ========== 播放完成循环控制（替代 MediaPlayer.setOnCompletionListener） ==========
    // 实例专属 listener 见 makeListener()：ENDED 时仅当自己是 activePlayer 才处理歌结束

    private fun handleSongEnded() {
        // 播完当前：暂停并清掉定时状态
        if (stopAfterCurrent) {
            stopAfterCurrent = false
            timerMode = 5
            stopTimer()
            pause()
            return
        }
        val ctx = appContext
        when (loopMode) {
            1 -> { // 单曲循环：重播当前
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
            3 -> { // 随机：每次切歌整队重新洗牌取第一首（每一步都随机、不可预测）
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

    // ========== DSP 音效管理（替代 audiofx） ==========

    // 任何 EQ/音效参数变化后调用：把当前状态实时刷进双 DSP 管线（A/B 实例各自一套，
    // 交叉淡入时另一个实例随时可能接棒，参数必须同步），并落盘缓存
    fun applyAudioFxState() {
        dspA.setEqLevels(eqBandLevels)
        dspB.setEqLevels(eqBandLevels)
        dspA.setEffects(
            vocalSuppress, effectDynamic, effectHifi,
            effectHigh, effectLow, effectSurround, effectReverb
        )
        dspB.setEffects(
            vocalSuppress, effectDynamic, effectHifi,
            effectHigh, effectLow, effectSurround, effectReverb
        )
        persistFxState()
    }
}
