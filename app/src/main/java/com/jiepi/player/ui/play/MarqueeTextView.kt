package com.jiepi.player.ui.play

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

// 跑马灯 TextView：始终认为自己有焦点，多个跑马灯互不抢焦点、各自都能滚动
// 短文本正常居中显示，长文本自动从右往左滚动（LED 灯牌字幕效果）
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun isFocused(): Boolean = true
}
