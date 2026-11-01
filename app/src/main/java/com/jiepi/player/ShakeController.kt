package com.jiepi.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 摇一摇切歌：
 * 加速度传感器 TYPE_ACCELEROMETER 读取无需任何运行时权限；
 * 传感器只感知"晃动"、无法区分方向，触发=固定切下一首（复用 PlayerManager.next，与歌词/列表切歌同一逻辑）；
 * 阈值 + 连续样本 + 冷却时间三重防误触；
 * 开关状态实时读 jiepi_data 的 sw_shake，设置页切换即时生效，无需额外回调。
 */
class ShakeController(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = null

    // 晃动判定（去重力后超阈值）
    // 阈值档位参考（2.0 普通甩 / 2.5 用力甩）
    private val shakeThreshold = 2.0f   // 阈值：|a| - g > 2.0 m/s²
    private val needStreak = 3          // 连续 3 次采样超阈值才触发一次
    private val cooldownMs = 1500L      // 触发后 1.5s 冷却，防连续误触
    private var streak = 0
    private var lastTriggerAt = 0L

    private val prefs by lazy {
        appContext.getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
    }

    fun register() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // SENSOR_DELAY_NORMAL(约200ms一次)足够识别晃动，比 UI/Game 采样率更省电
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
        accelerometer = null
        streak = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // 开关实时读取
        if (!prefs.getBoolean("sw_shake", false)) {
            streak = 0
            return
        }
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        if (magnitude - SensorManager.GRAVITY_EARTH > shakeThreshold) {
            streak++
        } else {
            streak = 0
        }
        if (streak >= needStreak) {
            streak = 0
            val now = SystemClock.elapsedRealtime()
            if (now - lastTriggerAt < cooldownMs) return
            lastTriggerAt = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onShake() {
        // 队列为空：不切不震
        if (PlayerManager.songPaths.isEmpty()) return
        PlayerManager.next(appContext)
        vibrate()
    }

    // 摇动成功震动反馈
    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            // 个别机型震动服务异常，忽略反馈不打断切歌
        }
    }
}
