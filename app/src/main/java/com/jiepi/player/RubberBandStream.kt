package com.jiepi.player

/**
 * Rubber Band 变速变调引擎的 Kotlin 流式封装，接口对齐 SonicStream
 * （writeShort / readShort / samplesAvailable / flush / setSpeed / setPitch /
 *  getSpeed / getPitch / getChannels / resetBuffers），可无缝替换 Sonic。
 *
 * 底层为官方 Rubber Band Library（v4.x，GPL-2.0+）NDK 编译的 librubberband.so
 * （arm64-v8a），经 JiepiRubberBandJNI.cpp 暴露最小接口：
 * create / destroy / process / available / retrieve / reset / 调参。
 *
 * 变速/变调映射（Rubber Band 侧）：
 * - setTimeRatio = 1/speed：speed=1.5 表示"播快 1.5 倍"→ 时间压缩到 0.667；
 * - setPitchScale = pitch：pitch=2.0 表示"升八度"→ 频谱整体上移 2 倍。
 * 二者独立（Rubber Band 支持变速不变调 / 变调不变速），流内实时调参生效。
 *
 * 音频为 short 交错 PCM16，Native 层负责解交织 float 分通道 → Rubber Band →
 * 回交织 short。
 */
class RubberBandStream(sampleRateIn: Int, numChannelsIn: Int) {

    private val sampleRate = sampleRateIn.coerceIn(1000, 500000)
    private val channels = numChannelsIn.coerceIn(1, 32)

    private var speed = 1f
    private var pitch = 1f

    private var nativeHandle: Long = nativeCreate(sampleRate, channels)

    init {
        if (nativeHandle == 0L) throw RuntimeException("RubberBand nativeCreate failed")
    }

    // ========== 参数（接口对齐 SonicStream） ==========

    fun setSpeed(v: Float) {
        speed = v.coerceIn(0.5f, 2f)
        nativeSetTimeRatio(nativeHandle, 1.0 / speed) // 时间压缩比 = 1/速度
    }

    fun setPitch(v: Float) {
        pitch = v.coerceIn(0.5f, 2f)
        nativeSetPitchScale(nativeHandle, pitch.toDouble())
    }

    fun getSpeed(): Float = speed
    fun getPitch(): Float = pitch
    fun getChannels(): Int = channels

    /** 清空 Native 内部缓冲但保留参数（seek/切歌时由外层调用，不产生输出） */
    fun resetBuffers() {
        nativeReset(nativeHandle)
    }

    // ========== 流式写入/读取（接口对齐 SonicStream） ==========

    /** 写入交错 PCM16；samples 传 null 且 numSamples=0 表示"仅触发处理"（flush 用） */
    fun writeShort(samples: ShortArray?, numSamples: Int): Boolean {
        val empty = ShortArray(0)
        nativeProcess(nativeHandle, samples ?: empty, if (samples == null) 0 else numSamples, false)
        return true
    }

    /** 读出最多 maxSamples 帧（交错 PCM16）到目标数组，返回实际读出帧数 */
    fun readShort(samples: ShortArray, maxSamples: Int): Int =
        nativeRetrieve(nativeHandle, samples, maxSamples)

    fun samplesAvailable(): Int = nativeAvailable(nativeHandle)

    /** 冲刷：把 Native 缓冲尾巴全部输出并清空流状态（final=1） */
    fun flush(): Boolean {
        nativeProcess(nativeHandle, ShortArray(0), 0, true)
        return true
    }

    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // ========== JNI（对应 JiepiRubberBandJNI.cpp 导出符号） ==========

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetTimeRatio(handle: Long, ratio: Double)
    private external fun nativeSetPitchScale(handle: Long, scale: Double)
    private external fun nativeGetTimeRatio(handle: Long): Double
    private external fun nativeGetPitchScale(handle: Long): Double
    private external fun nativeProcess(handle: Long, inBuf: ShortArray, frames: Int, isFinal: Boolean)
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeRetrieve(handle: Long, outBuf: ShortArray, maxFrames: Int): Int
    private external fun nativeReset(handle: Long)

    companion object {
        init {
            System.loadLibrary("rubberband")
        }
    }
}
