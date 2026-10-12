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
import android.widget.LinearLayout
import android.widget.TextView
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
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(pressLighten(NeonCard.BUTTON))
                    cornerRadius = dp(context, 28).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(NeonCard.BUTTON)
                    cornerRadius = dp(context, 28).toFloat()
                })
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
            setOnClickListener { onClick() }
        }

    // 菜单入口行：标题 + 副描述 + 右侧箭头（深灰圆角条，按压提亮）
    fun menuRow(context: Context, title: String, desc: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val corner = dp(context, 20).toFloat()
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(pressLighten(NeonCard.BUTTON))
                    cornerRadius = corner
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(NeonCard.BUTTON)
                    cornerRadius = corner
                })
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
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
            addView(TextView(context).apply {
                text = "›"
                textSize = 26f
                setTextColor(NeonCard.SUB)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(pressLighten(NeonCard.BUTTON))
                    cornerRadius = corner
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(NeonCard.BUTTON)
                    cornerRadius = corner
                })
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
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
