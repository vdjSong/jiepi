package com.jiepi.player.ui.play

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jiepi.player.ui.NeonSlider
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.PlayerManager
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentEqBinding
import kotlin.math.roundToInt

class EqFragment : Fragment() {

    private var _binding: FragmentEqBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 状态栏：全 app 统一黑底白字，MainActivity 已全局设置，这里不再各自保存/恢复

        // 隐藏底部三个悬浮按钮（不挡 EQ 控件）；离开时由详情页/主页自己恢复，这里不管恢复
        hideBottomBar()

        // 底部避开系统导航条（手势条/三键），预设按钮不被挡在下面
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }

        // 返回：回详情页
        binding.btnEqBack.setOnClickListener {
            findNavController().popBackStack()
        }

        // 10 个频段竖滑条：中间 24 = 0dB，左右 ±12.0dB（一格 0.5dB）
        // 拖动即写入 PlayerManager 并刷 Equalizer，曲线窗口同步
        val bandSeekIds = arrayOf(
            R.id.eq_band_1, R.id.eq_band_2, R.id.eq_band_3, R.id.eq_band_4, R.id.eq_band_5,
            R.id.eq_band_6, R.id.eq_band_7, R.id.eq_band_8, R.id.eq_band_9, R.id.eq_band_10
        )
        val bandValIds = arrayOf(
            R.id.eq_val_1, R.id.eq_val_2, R.id.eq_val_3, R.id.eq_val_4, R.id.eq_val_5,
            R.id.eq_val_6, R.id.eq_val_7, R.id.eq_val_8, R.id.eq_val_9, R.id.eq_val_10
        )
        for (i in 0 until 10) {
            val seek = binding.root.findViewById<NeonSlider>(bandSeekIds[i])
            val valTv = binding.root.findViewById<TextView>(bandValIds[i])
            // 打开 EQ 页时恢复当前设置（全局保存，切歌不丢）
            val initDb = PlayerManager.eqBandLevels[i]
            seek.progress = (initDb * 2 + 24).roundToInt().coerceIn(0, 48)
            valTv.text = if (initDb == 0f) "0.0" else String.format("%+.1f", initDb)
            // 曲线窗口同步恢复：新建的 EqCurveView 默认全 0，不喂值画出来就是平直线
            binding.eqCurve.setValue(i, initDb)
            seek.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
                override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                    val db = (progress - 24) / 2f
                    PlayerManager.eqBandLevels[i] = db
                    PlayerManager.applyAudioFxState()
                    valTv.text = if (db == 0f) "0.0" else String.format("%+.1f", db)
                    // 曲线窗口跟着画：第 i 个频段
                    binding.eqCurve.setValue(i, db)
                }

                override fun onStartTrackingTouch(slider: NeonSlider) {}

                override fun onStopTrackingTouch(slider: NeonSlider) {}
            })
        }

        // 滑动块：控制十频段调节区左右移动（曲线窗口固定一屏显示，不随动）
        binding.eqScrollSeek.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
            override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                binding.eqBandsViewport.post {
                    val contentW = binding.eqBandsContent.width
                    val viewportW = binding.eqBandsViewport.width
                    if (contentW > 0 && viewportW > 0) {
                        val maxScroll = (contentW - viewportW).coerceAtLeast(0)
                        binding.eqBandsContent.translationX = -maxScroll * progress / 100f
                    }
                }
            }

            override fun onStartTrackingTouch(slider: NeonSlider) {}

            override fun onStopTrackingTouch(slider: NeonSlider) {}
        })

        // 混响：0~100%，拖动即写 PlayerManager 并刷 PresetReverb
        val reverbSeek = binding.root.findViewById<NeonSlider>(R.id.eq_reverb_seek)
        val reverbVal = binding.root.findViewById<TextView>(R.id.eq_reverb_val)
        reverbSeek.progress = PlayerManager.effectReverb
        reverbVal.text = "${PlayerManager.effectReverb}%"
        reverbSeek.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
            override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                PlayerManager.effectReverb = progress
                PlayerManager.applyAudioFxState()
                reverbVal.text = "$progress%"
            }

            override fun onStartTrackingTouch(slider: NeonSlider) {}

            override fun onStopTrackingTouch(slider: NeonSlider) {}
        })

        // 5 个效果滑动条：动态推进/高保真/高音加强/低音加强/环绕，接对应 audiofx
        val effectSeekIds = arrayOf(
            R.id.eq_effect_dynamic, R.id.eq_effect_hifi, R.id.eq_effect_high,
            R.id.eq_effect_low, R.id.eq_effect_surround
        )
        val effectValIds = arrayOf(
            R.id.eq_effect_dynamic_val, R.id.eq_effect_hifi_val, R.id.eq_effect_high_val,
            R.id.eq_effect_low_val, R.id.eq_effect_surround_val
        )
        for (i in effectSeekIds.indices) {
            val seek = binding.root.findViewById<NeonSlider>(effectSeekIds[i])
            val valTv = binding.root.findViewById<TextView>(effectValIds[i])
            val initVal = intArrayOf(
                PlayerManager.effectDynamic, PlayerManager.effectHifi,
                PlayerManager.effectHigh, PlayerManager.effectLow, PlayerManager.effectSurround
            )[i]
            seek.progress = initVal
            valTv.text = "$initVal%"
            seek.setOnProgressChangeListener(object : NeonSlider.OnProgressChangeListener {
                override fun onProgressChanged(slider: NeonSlider, progress: Int, fromUser: Boolean) {
                    when (i) {
                        0 -> PlayerManager.effectDynamic = progress
                        1 -> PlayerManager.effectHifi = progress
                        2 -> PlayerManager.effectHigh = progress
                        3 -> PlayerManager.effectLow = progress
                        4 -> PlayerManager.effectSurround = progress
                    }
                    PlayerManager.applyAudioFxState()
                    valTv.text = "$progress%"
                }

                override fun onStartTrackingTouch(slider: NeonSlider) {}

                override fun onStopTrackingTouch(slider: NeonSlider) {}
            })
        }
    }

    // 把底部三个悬浮按钮藏起来（先取消动画，防止反弹结束后通过 withEndAction 重新弹回来）
    private fun hideBottomBar() {
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            val btn = requireActivity().findViewById<View>(id) ?: continue
            btn.animate().cancel()
            btn.visibility = View.GONE
        }
    }

    // 每次 EQ 页可见都强制隐藏（防旧实例残留、其他逻辑在 viewCreated 之后再把按钮弹回来）
    override fun onResume() {
        super.onResume()
        hideBottomBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 离开 EQ 页：状态栏不恢复（全 app 统一黑底白字，MainActivity 全局兜底）。
        // 底部按钮不在这里恢复——EQ 页总从播放详情页进来，详情页 view 还活着时应保持隐藏，
        // 切到歌单/我的页时由它们自己 onResume 拉回
        _binding = null
    }
}
