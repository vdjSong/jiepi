package com.jiepi.player.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * 全局弹窗/卡片配色统一入口：
 * 深灰 #2C2C2E 圆角 24dp 卡片 + 整圈霓虹四色描边。
 * 所有弹窗/卡片的色值统一引用本对象常量，禁止硬编码散落。
 */
object NeonCard {
    // —— 卡片色板（五色 + 辅助灰） ——
    const val CARD = 0xFF2C2C2E.toInt()       // 卡片深灰底
    const val BUTTON = 0xFF333333.toInt()     // 按钮深灰底
    const val RED = 0xFFFF9A8A.toInt()        // 红色语义（选中/回收站底）
    const val RED_FILL = 0xFFE05A4A.toInt()   // 红色语义按钮填充底（配浅字）
const val BLUE_FILL = 0xFF1565C0.toInt()  // 浓蓝按钮填充底（配白图标）
    const val GREEN = 0xFF1ED760.toInt()      // 绿色（正在播放/添加弹窗选中）
    const val WHITE = 0xFFE0E0E0.toInt()      // 柔和白（普通文字/勾选框）
    const val SUB = 0xFF888888.toInt()        // 次级灰字（副标题/提示）
    const val DARK_TEXT = 0xFF1A1A1A.toInt()  // 亮色底上的深色字
    const val GRAY_TEXT = 0xFFCCCCCC.toInt()  // 未选中浅灰字

    // —— 霓虹四色（整圈扫描渐变闭环：紫 → 粉 → 青 → 紫） ——
    const val NEON_PURPLE = 0xFFC084FC.toInt()
    const val NEON_PINK = 0xFFF472B6.toInt()
    const val NEON_CYAN = 0xFF38BDF8.toInt()
    const val NEON_BLUE = 0xFF60A5FA.toInt()  // 两色模式次色（紫↔蓝↔紫）

    /**
     * 深灰圆角卡片 + 整圈霓虹四色描边（与三点菜单同一套）。
     * @param cornerDp 卡片圆角半径，24dp
     * @param strokeDp 描边线宽，3dp
     */
    fun cardBackground(
        context: Context,
        cornerDp: Int = 24,
        strokeDp: Int = 3
    ): LayerDrawable {
        val density = context.resources.displayMetrics.density
        val cornerPx = cornerDp * density
        return LayerDrawable(
            arrayOf(
                // 第1层：背景底，原始单层深灰
                GradientDrawable().apply {
                    setColor(CARD)
                    cornerRadius = cornerPx
                },
                // 第2层：整圈霓虹描边，与背景底同圆角，光条骑在卡片边缘上
                NeonCornerDrawable(
                    cornerRadius = cornerPx,
                    strokeWidth = strokeDp * density,
                    glowColor = NEON_PURPLE,
                    secondColor = NEON_BLUE,
                    colors = intArrayOf(
                        NEON_PURPLE, // 霓虹紫
                        NEON_PINK,   // 高光粉
                        NEON_CYAN,   // 电光青
                        NEON_PURPLE  // 回到霓虹紫，闭环
                    )
                )
            )
        ).apply {
            // STACK 模式：描边层按全尺寸布局，不被内缩
            paddingMode = LayerDrawable.PADDING_MODE_STACK
        }
    }
}
