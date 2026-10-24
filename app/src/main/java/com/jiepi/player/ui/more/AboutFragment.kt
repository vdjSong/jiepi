package com.jiepi.player.ui.more

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.BuildConfig
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentAboutBinding
import com.jiepi.player.ui.NeonCard
import com.jiepi.player.ui.NeonSlider
import com.jiepi.player.update.UpdateManager
import java.io.File

/**
 * 关于页：
 * 版本信息 + 检查更新（联网检查 GitHub 最新版，有新版本弹窗下载安装）+ 联系客服（纯文字展示）。
 */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    /** 等待用户开启"安装未知应用"权限后自动重试的安装包 */
    private var pendingInstall: File? = null

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
        // 从"允许安装未知应用"设置页返回后，自动重试拉起安装器
        val f = pendingInstall
        if (f != null) {
            pendingInstall = null
            tryInstall(requireContext(), f)
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
                    showDownloadDialog(ctx, apkUrl!!)
                }
                .setNegativeButton("暂不更新", null)
                .show()
                .let { styleDialog(it) }
        }
    }

    /**
     * 下载进度对话框：
     * 下载过程实时显示百分比进度条，下载完成自动调系统安装器；失败/取消时关闭并提示。
     */
    private fun showDownloadDialog(ctx: Context, apkUrl: String) {
        val density = ctx.resources.displayMetrics.density
        val progressText = TextView(ctx).apply {
            text = "0%"
            setTextColor(NeonCard.WHITE)
            textSize = 13f
        }
        val slider = NeonSlider(ctx).apply {
            showThumb = false
            max = 100
            progress = 0
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                (20 * density).toInt(), (12 * density).toInt(),
                (20 * density).toInt(), (20 * density).toInt()
            )
            addView(
                slider,
                LinearLayout.LayoutParams(0, (8 * density).toInt(), 1f)
            )
            addView(
                progressText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (12 * density).toInt() })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("正在下载更新包")
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(NeonCard.cardBackground(ctx))
        dialog.show()
        dialog.window?.decorView?.post {
            val decor = dialog.window?.decorView ?: return@post
            applyDialogTextColor(decor)
        }

        UpdateManager.downloadApk(
            ctx, apkUrl,
            onProgress = { pct ->
                if (!dialog.isShowing) return@downloadApk
                if (pct < 0) {
                    progressText.text = "…"
                } else {
                    slider.progress = pct
                    progressText.text = "$pct%"
                }
            },
            onDone = { file, downloadError ->
                if (dialog.isShowing) dialog.dismiss()
                if (downloadError != null || file == null) {
                    Toast.makeText(ctx, downloadError ?: "下载失败", Toast.LENGTH_SHORT).show()
                } else {
                    tryInstall(ctx, file)
                }
            }
        )
    }

    /**
     * 下载完成后的安装：
     * 已授予"安装未知应用"权限 → 直接拉起系统安装器；
     * 未授权 → 弹窗提示并引导去设置开启，授权返回后自动重试安装。
     */
    private fun tryInstall(ctx: Context, file: File) {
        if (UpdateManager.installApk(ctx, file)) {
            return
        }
        val sizeMb = "%.1f".format(file.length() / 1024.0 / 1024.0)
        AlertDialog.Builder(ctx)
            .setTitle("更新包下载完成")
            .setMessage("大小：$sizeMb MB\n需要允许\"洁癖播放器\"安装未知应用，才能继续安装。")
            .setPositiveButton("去开启") { _, _ ->
                pendingInstall = file
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName)
                )
                try {
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    pendingInstall = null
                    Toast.makeText(ctx, "请到系统设置中手动开启安装未知应用权限", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("暂不安装", null)
            .show()
            .let { styleDialog(it) }
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(NeonCard.cardBackground(requireContext()))
        dialog.window?.decorView?.post {
            val decor = dialog.window?.decorView ?: return@post
            applyDialogTextColor(decor)
        }
    }

    private fun applyDialogTextColor(view: View) {
        when (view) {
            is EditText -> {
                view.setTextColor(NeonCard.WHITE)
                view.setHintTextColor(NeonCard.SUB)
                view.setBackgroundResource(R.drawable.bg_input_rounded)
            }
            is android.widget.CompoundButton -> {
                view.setTextColor(NeonCard.WHITE)
                view.buttonTintList =
                    android.content.res.ColorStateList.valueOf(NeonCard.WHITE)
            }
            is android.widget.CheckedTextView -> {
                view.setTextColor(NeonCard.WHITE)
                view.checkMarkTintList =
                    android.content.res.ColorStateList.valueOf(NeonCard.WHITE)
            }
            is Button -> {
                view.setTextColor(NeonCard.WHITE)
                val density = view.resources.displayMetrics.density
                view.setBackgroundResource(R.drawable.bg_dialog_button)
                view.setPadding(
                    (24 * density).toInt(),
                    view.paddingTop,
                    (24 * density).toInt(),
                    view.paddingBottom
                )
            }
            is TextView -> view.setTextColor(NeonCard.WHITE)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyDialogTextColor(view.getChildAt(i))
            }
        }
    }
}
