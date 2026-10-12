package com.jiepi.player.ui.more

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.BuildConfig
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentAboutBinding
import com.jiepi.player.update.UpdateManager

/**
 * 关于页：
 * 版本信息 + 检查更新（联网检查 GitHub 最新版，有新版本弹窗下载安装）+ 联系客服（纯文字展示）。
 */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildPage()
    }

    override fun onResume() {
        super.onResume()
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            requireActivity().findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private fun buildPage() {
        val ctx = requireContext()
        val content = binding.aboutContent
        content.removeAllViews()

        content.addView(
            MoreUi.headerBar(ctx, "关于") { findNavController().popBackStack() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        fun addRow(view: View) {
            content.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = MoreUi.dp(ctx, 18)
                }
            )
        }

        addRow(MoreUi.infoRow(ctx, "应用名称", "洁癖播放器"))
        addRow(MoreUi.infoRow(ctx, "当前版本", "v${BuildConfig.VERSION_NAME}"))
        addRow(MoreUi.infoRow(ctx, "作者", "Song • Ma"))
        addRow(MoreUi.menuRow(ctx, "检查更新", "联网检查 GitHub 最新版本", onClick = {
            checkUpdate(ctx)
        }))
        addRow(MoreUi.menuRow(ctx, "联系客服", "QQ：120826194（纯文字展示，不跳转）", onClick = {
            Toast.makeText(ctx, "联系客服：QQ 120826194", Toast.LENGTH_SHORT).show()
        }))
    }

    /**
     * 联网检查更新：
     * 查 GitHub 最新版 → 无新版提示已最新；有新版本弹窗 → 用户确认后下载并调系统安装器。
     */
    private fun checkUpdate(ctx: Context) {
        Toast.makeText(ctx, "正在检查更新…", Toast.LENGTH_SHORT).show()
        UpdateManager.checkLatest(ctx) { latestTag, apkUrl, error ->
            if (error != null) {
                Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
                return@checkLatest
            }
            val current = BuildConfig.VERSION_NAME
            if (!UpdateManager.isNewer(latestTag, current)) {
                Toast.makeText(ctx, "当前 v$current 已是最新版本", Toast.LENGTH_SHORT).show()
                return@checkLatest
            }
            AlertDialog.Builder(ctx)
                .setTitle("发现新版本 v$latestTag")
                .setMessage("当前版本 v$current\n是否立即下载并安装？")
                .setPositiveButton("立即更新") { _, _ ->
                    Toast.makeText(ctx, "正在下载更新包…", Toast.LENGTH_SHORT).show()
                    UpdateManager.downloadApk(ctx, apkUrl!!) { file, downloadError ->
                        if (downloadError != null || file == null) {
                            Toast.makeText(ctx, downloadError ?: "下载失败", Toast.LENGTH_SHORT).show()
                        } else {
                            UpdateManager.installApk(ctx, file)
                        }
                    }
                }
                .setNegativeButton("暂不更新", null)
                .show()
        }
    }
}
