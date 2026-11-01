package com.jiepi.player.ui.play

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 星光闪烁背景：播放详情页最底层。
 *
 * 星星有大有小（但都克制，像远处小亮点）、分两种光芒样式：
 *  - TWO_SPIKE：一条两端尖的长菱形光芒（两个尖尖）
 *  - FOUR_SPIKE：十字交叉的四角星芒（四个尖尖）
 * 每颗星有独立生命周期：延迟等待 → 淡入 → 驻留闪烁（若隐若现）→ 淡出 → 再随机延迟，
 * 出现时机有先有后、驻留时长不等，星星错落登场，不会一窝蜂出现又一窝蜂熄灭。
 * 播放时闪，暂停时整体缓缓隐去；恢复播放重新错落出现。
 */
class StarFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class StarStyle { TWO_SPIKE, FOUR_SPIKE }

    private class Star {
        var x = 0f
        var y = 0f
        var bitmap: Bitmap? = null
        var side = 0f
        // 生命周期（毫秒）
        var waitMs = 0f
        var riseMs = 0f
        var dwellMs = 0f
        var fadeMs = 0f
        var dwellLeft = 0f
        var lifeAlpha = 0f          // 生命周期亮度 0..1
        var state = 0               // 0=等待 1=淡入 2=驻留闪烁 3=淡出
        // 驻留期间的闪烁参数（各自独立，互不同步）
        var twinklePhase = 0f
        var twinkleFreq = 0f        // 每秒波动次数
        var twinkleMin = 0.5f       // 闪烁时降到的最低亮度（若隐若现）
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stars = ArrayList<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var playing = false       // 播放中才闪
    private var animating = false     // 帧循环是否在跑
    private var globalAlpha = 0f      // 整体透明度（暂停缓缓隐去）
    private var globalTarget = 0f     // 整体透明度目标
    private var timeMs = 0L           // 累计时间（闪烁相位用）
    private var lastFrame = 0L
    private var sizeReady = false

    // 帧间隔 ~33ms（约 30fps，安静克制的帧率）
    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            val now = System.currentTimeMillis()
            val dt = (now - lastFrame).coerceIn(1L, 100L)
            lastFrame = now
            timeMs += dt
            // 整体透明度缓动（暂停淡出 / 播放淡入）
            val step = 0.004f * dt
            globalAlpha = if (globalAlpha < globalTarget) {
                min(globalAlpha + step, globalTarget)
            } else {
                max(globalAlpha - step, globalTarget)
            }
            // 推进每颗星的生命周期
            for (s in stars) stepStar(s, dt)
            invalidate()
            // 完全隐去后停帧循环省电
            if (globalTarget <= 0f && globalAlpha <= 0f) {
                animating = false
                return
            }
            handler.postDelayed(this, 33L)
        }
    }

    // ---------- 对外接口 ----------

    /** 播放中=true 星光开始错落闪烁；暂停=false 星光缓缓隐去（闪烁停止）。 */
    fun setPlaying(play: Boolean) {
        if (play == playing) return
        playing = play
        globalTarget = if (play) 1f else 0f
        ensureStars()
        startAnimation()
    }

    /** 页面销毁时释放资源。 */
    fun release() {
        playing = false
        animating = false
        handler.removeCallbacks(frameRunnable)
        for (s in stars) s.bitmap?.recycle()
        stars.clear()
    }

    // ---------- 初始化 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeReady = w > 0 && h > 0
        ensureStars()
        if (playing && globalAlpha > 0f && !animating) startAnimation()
    }

    private fun ensureStars() {
        if (stars.isNotEmpty() || !sizeReady) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val rnd = Random.Default
        // 数量略减：从 55 降到 44，画面留白更多、不显拥挤
        val count = 44
        repeat(count) {
            // 星星半径（远处小亮点尺寸）
            val radius = (1.5f + rnd.nextFloat() * 1.5f) * resources.displayMetrics.density
            val style = if (rnd.nextFloat() < 0.5f) StarStyle.TWO_SPIKE else StarStyle.FOUR_SPIKE
            // 光芒长度倍数（2 尖细长、4 尖短）
            val lenMul = if (style == StarStyle.TWO_SPIKE) {
                2.5f + rnd.nextFloat() * 1.5f
            } else {
                1.8f + rnd.nextFloat() * 1.2f
            }
            // 2 尖随机倾角，4 尖轻微旋转
            val angle = if (style == StarStyle.TWO_SPIKE) rnd.nextFloat() * 180f
                        else rnd.nextFloat() * 40f - 20f
            val s = Star()
            s.x = rnd.nextFloat() * w
            s.y = rnd.nextFloat() * h
            // 错落登场（时机/驻留/节奏各异）
            s.waitMs = rnd.nextLong(200L, 3600L).toFloat()
            s.riseMs = rnd.nextLong(350L, 900L).toFloat()
            s.dwellMs = rnd.nextLong(1600L, 4800L).toFloat()
            s.fadeMs = rnd.nextLong(350L, 850L).toFloat()
            s.state = 0
            s.lifeAlpha = 0f
            s.twinklePhase = rnd.nextFloat() * 6.2832f
            // 呼吸频率（每秒 0.7~1.7 次）
            s.twinkleFreq = 0.7f + rnd.nextFloat() * 1.0f
            s.twinkleMin = 0.45f + rnd.nextFloat() * 0.25f
            val bmp2 = createStarBitmap(radius, lenMul, style, angle)
            s.bitmap = bmp2
            s.side = bmp2.width.toFloat()
            stars.add(s)
        }
    }

    private fun startAnimation() {
        if (animating) return
        animating = true
        lastFrame = System.currentTimeMillis()
        handler.removeCallbacks(frameRunnable)
        handler.post(frameRunnable)
    }

    // ---------- 生命周期推进 ----------

    private fun stepStar(s: Star, dt: Long) {
        when (s.state) {
            0 -> { // 等待：随机延迟后登场
                s.waitMs -= dt
                if (s.waitMs <= 0f) {
                    s.state = 1
                    s.lifeAlpha = 0f
                }
            }
            1 -> { // 淡入
                s.lifeAlpha += dt.toFloat() / s.riseMs
                if (s.lifeAlpha >= 1f) {
                    s.lifeAlpha = 1f
                    s.dwellLeft = s.dwellMs
                    s.state = 2
                }
            }
            2 -> { // 驻留闪烁（亮度由 onDraw 里按相位算）
                s.dwellLeft -= dt
                if (s.dwellLeft <= 0f) s.state = 3
            }
            3 -> { // 淡出
                s.lifeAlpha -= dt.toFloat() / s.fadeMs
                if (s.lifeAlpha <= 0f) {
                    s.lifeAlpha = 0f
                    s.state = 0
                    // 下一轮重新错落
                    s.waitMs = Random.nextLong(1500L, 4200L).toFloat()
                }
            }
        }
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (globalAlpha <= 0.01f || stars.isEmpty()) return
        val tSec = timeMs / 1000.0
        for (s in stars) {
            val life = s.lifeAlpha
            if (life <= 0.01f) continue
            var brightness = 1f
            if (s.state == 2) {
                // 驻留期间若隐若现：余弦波在 [twinkleMin..1] 之间来回
                val wave = 0.5 + 0.5 * cos(2.0 * Math.PI * tSec * s.twinkleFreq + s.twinklePhase)
                brightness = (s.twinkleMin + (1f - s.twinkleMin) * wave).toFloat()
            }
            val a = (globalAlpha * life * brightness).coerceIn(0f, 1f)
            if (a <= 0.01f) continue
            paint.alpha = (a * 255).toInt()
            val bmp = s.bitmap ?: continue
            canvas.drawBitmap(bmp, s.x - s.side / 2f, s.y - s.side / 2f, paint)
        }
        paint.alpha = 255
    }

    // ---------- 星芒位图预生成 ----------

    private fun createStarBitmap(radius: Float, lenMul: Float, style: StarStyle, angleDeg: Float): Bitmap {
        val len = radius * lenMul
        val pad = 4f
        val side = ceil((2f * (radius + len) + 2f * pad).toDouble()).toInt()
        val cx = side / 2f
        val cy = side / 2f
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 中心柔光晕：远处亮点的光晕感
        p.shader = RadialGradient(
            cx, cy, radius * 1.9f,
            intArrayOf(0x66FFFFFF, 0x2EFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, radius * 1.9f, p)
        p.shader = null
        // 中心小亮点
        p.color = 0xBFFFFFFF.toInt()
        c.drawCircle(cx, cy, radius * 0.72f, p)
        // 光芒：中心亮、尖端渐隐
        when (style) {
            StarStyle.TWO_SPIKE -> {
                drawSpike(c, cx, cy, len, radius * 0.32f, angleDeg, p)
            }
            StarStyle.FOUR_SPIKE -> {
                drawSpike(c, cx, cy, len, radius * 0.28f, angleDeg, p)
                drawSpike(c, cx, cy, len, radius * 0.28f, angleDeg + 90f, p)
            }
        }
        return bmp
    }

    /** 画一条以 (cx,cy) 为中心、向 ±len 两端伸出并收敛为尖的长菱形光芒（两端尖）。 */
    private fun drawSpike(
        c: Canvas,
        cx: Float,
        cy: Float,
        len: Float,
        halfW: Float,
        angleDeg: Float,
        p: Paint
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val nx = -sinA
        val ny = cosA
        val ex1 = cx + len * cosA
        val ey1 = cy + len * sinA
        val ex2 = cx - len * cosA
        val ey2 = cy - len * sinA
        val path = Path()
        path.moveTo(ex1, ey1)                                     // 正尖端（收敛）
        path.lineTo(cx + nx * halfW, cy + ny * halfW)             // 中心一侧
        path.lineTo(ex2, ey2)                                     // 负尖端（收敛）
        path.lineTo(cx - nx * halfW, cy - ny * halfW)             // 中心另一侧
        path.close()
        p.shader = LinearGradient(
            ex2, ey2, ex1, ey1,
            intArrayOf(0x00FFFFFF, 0x78FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, p)
        p.shader = null
    }
}
