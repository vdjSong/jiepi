package com.jiepi.player.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.BuildConfig
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentMoreBinding
import com.jiepi.player.ui.NeonCard

/**
 * 更多页：
 * 顶部 logo + 应用名 + 版本号；四入口：
 * 权限展示与说明（折叠）/ 软件设置 / 开源声明（折叠，四个库各自独立声明页）/ 关于。
 * 折叠面板用自绘霓虹体系（MoreUi），色值统一走 NeonCard。
 */
class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    // 两个折叠面板的当前状态
    private var permissionOpen = false
    private var licenseOpen = false

    // 开源声明四个条目：显示名 / 一句说明 / 声明页序号
    private val licenseItems = listOf(
        Triple("AndroidX", "AndroidX 全家桶（Google，含 Media3 等 10 个组件）", 0),
        Triple("rubber band", "音频变速 / 变调处理引擎", 1),
        Triple("pinyin4j", "中文拼音排序", 2),
        Triple("洁癖播放器", "本项目自身", 3)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvMoreVersion.text = "v${BuildConfig.VERSION_NAME}"
        buildMenu()
    }

    // 切回本页把底部三悬浮按钮拉回来（二级页会藏，回来必须露）
    override fun onResume() {
        super.onResume()
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            requireActivity().findViewById<View>(id)?.visibility = View.VISIBLE
        }
    }

    private fun buildMenu() {
        val ctx = requireContext()
        val container = binding.moreMenuContainer
        container.removeAllViews()

        fun addRow(view: View, withTopMargin: Boolean = true) {
            container.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (withTopMargin) topMargin = MoreUi.dp(ctx, 8)
                }
            )
        }

        // —— 入口 1：权限展示与说明（折叠面板） ——
        addRow(MoreUi.menuRow(ctx, "权限展示与说明", "本应用用到的权限及用途") { togglePermissionPanel() })

        val permissionPanel = MoreUi.panelBox(ctx)
        val permissions = listOf(
            "SAF（存储访问框架）" to "您亲自挑选文件夹授权给本应用，App 只能读写您授权的文件夹，拿不到其他任何文件，因此无需任何存储权限。找歌、删歌只在授权文件夹内进行；授权可随时在系统设置中撤销",
            "INTERNET" to "联网权限：仅用于检查更新，用完即弃",
            "ACCESS_NETWORK_STATE" to "网络状态：配合联网权限判断网络是否可用",
            "POST_NOTIFICATIONS" to "通知权限：播放状态通知栏常驻展示",
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK" to "前台服务：后台持续播放音乐"
        )
        permissions.forEachIndexed { i, (name, desc) ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, MoreUi.dp(ctx, if (i == 0) 0 else 12), 0, 0)
            }
            line.addView(TextView(ctx).apply {
                text = name
                textSize = 14f
                setTextColor(NeonCard.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            line.addView(TextView(ctx).apply {
                text = desc
                textSize = 12f
                setTextColor(NeonCard.SUB)
                setPadding(0, MoreUi.dp(ctx, 3), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            permissionPanel.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addRow(permissionPanel, withTopMargin = true)

        // —— 入口 2：软件设置（独立页） ——
        addRow(MoreUi.menuRow(ctx, "软件设置", "播放偏好、回收站与电池白名单") {
            findNavController().navigate(R.id.action_more_to_settings)
        })

        // —— 入口 3：开源声明（折叠面板） ——
        addRow(MoreUi.menuRow(ctx, "开源声明", "本项目使用的开源库与许可协议") { toggleLicensePanel() })

        val licensePanel = MoreUi.panelBox(ctx)
        licenseItems.forEach { (name, desc, index) ->
            licensePanel.addView(
                MoreUi.menuRow(ctx, name, desc) {
                    findNavController().navigate(
                        R.id.action_more_to_license,
                        bundleOf("index" to index)
                    )
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = MoreUi.dp(ctx, 10)
                }
            )
        }
        addRow(licensePanel, withTopMargin = true)

        // —— 入口 4：关于（独立页） ——
        addRow(MoreUi.menuRow(ctx, "关于", "版本信息、检查更新与联系客服") {
            findNavController().navigate(R.id.action_more_to_about)
        })

        // 记录面板引用，供折叠切换
        morePermissionPanel = permissionPanel
        moreLicensePanel = licensePanel

        // 每次重建视图都从收起状态开始（从二级页返回时 view 重建、但实例成员会残留，
        // 不重置会导致折叠标志位与真实 UI 状态不同步，出现"点一次没反应、点两次才展开"）
        permissionOpen = false
        licenseOpen = false
    }

    private var morePermissionPanel: LinearLayout? = null
    private var moreLicensePanel: LinearLayout? = null

    private fun togglePermissionPanel() {
        // 以面板真实可见性为准，不依赖可能过期的标志位，避免"点一次没反应、点两次才展开"
        val panel = morePermissionPanel ?: return
        val opening = panel.visibility != View.VISIBLE
        permissionOpen = opening
        panel.visibility = if (opening) View.VISIBLE else View.GONE
    }

    private fun toggleLicensePanel() {
        val panel = moreLicensePanel ?: return
        val opening = panel.visibility != View.VISIBLE
        licenseOpen = opening
        panel.visibility = if (opening) View.VISIBLE else View.GONE
    }
}
