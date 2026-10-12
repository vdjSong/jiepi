package com.jiepi.player.ui.more

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jiepi.player.R
import com.jiepi.player.databinding.FragmentRecycleBinBinding
import com.jiepi.player.ui.NeonCard
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站页：
 * 读的是同一个 SharedPreferences「jiepi_data」里的 recycle_bin 列表（{path,name,time}），
 * 只提供单条恢复（去掉「删除」「清空」——彻底删除后门永关，列表永不隐藏），App 重启不丢。
 * 恢复 = 从回收站移除记录 + 从排除表去掉该路径，歌曲页下拉重新扫描即可回来；
 * 歌曲文件本身全程不动，符合"不删用户数据"产品底线。
 */
class RecycleBinFragment : Fragment() {

    private var _binding: FragmentRecycleBinBinding? = null
    private val binding get() = _binding!!

    // 回收站条目：地址 + 歌名 + 删除时间
    private data class Entry(val path: String, val name: String, val time: Long)

    private var entries = mutableListOf<Entry>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecycleBinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadEntries()
        buildPage()
    }

    override fun onResume() {
        super.onResume()
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            requireActivity().findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private fun loadEntries() {
        entries = mutableListOf()
        try {
            val raw = requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
                .getString("recycle_bin", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries.add(
                    Entry(
                        obj.getString("path"),
                        obj.getString("name"),
                        obj.optLong("time", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            entries = mutableListOf()
        }
    }

    private fun saveEntries() {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("path", e.path)
                    .put("name", e.name)
                    .put("time", e.time)
            )
        }
        requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
            .edit()
            .putString("recycle_bin", arr.toString())
            .apply()
    }

    private fun removedPaths(): MutableSet<String> {
        val set = mutableSetOf<String>()
        try {
            val raw = requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
                .getString("removed_paths", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) set.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return set
    }

    private fun saveRemovedPaths(set: Set<String>) {
        val arr = JSONArray()
        for (p in set) arr.put(p)
        requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
            .edit()
            .putString("removed_paths", arr.toString())
            .apply()
    }

    private fun buildPage() {
        val ctx = requireContext()
        val content = binding.recycleContent
        content.removeAllViews()

        content.addView(
            MoreUi.headerBar(ctx, "回收站") { findNavController().popBackStack() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        if (entries.isEmpty()) {
            content.addView(
                TextView(ctx).apply {
                    text = "回收站是空的"
                    textSize = 15f
                    setTextColor(NeonCard.SUB)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(0, MoreUi.dp(ctx, 40), 0, 0)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            return
        }

        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        entries.forEachIndexed { i, e ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(NeonCard.BUTTON)
                    cornerRadius = MoreUi.dp(ctx, 20).toFloat()
                }
                setPadding(MoreUi.dp(ctx, 18), MoreUi.dp(ctx, 12), MoreUi.dp(ctx, 12), MoreUi.dp(ctx, 12))
            }
            val textBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            textBox.addView(TextView(ctx).apply {
                text = e.name
                textSize = 15f
                setTextColor(NeonCard.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            textBox.addView(TextView(ctx).apply {
                text = "删除于 ${dateFmt.format(Date(e.time))}"
                textSize = 12f
                setTextColor(NeonCard.SUB)
                setPadding(0, MoreUi.dp(ctx, 3), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // 只留恢复（绿语义）：无删除 / 清空，彻底删除后门永关
            row.addView(
                MoreUi.neonButton(ctx, "恢复", NeonCard.GREEN) { restoreEntry(i) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, MoreUi.dp(ctx, 44))
            )

            content.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = MoreUi.dp(ctx, 14)
                }
            )
        }
    }

    // 恢复：从回收站移除记录 + 排除表去掉该路径，歌曲页下拉重新扫描即可回列表
    private fun restoreEntry(i: Int) {
        if (i !in entries.indices) return
        val e = entries[i]
        entries.removeAt(i)
        val removed = removedPaths()
        removed.remove(e.path)
        saveRemovedPaths(removed)
        saveEntries()
        Toast.makeText(requireContext(), "已恢复：${e.name}（歌曲页下拉刷新后出现）", Toast.LENGTH_SHORT).show()
        buildPage()
    }
}
