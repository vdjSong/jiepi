package com.jiepi.player.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable

/**
 * 霓虹整圈描边 Drawable（一笔勾勒版）：
 * 沿卡片外缘画一整圈圆角矩形轮廓，颜色用 SweepGradient 绕中心扫描渐变。
 * 默认两色三标（紫↔蓝↔紫），也可通过 colors 传入任意多色组（如 紫→粉→青→紫 四色闭环），
 * positions 按颜色数均匀分布。光条宽度均匀、骑在卡片边缘上。无辉光层。
 * 参数均为像素值：cornerRadius 圆角半径、strokeWidth 线宽、glowColor 主色、secondColor 次色、colors 可选多色组。
 */
class NeonCornerDrawable(
    private val cornerRadius: Float,
    private val strokeWidth: Float,
    private val glowColor: Int,
    private val secondColor: Int,
    private val colors: IntArray? = null
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rectF = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val inset = strokeWidth / 2f
        // 用绝对边界，保证光条骑在真实边缘上、四边对称
        rectF.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
        path.reset()
        path.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
        paint.strokeWidth = strokeWidth
        // 整圈扫描渐变：默认紫→蓝→紫三标；colors 非空时按传入色组均匀分布（四色即 0/0.33/0.66/1 闭环）
        val sweepColors = colors ?: intArrayOf(glowColor, secondColor, glowColor)
        val sweepPositions = FloatArray(sweepColors.size) { i ->
            if (sweepColors.size == 1) 0f else i.toFloat() / (sweepColors.size - 1)
        }
        paint.shader = SweepGradient(
            rectF.centerX(), rectF.centerY(),
            sweepColors,
            sweepPositions
        )
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
