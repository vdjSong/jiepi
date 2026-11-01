package com.jiepi.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import android.util.Log
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

// 应用内 DSP 音效处理器（绕开系统 audiofx）
// 功能链：EQ → 混响 → 环绕 → 动态 → 限幅
class JiepiAudioProcessor : AudioProcessor {

    // 10 段 EQ 中心频率（与 UI 标签一致）
    private val eqFreqs = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    // 每段双二阶系数 b0 b1 b2 a1 a2
    private val eqCoeffs = Array(10) { FloatArray(5) }
    // 每声道每段 IIR 状态 [ch][band][s1,s2]
    private val eqState = Array(2) { Array(10) { FloatArray(2) } }
    // denormal 防初始化（近零输入防 CPU 降速）
    private val DENORMAL_EPS = 1e-25f

    // EQ 系数平滑渐变（12 块 ≈ 150~250ms，避免参数突变）
    private val eqCoeffsTarget = Array(10) { FloatArray(5) }
    private var coeffFadeRemaining = 0
    private val COEFF_FADE_BLOCKS = 12

    // 混响延迟表：16 梳状非等差错开 + 预延迟 45ms
    private val combDelays = floatArrayOf(
        0.02500f, 0.02609f, 0.02736f, 0.02873f, 0.03018f, 0.03182f, 0.03355f, 0.03536f,
        0.03727f, 0.03927f, 0.04136f, 0.04355f, 0.04555f, 0.04736f, 0.04882f, 0.05000f
    )
    // 全通延迟表（互质样本数，5 级串联）
    private val allpassDelays = floatArrayOf(0.0097f, 0.0073f, 0.0056f, 0.0041f, 0.0029f)
    // 梳状反馈 0.88（大厅级：RT60 中频约 2.0s）
    private val combFeedback = FloatArray(16) { 0.88f }
    // 全通反馈 0.45
    private val allpassFeedback = 0.45f
    // 高频阻尼强度（阻尼 0.55）
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
    // 湿声总低通状态 [ch]
    private var reverbLpState = FloatArray(0)
    // 湿声高通状态 [ch]（砍 300Hz 以下低频）
    private var reverbHpState = FloatArray(0)
    private var reverbHpXPrev = FloatArray(0)
    // 入口低切状态 [ch]（300Hz 低切，低频不进混响）
    private var reverbInHpState = FloatArray(0)
    private var reverbInHpXPrev = FloatArray(0)

    // 输入/输出格式状态
    private var sampleRate = 48000
    private var channelCount = 2
    private var active = false
    private var ended = false
    private var outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())

    // 峰值限幅器缓冲（复用防每块分配）
    private var limiterBuf = FloatArray(0)
    private var limiterBufR = FloatArray(0)
    // 分频限幅：低/高频段独立限幅
    private var limSmoothGainLow = 1f
    private var limSmoothGainHigh = 1f
    // LR4 分频器（交叉 250Hz，24dB/oct）
    private val lr4CrossoverHz = 250.0
    private var lr4LpCoeff = FloatArray(5) // 归一化 [b0,b1,b2,a1,a2]
    private var lr4HpCoeff = FloatArray(5)
    private var lr4State = Array(2) { Array(4) { FloatArray(4) } } // [ch][0..1 LP, 2..3 HP][x1,x2,y1,y2]
    private var lr4InitSampleRate = 0
    private var limiterLow = Array(2) { FloatArray(0) } // [ch][f] 低频段缓冲
    private var limiterHigh = Array(2) { FloatArray(0) } // [ch][f] 高频段缓冲
    // 切歌防 pop：淡出到 0 再停、从 0 淡入
    private var fadeGain = 1f
    private var fadeTarget = 1f
    // 淡入淡出时长（ms）：默认 25ms，恢复播放用 150ms
    private var fadeMs = 25
    // ========== 滤波状态：播放前真实信号预滚（水流声根治） ==========
    // IIR 状态于真实信号中预收敛

    // 变速/变调（Rubber Band 引擎，块级调参 + 门控静音兜底）
    private var sonic: RubberBandStream? = null
    @Volatile private var targetSpeed = 1f
    @Volatile private var targetPitch = 1f
    private var fadeFromSpeed = 1f
    private var fadeFromPitch = 1f
    private var fadeRemaining = 0
    private var fadeTotal = 1
    // 渐变块数（6 块 ≈ 100~260ms 到目标）
    private val FADE_BLOCK_COUNT = 6
    private var sonicScratch = ShortArray(0) // Rubber Band 中转缓冲
    // 变速窗口门控：窗口重建期输出静音，恢复输出时解除并淡入
    @Volatile private var sonicGateActive = false
    // 静音帧标记（恢复输出时需淡入防突变）
    @Volatile private var silentSent = false
    // 门控解除后窗口稳定期（先静音稳定再淡入）
    private var gateStableSamples = 0
    // 淡入完成同步信号（通知 PlayerManager 恢复硬件音量）
    @Volatile private var fadeInDoneNotified = false
    // 淡入完成回调（爬到 1 时触发一次）
    @Volatile var onFadeInDone: (() -> Unit)? = null
    // 预滚完成标记（同格式 configure 保留稳态）
    @Volatile private var warmedFormatReady = false

    // 变速/变调参数设置（一步到位，渐变由引擎内部完成）
    fun setSpeedPitch(speed: Float, pitch: Float) {
        synchronized(this) {
            val s = speed.coerceIn(0.5f, 2f)
            val p = pitch.coerceIn(0.5f, 2f)
            if (s == targetSpeed && p == targetPitch) return
            // 引擎未创建时新建（configure 前只记参数）
            if (sonic == null && sampleRate > 0) {
                sonic = RubberBandStream(sampleRate, channelCount)
            }
            fadeFromSpeed = sonic?.getSpeed() ?: targetSpeed
            fadeFromPitch = sonic?.getPitch() ?: targetPitch
            targetSpeed = s
            targetPitch = p
            fadeRemaining = 1 // 一步到位
        }
    }

    fun setSpeed(speed: Float) { setSpeedPitch(speed, targetPitch) }
    fun setPitch(pitch: Float) { setSpeedPitch(targetSpeed, pitch) }

    // 判定真正需要 Sonic（目标非原速或仍在渐变中）
    fun isSonicActive(): Boolean {
        val s = sonic ?: return false
        return Math.abs(targetSpeed - 1f) > 1e-4f || Math.abs(targetPitch - 1f) > 1e-4f ||
            Math.abs(s.getSpeed() - 1f) > 1e-3f || Math.abs(s.getPitch() - 1f) > 1e-3f
    }

    // 淡入完成校验（防竞态捅破新 seek 静音罩）
    fun isFadeInDone(): Boolean =
        fadeInDoneNotified && fadeTarget == 1f && fadeGain >= 1f

    // 每音频块向目标推进一次变速参数
    private fun advanceFade() {
        if (fadeRemaining <= 0) return
        fadeRemaining--
        val k = if (fadeTotal > 0) 1f - fadeRemaining.toFloat() / fadeTotal else 1f
        sonic?.setSpeed(fadeFromSpeed + (targetSpeed - fadeFromSpeed) * k)
        sonic?.setPitch(fadeFromPitch + (targetPitch - fadeFromPitch) * k)
    }

    // 音效参数（0~100 归一化为 0~1）
    @Volatile private var eqLevels = FloatArray(10) // -12 ~ +12 dB
    @Volatile private var effectDynamic = 0f
    @Volatile private var effectHifi = 0f
    @Volatile private var effectHigh = 0f
    @Volatile private var effectLow = 0f
    @Volatile private var effectSurround = 0f
    @Volatile private var effectReverb = 0f

    // 效果参数平滑（一阶指数逼近，时间常数 ~15ms）
    private var fxSmoothCoef = 0f
    private var smoothEffectDynamic = 0f
    private var smoothEffectSurround = 0f
    private var smoothEffectReverb = 0f
    // 音效参数待重建标志（UI 置位，播放线程执行 rebuild）
    @Volatile private var fxDirty = false

    fun setEqLevels(levels: FloatArray) {
        eqLevels = levels.copyOf()
        // 置待重建标志（播放线程执行 rebuild，防并发撕裂）
        fxDirty = true
    }

    fun setEffects(
        effectDynamic: Int, effectHifi: Int,
        effectHigh: Int, effectLow: Int, effectSurround: Int, effectReverb: Int
    ) {
        this.effectDynamic = effectDynamic / 100f
        this.effectHifi = effectHifi / 100f
        this.effectHigh = effectHigh / 100f
        this.effectLow = effectLow / 100f
        this.effectSurround = effectSurround / 100f
        this.effectReverb = effectReverb / 100f
        // 置待重建标志（播放线程执行 rebuild）
        fxDirty = true
    }

    // 重算 EQ 系数并重建混响延时线（true：系数写定 + zi 注入；false：块级渐变）
    private fun rebuild(clearState: Boolean = false) {
        val finalDb = FloatArray(10)
        for (i in 0..9) {
            var db = eqLevels[i]
            if (i in 8..9) db += effectHigh * 5.0f // 高音增强
            if (i >= 5) db += effectHifi * 3.0f // 高保真激励
            if (i <= 2) db += effectLow * 2.6f // 低音增强
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
            val c = floatArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            if (clearState) {
                // 系数一步写定
                eqCoeffs[band] = c
            } else {
                // 目标系数进 target，块级渐变逼近
                eqCoeffsTarget[band] = c
            }
        }
        if (clearState) {
            coeffFadeRemaining = 0 // 无渐变待推
            injectEqSteadyState() // 注入 zi 稳态
        } else {
            coeffFadeRemaining = COEFF_FADE_BLOCKS
        }
        initReverb()
    }

    // DC 稳态初始条件（zi）注入：状态重建时稳态起步，无零状态瞬态
    private fun injectEqSteadyState() {
        for (ch in 0 until 2) {
            var g = 1f
            for (band in 0 until 10) {
                val c = eqCoeffs[band]
                val gSum = c[0] + c[1] + c[2]
                val aSum = 1f + c[3] + c[4]
                val y0 = if (aSum != 0f) g * gSum / aSum else g * gSum
                val st = eqState[ch][band]
                st[0] = (c[1] + c[2]) * g - (c[3] + c[4]) * y0
                st[1] = c[2] * g - c[4] * y0
                g = y0
            }
        }
    }

    private fun initReverb() {
        val ch = channelCount.coerceIn(1, 2)
        // 格式变化才重建延时线（保留混响尾巴）
        val needRebuild = combBuf.size != ch ||
            allpassBuf.size != ch ||
            preDelayLen != (sampleRate * 0.032f).toInt().coerceAtLeast(1000) ||
            (combBuf.firstOrNull()?.firstOrNull()?.size ?: 0) !=
            (sampleRate * combDelays[0]).toInt().coerceAtLeast(100) ||
            (allpassBuf.firstOrNull()?.firstOrNull()?.size ?: 0) !=
            (sampleRate * allpassDelays[0]).toInt().coerceAtLeast(20)
        if (!needRebuild) return
        // 每级缓冲按各自延时表建（非等差错开）
        combBuf = Array(ch) { _ -> Array(16) { n -> FloatArray((sampleRate * combDelays[n]).toInt().coerceAtLeast(100)) } }
        combIdx = Array(ch) { IntArray(16) }
        allpassBuf = Array(ch) { _ -> Array(5) { n -> FloatArray((sampleRate * allpassDelays[n]).toInt().coerceAtLeast(20)) } }
        allpassIdx = Array(ch) { IntArray(5) }
        // 预延迟 32ms（大厅类 20~40ms 区间内）
        preDelayLen = (sampleRate * 0.032f).toInt().coerceAtLeast(1000)
        preDelayBuf = Array(ch) { FloatArray(preDelayLen) }
        preDelayIdx = IntArray(ch)
        reverbLpState = FloatArray(ch)
        reverbHpState = FloatArray(ch)
        reverbHpXPrev = FloatArray(ch)
        reverbInHpState = FloatArray(ch)
        reverbInHpXPrev = FloatArray(ch)
        for (c in 0 until 2) combLowpass[c].fill(0f)
    }

    // ========== AudioProcessor 接口 ==========

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 格式真变化或未预滚才清状态
        val fmtChanged = inputAudioFormat.sampleRate != sampleRate ||
            inputAudioFormat.channelCount != channelCount
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        // 变速态且引擎缺失/声道变化时创建引擎
        val cur = sonic
        if ((targetSpeed != 1f || targetPitch != 1f) &&
            (cur == null || cur.getChannels() != channelCount)
        ) {
            sonic = RubberBandStream(sampleRate, channelCount)
            sonic?.setSpeed(targetSpeed)
            sonic?.setPitch(targetPitch)
        }
        fadeTotal = FADE_BLOCK_COUNT // 渐变块数
        sonicScratch = ShortArray(0)
        // 效果参数平滑系数（按采样率归一化，时间常数 ~15ms）
        fxSmoothCoef = 1f - exp(-1f / (sampleRate * 0.015f))
        // 预滚且格式未变则保留稳态，否则重建（系数写定 + zi 注入）
        if (fmtChanged || !warmedFormatReady) {
            Log.i("JiepiWarm", "configure: REBUILD in=${inputAudioFormat.sampleRate}/${inputAudioFormat.channelCount} fmtChanged=$fmtChanged warmed=$warmedFormatReady")
            rebuild(clearState = true)
        } else {
            // 预滚同格式：跳过 rebuild（避免重爬系数渐变）
            Log.i("JiepiWarm", "configure: KEEP warmed state in=${inputAudioFormat.sampleRate}/${inputAudioFormat.channelCount} fmtChanged=$fmtChanged warmed=$warmedFormatReady")
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) return
        // 播放线程内执行待重建的音效参数（与 processFrames 串行，防并发撕裂）
        if (fxDirty) {
            fxDirty = false
            rebuild()
        }
        val bytes = inputBuffer.remaining()
        if (bytes < 2) return
        val ch = channelCount.coerceIn(1, 2)
        val frames = bytes / 2 / ch
        if (frames == 0) return

        // ---- 0) 变速/变调（RubberBand NDK 引擎，DSP 链最前） ----
        val s = sonic
        // 变速门控：恢复原速后绕开 Sonic 走直通链，避免状态传染
        val needSonic = s != null && isSonicActive()
        if (s != null && needSonic) {
            val need = frames * ch
            if (sonicScratch.size < need) sonicScratch = ShortArray(need)
            // 顺序读 short（禁止 mark/reset 回退，否则 Media3 管线卡死无声）
            for (i in 0 until need) sonicScratch[i] = inputBuffer.short
            if (!s.writeShort(sonicScratch, frames)) {
                inputBuffer.position(inputBuffer.limit())
                return
            }
            val avail = s.samplesAvailable()
            if (avail > 0) {
                if (sonicScratch.size < avail * ch) sonicScratch = ShortArray(avail * ch)
                val got = s.readShort(sonicScratch, avail)
                // 门控解除：Sonic 恢复输出时拉回淡入目标（长渐变平滑接入）
                if (sonicGateActive) {
                    sonicGateActive = false
                    // 窗口稳定期：先静音稳定，锁定完成再淡入
                    gateStableSamples = (sampleRate / 10).coerceAtLeast(2048)
                }
                // 从静音帧恢复：fadeGain 已压 0，自动从 0 淡入防突变
                if (silentSent) silentSent = false
                if (gateStableSamples > 0) {
                    // 稳定期强制静音
                    gateStableSamples -= got
                    fadeGain = 0f
                    fadeTarget = 0f
                } else if (fadeTarget != 1f) {
                    // 稳定期结束启动淡入
                    fadeIn(500)
                }
                processFrames(sonicScratch, got, ch)
            } else {
                // avail==0：窗口重建期/渐变适应期/缓冲不足，填静音帧防 underrun 断音
                outputBuffer.clear()
                val silentBytes = (frames * ch * 2).coerceAtLeast(4096)
                if (outputBuffer.capacity() < silentBytes) {
                    outputBuffer = ByteBuffer.allocateDirect(silentBytes).order(ByteOrder.nativeOrder())
                }
                silentSent = true
                // 变速渐变期保持增益接续；非变速压 0 待恢复淡入
                if (fadeTarget == 1f && !isSonicActive()) fadeGain = 0f
                var n = silentBytes / 2
                while (n-- > 0) outputBuffer.putShort(0)
                outputBuffer.flip()
            }
        } else {
            // 直通分支（无 Sonic / 已恢复原速绕开）
            // 归零摘链：引擎不需要时直接销毁，残留状态不再传染
            if (s != null) {
                s.close()
                sonic = null
            }
            // 门控残留清理：绕开 Sonic 前主动拉回音量目标（防永久无声）
            if (sonicGateActive) {
                sonicGateActive = false
                fadeIn(500)
            }
            // 无 Sonic：直接走链
            val inShort = ShortArray(frames * ch)
            for (i in inShort.indices) inShort[i] = inputBuffer.short
            processFrames(inShort, frames, ch)
        }
    }

    // 交错 PCM16 帧逐帧过 DSP 链
    private fun processFrames(inShort: ShortArray, frames: Int, ch: Int, offset: Int = 0) {
        // 输出缓冲按需扩容（变速最多 4 倍样本数）
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
        // 预热稳定期：真实信号过链收敛，输出静音
        advanceFade() // 变速渐变推进
        // EQ 系数渐变推进（不清 IIR 历史，自然连续过渡）
        if (coeffFadeRemaining > 0) {
            val step = 1f / coeffFadeRemaining
            for (band in 0..9) for (j in 0..4) {
                eqCoeffs[band][j] += (eqCoeffsTarget[band][j] - eqCoeffs[band][j]) * step
            }
            coeffFadeRemaining--
        }
        for (f in 0 until frames) {
            // 效果参数平滑逼近（防逐样本突变毛刺）
            smoothEffectDynamic += (effectDynamic - smoothEffectDynamic) * fxSmoothCoef
            smoothEffectSurround += (effectSurround - smoothEffectSurround) * fxSmoothCoef
            smoothEffectReverb += (effectReverb - smoothEffectReverb) * fxSmoothCoef
            var l = inShort[(offset + f) * ch].toFloat() / 32768f
            var r = if (ch == 2) inShort[(offset + f) * ch + 1].toFloat() / 32768f else l

            // 1) EQ：10 段双二阶级联
            l = processEq(l, 0)
            r = processEq(r, if (ch == 2) 1 else 0)

            // 2) 混响：湿声按干湿比混入
            if (smoothEffectReverb > 0f) {
                val wl = processReverb(l, 0)
                val wr = if (ch == 2) processReverb(r, 1) else wl
                val mix = smoothEffectReverb * 0.42f // 干湿比 0.42（湿声多一点，现场包裹感）
                l += (wl - l) * mix
                r += (wr - r) * mix
            }

            // 3) 环绕：M/S 拓宽
            if (ch == 2 && smoothEffectSurround > 0f) {
                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f
                val k = 1f + smoothEffectSurround * 0.9953f // 侧信号最多 +6.0dB
                l = m + s * k
                r = m - s * k
            }

            // 4) 动态推进：tanh 软限幅 + 干湿混合稀释
            if (smoothEffectDynamic > 0f) {
                val g = 1f + smoothEffectDynamic * 0.9f // 压缩强度
                val wet = smoothEffectDynamic * 0.35f // 湿声比例
                val dry = 1f - wet * 0.8f // 干声兜底
                val norm = 1f / tanh(g)
                l = l * dry + tanh(l * g) * norm * wet
                r = r * dry + tanh(r * g) * norm * wet
            }

            limiterBuf[f] = l
            limiterBufR[f] = r
        }

        // 5) 常驻峰值限幅器（LR4 双频段，-0.63dBFS）
        ensureLr4Coeffs()
        var lowPeak = 0f
        var highPeak = 0f
        for (f in 0 until frames) {
            // 每声道：低通/高通 2 级级联分频；单声道只跑左声道（防旧歌残留误触发）
            val a = limiterBuf[f]
            val b = limiterBufR[f]
            for (chIdx in 0 until (if (ch == 2) 2 else 1)) {
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
        // 双频段独立目标增益（超阈值才压本段）
        val lowTargetGain = if (lowPeak > 0.93f) 0.93f / lowPeak else 1f
        val highTargetGain = if (highPeak > 0.93f) 0.93f / highPeak else 1f
        // attack 快压 / release 慢恢复
        val attackCoef = 1f - exp(-1f / (sampleRate * 0.002f))
        val releaseCoef = 1f - exp(-1f / (sampleRate * 0.3f))
        // 淡入淡出步长（按 fadeMs 每样本推进）
        val fadeStep = 1f / (sampleRate * fadeMs / 1000f)
        for (f in 0 until frames) {
            if (fadeGain < fadeTarget) fadeGain = min(fadeGain + fadeStep, fadeTarget)
            else if (fadeGain > fadeTarget) fadeGain = max(fadeGain - fadeStep, fadeTarget)
            // 淡入完成检测：爬到 1 时通知 PlayerManager 恢复硬件音量（单次触发）
            if (fadeTarget == 1f && fadeGain >= 1f && !fadeInDoneNotified) {
                fadeInDoneNotified = true
                onFadeInDone?.invoke()
            }
            // 低/高频段各自平滑逼近（防泵动效应）
            val coefL = if (lowTargetGain < limSmoothGainLow) attackCoef else releaseCoef
            limSmoothGainLow += (lowTargetGain - limSmoothGainLow) * coefL
            val coefH = if (highTargetGain < limSmoothGainHigh) attackCoef else releaseCoef
            limSmoothGainHigh += (highTargetGain - limSmoothGainHigh) * coefH
            // 求和输出（LR4 交叉点 -6dB、求和平坦）
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
        // 冲刷 Sonic 缓冲尾巴后再宣告结束
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

    // 淡出：设置目标，播放线程逐样本逼近（切歌防 pop）
    fun fadeOut(durationMs: Int = 25) {
        fadeMs = durationMs.coerceIn(10, 500)
        fadeTarget = 0f
    }
    fun fadeIn(durationMs: Int = 25) { fadeMs = durationMs.coerceIn(10, 500); fadeTarget = 1f }

    // 播放前真实信号预滚（IIR 收敛）
    fun warmUp(pcm: ShortArray, inSampleRate: Int, inChannels: Int) {
        if (pcm.isEmpty()) return
        sampleRate = inSampleRate
        channelCount = inChannels.coerceIn(1, 2)
        active = true
        Log.i("JiepiWarm", "warmUp: pcm=${pcm.size} sr=$inSampleRate ch=$inChannels")
        // 稳态起步：系数写定 + zi 注入，再真实信号预滚收敛
        rebuild(clearState = true)
        fxSmoothCoef = 1f - exp(-1f / (sampleRate * 0.015f))
        // 预滚收敛：真实信号过链、输出静音（保持 prepareFadeIn 态）
        fadeGain = 0f
        fadeTarget = 0f
        fadeInDoneNotified = false
        // 直接喂原始真实信号过链（不经过变速引擎）
        val ch = channelCount.coerceIn(1, 2)
        val frames = pcm.size / ch
        // 分块喂，避免超大输出缓冲
        var off = 0
        while (off < frames) {
            val n = minOf(2048, frames - off)
            processFrames(pcm, n, ch, off)
            off += n
        }
        // 预滚完成标记（configure 同格式保留稳态）
        warmedFormatReady = true
        // 限幅器平滑增益复位（播放从头重放，防预滚压缩值错位调制）
        limSmoothGainLow = 1f
        limSmoothGainHigh = 1f
    }
    // 预加载期强制静音（防 play start 轰出咔哒）
    fun prepareFadeIn() {
        fadeGain = 0f
        fadeTarget = 0f
        fadeInDoneNotified = false
    }
    // 武装变速窗口门控（seek/切歌窗口重建期保持静音）
    fun armSonicGate() {
        sonicGateActive = true
        fadeGain = 0f
        fadeTarget = 0f
        fadeInDoneNotified = false
    }

    // 拖动 seek 期间踢出变速/变调引擎（走直通链，防窗口重建瞬态）
    fun suspendSonic() {
        // 销毁引擎实例（清掉内部状态，归零后与从没开过等价）
        val s = sonic
        if (s != null) {
            s.close()
            sonic = null
        }
        targetSpeed = 1f
        targetPitch = 1f
        fadeFromSpeed = 1f
        fadeFromPitch = 1f
        fadeRemaining = 0
        sonicGateActive = false
        fadeInDoneNotified = false
        fadeGain = 0f
        fadeTarget = 0f
    }

    // 拖动 seek 完成：恢复变速/变调参数，引擎从原速重新武装
    fun restoreSonic(speed: Float, pitch: Float) {
        // 实例已销毁则重建，否则复用
        var s = sonic
        if (s == null) {
            s = RubberBandStream(sampleRate, channelCount)
            sonic = s
        }
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
        // 销毁重建引擎实例（nativeReset 不清内部状态，防残留传染）
        val cur = sonic
        if (cur != null) {
            cur.close()
            // 归零状态不再重建（彻底摘链）；非归零按目标参数重建
            if (targetSpeed == 1f && targetPitch == 1f) {
                sonic = null
            } else {
                sonic = RubberBandStream(sampleRate, channelCount)
                sonic?.setSpeed(targetSpeed)
                sonic?.setPitch(targetPitch)
            }
        }
        fadeRemaining = 0
        // flush 保留 fadeGain 与 EQ 状态；平滑参数归零
        smoothEffectDynamic = 0f
        smoothEffectSurround = 0f
        smoothEffectReverb = 0f
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
        // 入口低切状态清零
        if (reverbInHpState.isNotEmpty()) {
            reverbInHpState.fill(0f)
            reverbInHpXPrev.fill(0f)
        }
        limSmoothGainLow = 1f
        limSmoothGainHigh = 1f
        // LR4 状态不清；限幅缓冲清空
        if (limiterBuf.isNotEmpty()) limiterBuf.fill(0f)
        if (limiterBufR.isNotEmpty()) limiterBufR.fill(0f)
        for (c in 0 until 2) {
            if (limiterLow[c].isNotEmpty()) limiterLow[c].fill(0f)
            if (limiterHigh[c].isNotEmpty()) limiterHigh[c].fill(0f)
        }
        outputBuffer.clear()
        ended = false
    }

    override fun reset() = flush()

    // ========== DSP 算法 ==========

    // LR4 分频器系数：交叉 250Hz，Q=1/√2 双二阶 Butterworth 级联（RBJ 公式）
    private fun ensureLr4Coeffs() {
        if (lr4InitSampleRate == sampleRate) return
        lr4InitSampleRate = sampleRate
        val w0 = 2.0 * PI * lr4CrossoverHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071067811865476) // Q=1/√2
        val a0 = 1.0 + alpha
        // 低通（RBJ LPF）
        val lpB0 = (1.0 - cosW0) / 2.0 / a0
        val lpB1 = (1.0 - cosW0) / a0
        val lpA1 = (-2.0 * cosW0) / a0
        val lpA2 = (1.0 - alpha) / a0
        // 高通（RBJ HPF，a 系数与低通相同）
        val hpB0 = (1.0 + cosW0) / 2.0 / a0
        val hpB1 = -(1.0 + cosW0) / a0
        lr4LpCoeff = floatArrayOf(lpB0.toFloat(), lpB1.toFloat(), lpB0.toFloat(), lpA1.toFloat(), lpA2.toFloat())
        lr4HpCoeff = floatArrayOf(hpB0.toFloat(), hpB1.toFloat(), hpB0.toFloat(), lpA1.toFloat(), lpA2.toFloat())
        for (chIdx in 0 until 2) for (b in 0 until 4) lr4State[chIdx][b].fill(0f)
    }

    // 单级双二阶（Direct Form II Transposed）
    private fun processBiquad(x: Float, st: FloatArray, c: FloatArray): Float {
        val out = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3]
        st[1] = st[0]
        st[0] = x
        st[3] = st[2]
        st[2] = out
        return out
    }

    // 10 段双二阶级联（DF2T + denormal 防护）
    private fun processEq(x: Float, ch: Int): Float {
        var y = x
        for (band in 0 until 10) {
            val st = eqState[ch][band]
            val c = eqCoeffs[band]
            val out = c[0] * y + st[0]
            val ns1 = c[1] * y - c[3] * out + st[1]
            val ns2 = c[2] * y - c[4] * out
            if (Math.abs(ns1) < DENORMAL_EPS) st[0] = ns1 + DENORMAL_EPS else st[0] = ns1
            if (Math.abs(ns2) < DENORMAL_EPS) st[1] = ns2 + DENORMAL_EPS else st[1] = ns2
            y = out
        }
        return y
    }

    // Schroeder 混响：预延迟 → 16 梳状并联（带阻尼低通）→ 5 全通串联，返回湿声
    private fun processReverb(xIn: Float, ch: Int): Float {
        var x = xIn
        // 入口低切 250Hz（一阶高通，250Hz 以下低频不进混响，与末端高通一致）
        if (reverbInHpState.isNotEmpty()) {
            val yPrev = reverbInHpState[ch]
            val xPrev = reverbInHpXPrev[ch]
            val y = 0.9825f * (x - xPrev) + 0.9650f * yPrev
            reverbInHpState[ch] = y
            reverbInHpXPrev[ch] = x
            x = y
        }
        // 预延迟：干声先到、混响晚 32ms（大空间距离感）
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
            // 阻尼低通（压输出与反馈，返回声从开始就模糊）
            val lp = combLowpass[ch][n] + dampAlpha * (delayed - combLowpass[ch][n])
            combLowpass[ch][n] = lp
            buf[idx] = x + combFeedback[n] * lp
            idx++
            if (idx >= len) idx = 0
            combIdx[ch][n] = idx
            acc += lp
        }
        // 16 梳状平均 0.20（湿声内部电平降档）
        var wet = acc * 0.20f
        // 5 级全通链（Schroeder 原版，不加低通）
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
        // 湿声总低通（末端削高频，让混响更亮）
        if (reverbLpState.isNotEmpty()) {
            val s = reverbLpState[ch]
            // 末端总低通 0.42（略暗一点，找回朦胧氛围感）
            val lp = s + 0.42f * (wet - s)
            reverbLpState[ch] = lp
            wet = lp
        }
        // 湿声高通 fc=250Hz（一阶高通，压低频泥巴但保留 250-300Hz 温暖）
        if (reverbHpState.isNotEmpty()) {
            val yPrev = reverbHpState[ch]
            val xPrev = reverbHpXPrev[ch]
            val y = 0.9825f * (wet - xPrev) + 0.9650f * yPrev
            reverbHpState[ch] = y
            reverbHpXPrev[ch] = wet
            wet = y
        }
        return wet
    }
}
