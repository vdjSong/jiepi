package com.jiepi.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

// 应用内 DSP 音效处理器：完全绕开系统 audiofx，任何 ROM（LineageOS/国产 ROM）都生效
// 功能链：10 段参数 EQ（双二阶 IIR 级联，含伴奏中频抑制/高音加强/高保真激励/低音加强）
//        → 混响（Schroeder 简化：16 梳状并联 + 5 全通串联，按干湿比混入）
//        → 环绕（M/S 立体声拓宽）
//        → 动态推进（tanh 软限幅增益）
// 只处理 PCM 16bit，输出与输入同格式同采样率，不改变采样数
class JiepiAudioProcessor : AudioProcessor {

    // 10 段 EQ 中心频率（Hz），与 UI 标签一致（32/64/125/250/500/1K/2K/4K/8K/16K）。
    // 首段 32Hz 覆盖超低频，与 UI 第一档位对齐。
    private val eqFreqs = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    // 每段双二阶系数 b0 b1 b2 a1 a2
    private val eqCoeffs = Array(10) { FloatArray(5) }
    // 每声道每段状态 [ch][band][x1, x2, y1, y2]
    private val eqState = Array(2) { Array(10) { FloatArray(4) } }

    // 混响：16 梳状 + 4 全通（Freeverb 结构）+ 预延迟 + 高频阻尼。
    // 延迟表用 Freeverb 式非等差错开（25.31~49.73ms，间隔 52~134 样本不规则、无公共因子），
    // 避免等距延迟的共振峰周期对齐——等距步进会使 16 个梳状在 630/3125/6875Hz 等频点
    // 16 重叠加，反馈稍高就激发成窄带"吱"声。
    // 窗口 25~50ms：落在直达声加固区（30~50ms）内并含低端回声密度层；上限收在
    // CCRMA 回声感知阈（反射超 40~50ms 易被感知为独立回声）以内。
    // 梳状反馈 0.86、阻尼 0.55、总低通 0.30、全通反馈 0.45、预延迟 45ms、干湿比 0.35。
    private val combDelays = floatArrayOf(
        0.02500f, 0.02609f, 0.02736f, 0.02873f, 0.03018f, 0.03182f, 0.03355f, 0.03536f,
        0.03727f, 0.03927f, 0.04136f, 0.04355f, 0.04555f, 0.04736f, 0.04882f, 0.05000f
    )
    // 全通延迟表：全部缩在 <10ms 无色界限内且样本数互质（@48k：466/350/269/197/139，
    // 139 为质数，五者两两无公因子），直通副本叠加不产生共振发色；
    // 5 级全通串联（Schroeder 原版结构）保证回声密度。
    private val allpassDelays = floatArrayOf(0.0097f, 0.0073f, 0.0056f, 0.0041f, 0.0029f)
    // 梳状反馈 0.86：对标 Freeverb 经典 0.84 略高一档，尾音更长空间更饱满；
    // 仍远低于 DC 稳定上限（0.98），安全。
    private val combFeedback = FloatArray(16) { 0.86f }
    // 全通反馈 0.45：扩散降档，回声摆远变淡，避免混响过浓/朦胧。
    private val allpassFeedback = 0.45f
    // 高频阻尼强度（内部 alpha=1/(1+damp)，damp=0→直通、∞→完全无高频）
    // 阻尼 0.55：高频衰减放慢一档，尾音更亮更透。
    private val damp = 0.55f
    private val dampAlpha = 1f / (1f + damp) // ≈0.645：一次反射后高频保留更多
    private var combLowpass = Array(2) { FloatArray(16) } // [ch][n] 每个梳状反馈路径的低通状态
    private var preDelayBuf: Array<FloatArray> = emptyArray() // [ch][delay line]，预延迟缓冲
    private var preDelayIdx = IntArray(0)
    private var preDelayLen = 0
    private var combBuf: Array<Array<FloatArray>> = emptyArray() // [ch][n][delay line]
    private var combIdx = emptyArray<IntArray>()
    private var allpassBuf: Array<Array<FloatArray>> = emptyArray()
    private var allpassIdx = emptyArray<IntArray>()
    // 湿声总低通状态 [ch]：混响链末端再削一道高频，返回声彻底朦胧（消除"高频跟在后面"的跟拍感）
    private var reverbLpState = FloatArray(0)
    // 湿声高通状态 [ch]（xPrev/yPrev）。业界标准：湿声路径高通砍 200Hz 以下低频泥巴，
    // 低频混响变少、高频仙气通透。一阶高通 α≈0.0255 @48k（f_c≈200Hz）。
    private var reverbHpState = FloatArray(0)
    private var reverbHpXPrev = FloatArray(0)

    // 输入/输出格式状态
    private var sampleRate = 48000
    private var channelCount = 2
    private var active = false
    private var ended = false
    private var outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())

    // 常驻峰值限幅器缓冲：复用避免每块分配（输出端兜底防削波爆音）
    private var limiterBuf = FloatArray(0)
    private var limiterBufR = FloatArray(0)
    // 分频限幅：低频段/高频段各自独立峰值检测与平滑增益，
    // 低频超 0.94 只压低频段、不再连坐压掉高音（泵动效应根治）
    private var limSmoothGainLow = 1f
    private var limSmoothGainHigh = 1f
    // LR4 分频器：交叉点 250Hz、24dB/oct（低通/高通各 2 个二阶 Butterworth biquad 级联，
    // Q=1/√2，网上公式核实）。系数 Double 计算防低频量化噪声；滤波链常运行、不触发时
    // 增益=1（禁直通绕过，避免滤波拓扑突变频谱跳变爆音）
    private val lr4CrossoverHz = 250.0
    private var lr4LpCoeff = FloatArray(5) // 归一化 [b0,b1,b2,a1,a2]
    private var lr4HpCoeff = FloatArray(5)
    private var lr4State = Array(2) { Array(4) { FloatArray(4) } } // [ch][0..1 LP, 2..3 HP][x1,x2,y1,y2]
    private var lr4InitSampleRate = 0
    private var limiterLow = Array(2) { FloatArray(0) } // [ch][f] 低频段缓冲
    private var limiterHigh = Array(2) { FloatArray(0) } // [ch][f] 高频段缓冲
    // 切歌防 pop：旧歌淡出到 0 再停、新歌从 0 淡入，消除 AudioTrack 硬停/重启的波形截断声。
    // 手动切歌是"播放中硬切"，stop 瞬间波形被截断会出一声咔哒；自动切歌歌已自然播完所以没声。
    private var fadeGain = 1f
    private var fadeTarget = 1f
    // 淡入淡出时长（ms）：默认 25ms 保切歌手感；resume 恢复播放用 150ms 长渐变盖 AudioTrack
    // 硬件恢复期（网上标准 issue #3336：pause 后硬件仍播 50~150ms 残余，play 空缓冲启动有瞬态）。
    private var fadeMs = 25

    // 变速 / 变调（Rubber Band 引擎，NDK 编译 librubberband.so）
    // 不走 ExoPlayer PlaybackParameters：那会让 Media3 Sonic + 整条 DSP 链 flush 重置，输出空窗爆音。
    // Rubber Band 原生支持流内实时调参（RealTime 模式），且内部自带参数平滑——setTimeRatio /
    // setPitchScale 的新目标会在引擎内部平滑过渡到新值。因此这里不再逐样本微调（逐样本改目标
    // 只会让内部平滑器永远追不上 → 引擎持续重同步 → 输出间歇停滞 avail==0 → 变速断续），
    // 改为每个音频块推进一次目标值，约 6 块（100~200ms）渐变到新值，块间过渡由引擎内部平滑完成。
    // 与 seek/切歌的门控（armSonicGate）配合：窗口重建期静音帧兜底，恢复输出淡入，杜绝卡顿爆音。
    private var sonic: RubberBandStream? = null
    @Volatile private var targetSpeed = 1f
    @Volatile private var targetPitch = 1f
    private var fadeFromSpeed = 1f
    private var fadeFromPitch = 1f
    private var fadeRemaining = 0
    private var fadeTotal = 1
    // 渐变块数：每个音频块（Media3 常见 480~2048 帧 ≈ 10~43ms@48k）调一次参数，
    // 6 块 ≈ 100~260ms 平滑渐变到目标（引擎内部平滑填充块间过渡）
    private val FADE_BLOCK_COUNT = 6
    private var sonicScratch = ShortArray(0) // Rubber Band 输入/输出中转（交错 PCM16）
    // 变速窗口门控：seek/切歌后 Sonic reset 重建内部预缓冲窗口期间（首批输出未出现），
    // processor 保持输出静音；Sonic 恢复输出那一刻解除门控并启动淡入——数据流触发
    // 替代固定时钟等待（时钟盖不住更深窗口 → 首批数据以满增益轰出 → 爆音漏网）
    @Volatile private var sonicGateActive = false
    // 静音帧标记：avail==0 时刚输出过静音帧（Sonic 窗口重建/变速渐变适应/缓冲不足）。
    // avail>0 恢复输出时用它区分"从意外静音恢复"（需淡入防突变小尾巴）与正常播放
    // （保持当前 fadeTarget，切歌 fadeOut 等主动流程不受干扰）
    @Volatile private var silentSent = false
    // 变速 seek/切歌门控解除后的窗口稳定期（样本数）：Sonic resetBuffers 后恢复输出时，
    // 引擎内部还在锁定新位置的基音周期/窗相位，头 ~100ms 输出有抖动瞬态（变速拖条
    // "短杂音"的嫌疑点）。恢复输出后先静音稳定期，再 fadeIn(500) 平滑接入。
    private var gateStableSamples = 0
    // 淡入完成同步信号（RubberBand 与 Media3 seek 配合的关键）：fadeGain 从 0 爬到 1
    // （fadeIn 真正完成、引擎预热伪影已平滑接入）时置 true。PlayerManager 的 seek 完成
    // 回调（onPositionDiscontinuity）据此决定何时恢复硬件音量——变速/变调 seek 后引擎
    // 要经历窗口重建→稳定期→500ms 淡入，seek 完成回调抢跑 setVolume(1f) 会把预热中段的
    // 抖动瞬态从渐变里轰出来（变速/变调拖条杂音的根因）。非变速直通无预热，不受影响。
    @Volatile private var fadeInDoneNotified = false
    // seek 完成回调注册的"淡入完成"监听：播放线程渐变爬到 1 时触发一次
    @Volatile var onFadeInDone: (() -> Unit)? = null

    // 线程安全：UI 线程设置目标值，播放线程逐帧平滑过渡
    fun setSpeedPitch(speed: Float, pitch: Float) {
        synchronized(this) {
            val s = speed.coerceIn(0.5f, 2f)
            val p = pitch.coerceIn(0.5f, 2f)
            if (s == targetSpeed && p == targetPitch) return
            fadeFromSpeed = sonic?.getSpeed() ?: targetSpeed
            fadeFromPitch = sonic?.getPitch() ?: targetPitch
            targetSpeed = s
            targetPitch = p
            fadeRemaining = fadeTotal
        }
    }

    fun setSpeed(speed: Float) { setSpeedPitch(speed, targetPitch) }
    fun setPitch(pitch: Float) { setSpeedPitch(targetSpeed, pitch) }

    // 判定"真正需要 Sonic"：目标非原速，或当前实际速度/音调还在渐变中（未回到 1）。
    // 与 queueInput 的 needSonic 同一套标准；PlayerManager 用它区分变速/变调路径与直通，
    // 避免"恢复原速但渐变未完成"时误判走非变速快速淡入（引擎预热伪影被快速渐变放大 = 传染）。
    fun isSonicActive(): Boolean {
        val s = sonic ?: return false
        return Math.abs(targetSpeed - 1f) > 1e-4f || Math.abs(targetPitch - 1f) > 1e-4f ||
            Math.abs(s.getSpeed() - 1f) > 1e-3f || Math.abs(s.getPitch() - 1f) > 1e-3f
    }

    // 淡入完成且门控未被新一轮 seek/切歌复位（fadeTarget 仍为 1、fadeGain 已爬到 1）：
    // PlayerManager 的 onFadeInDone 回调据此校验后恢复硬件音量，防止"事件触发后用户立刻
    // 再次拖条"的竞态窗口里把新 seek 的静音罩提前捅破。
    fun isFadeInDone(): Boolean =
        fadeInDoneNotified && fadeTarget == 1f && fadeGain >= 1f

    // 每个音频块调用一次（processFrames 入口）：把 Rubber Band 参数向目标推进 1/fadeTotal。
    // 不逐样本调参：RubberBand realtime 内部自带参数平滑，逐样本改目标会阻止平滑器收敛
    // （引擎持续重同步 → 输出间歇停滞 → 变速加快断续的根源），块级推进让引擎块间平滑过渡。
    private fun advanceFade() {
        if (fadeRemaining <= 0) return
        fadeRemaining--
        val k = if (fadeTotal > 0) 1f - fadeRemaining.toFloat() / fadeTotal else 1f
        sonic?.setSpeed(fadeFromSpeed + (targetSpeed - fadeFromSpeed) * k)
        sonic?.setPitch(fadeFromPitch + (targetPitch - fadeFromPitch) * k)
    }

    // 音效参数（0~100 的百分比在 setter 里归一化成 0~1）
    private var eqLevels = FloatArray(10) // -12 ~ +12 dB
    private var vocalSuppress = 0f
    private var effectDynamic = 0f
    private var effectHifi = 0f
    private var effectHigh = 0f
    private var effectLow = 0f
    private var effectSurround = 0f
    private var effectReverb = 0f

    fun setEqLevels(levels: FloatArray) {
        eqLevels = levels.copyOf()
        rebuild()
    }

    fun setEffects(
        vocalSuppress: Int, effectDynamic: Int, effectHifi: Int,
        effectHigh: Int, effectLow: Int, effectSurround: Int, effectReverb: Int
    ) {
        this.vocalSuppress = vocalSuppress / 100f
        this.effectDynamic = effectDynamic / 100f
        this.effectHifi = effectHifi / 100f
        this.effectHigh = effectHigh / 100f
        this.effectLow = effectLow / 100f
        this.effectSurround = effectSurround / 100f
        this.effectReverb = effectReverb / 100f
        rebuild()
    }

    // 合成最终各段 dB → 重算 EQ 双二阶系数，并重建混响延时线
    private fun rebuild() {
        val finalDb = FloatArray(10)
        for (i in 0..9) {
            var db = eqLevels[i]
            // 伴奏人声抑制：只压人声主频带，不碰 8k 以上乐器高频区。
            // 旧写法 4~7 段一刀切，8k（镲片/弦泛音等乐器特征）也被压掉，听感就是"中频乐器被削"。
            // 新写法：2kHz 人声主体压满，1k/4k 侧翼减半，500Hz 胸腔共鸣轻微压，8k/12k/16k 全保留。
            if (i == 5) db -= vocalSuppress * 12f
            if (i == 4 || i == 6) db -= vocalSuppress * 6f
            if (i == 3) db -= vocalSuppress * 3f
            if (i in 8..9) db += effectHigh * 5.0f // 高音增强（上限 5.0dB）
            if (i >= 5) db += effectHifi * 3.0f // 超高保真激励（上限 3.0dB）
            if (i <= 2) db += effectLow * 2.6f // 低音增强（上限 2.6dB）
            finalDb[i] = db.coerceIn(-12f, 12f)
        }
        val fs = sampleRate.toFloat()
        for (band in 0..9) {
            val f0 = eqFreqs[band]
            val q = 1.2f
            val db = finalDb[band]
            val a = 10f.pow(db / 40f)
            val w0 = 2f * PI.toFloat() * f0 / fs
            val alpha = sin(w0) / (2f * q)
            val cosW = cos(w0)
            val b0 = 1f + alpha * a
            val b1 = -2f * cosW
            val b2 = 1f - alpha * a
            val a0 = 1f + alpha / a
            val a1 = -2f * cosW
            val a2 = 1f - alpha / a
            eqCoeffs[band][0] = b0 / a0
            eqCoeffs[band][1] = b1 / a0
            eqCoeffs[band][2] = b2 / a0
            eqCoeffs[band][3] = a1 / a0
            eqCoeffs[band][4] = a2 / a0
        }
        initReverb()
    }

    private fun initReverb() {
        val ch = channelCount.coerceIn(1, 2)
        // 注意：内层 lambda 参数 n 是梳状/全通索引（不是声道索引）。
        // 每级缓冲长度必须按各自延时表计算（16 级非等差错开），声道间复用同一延时表；
        // 若误用声道索引会导致各级同长、首反射同刻叠加成一道强回声。
        combBuf = Array(ch) { _ -> Array(16) { n -> FloatArray((sampleRate * combDelays[n]).toInt().coerceAtLeast(100)) } }
        combIdx = Array(ch) { IntArray(16) }
        allpassBuf = Array(ch) { _ -> Array(5) { n -> FloatArray((sampleRate * allpassDelays[n]).toInt().coerceAtLeast(20)) } }
        allpassIdx = Array(ch) { IntArray(5) }
        // 预延迟 45ms：干声先到、混响核心晚 45ms 启动——空间距离感拉开但不失紧凑
        preDelayLen = (sampleRate * 0.045f).toInt().coerceAtLeast(1000)
        preDelayBuf = Array(ch) { FloatArray(preDelayLen) }
        preDelayIdx = IntArray(ch)
        reverbLpState = FloatArray(ch)
        // 湿声高通状态初始化
        reverbHpState = FloatArray(ch)
        reverbHpXPrev = FloatArray(ch)
        for (c in 0 until 2) combLowpass[c].fill(0f)
    }

    // ========== AudioProcessor 接口 ==========

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        // Rubber Band 按声道创建：切歌时 mono/stereo 交替必须重建，否则交错缓冲错乱。
        // 采样率变化不必重建（Rubber Band 构造时按 sampleRate 建窗），但渐变时长要按新采样率重算。
        val cur = sonic
        if (cur == null || cur.getChannels() != channelCount) {
            sonic = RubberBandStream(sampleRate, channelCount)
            sonic?.setSpeed(targetSpeed)
            sonic?.setPitch(targetPitch)
        }
        fadeTotal = FADE_BLOCK_COUNT // 渐变块数（不再按样本数：逐样本调参见 advanceFade 注释）
        sonicScratch = ShortArray(0)
        rebuild()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) return
        val bytes = inputBuffer.remaining()
        if (bytes < 2) return
        val ch = channelCount.coerceIn(1, 2)
        val frames = bytes / 2 / ch
        if (frames == 0) return

        // ---- 0) 变速/变调（RubberBand NDK 引擎，放在 DSP 链最前） ----
        val s = sonic
        // 变速门控开关：避免变速状态传染正常拖条。
        // sonic 实例一旦创建就永远挂在链上，即使 setSpeed(1.0) 恢复原速，Sonic 内部仍有
        // 预缓冲窗口——seek 时 resetBuffers 重建窗口产生的起振瞬态，会传染给正常拖条。
        // 判定"真正需要 Sonic"：目标非原速，或当前实际速度/音调还在渐变中（未回到 1）。
        // 恢复原速后渐变完成（实际≈1）→ 直接绕开 Sonic 走直通链，彻底与变速状态隔离。
        val needSonic = s != null && isSonicActive()
        if (s != null && needSonic) {
            val need = frames * ch
            if (sonicScratch.size < need) sonicScratch = ShortArray(need)
            // 直接顺序读 short：读完 position 自动到 limit，表示本块输入已被消费。
            // 禁止用 mark/reset 回退——Media3 认为缓冲未消费会卡住整个管线，表现就是无声。
            for (i in 0 until need) sonicScratch[i] = inputBuffer.short
            if (!s.writeShort(sonicScratch, frames)) {
                inputBuffer.position(inputBuffer.limit())
                return
            }
            val avail = s.samplesAvailable()
            if (avail > 0) {
                if (sonicScratch.size < avail * ch) sonicScratch = ShortArray(avail * ch)
                val got = s.readShort(sonicScratch, avail)
                // 变速窗口门控解除：Sonic 恢复输出 = 内部预缓冲窗口已重建完成。
                // armSonicGate 把 fadeTarget 置 0 是"窗口期静音"，Sonic 恢复输出那一刻必须
                // 把目标拉回 1（fadeIn）启动淡入。若只解除 gate 不拉回目标，processor 将
                // 永远以 fadeGain=0 输出 → 变速拖条后进度条在走但永久无声。
                // 150ms 长渐变：Sonic 恢复输出首批数据带窗口重建的起振瞬态，默认 25ms
                // 渐变爬升太快盖不住（变速拖条"很短杂音"的嫌疑点），顶到 150ms 平滑接入，
                // 代价仅是恢复接入略慢几十毫秒，无爆音更重要。
                if (sonicGateActive) {
                    sonicGateActive = false
                    // 窗口稳定期：Sonic 恢复输出头 ~100ms 引擎还在锁定新位置窗相位，
                    // 先静音稳定（fadeTarget 保持 0、fadeGain 压 0），等锁定完成再淡入。
                    // 变速拖条"短杂音"即使 500ms 淡入也盖不住中间段抖动，
                    // 必须让引擎先稳定下来，fadeIn 才真正是"平滑接入"而非"平滑放大抖动"。
                    gateStableSamples = (sampleRate / 10).coerceAtLeast(2048)
                }
                // 从静音帧恢复（刚输出过静音）：Sonic 恢复输出那一刻 fadeGain 已在
                // 静音期被压 0，fadeTarget 仍为 1 → 自动从 0 淡入，防"静音→满增益"
                // 突变产生恢复瞬态小尾巴（普通拖条/变速加快"断-小尾巴-接上"的根源）。
                // 非恢复路径（正常播放 / 切歌 fadeOut 中）不碰 fadeTarget，互不干扰。
                if (silentSent) silentSent = false
                if (gateStableSamples > 0) {
                    // 稳定期：强制静音输出，不启动淡入
                    gateStableSamples -= got
                    fadeGain = 0f
                    fadeTarget = 0f
                } else if (fadeTarget != 1f) {
                    // 稳定期结束才启动淡入（长渐变上限，与 fadeOut 对称）
                    fadeIn(500)
                }
                processFrames(sonicScratch, got, ch)
            } else {
                // avail==0：Sonic 窗口重建期 / 变速渐变适应期 / 缓冲不够一个基音周期。
                // 无条件填静音帧保持 AudioTrack 数据流连续，防 underrun"断音"（原非门控
                // 分支输出空 buffer → 硬件 underrun → 断一下接一下）；若此时是正常播放
                // 被意外打断（fadeTarget==1），把 fadeGain 压到 0，恢复输出时由 silentSent
                // 触发淡入，平滑接入无瞬态。主动静音流程（fadeOut / prepareFadeIn / gate
                // 已置 fadeTarget=0）不压增益，尊重原流程的淡入淡出状态。
                outputBuffer.clear()
                val silentBytes = (frames * ch * 2).coerceAtLeast(4096)
                if (outputBuffer.capacity() < silentBytes) {
                    outputBuffer = ByteBuffer.allocateDirect(silentBytes).order(ByteOrder.nativeOrder())
                }
                silentSent = true
                if (fadeTarget == 1f) fadeGain = 0f
                var n = silentBytes / 2
                while (n-- > 0) outputBuffer.putShort(0)
                outputBuffer.flip()
            }
        } else {
            // 直通分支（无 Sonic / 已恢复原速原调绕开 Sonic）
            // 门控残留清理：变速 seek/切歌的 Sonic 静音期若尚未解除（armSonicGate 把
            // fadeTarget 压 0）就恢复原速绕开 Sonic，Sonic 恢复输出分支再也不会触发，
            // 必须在此主动 fadeIn(500) 拉回音量目标，否则 processor 永远 0 增益 →
            // 进度条在走但永久无声（与变速拖条无声回归同一陷阱）。
            // 主动 fadeOut（切歌静音）时 sonicGateActive 为 false，此处不碰，尊重原流程。
            if (sonicGateActive) {
                sonicGateActive = false
                fadeIn(500)
            }
            // 无 Sonic（异常兜底）：直接走链
            val inShort = ShortArray(frames * ch)
            for (i in inShort.indices) inShort[i] = inputBuffer.short
            processFrames(inShort, frames, ch)
        }
    }

    // 把交错 PCM16 帧逐帧过 DSP 链；frames 为 0 时仅清空输出缓冲
    private fun processFrames(inShort: ShortArray, frames: Int, ch: Int) {
        // 变速会改变样本数：最多 4 倍（0.25x 慢放），输出缓冲按需扩容
        val needBytes = (frames.toLong() * ch * 8L + 4096L).toInt().coerceAtLeast(65536)
        if (outputBuffer.capacity() < needBytes) {
            outputBuffer = ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
        }
        outputBuffer.clear()
        if (limiterBuf.size < frames) {
            limiterBuf = FloatArray(frames)
            limiterBufR = FloatArray(frames)
            limiterLow = Array(2) { FloatArray(frames) }
            limiterHigh = Array(2) { FloatArray(frames) }
        }
        advanceFade() // 变速渐变推进：每音频块调一次（不逐样本调参，见 advanceFade 注释）
        for (f in 0 until frames) {
            var l = inShort[f * ch].toFloat() / 32768f
            var r = if (ch == 2) inShort[f * ch + 1].toFloat() / 32768f else l

            // 1) EQ：10 段双二阶级联（含伴奏/高音/高保真/低音补偿）
            l = processEq(l, 0)
            r = processEq(r, if (ch == 2) 1 else 0)

            // 2) 混响：湿声按干湿比混入（100% 滑块湿声 30%，原声始终是主体，混响营造氛围）
            if (effectReverb > 0f) {
                val wl = processReverb(l, 0)
                val wr = if (ch == 2) processReverb(r, 1) else wl
                val mix = effectReverb * 0.35f // 干湿比 0.35：混响存在感加强，湿声更明显
                l += (wl - l) * mix
                r += (wr - r) * mix
            }

            // 3) 环绕：M/S 拓宽（100% 时侧信号 +2.0dB）
            if (ch == 2 && effectSurround > 0f) {
                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f
                val k = 1f + effectSurround * 0.9953f // 10^(6.0/20)≈1.9953，侧信号最多 +6.0dB
                l = m + s * k
                r = m - s * k
            }

            // 4) 动态推进：tanh 软限幅 + 干湿混合稀释。
            // 干声兜底 + 湿声缓升：低段几乎无感，100% 有效小信号增益约 +3.0dB。
            if (effectDynamic > 0f) {
                val g = 1f + effectDynamic * 0.9f // 压缩强度 1~1.9（小信号增益 +3.0dB）
                val wet = effectDynamic * 0.35f // 湿声比例 0~0.35
                val dry = 1f - wet * 0.8f // 干声兜底
                val norm = 1f / tanh(g)
                l = l * dry + tanh(l * g) * norm * wet
                r = r * dry + tanh(r * g) * norm * wet
            }

            limiterBuf[f] = l
            limiterBufR[f] = r
        }

        // 5) 常驻峰值限幅器：LR4 分频双频段独立限幅。
        // 若全频段一刀切：低频超 0.94 阈值会连坐压掉高音 → 泵动效应。
        // 方案：信号 → LR4 分频（交叉 250Hz、24dB/oct，低通/高通各 2 个二阶 Butterworth
        // 级联 Q=1/√2，网上公式核实）→ 低频段独立限幅 + 高频段独立限幅 → 求和输出。
        // 阈值 0.94、attack ~2ms 快压防削波、release ~300ms 缓释防 pumping（参数沿用）。
        // 系数 Double 精度计算防低频量化噪声；滤波链常运行、不触发时增益=1（禁直通绕过）。
        ensureLr4Coeffs()
        var lowPeak = 0f
        var highPeak = 0f
        for (f in 0 until frames) {
            // 每声道：低通 2 级级联 = LR4 低频段；高通 2 级级联 = LR4 高频段
            val a = limiterBuf[f]
            val b = limiterBufR[f]
            for (chIdx in 0 until 2) {
                val x = if (chIdx == 0) a else b
                val yL = processBiquad(processBiquad(x, lr4State[chIdx][0], lr4LpCoeff), lr4State[chIdx][1], lr4LpCoeff)
                val yH = processBiquad(processBiquad(x, lr4State[chIdx][2], lr4HpCoeff), lr4State[chIdx][3], lr4HpCoeff)
                limiterLow[chIdx][f] = yL
                limiterHigh[chIdx][f] = yH
                if (yL > lowPeak) lowPeak = yL
                if (-yL > lowPeak) lowPeak = -yL
                if (yH > highPeak) highPeak = yH
                if (-yH > highPeak) highPeak = -yH
            }
        }
        // 双频段各自独立目标增益：本段超 0.94 才压本段，不再跨段连坐
        val lowTargetGain = if (lowPeak > 0.94f) 0.94f / lowPeak else 1f
        val highTargetGain = if (highPeak > 0.94f) 0.94f / highPeak else 1f
        // attack：目标增益低于当前平滑值时快速压（~2ms 防削波）；release：高于当前时慢恢复（~300ms 防 pumping）
        val attackCoef = 1f - exp(-1f / (sampleRate * 0.002f))
        val releaseCoef = 1f - exp(-1f / (sampleRate * 0.3f))
        // 淡入淡出步长：fadeMs 毫秒内从 0 到 1（或反向），跟随每样本推进
        val fadeStep = 1f / (sampleRate * fadeMs / 1000f)
        for (f in 0 until frames) {
            if (fadeGain < fadeTarget) fadeGain = min(fadeGain + fadeStep, fadeTarget)
            else if (fadeGain > fadeTarget) fadeGain = max(fadeGain - fadeStep, fadeTarget)
            // 淡入完成检测：fadeGain 爬到 1（引擎预热伪影已平滑接入）→ 通知 PlayerManager
            // 恢复硬件音量（seek 完成回调抢跑 setVolume(1f) 会把预热中段抖动轰出来的根因）。
            // 每次触发一次（fadeInDoneNotified 由 armSonicGate/prepareFadeIn/suspendSonic 复位）。
            if (fadeTarget == 1f && fadeGain >= 1f && !fadeInDoneNotified) {
                fadeInDoneNotified = true
                onFadeInDone?.invoke()
            }
            // 低频段/高频段各自平滑逼近：低频轰不连坐高音（泵动效应根治）
            val coefL = if (lowTargetGain < limSmoothGainLow) attackCoef else releaseCoef
            limSmoothGainLow += (lowTargetGain - limSmoothGainLow) * coefL
            val coefH = if (highTargetGain < limSmoothGainHigh) attackCoef else releaseCoef
            limSmoothGainHigh += (highTargetGain - limSmoothGainHigh) * coefH
            // 求和输出：低段*低段增益 + 高段*高段增益（LR4 交叉点 -6dB、求和 0dB 平坦）
            val outL = (limiterLow[0][f] * limSmoothGainLow + limiterHigh[0][f] * limSmoothGainHigh) * fadeGain
            outputBuffer.putShort((outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            if (ch == 2) {
                val outR = (limiterLow[1][f] * limSmoothGainLow + limiterHigh[1][f] * limSmoothGainHigh) * fadeGain
                outputBuffer.putShort((outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        // 先把 Sonic 缓冲尾巴冲刷出来过 DSP 链，再宣告结束
        val s = sonic
        if (s != null) {
            s.flush()
            val avail = s.samplesAvailable()
            if (avail > 0) {
                val ch = channelCount.coerceIn(1, 2)
                if (sonicScratch.size < avail * ch) sonicScratch = ShortArray(avail * ch)
                val got = s.readShort(sonicScratch, avail)
                processFrames(sonicScratch, got, ch)
            }
        }
        ended = true
    }

    override fun getOutput(): ByteBuffer =
        if (outputBuffer.remaining() > 0) outputBuffer else AudioProcessor.EMPTY_BUFFER

    override fun isActive(): Boolean = active

    override fun isEnded(): Boolean = ended && outputBuffer.remaining() == 0

    // 切歌防 pop：UI 线程调用设置目标，播放线程在处理音频时逐样本逼近，天然跟随数据流无竞态
    // fadeOut(durationMs)：与 fadeIn 对称的时长参数；交叉淡入时新旧歌用同一时长
    //（时长顶到最大），避免"新歌 500ms 慢入 + 旧歌 25ms 突断"的不对称听感。
    fun fadeOut(durationMs: Int = 25) { fadeMs = durationMs.coerceIn(10, 500); fadeTarget = 0f }
    fun fadeIn(durationMs: Int = 25) { fadeMs = durationMs.coerceIn(10, 500); fadeTarget = 1f }
    // 双实例交叉淡入：目标实例预加载期间强制静音（fadeGain=0），防止满音量 PCM 提前
    // 写进 AudioTrack buffer，等 play() start 瞬间轰出 start 咔哒；READY 后由 fadeIn() 拉起
    fun prepareFadeIn() {
        fadeGain = 0f
        fadeTarget = 0f
        fadeInDoneNotified = false
    }
    // 武装变速窗口门控：seek/切歌后 Sonic reset 重建窗口期间保持静音，
    // Sonic 恢复输出那一刻（queueInput 检测 avail>0）自动解除并淡入
    fun armSonicGate() {
        sonicGateActive = true
        fadeGain = 0f
        fadeTarget = 0f
        fadeInDoneNotified = false
    }

    // 拖条踢引擎：拖动播放进度条期间临时把变速/变调引擎踢出处理链——
    // seek 是 Media3 flush+prefill，RubberBand resetBuffers 重建预缓冲窗口的起振瞬态
    // 正是变速/变调拖条杂音与"传染"正常拖条的源头。拖动期间让引擎完全不参与
    //（参数归 1 + 清缓冲 → needSonic=false → 走与"未变速未变调"相同的直通链），
    // seek 完成后再由 restoreSonic 恢复参数重新武装。
    // 思路与"按场景动态切换处理策略"一致：不需要变速处理的阶段直接让处理链直通。
    fun suspendSonic() {
        val s = sonic ?: return
        targetSpeed = 1f
        targetPitch = 1f
        fadeFromSpeed = 1f
        fadeFromPitch = 1f
        fadeRemaining = 0
        s.setSpeed(1f)
        s.setPitch(1f)
        s.resetBuffers()
        sonicGateActive = false
        fadeInDoneNotified = false
        fadeGain = 0f
        fadeTarget = 0f
    }

    // 拖动 seek 完成：恢复变速/变调参数，引擎从原速重新武装（resetBuffers + 从 1 渐变到目标，
    // 由 advanceFade 每音频块推进，块间过渡引擎内部平滑）。预热期（窗口重建→稳定期→500ms
    // 淡入）由 armSonicGate 门控接管，硬件音量恢复由 onFadeInDone 事件驱动（PlayerManager
    // seek 完成回调不抢跑 setVolume(1f)，防把预热中段抖动瞬态轰出）。
    fun restoreSonic(speed: Float, pitch: Float) {
        val s = sonic ?: return
        targetSpeed = speed.coerceIn(0.5f, 2f)
        targetPitch = pitch.coerceIn(0.5f, 2f)
        fadeFromSpeed = 1f
        fadeFromPitch = 1f
        fadeRemaining = fadeTotal
        s.resetBuffers()
        s.setSpeed(1f)
        s.setPitch(1f)
        armSonicGate()
    }

    override fun flush() {
        // Sonic 清缓冲但保留参数（seek/切歌不丢变速变调）；渐变直接归位目标值
        sonic?.resetBuffers()
        sonic?.setSpeed(targetSpeed)
        sonic?.setPitch(targetPitch)
        fadeRemaining = 0
        // fadeGain 字段刻意不在 flush 里归 1（保留调用方现值，由 fadeOut/fadeIn 控制）：
        // 手动切歌时 fadeOut 已把 fadeGain 压到 0，若这里拉回 1，新歌 AudioTrack
        // 重启瞬间会满音量轰出（start 咔哒），fadeIn 也因此失效——残留杂音的根因。
        // 保留 0 后由 fadeIn() 让新歌 45ms 淡入，正好盖住 AudioTrack start 的咔哒。
        // 冷启动（初始 1）与自动切歌（无人调 fadeOut，仍为 1）不受影响。
        for (ch in 0 until 2) for (b in 0 until 10) eqState[ch][b].fill(0f)
        if (combBuf.isNotEmpty()) {
            for (ch in combBuf.indices) {
                for (n in 0 until 16) { combBuf[ch][n].fill(0f); combIdx[ch][n] = 0 }
            }
        }
        if (allpassBuf.isNotEmpty()) {
            for (ch in allpassBuf.indices) {
                for (n in 0 until 5) { allpassBuf[ch][n].fill(0f); allpassIdx[ch][n] = 0 }
            }
        }
        if (preDelayBuf.isNotEmpty()) {
            for (ch in preDelayBuf.indices) { preDelayBuf[ch].fill(0f); preDelayIdx[ch] = 0 }
        }
        for (c in 0 until 2) combLowpass[c].fill(0f)
        if (reverbLpState.isNotEmpty()) reverbLpState.fill(0f)
        // 湿声高通状态清零
        if (reverbHpState.isNotEmpty()) {
            reverbHpState.fill(0f)
            reverbHpXPrev.fill(0f)
        }
        limSmoothGainLow = 1f
        limSmoothGainHigh = 1f
        // 分频限幅：LR4 滤波状态清零（防切歌后旧状态残留瞬态），缓冲不需清（按块重写）
        for (chIdx in 0 until 2) for (b in 0 until 4) lr4State[chIdx][b].fill(0f)
        outputBuffer.clear()
        ended = false
    }

    override fun reset() = flush()

    // ========== DSP 算法 ==========

    // LR4 分频器系数：交叉点 250Hz，Q=1/√2 双二阶 Butterworth 级联（低通/高通各 2 级），
    // RBJ Audio EQ Cookbook 公式，Double 精度计算防低频量化噪声。
    // 采样率变化时自动重算并清空滤波状态，防旧状态套新系数产生瞬态爆音。
    private fun ensureLr4Coeffs() {
        if (lr4InitSampleRate == sampleRate) return
        lr4InitSampleRate = sampleRate
        val w0 = 2.0 * PI * lr4CrossoverHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071067811865476) // Q=1/√2 ≈ 0.7071
        val a0 = 1.0 + alpha
        // 低通（RBJ LPF）：b0=(1-cos)/2, b1=1-cos, b2=b0；a1=-2cos, a2=1-alpha（均除以 a0）
        val lpB0 = (1.0 - cosW0) / 2.0 / a0
        val lpB1 = (1.0 - cosW0) / a0
        val lpA1 = (-2.0 * cosW0) / a0
        val lpA2 = (1.0 - alpha) / a0
        // 高通（RBJ HPF）：b0=(1+cos)/2, b1=-(1+cos), b2=b0；a 系数与低通相同
        val hpB0 = (1.0 + cosW0) / 2.0 / a0
        val hpB1 = -(1.0 + cosW0) / a0
        lr4LpCoeff = floatArrayOf(lpB0.toFloat(), lpB1.toFloat(), lpB0.toFloat(), lpA1.toFloat(), lpA2.toFloat())
        lr4HpCoeff = floatArrayOf(hpB0.toFloat(), hpB1.toFloat(), hpB0.toFloat(), lpA1.toFloat(), lpA2.toFloat())
        for (chIdx in 0 until 2) for (b in 0 until 4) lr4State[chIdx][b].fill(0f)
    }

    // 单级双二阶（Direct Form II Transposed 等价写法，与 processEq 同构）
    private fun processBiquad(x: Float, st: FloatArray, c: FloatArray): Float {
        val out = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3]
        st[1] = st[0]
        st[0] = x
        st[3] = st[2]
        st[2] = out
        return out
    }

    // 单声道过 10 段双二阶（Direct Form II Transposed 等价写法）
    private fun processEq(x: Float, ch: Int): Float {
        var y = x
        for (band in 0 until 10) {
            val st = eqState[ch][band]
            val c = eqCoeffs[band]
            val out = c[0] * y + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3]
            st[1] = st[0]
            st[0] = y
            st[3] = st[2]
            st[2] = out
            y = out
        }
        return y
    }

    // Schroeder 混响：预延迟 45ms → 16 梳状并联（反馈路径带一阶低通阻尼）→ 5 全通串联，返回湿声
    private fun processReverb(xIn: Float, ch: Int): Float {
        var x = xIn
        // 预延迟：写当前干声、读 45ms 前的信号给混响核心。
        // 混响晚 45ms 出现：鼓点先以干声到达，混响像从远处传回，大空间距离感。
        val pd = preDelayBuf[ch]
        if (pd.isNotEmpty()) {
            val pdi = preDelayIdx[ch]
            val pdDelayed = pd[pdi]
            pd[pdi] = x
            preDelayIdx[ch] = if (pdi + 1 >= preDelayLen) 0 else pdi + 1
            x = pdDelayed
        }
        var acc = 0f
        for (n in 0 until 16) {
            val buf = combBuf[ch][n]
            val len = buf.size
            var idx = combIdx[ch][n]
            val delayed = buf[idx]
            // 阻尼低通：同时压输出与反馈——第一个反射就被削掉高频，返回声从一开始就模糊。
            // 反馈路径与输出共用同一低通结果，保证首个输出样本同样经过阻尼。
            val lp = combLowpass[ch][n] + dampAlpha * (delayed - combLowpass[ch][n])
            combLowpass[ch][n] = lp
            buf[idx] = x + combFeedback[n] * lp
            idx++
            if (idx >= len) idx = 0
            combIdx[ch][n] = idx
            acc += lp
        }
        // 16 个梳状平均 0.20：湿声内部电平降档（不碰干湿比）
        var wet = acc * 0.20f
        // 全通链按教科书结构：逐级低通撤除（低通只该在梳状反馈回路做 Moorer 阻尼），
        // 高频得以进入混响；吱声土壤已由 <10ms 互质延迟表铲净，此处直接过原样 delayed。
        // 全通链 5 级（Schroeder 原版），处理循环随之扩到 5
        for (n in 0 until 5) {
            val buf = allpassBuf[ch][n]
            val len = buf.size
            var idx = allpassIdx[ch][n]
            val delayed = buf[idx]
            val out = delayed - allpassFeedback * wet
            buf[idx] = wet + allpassFeedback * delayed
            idx++
            if (idx >= len) idx = 0
            allpassIdx[ch][n] = idx
            wet = out
        }
        // 湿声总低通：混响链末端削一道高频（alpha=0.3，约 3.8kHz 起滚降）。
        // 中高频交给阻尼在路上压，门口别闷着，让混响更亮更通透。
        if (reverbLpState.isNotEmpty()) {
            val s = reverbLpState[ch]
            // 末端总低通 0.30：湿声高频再少削一道
            val lp = s + 0.30f * (wet - s)
            reverbLpState[ch] = lp
            wet = lp
        }
        // 湿声高通 fc=200Hz：标准一阶高通公式。
        // 标准一阶高通：y=(1+b)/2*(x[n]-x[n-1]) + b*y[n-1]，b=(1-tan(π·fc/fs))/(1+tan(π·fc/fs))，
        // fc=200Hz @48k → b≈0.9742、(1+b)/2≈0.9871。200Hz 以下衰减、以上保留，去泥巴保体量。
        if (reverbHpState.isNotEmpty()) {
            val yPrev = reverbHpState[ch]
            val xPrev = reverbHpXPrev[ch]
            val y = 0.9871f * (wet - xPrev) + 0.9742f * yPrev
            reverbHpState[ch] = y
            reverbHpXPrev[ch] = wet
            wet = y
        }
        return wet
    }
}
