package com.jiepi.player.ui.play

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

// 频段可视化曲线：10 个点的折线，横轴=频段从左到右，纵轴=增益(dB)
// 滑条一拖就调用 setValue，曲线跟着画，跟滑条一一对应
class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 10 个频段的增益值：-12.0 ~ +12.0，0 表示 0dB（不动）
    private val values = FloatArray(10)

    // 曲线画笔：柔和白
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0F0F0.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    // 小圆点画笔：柔和白
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0F0F0.toInt()
        style = Paint.Style.FILL
    }

    // 中线（0dB）画笔：暗一点
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        strokeWidth = 2f
    }

    // 滑条一拖就叫这里：第几个频段 + 当前增益
    fun setValue(index: Int, db: Float) {
        if (index in values.indices) {
            values[index] = db
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        // 上下留点边，最高/最低增益不顶到边界
        val amp = h / 2f - 24f

        // 中线：0dB
        canvas.drawLine(0f, midY, w, midY, midPaint)

        // 10 个点：x 均分整条宽，y 由增益算出来（+12dB 往上，-12dB 往下）
        val points = mutableListOf<PointF>()
        for (i in values.indices) {
            val x = w * (i + 0.5f) / values.size
            val y = midY - values[i] / 12f * amp
            points.add(PointF(x, y))
        }

        // 圆滑曲线：Catmull-Rom 转 Bezier，频段间平滑过渡不硬拐弯
        val path = Path()
        for (i in points.indices) {
            val cur = points[i]
            if (i == 0) {
                path.moveTo(cur.x, cur.y)
                continue
            }
            val prev = points[i - 1]
            val prevPrev = if (i >= 2) points[i - 2] else prev
            val next = if (i + 1 < points.size) points[i + 1] else cur
            val c1x = prev.x + (cur.x - prevPrev.x) / 6f
            val c1y = prev.y + (cur.y - prevPrev.y) / 6f
            val c2x = cur.x - (next.x - prev.x) / 6f
            val c2y = cur.y - (next.y - prev.y) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, cur.x, cur.y)
        }
        canvas.drawPath(path, curvePaint)
        // 每个频段位置补个小圆点，跟滑条对得上
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 6f, dotPaint)
        }
    }
}
