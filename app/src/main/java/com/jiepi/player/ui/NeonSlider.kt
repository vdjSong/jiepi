package com.jiepi.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * NeonSlider：自绘美化滑动条，全局替换系统 SeekBar。
 *
 * - 轨道：圆角细条，未填充深灰、已填充霓虹青→霓虹紫渐变（呼应 NeonCard 色板）
 * - 滑块：白色胶囊（圆角矩形）+ 青色描边与光晕，拖动时微微放大
 * - 档位：progress 天然整数即硬档位，跨档才变化，无需额外吸附算法
 * - 兼容：XML 沿用 android:max / android:progress；EQ 竖滑条沿用 rotation=270
 *   （触摸用本地坐标 x，View 旋转后坐标自动映射，与系统 SeekBar 行为一致）
 */
class NeonSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnProgressChangeListener {
        fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(slider: NeonSlider)
        fun onStopTrackingTouch(slider: NeonSlider)
    }

    var listener: OnProgressChangeListener? = null

    fun setOnProgressChangeListener(l: OnProgressChangeListener?) {
        listener = l
    }

    var max: Int = 100
        set(value) {
            val v = if (value > 0) value else 1
            if (field != v) {
                field = v
                if (progress > v) progress = v
                invalidate()
            }
        }

    var progress: Int = 0
        set(value) {
            val v = value.coerceIn(0, max)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private var isDragging = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    init {
        // 兼容 XML 里的 android:max / android:progress（与系统 SeekBar 属性同名）
        // 注：android.R.styleable 非公开 API，改用 getAttributeIntValue 直接读 android 命名空间
        if (attrs != null) {
            val androidNs = "http://schemas.android.com/apk/res/android"
            max = attrs.getAttributeIntValue(androidNs, "max", 100)
            progress = attrs.getAttributeIntValue(androidNs, "progress", 0)
        }
        // 触摸目标最小高度：布局 wrap_content 时给够手感
        minimumHeight = dp(40f).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val defaultW = dp(120f).toInt()
        val defaultH = dp(40f).toInt()
        val w = when (wMode) {
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> minOf(wSize, defaultW)
            else -> defaultW
        }
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> minOf(hSize, defaultH)
            else -> defaultH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackH = dp(4f)
        val trackTop = (height - trackH) / 2f

        // 底轨：深灰圆角条
        trackPaint.style = Paint.Style.FILL
        trackPaint.color = 0xFF3A3A3C.toInt()
        trackRect.set(0f, trackTop, width.toFloat(), trackTop + trackH)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        // 已填充段：霓虹青 → 霓虹紫 渐变
        if (progress > 0 && max > 0) {
            val thumbW = dp(if (isDragging) 26f else 24f)
            val usable = width - thumbW
            val fillEnd = thumbW / 2f + usable * progress / max
            if (fillEnd > 0) {
                fillPaint.shader = LinearGradient(
                    0f, 0f, fillEnd, 0f,
                    0xFF38BDF8.toInt(), 0xFFC084FC.toInt(),
                    Shader.TileMode.CLAMP
                )
                val fillRect = RectF(0f, trackTop, fillEnd, trackTop + trackH)
                canvas.drawRoundRect(fillRect, trackH / 2f, trackH / 2f, fillPaint)
                fillPaint.shader = null
            }
        }

        // 胶囊滑块：白色主体 + 青色描边 + 外圈光晕
        val thumbW = dp(if (isDragging) 26f else 24f)
        val thumbH = dp(if (isDragging) 22f else 20f)
        val cx = if (max > 0) {
            val usable = width - thumbW
            thumbW / 2f + usable * progress / max
        } else thumbW / 2f
        val cy = height / 2f
        val left = cx - thumbW / 2f
        val top = cy - thumbH / 2f
        val right = cx + thumbW / 2f
        val bottom = cy + thumbH / 2f

        // 光晕：半透明青，略大于滑块
        val glowPad = dp(4f)
        glowPaint.style = Paint.Style.FILL
        glowPaint.color = if (isDragging) 0x5538BDF8.toInt() else 0x2638BDF8.toInt()
        canvas.drawRoundRect(
            RectF(left - glowPad, top - glowPad, right + glowPad, bottom + glowPad),
            thumbH / 2f + glowPad, thumbH / 2f + glowPad, glowPaint
        )

        // 主体
        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = 0xFFF5F5F5.toInt()
        canvas.drawRoundRect(
            RectF(left, top, right, bottom),
            thumbH / 2f, thumbH / 2f, thumbPaint
        )

        // 描边
        thumbStrokePaint.style = Paint.Style.STROKE
        thumbStrokePaint.strokeWidth = dp(1.5f)
        thumbStrokePaint.color = 0xFF38BDF8.toInt()
        canvas.drawRoundRect(
            RectF(left, top, right, bottom),
            thumbH / 2f, thumbH / 2f, thumbStrokePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                listener?.onStartTrackingTouch(this)
                updateProgress(event.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateProgress(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                invalidate()
                listener?.onStopTrackingTouch(this)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateProgress(x: Float) {
        if (max <= 0 || width <= 0) return
        val fraction = (x / width).coerceIn(0f, 1f)
        val newProgress = (fraction * max).roundToInt().coerceIn(0, max)
        if (newProgress != progress) {
            progress = newProgress
            listener?.onProgressChanged(this, newProgress, true)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
