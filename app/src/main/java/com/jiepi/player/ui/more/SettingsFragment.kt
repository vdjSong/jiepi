package com.jiepi.player.ui.more

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.BatteryWhiteList
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentSettingsBinding

/**
 * 软件设置页：
 * 摇一摇 / 播放记忆 / 启动自动播放 三行开关，状态持久化到 jiepi_data；
 * 摇一摇由 ShakeController 全局监听加速度传感器触发，固定切下一首；
 * 回收站为查看与恢复入口；
 * 电池白名单点击跳转由 BatteryWhiteList 承载：厂商映射表直达（华为/小米/OPPO/vivo/三星等），
 * 失败自动回退系统标准「电池优化」设置页，全程零新增权限。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildPage()
    }

    // 二级页不露底部悬浮按钮（返回更多页时由 MoreFragment 拉回）
    override fun onResume() {
        super.onResume()
        hideBottomButtons()
    }

    private fun hideBottomButtons() {
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            requireActivity().findViewById<View>(id)?.visibility = View.GONE
        }
    }

    // ---- 开关状态存储（jiepi_data，与回收站同一份持久化）----
    private fun prefs() =
        requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)

    private fun switchPref(key: String, def: Boolean): Boolean =
        prefs().getBoolean(key, def)

    private fun saveSwitch(key: String, value: Boolean) {
        prefs().edit().putBoolean(key, value).apply()
    }

    private fun buildPage() {
        val ctx = requireContext()
        val content = binding.settingsContent
        content.removeAllViews()

        content.addView(
            MoreUi.headerBar(ctx, "软件设置") { findNavController().popBackStack() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val margin = 18
        fun addRow(view: View) {
            content.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = MoreUi.dp(ctx, margin)
                }
            )
        }

        // 播放偏好：开关样式 + 状态存储（jiepi_data 持久化）
        addRow(MoreUi.switchRow(ctx, "摇一摇切歌", "晃动手机切换到下一首",
            initialOn = switchPref("sw_shake", false)) { saveSwitch("sw_shake", it) })
        addRow(MoreUi.switchRow(ctx, "播放记忆", "记住上次播放位置",
            initialOn = switchPref("sw_resume", false)) { saveSwitch("sw_resume", it) })
        addRow(MoreUi.switchRow(ctx, "启动自动播放", "打开 App 自动继续播放上次队列",
            initialOn = switchPref("sw_autoplay", false)) { saveSwitch("sw_autoplay", it) })

        // 回收站：查看与恢复入口
        addRow(MoreUi.menuRow(ctx, "回收站", "查看已回收歌曲，可一键恢复", onClick = {
            findNavController().navigate(R.id.action_settings_to_recycle_bin)
        }))

        // 电池白名单：按厂商直达对应电池/自启动管理页，失败回退系统标准页（无权限需求）
        addRow(MoreUi.menuRow(ctx, "电池白名单", "防省电杀进程，自动识别厂商跳转", onClick = { openBatterySettings() }))
    }

    // 跳电池白名单：厂商映射表直达（华为/小米/OPPO/vivo/三星等），失败自动回退系统电池优化页
    private fun openBatterySettings() {
        BatteryWhiteList.open(requireContext())
    }
}
