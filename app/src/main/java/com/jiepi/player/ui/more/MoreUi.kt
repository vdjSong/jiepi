package com.jiepi.player.ui.more

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jiepi.player.R
import com.jiepi.player.ui.NeonCard

/**
 * 更多页共用自绘件：
 * 沿用全局霓虹体系——黑底 + 深灰圆角条 + 霓虹卡片/胶囊按钮。
 * 设置 / 关于 / 回收站 / 开源声明四个二级页全部复用本工具类，色值只走 NeonCard。
 */
object MoreUi {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    // 按压提亮：往白色方向抬，模拟半透明白覆盖在按钮上（与 MusicFragment 同一手感）
    fun pressLighten(color: Int): Int {
        val r = (((color shr 16) and 0xFF) + 45).coerceAtMost(255)
        val g = (((color shr 8) and 0xFF) + 45).coerceAtMost(255)
        val b = ((color and 0xFF) + 45).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // 圆角水波纹（全 App 统一标准做法）：
    // content 传圆角背景（普通态 + 按压提亮态），mask 传同圆角裁剪层，
    // 波纹被约束在圆角范围内，不再按矩形边界扩散导致四角溢出圆角按钮。
    fun roundedRipple(context: Context, cornerPx: Float, normalColor: Int): RippleDrawable {
        val bg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                setColor(pressLighten(normalColor))
                cornerRadius = cornerPx
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(normalColor)
                cornerRadius = cornerPx
            })
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x22FFFFFF.toInt()),
            bg,
            GradientDrawable().apply {
                // mask 颜色任意，仅作为裁剪遮罩参与绘制
                setColor(0xFF000000.toInt())
                cornerRadius = cornerPx
            }
        )
    }

    // 霓虹卡片根：深灰圆角 24dp + 整圈四色描边（弹窗/大卡片用）
    fun neonCardRoot(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = NeonCard.cardBackground(context)
            // background 赋值会用 drawable 的 padding 覆盖本 padding，必须放在 background 之后
            setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 22))
        }

    // 深灰胶囊按钮（弹窗/页内主按钮），字色由调用方传语义色
    fun neonButton(context: Context, text: String, textColor: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
            background = roundedRipple(context, dp(context, 28).toFloat(), NeonCard.BUTTON)
            setOnClickListener { onClick() }
        }

    // 菜单入口行：标题 + 副描述 + 右侧箭头（深灰圆角条，按压提亮）
    fun menuRow(context: Context, title: String, desc: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val corner = dp(context, 20).toFloat()
            background = roundedRipple(context, corner, NeonCard.BUTTON)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            setOnClickListener { onClick() }

            val textBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            textBox.addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(NeonCard.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            textBox.addView(TextView(context).apply {
                text = desc
                textSize = 12f
                setTextColor(NeonCard.SUB)
                setPadding(0, dp(context, 3), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val arrow = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NeonCard.SUB)
                // 图标中心 = View 中心，旋转绕 View 中心即绕图标中心，绝对精确（无字体度量偏移）
            }
            // 固定正方形尺寸：图标中心 = 容器中心，旋转 pivot 取容器中心即绕图标中心转
            addView(arrow, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)))
            // 记录箭头引用：折叠面板类入口用 row.tag 取箭头做旋转动画；非抽屉入口不调用无副作用
            this.tag = arrow
        }

    // 抽屉箭头旋转：展开→顺时针旋转 90°（箭头朝下），收起→逆时针转回 0°（箭头朝右）
    // 旋转中心取箭头自身几何中心（View 默认 pivot 在左上角，直接 rotation 会绕一角转、视觉别扭）；
    // 动画直接落到目标角度而非递增：即使中途被打断也会停在正确位置；
    // 调用方每次都以面板真实可见性重新计算 open，箭头状态与抽屉真实状态天然同步，不会错位
    fun rotateArrow(row: View, open: Boolean) {
        val arrow = row.tag as? View ?: return
        // 布局可能尚未完成：此刻 width/height 为 0，若直接设 pivotX = 0 会导致旋转仍绕左上角（一角钉住不动）。
        // post 到下一帧保证拿到真实几何尺寸后，再以几何中心为轴心旋转。
        arrow.post {
            val px = arrow.width / 2f
            val py = arrow.height / 2f
            if (arrow.pivotX != px) arrow.pivotX = px
            if (arrow.pivotY != py) arrow.pivotY = py
            arrow.animate().rotation(if (open) 90f else 0f).setDuration(150).start()
        }
    }

    // 抽屉面板：内嵌深灰圆角盒，内容由调用方填充（默认隐藏）
    fun panelBox(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(NeonCard.CARD)
                cornerRadius = dp(context, 20).toFloat()
            }
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
            visibility = View.GONE
        }

    // 可点击详情行：左标签 + 右侧箭头（关于页"联系客服"用），样式对齐 infoRow（15f 白字、普通字重），带按压提亮与水波纹
    fun infoRowAction(context: Context, title: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val corner = dp(context, 20).toFloat()
            background = roundedRipple(context, corner, NeonCard.BUTTON)
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(NeonCard.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val arrow = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NeonCard.SUB)
                // 图标中心 = View 中心，旋转绕 View 中心即绕图标中心，绝对精确（无字体度量偏移）
            }
            // 固定正方形尺寸：图标中心 = 容器中心，旋转 pivot 取容器中心即绕图标中心转
            addView(arrow, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)))
            // 记录箭头引用：折叠面板类入口用 row.tag 取箭头做旋转动画
            this.tag = arrow
        }

    // 面板内纯文字单行按钮：深灰圆角条（BUTTON 色）+ 按压提亮 + 水波纹，左文字上下居中、无副描述无箭头
    // （联系客服抽屉里的 QQ 行用）
    fun plainRow(context: Context, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val corner = dp(context, 20).toFloat()
            background = roundedRipple(context, corner, NeonCard.BUTTON)
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(NeonCard.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    // 二级页顶部栏：返回箭头 + 标题
    fun headerBar(context: Context, title: String, onBack: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = "‹"
                textSize = 30f
                setTextColor(NeonCard.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(context, 12), 0)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))

            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTextColor(NeonCard.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    // 详情行：左侧说明 + 右侧值（设置页 / 关于页用）
    fun infoRow(context: Context, label: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(NeonCard.BUTTON)
                cornerRadius = dp(context, 20).toFloat()
            }
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(NeonCard.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(NeonCard.SUB)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    // 开关行：标题 + 副描述 + 右侧霓虹开关（开=霓虹绿圆钮靠右，关=深灰圆钮靠左），整行点击切换
    fun switchRow(
        context: Context,
        title: String,
        desc: String,
        initialOn: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        var on = initialOn
        val trackW = dp(context, 52)
        val trackH = dp(context, 30)
        val thumbSize = dp(context, 24)
        val gap = dp(context, 3)

        // 轨道：圆角矩形，开=霓虹绿，关=卡片深灰（比行背景深一档，保证关态也有清晰轮廓）
        val track = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = trackH / 2f
                setColor(if (on) NeonCard.GREEN else NeonCard.CARD)
            }
        }
        // 滑块：圆形，开=白，关=次级灰
        val thumb = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = thumbSize / 2f
                setColor(if (on) NeonCard.WHITE else NeonCard.SUB)
            }
            elevation = dp(context, 2).toFloat()
        }
        val switchBox = FrameLayout(context).apply {
            addView(track, FrameLayout.LayoutParams(trackW, trackH, Gravity.CENTER_VERTICAL))
            addView(thumb, FrameLayout.LayoutParams(thumbSize, thumbSize))
        }

        fun refresh() {
            (track.background as GradientDrawable).setColor(if (on) NeonCard.GREEN else NeonCard.CARD)
            (thumb.background as GradientDrawable).setColor(if (on) NeonCard.WHITE else NeonCard.SUB)
            val lp = thumb.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (on) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            lp.leftMargin = if (on) 0 else gap
            lp.rightMargin = if (on) gap else 0
            thumb.layoutParams = lp
        }
        refresh()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val corner = dp(context, 20).toFloat()
            background = roundedRipple(context, corner, NeonCard.BUTTON)
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))

            val textBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            textBox.addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(NeonCard.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            textBox.addView(TextView(context).apply {
                text = desc
                textSize = 12f
                setTextColor(NeonCard.SUB)
                setPadding(0, dp(context, 3), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchBox, LinearLayout.LayoutParams(trackW, trackH))

            setOnClickListener {
                on = !on
                refresh()
                onChange(on)
            }
        }
    }

    // 纵向间距统一由调用方内联 LayoutParams 控制（dp 需要 Context，不放静态方法里）
}
