package com.jiepi.player.ui.more

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

/**
 * 关于页：
 * 版本信息 + 检查更新（本地占位，不联网）+ 联系客服（纯文字展示）。
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
        addRow(MoreUi.menuRow(ctx, "检查更新", "当前已是最新版本（本地纯离线，不联网）", onClick = {
            Toast.makeText(ctx, "当前 v${BuildConfig.VERSION_NAME} 已是最新版本", Toast.LENGTH_SHORT).show()
        }))
        addRow(MoreUi.menuRow(ctx, "联系客服", "QQ：120826194（纯文字展示，不跳转）", onClick = {
            Toast.makeText(ctx, "联系客服：QQ 120826194", Toast.LENGTH_SHORT).show()
        }))
    }
}
