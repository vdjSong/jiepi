package com.jiepi.player.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentLicenseBinding
import com.jiepi.player.ui.NeonCard

/**
 * 开源声明详情页：
 * 由更多页的「开源声明」折叠面板四个按钮分别进入，index 0-3 对应：
 * AndroidX 开源组件汇总 / rubber band / pinyin4j / 洁癖播放器（本项目自身）。
 * 排版为：库名 / 版本 / 作者 / 地址 / 许可证 + 英文原文 / 中文参考翻译（可切换）。
 * index 0 = AndroidX 开源组件汇总（Apache-2.0，展开 10 个组件清单）、
 * index 1 = Rubber Band（GPL-2.0-or-later）、
 * index 2 = pinyin4j（GPL-2.0-or-later）、
 * index 3 = 洁癖播放器（本项目自身，GPL-3.0）；
 * 均为英文原文 + 非官方中文参考译本（可切换）。
 */
class LicenseFragment : Fragment() {

    private var _binding: FragmentLicenseBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLicenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildPage(requireArguments().getInt("index", 0))
    }

    override fun onResume() {
        super.onResume()
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            requireActivity().findViewById<View>(id)?.visibility = View.GONE
        }
    }

    /** 汇总条目内的单个组件：组件名/版本/发布页 */
    private class LicenseComponent(
        val name: String,
        val version: String,
        val url: String
    )

    /** 单库声明条目：库名/版本/作者/地址/许可证 + 原文/中文 raw 资源 + 可选组件清单 */
    private class LicenseEntry(
        val name: String,
        val version: String,
        val author: String,
        val url: String,
        val license: String,
        val rawEn: Int,
        val rawZh: Int,
        val components: List<LicenseComponent> = emptyList()
    )

    private fun buildPage(index: Int) {
        val ctx = requireContext()
        val content = binding.licenseContent
        content.removeAllViews()

        // index 0-3：AndroidX 开源组件汇总（Apache-2.0）/ Rubber Band（GPL-2.0-or-later）/
        // pinyin4j（GPL-2.0-or-later）/ 洁癖播放器（GPL-3.0）
        // 版本取自工程 libs.versions.toml / build.gradle.kts。
        val entries = listOf(
            LicenseEntry(
                name = "AndroidX 开源组件（Google）",
                version = "10 个组件，见下方清单",
                author = "Android Open Source Project（Google）",
                url = "https://developer.android.com/jetpack/androidx",
                license = "Apache-2.0",
                rawEn = R.raw.license_apache2,
                rawZh = R.raw.license_apache2_zh,
                components = listOf(
                    LicenseComponent("core-ktx", "1.10.1", "https://developer.android.com/jetpack/androidx/releases/core"),
                    LicenseComponent("appcompat", "1.6.1", "https://developer.android.com/jetpack/androidx/releases/appcompat"),
                    LicenseComponent("material", "1.10.0", "https://developer.android.com/jetpack/androidx/releases/material"),
                    LicenseComponent("constraintlayout", "2.1.4", "https://developer.android.com/jetpack/androidx/releases/constraintlayout"),
                    LicenseComponent("lifecycle（livedata/viewmodel）", "2.6.1", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
                    LicenseComponent("navigation（fragment/ui）", "2.6.0", "https://developer.android.com/jetpack/androidx/releases/navigation"),
                    LicenseComponent("documentfile", "1.0.1", "https://developer.android.com/jetpack/androidx/releases/documentfile"),
                    LicenseComponent("swiperefreshlayout", "1.1.0", "https://developer.android.com/jetpack/androidx/releases/swiperefreshlayout"),
                    LicenseComponent("media3-exoplayer", "1.5.1", "https://developer.android.com/jetpack/androidx/releases/media3"),
                    LicenseComponent("media3-common", "1.5.1", "https://developer.android.com/jetpack/androidx/releases/media3")
                )
            ),
            LicenseEntry(
                name = "Rubber Band",
                version = "4.0.0（源码集成）",
                author = "Chris Cannam / Particular Programs Ltd",
                url = "https://breakfastquay.com/rubberband/",
                license = "GPL-2.0-or-later",
                rawEn = R.raw.license_gpl2,
                rawZh = R.raw.license_gpl2_zh
            ),
            LicenseEntry(
                name = "pinyin4j",
                version = "2.5.1",
                author = "Li Min",
                url = "https://pinyin4j.sourceforge.net/",
                license = "GPL-2.0-or-later",
                rawEn = R.raw.license_gpl2,
                rawZh = R.raw.license_gpl2_zh
            ),
            LicenseEntry(
                name = "洁癖播放器",
                version = "1.0",
                author = "Song • Ma",
                url = "GitHub（开源发布后公布）",
                license = "GPL-3.0",
                rawEn = R.raw.license_gpl3,
                rawZh = R.raw.license_gpl3_zh
            )
        )
        val entry = entries.getOrNull(index.coerceIn(0, entries.size - 1)) ?: entries[0]

        content.addView(
            MoreUi.headerBar(ctx, "开源声明") { findNavController().popBackStack() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // 库名标题
        content.addView(
            TextView(ctx).apply {
                text = entry.name
                textSize = 20f
                setTextColor(NeonCard.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, MoreUi.dp(ctx, 22), 0, 0)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // 库名/版本/作者/地址/许可证
        val info = listOf(
            "库名" to entry.name,
            "版本" to entry.version,
            "作者" to entry.author,
            "地址" to entry.url,
            "许可证" to entry.license
        )
        for ((label, value) in info) {
            content.addView(
                MoreUi.infoRow(ctx, label, value),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = MoreUi.dp(ctx, 10) }
            )
        }

        // AndroidX 汇总条目：展开 10 个组件清单（库名/版本/发布页）
        if (entry.components.isNotEmpty()) {
            content.addView(
                TextView(ctx).apply {
                    text = "包含组件（${entry.components.size} 个，版本取自工程依赖）"
                    textSize = 16f
                    setTextColor(NeonCard.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, MoreUi.dp(ctx, 18), 0, 0)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            for (c in entry.components) {
                content.addView(
                    MoreUi.infoRow(ctx, c.name, c.version),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = MoreUi.dp(ctx, 8) }
                )
                content.addView(
                    TextView(ctx).apply {
                        text = c.url
                        textSize = 11f
                        setTextColor(NeonCard.SUB)
                        setPadding(MoreUi.dp(ctx, 16), MoreUi.dp(ctx, 2), 0, 0)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                )
            }
        }

        // 正文区：标注 + 原文/中文切换 + 全文
        // 默认显示许可证英文原文（官方文本），中文参考译本可切换查看
        var showZh = false
        val note = TextView(ctx).apply {
            textSize = 12f
            setTextColor(NeonCard.SUB)
            setPadding(0, MoreUi.dp(ctx, 20), 0, 0)
        }
        val body = TextView(ctx).apply {
            textSize = 13f
            setTextColor(NeonCard.SUB)
            setLineSpacing(0f, 1.35f)
            setPadding(0, MoreUi.dp(ctx, 12), 0, 0)
        }
        val switchBtn = MoreUi.neonButton(ctx, "", NeonCard.WHITE) {}

        fun refresh() {
            if (showZh) {
                note.text = "非官方参考翻译，仅供参考，以英文原文为准"
                body.text = readRaw(entry.rawZh)
                switchBtn.text = "查看英文原文"
            } else {
                note.text = "以下为许可证英文原文（官方文本）"
                body.text = readRaw(entry.rawEn)
                switchBtn.text = "查看中文参考翻译"
            }
        }
        switchBtn.setOnClickListener {
            showZh = !showZh
            refresh()
        }
        refresh()

        content.addView(
            note,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        content.addView(
            switchBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = MoreUi.dp(ctx, 10) }
        )
        content.addView(
            body,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun readRaw(resId: Int): String =
        requireContext().resources.openRawResource(resId)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
