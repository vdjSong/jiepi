package com.jiepi.player.ui.music

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import com.jiepi.player.R
import com.jiepi.player.PlayerManager
import com.jiepi.player.databinding.FragmentMusicBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import com.jiepi.player.ui.NeonCard

class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    // 按压提亮：把颜色往白色方向抬，模拟半透明白覆盖在按钮上的效果
    private fun pressLighten(color: Int): Int {
        val r = (((color shr 16) and 0xFF) + 45).coerceAtMost(255)
        val g = (((color shr 8) and 0xFF) + 45).coerceAtMost(255)
        val b = ((color and 0xFF) + 45).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // 进程内缓存：切页面秒开；磁盘缓存：App 重启秒开；后台扫描：对齐增删
    companion object {
        private var cachedSongs: List<Song>? = null
        // 磁盘缓存文件名（存在 App 私有目录，不需要任何权限）
        private const val SONGS_CACHE_FILE = "jiepi_songs_cache.json"
    }

    // 一首歌的信息：name 是文件原名（排序/歌词/回收站仍用它），id3 字段只用于列表显示
    private data class Song(
        val name: String,
        val path: String,
        val dirName: String,
        val dirPath: String,
        val id3Title: String? = null,
        val id3Artist: String? = null
    ) {
        // 列表显示名：ID3 歌名优先；缺失回退「去扩展名 + 去『 - 歌手』尾巴」的文件名
        val displayName: String
            get() {
                val t = id3Title
                if (!t.isNullOrBlank()) return t
                var base = name.substringBeforeLast('.')
                return base.replace(Regex("\\s*[-–—]\\s*[^-–—]+$"), "").trim()
            }
    }

    // 给一首歌补读 ID3 歌名/歌手（只读；失败/空白返回原条目，列表回退文件名）
    private fun enrichWithId3(song: Song, ctx: Context): Song {
        val pair = Id3Reader.readTitleArtist(ctx, Uri.parse(song.path)) ?: return song
        return song.copy(
            id3Title = pair.first.ifEmpty { null },
            id3Artist = pair.second.ifEmpty { null }
        )
    }

    // 一个歌单：名字 + 里面装的歌（存歌曲地址，App 重启不丢）
    private data class Playlist(
        var name: String,
        val songs: MutableList<String>
    )

    // 回收站里的一首歌：地址 + 歌名 + 删除时间（App 重启不丢，可恢复）
    private data class RecycleEntry(
        val path: String,
        val name: String,
        val time: Long
    )

    // 列表里的一行：组头 or 歌曲
    private data class Row(
        val isHeader: Boolean,
        val text: String,
        val songIndex: Int,
        val artist: String? = null
    )

    // 当前选中的分类：0=歌曲 1=歌单 2=文件夹
    private var currentCategory = 0

    // 底部三按钮当前状态：true=已经藏了，false=还露着（只在状态真变时播动画）
    private var bottomButtonsHidden = false

    // 当前展开的文件夹（抽屉只开一个；null = 全部收起）
    private var expandedFolderUri: String? = null

    // 当前展开的歌单（抽屉只开一个；null = 全部收起）
    private var expandedPlaylistName: String? = null

    // 索引条字母：# 在最上面（和列表排序对齐：符号/数字开头排最前）+ A〜Z
    private val indexLetters: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    // 当前显示的行（滚动高亮用）
    private var currentRows = listOf<Row>()

    // 扫到的全部歌曲（列表顺序 = 全局播放器顺序）
    private var songs = listOf<Song>()

    // 当前播放队列来源：all / playlist / folder（主列表点歌或后台扫描时据此判断是否需要把播放器数组切回全部歌曲）
    private var listSource = "all"

    // 启动自动播放兜底标记：首次启动磁盘无缓存（队列空）时登记，等扫描完成队列就绪后开播第一首
    private var autoPlayPending = false

    // 当前在播的歌单名（listSource=="playlist" 时歌单展开区据此定位高亮行）
    private var activePlaylistName: String? = null
    // 当前在播的文件夹 uri（listSource=="folder" 时文件夹展开区据此定位高亮行）
    private var activeFolderUri: String? = null

    // 歌单列表（SharedPreferences 里存着，App 重启不丢）
    private var playlists = mutableListOf<Playlist>()

    // 软件内已删除的歌曲地址（普通删除后文件还在，扫描时必须跳过；App 重启不丢）
    private var removedPaths = mutableSetOf<String>()

    // 回收站：被移入回收站的歌曲（地址/歌名/删除时间），可恢复回列表
    private var recycleBin = mutableListOf<RecycleEntry>()

    // SAF 授权的文件夹列表（存的是 content:// 地址，App 重启不丢）
    private var folderUris = mutableListOf<String>()

    // pinyin4j 输出格式：不要声调（我们要的是纯字母）
    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    // 主线程 Handler：后台扫描完回主线程刷新界面
    private val handler = Handler(Looper.getMainLooper())

    // 歌曲数据版本：每次扫描完成 +1；歌曲页重建后记下对应版本，切页时版本没变就跳过重建（避免扫描完成刷新与切页刷新连续全量重建）
    private var dataVersion = 0
    private var refreshedSongVersion = -1

    // 切歌回调：播放器切歌时把"正在播放"绿字刷新到新行（只重绘不改位置）
    private val onSongChangeListener: (Int) -> Unit = { _: Int ->
        handler.post {
            if (!isAdded || _binding == null) return@post
            // 歌曲页：队列是全部歌曲时按 currentIndex 重绘绿字
            if (currentCategory == 0) {
                (binding.songList.adapter as? SongAdapter)?.notifyDataSetChanged()
            }
            // 歌单/文件夹展开区：队列来源匹配且正展开时重建展开区，让高亮跟着切歌走（不重建整页）
            if (listSource == "playlist" && activePlaylistName != null && expandedPlaylistName == activePlaylistName) {
                val block = binding.playlistListContainer.findViewWithTag<LinearLayout>(activePlaylistName!!)
                val container = block?.getChildAt(1) as? LinearLayout
                val p = playlists.find { it.name == activePlaylistName }
                if (container != null && p != null) fillPlaylistSongs(p, container)
            }
            if (listSource == "folder" && activeFolderUri != null && expandedFolderUri == activeFolderUri) {
                val block = binding.folderListContainer.findViewWithTag<LinearLayout>(activeFolderUri!!)
                val container = block?.getChildAt(1) as? LinearLayout
                if (container != null) {
                    val folderSongs = songs.filter { it.dirPath == activeFolderUri }
                    fillFolderSongs(folderSongs, container, activeFolderUri!!)
                }
            }
        }
    }

    // 系统文件夹选择器（SAF）：选完目录就拿到它的"长期通行证"，以后不用再问
    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult  // 用户没选，取消
            // 把读权限存成持久授权：App 重启后还能读这个文件夹
            try {
                // 读写一起授权：读是播放用，写是未来写回元数据标签要用
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // 个别系统不给写权限：退回只读授权（读播放不受影响）
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "授权保存失败，换个文件夹试试", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (folderUris.contains(uri.toString())) {
                Toast.makeText(requireContext(), "这个文件夹已经加过了", Toast.LENGTH_SHORT).show()
            } else {
                folderUris.add(uri.toString())
                saveFolders()
                // 新加的文件夹：后台扫一遍对齐（界面不卡）
                scanSongsAsync()
                Toast.makeText(requireContext(), "已添加文件夹", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 页面出来之后：先内存缓存 → 再磁盘缓存 → 都没有就空列表，然后后台扫一遍对齐
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 切歌时列表"正在播放"绿字跟着走（onDestroyView 里成对移除）
        PlayerManager.addSongChangeListener(onSongChangeListener)

        // 注册三个分类 tab：点整个区域都算数（不再只点小字），谁选中谁加粗 + 图标/文字变白
        binding.tabSong.setOnClickListener { selectCategory(0) }
        binding.tabPlaylist.setOnClickListener { selectCategory(1) }
        binding.tabFolder.setOnClickListener { selectCategory(2) }

        // 新建歌单：弹输入框，名字存本地
        binding.btnNewPlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }
        // 删除歌单：弹多选框，勾选要删的，确定就删
        binding.btnDeletePlaylist.setOnClickListener {
            showDeletePlaylistDialog()
        }
        // 添加文件夹：打开系统目录选择器（SAF）
        binding.btnAddFolder.setOnClickListener {
            openFolder.launch(null)
        }
        // 删除文件夹：弹多选框，勾选要删的，确定就删
        binding.btnDeleteFolder.setOnClickListener {
            showDeleteFolderDialog()
        }

        // 从本地读歌单 / 读文件夹（App 重启不丢）
        loadPlaylists()
        loadFolders()
        loadRemovedPaths()
        loadRecycleBin()

        // 先默认选中"歌曲"（字体、图标、列表都摆正）
        selectCategory(0)

        // 右侧索引条：竖排 # + A〜Z，触摸统一交给整条条（按下/滑动/松开都跟手）
        initIndexBar()
        setupIndexBarTouch()

        // 列表滚动：滚到底，底部三个按钮缩小消失；滚回来，弹上来
        binding.songList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                val shouldHide =
                    totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount
                // 只在状态真的变了才播动画，免得滚一格就抖一下
                if (shouldHide != bottomButtonsHidden) {
                    bottomButtonsHidden = shouldHide
                    setBottomButtonsVisible(!shouldHide)
                }
                // 顺便点亮当前字母
                updateIndexHighlight(firstVisibleItem)
            }
        })

        // 歌曲数据：内存缓存 → 磁盘缓存 → 空列表，然后统一后台扫一遍对齐
        if (cachedSongs != null) {
            songs = cachedSongs!!
        } else {
            val disk = loadSongsCache()
            songs = disk ?: emptyList()
            cachedSongs = songs
        }
        pushToPlayer()
        // 冷启动播放记忆恢复：缓存装载完、队列已推全量后再续播（页面重建时 currentIndex 已就位会自动跳过）
        restoreResumeIfNeeded()
        refreshList()
        // 从播放详情页返回时 view 会重建重走这里：此刻歌曲数据已装载完，重新定位到当前播放歌曲
        // （onViewCreated 里 selectCategory(0) 调用时列表还没装数据，那次的 scrollToPlayingRow 找不到行，必须在这补一次）
        scrollToPlayingRow()
        scanSongsAsync()

        // 下拉刷新：手动激活一次扫描（缓存 vs 文件夹对比，删失文件、加新文件）
        // 大厂质感：圈加大居中、多彩、深色底，初始藏在列表顶部外，下拉才滑出
        binding.songSwipe.setSize(SwipeRefreshLayout.LARGE)
        binding.songSwipe.setColorSchemeColors(
            0xFFFF5252.toInt(), 0xFFFFC400.toInt(), 0xFF00E676.toInt(), 0xFF448AFF.toInt()
        )
        binding.songSwipe.setProgressBackgroundColorSchemeColor(NeonCard.DARK_TEXT)
        binding.songSwipe.setProgressViewOffset(false, -dp(64), dp(24))
        binding.songSwipe.setDistanceToTriggerSync(dp(90))
        binding.songSwipe.setOnRefreshListener { scanSongsAsync() }
    }

    // 建右侧索引条：每个字母一个 TextView（不自己抢触摸，点击/滑动都由整条条统一处理）
    private fun initIndexBar() {
        binding.indexBar.removeAllViews()
        for (letter in indexLetters) {
            val tv = TextView(requireContext()).apply {
                text = letter
                textSize = 11f
                setTextColor(NeonCard.SUB)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                gravity = Gravity.CENTER
            }
            binding.indexBar.addView(tv)
        }
    }

    // 索引条触摸：按下跳一次、滑动一路跟手、松开再精确一次
    private fun setupIndexBarTouch() {
        binding.indexBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    val y = event.y
                    val childCount = binding.indexBar.childCount
                    // 按手指 Y 坐标找落在哪个字母的范围里
                    for (i in 0 until childCount) {
                        val c = binding.indexBar.getChildAt(i)
                        if (y >= c.top && y <= c.bottom) {
                            val tv = c as? TextView ?: break
                            scrollToLetter(tv.text.toString())
                            break
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // 跳/滑到字母：跳到第一首以它开头的歌（中英文同一套首字母规则）
    private fun scrollToLetter(letter: String) {
        val base = songs
        val idx = base.indexOfFirst { firstLetter(it.name) == letter }
        if (idx < 0) return
        val pos = currentRows.indexOfFirst { !it.isHeader && it.songIndex == idx }
        if (pos >= 0) binding.songList.setSelection(pos)
    }

    // 进入歌曲列表时定位到当前正在播放的歌曲那一行：目标行放到可视区中间（居中）
    // 列表头/尾的歌曲不硬居中：滚动边界会自动把它们贴顶/贴底，不会滚出空白
    private fun scrollToPlayingRow() {
        // 只在播放队列是全部歌曲时定位；歌单/文件夹播放中歌曲页不高亮也不滚动（避免按歌单索引错位滚行）
        if (listSource != "all") return
        val idx = PlayerManager.currentIndex
        if (idx < 0) return
        val pos = currentRows.indexOfFirst { !it.isHeader && it.songIndex == idx }
        if (pos < 0) return
        // 布局可能还没量出来（刚 refreshList 完就调用），等一帧拿到真实高度再算
        binding.songList.post {
            if (_binding == null) return@post
            val listH = binding.songList.height
            val firstChild = binding.songList.getChildAt(0)
            val itemH = firstChild?.height ?: 0
            if (listH <= 0 || itemH <= 0) {
                // 高度拿不到：退化为顶部对齐，别丢定位
                binding.songList.setSelection(pos)
                return@post
            }
            val visibleCount = (listH / itemH).coerceAtLeast(1)
            // 目标行往上错开半屏，让它落在可视区中间；头/尾越界由 ListView 自动贴边
            val target = (pos - (visibleCount - 1) / 2).coerceIn(0, currentRows.lastIndex)
            binding.songList.setSelection(target)
        }
    }

    // 滚动时：把当前第一条可见的歌对应字母点亮（白色加粗），其它保持灰
    private fun updateIndexHighlight(firstVisibleItem: Int) {
        if (currentRows.isEmpty()) return
        var pos = firstVisibleItem
        while (pos < currentRows.size && currentRows[pos].isHeader) pos++
        if (pos >= currentRows.size) return
        val row = currentRows[pos]
        if (row.isHeader || row.songIndex !in songs.indices) return
        val letter = firstLetter(songs[row.songIndex].name)
        for (i in 0 until binding.indexBar.childCount) {
            val tv = binding.indexBar.getChildAt(i) as? TextView ?: continue
            val active = tv.text.toString() == letter
            tv.setTextColor(if (active) NeonCard.WHITE else NeonCard.SUB)
            tv.typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        }
    }

    // 后台扫 SAF 文件夹（不卡界面）：批量 query 每层拿整层子项，扫完和旧列表增量对账
    // 对账规则：还存在的原条目不动（含排序位置）；消失的删掉；新增的单独排序后归并
    private fun scanSongsAsync() {
        val ctx = requireContext().applicationContext
        val uris = folderUris.toList()

        Thread {
            // 已删除清单快照：removedPaths 可能被主线程并发写（删歌），后台线程只读快照防跨线程读写
            val removedSnapshot = removedPaths.toSet()
            // 最新全集：path -> Song（同一 path 只进一次）
            val latest = LinkedHashMap<String, Song>()
            for (uriStr in uris) {
                val treeUri = Uri.parse(uriStr)
                val folderList = mutableListOf<Pair<String, String>>()
                collectFolderSongsBatch(ctx, treeUri, folderList)
                val dirName = folderDisplayName(uriStr, ctx)
                for ((name, path) in folderList) {
                    if (removedSnapshot.contains(path)) continue
                    if (latest.containsKey(path)) continue
                    latest[path] = Song(name, path, dirName, uriStr)
                }
            }

            // 基准 = 当前内存列表（App 启动时由磁盘缓存装载，天然有序）
            val base = songs.toMutableList()
            val kept = mutableListOf<Song>()
            val added = mutableListOf<Song>()
            for (old in base) {
                val fresh = latest.remove(old.path)
                if (fresh != null) {
                    // 完全没变的：已有 ID3 的原条目直接保留（顺序不重排、对象不重建、不重读）
                    if (fresh.name == old.name && fresh.dirName == old.dirName && fresh.dirPath == old.dirPath) {
                        if (old.id3Title != null || old.id3Artist != null) {
                            kept.add(old)
                        } else {
                            // 旧缓存/上次读取失败没拿到 ID3 的：用 fresh 原位补读一次（保持原顺序）
                            kept.add(enrichWithId3(fresh, ctx))
                        }
                    } else {
                        // 名字/目录变了（重命名等）：当新增处理，重新排序
                        added.add(fresh)
                    }
                }
                // fresh == null：文件已消失，不保留
            }
            // 剩下的 latest 全是新歌
            added.addAll(latest.values)
            // 只对新歌排序，再和有序旧列表归并（整体仍有序）；新歌/变更歌统一批量读 ID3
            added.sortWith(Comparator { a, b -> compareName(a.name, b.name) })
            val enrichedAdded = added.map { enrichWithId3(it, ctx) }
            val found = mergeSorted(kept, enrichedAdded)

            // 最新集合先写磁盘缓存：纯 IO 挪后台线程，不占主线程（下次启动秒出）
            saveSongsCache(found, ctx)

            // 回主线程更新（页面可能已关，先检查再动）
            handler.post {
                if (!isAdded || _binding == null) return@post
                songs = found
                cachedSongs = found
                // 正在歌单/文件夹上下文播放时：保留当前队列不被扫描重置（否则歌单点歌会被后台扫描打断回全部歌曲）
                // 全部歌曲上下文才重新推全量；歌单/文件夹队列依赖的 path 未变，旧引用仍可继续播
                if (listSource == "all") {
                    pushToPlayer()
                    // 扫描后队列收缩/重排：按实际播放的媒体 path 重定位 currentIndex
                    //（当前歌还在 → 精确指到它；被外部删掉 → 切到原下一首，UI 与实播一致）
                    PlayerManager.realignCurrentIndex(requireContext())
                }
                // 启动自动播放兜底：首次启动磁盘无缓存时队列空没法开播，扫描完成队列就绪后接第一首直接响
                // （用户已手动点过歌 currentIndex!=-1 或自动播没开时不触发）
                if (autoPlayPending && PlayerManager.currentIndex == -1 && songs.isNotEmpty()) {
                    autoPlayPending = false
                    PlayerManager.play(0, requireContext())
                }
                // 数据版本 +1：歌曲页只在版本变化时重建一次；切页时版本没变就跳过，避免扫描完成刷新与切页刷新连续全量重建
                dataVersion++
                when (currentCategory) {
                    0 -> refreshListIfStale()
                    // 歌单页也在：重建行并恢复展开（歌单里可能有歌找不到了，跟着变）
                    1 -> refreshPlaylistPage()
                    // 文件夹页也在：重建行并恢复展开（歌曲集合变了，展开内容跟着变）
                    2 -> refreshFolderList()
                }
                // 下拉刷新完成：停止转圈（启动扫描/新加文件夹触发的扫描也会走到这里，无害）
                binding.songSwipe.isRefreshing = false
            }
        }.start()
    }

    // 两个有序列表按歌名归并（保留各自顺序，结果仍有序）
    private fun mergeSorted(a: List<Song>, b: List<Song>): List<Song> {
        val out = ArrayList<Song>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (compareName(a[i].name, b[j].name) <= 0) out.add(a[i++]) else out.add(b[j++])
        }
        while (i < a.size) out.add(a[i++])
        while (j < b.size) out.add(b[j++])
        return out
    }

    // 批量收集一个 SAF 树里的歌曲（歌名 + 播放地址），子文件夹也翻
    // 用 DocumentsContract 一层一次 query 拿整层子项，直接读 mime/名字，代替逐文件多次 Binder IPC
    private fun collectFolderSongsBatch(ctx: Context, treeUri: Uri, out: MutableList<Pair<String, String>>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        val audioExts = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg")
        while (queue.isNotEmpty()) {
            val dirId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            var cursor: Cursor? = null
            try {
                cursor = ctx.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )
                while (cursor != null && cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: "未知"
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(id)
                    } else {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in audioExts) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            out.add(name to docUri.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                // 某层读失败（权限被收回等）：跳过该层继续，别整单全挂
            } finally {
                cursor?.close()
            }
        }
    }

    // 把最新歌曲集合写进 App 私有目录（不需要任何权限）
    // ctx 显式传入：本函数可能被后台线程调用（扫描完成时写缓存不占主线程），不能依赖 requireContext()
    private fun saveSongsCache(list: List<Song>, ctx: Context) {
        try {
            val arr = JSONArray()
            for (s in list) {
                val o = JSONObject()
                o.put("name", s.name)
                o.put("path", s.path)
                o.put("dirName", s.dirName)
                o.put("dirPath", s.dirPath)
                // ID3 字段可空：空串占位，读缓存时转回 null，兼容旧缓存
                o.put("id3Title", s.id3Title ?: "")
                o.put("id3Artist", s.id3Artist ?: "")
                arr.put(o)
            }
            val file = File(ctx.filesDir, SONGS_CACHE_FILE)
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // 缓存写失败不致命，下次扫描再写
        }
    }

    // 启动时从磁盘缓存读歌曲清单（App 重启秒出界面）
    private fun loadSongsCache(): List<Song>? {
        return try {
            val file = File(requireContext().applicationContext.filesDir, SONGS_CACHE_FILE)
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Song(
                        name = o.getString("name"),
                        path = o.getString("path"),
                        dirName = o.getString("dirName"),
                        dirPath = o.getString("dirPath"),
                        id3Title = o.optString("id3Title", "").ifEmpty { null },
                        id3Artist = o.optString("id3Artist", "").ifEmpty { null }
                    )
                )
            }
            list
        } catch (e: Exception) {
            null
        }
    }

    // 把歌曲清单交给全局播放器（切页面它还记得；文件夹地址一起带上，播放页找歌词用）
    private fun pushToPlayer() {
        PlayerManager.songNames = songs.map { it.name }.toMutableList()
        PlayerManager.songPaths = songs.map { it.path }.toMutableList()
        PlayerManager.songDirs = songs.map { it.dirPath }.toMutableList()
        PlayerManager.id3Titles = songs.map { it.displayName }.toMutableList()
        PlayerManager.id3Artists = songs.map { it.id3Artist ?: "" }.toMutableList()
        syncResumeSource()
    }

    // ========== 冷启动开播编排：播放记忆恢复 + 启动自动播放（数据存取在 PlayerManager，编排在这里） ==========
    // 只在冷启动未播过时处理（currentIndex==-1）；从播放页返回等页面重建时已播过，跳过
    // 自动播与记忆严格分离：记忆=恢复队列+位置但停在暂停；自动播=开播（记忆能接就接上次位置，接不上播全量第一首）
    private fun restoreResumeIfNeeded() {
        if (PlayerManager.currentIndex != -1) return
        val ctx = requireContext()
        val autoPlay = PlayerManager.isAutoPlayEnabled(ctx)
        val resumeData = PlayerManager.loadResume(ctx)

        // 启动自动播放兜底：首次启动磁盘无缓存（songs 空）时开播没得放，登记等扫描完成再播
        if (autoPlay && songs.isEmpty()) {
            autoPlayPending = true
            return
        }
        autoPlayPending = false

        // 记忆不可用（无记录 / 记忆开关关）：自动播开就直接播全量第一首，关则什么都不做
        if (resumeData == null || !PlayerManager.isResumeEnabled(ctx)) {
            if (autoPlay) PlayerManager.play(0, ctx)
            return
        }
        val data = resumeData

        // 来源失效（歌单/文件夹已被删）：兜底回全部歌曲队列；自动播开时接第一首直接响
        val sourceOk = when (data.source) {
            "playlist" -> data.playlistName != null && playlists.any { it.name == data.playlistName }
            "folder" -> data.folderUri != null && folderUris.contains(data.folderUri)
            else -> true
        }
        if (!sourceOk) {
            listSource = "all"
            activePlaylistName = null
            activeFolderUri = null
            pushToPlayer()
            if (autoPlay) PlayerManager.play(0, ctx)
            return
        }

        val allPaths = songs.map { it.path }.toSet()

        if (data.source == "all") {
            // 全部歌曲：队列就是当前全量（pushToPlayer 已推），按 path 定位续播
            var idx = songs.indexOfFirst { it.path == data.currentPath }
            if (idx < 0) {
                // 当前曲目失效：向后找首个可播（绕回队头继续找）；整队失效则队列已是全量，自动播开时接第一首
                val startIdx = data.currentIndex.coerceIn(0, data.queue.lastIndex)
                val rest = data.queue.drop(startIdx + 1) + data.queue.take(startIdx + 1)
                val fallback = rest.firstOrNull { it in allPaths }
                idx = if (fallback != null) songs.indexOfFirst { it.path == fallback } else -1
            }
            if (idx >= 0) {
                // 恢复队列：记忆模式下停在暂停；自动播模式下接上次位置直接响
                PlayerManager.resumeRestored(idx, data.position, ctx, autoPlay)
            } else if (autoPlay) {
                PlayerManager.play(0, ctx)
            }
            return
        }

        // 歌单/文件夹：从保存队列里过滤还活着的歌（保持上次播放顺序），重建队列
        val alive = data.queue.filter { it in allPaths }
        if (alive.isEmpty()) {
            // 整队失效：兜底回全部歌曲队列；自动播开时接第一首直接响
            listSource = "all"
            activePlaylistName = null
            activeFolderUri = null
            pushToPlayer()
            if (autoPlay) PlayerManager.play(0, ctx)
            return
        }
        val list = alive.mapNotNull { path -> songs.find { it.path == path } }
        if (list.isEmpty()) {
            if (autoPlay) PlayerManager.play(0, ctx)
            return
        }
        PlayerManager.songNames = list.map { it.name }.toMutableList()
        PlayerManager.songPaths = list.map { it.path }.toMutableList()
        PlayerManager.songDirs = list.map { it.dirPath }.toMutableList()
        PlayerManager.id3Titles = list.map { it.displayName }.toMutableList()
        PlayerManager.id3Artists = list.map { it.id3Artist ?: "" }.toMutableList()

        // 定位：当前曲目还活着直接播它；失效向后找首个可播（绕回队头）
        var targetPath = data.currentPath
        if (targetPath !in alive) {
            val startIdx = data.currentIndex.coerceIn(0, data.queue.lastIndex)
            val rest = data.queue.drop(startIdx + 1) + data.queue.take(startIdx + 1)
            targetPath = rest.firstOrNull { it in alive } ?: alive.first()
        }
        val playIndex = list.indexOfFirst { it.path == targetPath }
        if (playIndex < 0) {
            if (autoPlay) PlayerManager.play(0, ctx)
            return
        }

        listSource = data.source
        activePlaylistName = if (data.source == "playlist") data.playlistName else null
        activeFolderUri = if (data.source == "folder") data.folderUri else null
        syncResumeSource()
        // 恢复队列：记忆模式下停在暂停；自动播模式下接上次位置直接响
        PlayerManager.resumeRestored(playIndex, data.position, ctx, autoPlay)
    }

    // 把当前队列来源同步给播放器（播放记忆保存时要知道队列是 all/歌单/文件夹）
    private fun syncResumeSource() {
        PlayerManager.resumeSource = listSource
        PlayerManager.resumePlaylistName = activePlaylistName
        PlayerManager.resumeFolderUri = activeFolderUri
    }

    // 两个歌名比大小：先比首字符类型（符号 < 数字 < 字母），再一段一段比
    private fun compareName(a: String, b: String): Int {
        // 首字符类型：0=符号 1=数字 2=字母/汉字
        val typeA = firstType(a)
        val typeB = firstType(b)
        if (typeA != typeB) return typeA - typeB
        // 同类型：拆段比较（数字段按数值，文字段按拼音/字母）
        val regex = Regex("\\d+|\\D+")
        val pa = regex.findAll(a).map { it.value }.toList()
        val pb = regex.findAll(b).map { it.value }.toList()
        var i = 0
        while (i < pa.size && i < pb.size) {
            val cmp = compareSegment(pa[i], pb[i])
            if (cmp != 0) return cmp
            i++
        }
        return pa.size.compareTo(pb.size)
    }

    // 首字符类型：0=符号（括号等） 1=数字 2=字母/汉字
    private fun firstType(name: String): Int {
        val c = name.trim().firstOrNull() ?: return 0
        return when {
            c.isDigit() -> 1
            c.isLetter() || isChinese(c) -> 2
            else -> 0
        }
    }

    // 一段一段比：数字段按数值（11 大于 2），文字段按拼音/字母
    private fun compareSegment(x: String, y: String): Int {
        val xd = x.all { it.isDigit() }
        val yd = y.all { it.isDigit() }
        if (xd && yd) return x.toLong().compareTo(y.toLong())
        if (xd) return -1  // 数字段排在文字段前面
        if (yd) return 1
        return toPinyinKey(x).compareTo(toPinyinKey(y))
    }

    // 文字段转拼音键：汉字换拼音、英文保留，统一小写再比
    private fun toPinyinKey(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(if (isChinese(c)) pinyinText(c) else c.lowercaseChar())
        }
        return sb.toString()
    }

    // 单个汉字转拼音（不带声调，取第一个读音，如 爱→ai）
    private fun pinyinText(c: Char): String {
        val arr = PinyinHelper.toHanyuPinyinStringArray(c, pinyinFormat)
        return if (arr == null || arr.isEmpty()) c.toString() else arr[0]
    }

    // 是不是汉字（常用汉字在 4E00〜9FFF 区间）
    private fun isChinese(c: Char): Boolean = c in '\u4E00'..'\u9FFF'

    // 索引首字母：汉字取拼音首字母，英文取大写，其它（数字/符号）归 #
    private fun firstLetter(name: String): String {
        val c = name.trim().firstOrNull() ?: return "#"
        return when {
            isChinese(c) -> pinyinText(c).substring(0, 1).uppercase()
            c in 'a'..'z' -> c.uppercaseChar().toString()
            c in 'A'..'Z' -> c.toString()
            else -> "#"
        }
    }

    // 按当前分类生成显示用的行（只有歌曲页需要）
    // 歌曲页永远显示全部歌曲；歌单/文件夹有自己的展开显示区，不替换这里的列表
    private fun buildRows(): List<Row> {
        val base = songs
        if (base.isEmpty()) {
            return emptyList()
        }
        val rows = mutableListOf<Row>()
        base.forEachIndexed { index, song ->
            rows.add(Row(isHeader = false, text = song.displayName, songIndex = index, artist = song.id3Artist))
        }
        return rows
    }

    // 歌曲页刷新：数据版本没变时跳过重建（扫描完成已刷过、切页又触发时不再重复全量重建）
    private fun refreshListIfStale() {
        if (refreshedSongVersion != dataVersion) {
            refreshList()
            refreshedSongVersion = dataVersion
        }
    }

    // 把当前分类刷到 ListView 上
    private fun refreshList() {
        // 重建前记住当前滚动位置，避免后台扫描完成刷新把列表弹回顶部
        val savedPos = binding.songList.firstVisiblePosition
        currentRows = buildRows()
        binding.songList.adapter = SongAdapter(currentRows)
        // 空态：只在歌曲页且没有歌曲时显示独立空提示（图标+文字），否则隐藏（防串门到歌单/文件夹页）
        binding.songEmpty.visibility =
            if (currentCategory == 0 && songs.isEmpty()) View.VISIBLE else View.GONE
        if (currentRows.isNotEmpty()) {
            // setSelection 无动画直接定位到原位置（防越界）
            binding.songList.setSelection(savedPos.coerceIn(0, currentRows.lastIndex))
        }

        // 点歌：歌曲行点一下交给全局播放器（带 context，SAF 的歌才能播）
        binding.songList.setOnItemClickListener { _, _, position, _ ->
            if (listSource != "all") {
                // 播放器队列当前是歌单/文件夹内容：主列表点歌需先把播放器数组切回全部歌曲，
                // 再用同一 position 重新取行（新行 songIndex 才是全部歌曲索引），避免索引错位
                listSource = "all"
                activePlaylistName = null
                activeFolderUri = null
                pushToPlayer()
            }
            val row = currentRows.getOrNull(position) ?: return@setOnItemClickListener
            if (!row.isHeader && row.songIndex in songs.indices) {
                // 逐曲记忆：受播放记忆开关约束——开关开着才读位置续播（>3 秒才续，防止残留
                // 一两秒无意义跳转）；开关关掉按老样子从头放。位置并入 play()：切歌走交叉
                // 淡入时 activePlayer 尚未切到新实例，外部 seekTo 会打在旧歌上，必须由
                // play 内部对真正目标实例 seek
                val trackPos = if (PlayerManager.isResumeEnabled(requireContext()))
                    PlayerManager.readTrackPosition(requireContext(), songs[row.songIndex].path) else 0
                PlayerManager.play(row.songIndex, requireContext(), if (trackPos > 3000) trackPos else 0)
                Toast.makeText(requireContext(), "正在播放：${row.text}", Toast.LENGTH_SHORT).show()
            }
        }

        // 长按已弃用：操作入口统一走每行右侧的三点按钮（弹歌曲菜单）
    }

    // ============ 歌曲三点菜单：移入回收站 / 加入歌单 ============

    // 三点菜单：居中圆角卡片（参考播放详情页变速弹窗样式，尺寸更大）
    private fun showSongMenu(song: Song) {
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = NeonCard.cardBackground(requireContext())
            // 关键：background 赋值会用 drawable 的 padding 覆盖本 padding，必须放在 background 之后设置
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }

        // 标题：歌名单行省略
        val titleTv = TextView(requireContext()).apply {
            text = song.displayName
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            titleTv,
            LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "歌曲操作"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            subTv,
            LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        )

        // 菜单按钮（统一圆角块状，比变速弹窗档位更大）
        fun addMenuButton(text: String, textColor: Int, bgColor: Int = NeonCard.BUTTON, onClick: () -> Unit) {
            val btn = TextView(requireContext()).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(textColor)
                setPadding(dp(24), 0, dp(24), 0)
                // 背景：默认深灰，红/蓝按钮由 bgColor 传语义色；按压时向白提亮模拟半透明覆盖
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(pressLighten(bgColor))
                            cornerRadius = dp(28).toFloat()
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply {
                            setColor(bgColor)
                            cornerRadius = dp(28).toFloat()
                        }
                    )
                }
                // 前景水波纹，提供按压反馈
                foreground = RippleDrawable(
                    ColorStateList.valueOf(0x22FFFFFF.toInt()),
                    null,
                    null
                )
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            root.addView(
                btn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
                    topMargin = dp(12)
                }
            )
        }

        addMenuButton("移入回收站", NeonCard.DARK_TEXT, NeonCard.RED) { confirmDeleteSong(song) }
        addMenuButton("加入歌单", NeonCard.WHITE) { showAddToPlaylistDialog(song) }

        dialog.setContentView(root)
        dialog.show()
    }

    // 删除前确认：移入回收站（文件不动，可恢复）
    private fun confirmDeleteSong(song: Song) {
        AlertDialog.Builder(requireContext())
            .setTitle("移入回收站")
            .setMessage("把「${song.name}」移入回收站？\n（歌曲文件不动，可从回收站恢复）")
            .setPositiveButton("移入回收站") { _, _ -> doDeleteSong(song) }
            .setNegativeButton("取消", null)
            .show()
            .let { styleDialog(it) }
    }

    // 移入回收站：记回收站条目 + 软件内同步移除（列表/缓存/歌单/播放器队列/排除表）
    private fun doDeleteSong(song: Song) {
        addToRecycleBin(song)
        removeSongFromSoftware(song)
        Toast.makeText(
            requireContext(),
            "已移入回收站：${song.name}",
            Toast.LENGTH_SHORT
        ).show()
    }

    // 从软件各处移除一首歌：歌曲列表 / 磁盘缓存 / 所有歌单 / 播放器列表 / 排除表
    private fun removeSongFromSoftware(song: Song) {
        removedPaths.add(song.path)
        saveRemovedPaths()
        songs = songs.filter { it.path != song.path }
        cachedSongs = songs
        saveSongsCache(songs, requireContext().applicationContext)
        for (p in playlists) {
            p.songs.remove(song.path)
        }
        savePlaylists()
        // 播放器队列同步：全部歌曲上下文整体重推（删除+排序变更一起生效）；
        // 歌单/文件夹上下文只剔除这一首，不把当前队列悄悄切成全部歌曲（否则正在播的歌单/文件夹被打断）
        if (listSource == "all") {
            pushToPlayer()
        } else {
            PlayerManager.removeSongFromQueue(song.path)
        }
        // 删除后按实际播放的媒体 path 重定位 currentIndex：
        // 删的是当前播放曲 → 自动切到新队列原位置（原下一首，交叉淡入无爆音），UI 与实播一致；
        // 删的是别首 → 精确指到仍在播的那首（顺带修正下标前移）
        PlayerManager.realignCurrentIndex(requireContext())
        refreshList()
        if (currentCategory == 1) refreshPlaylistPage()
        if (currentCategory == 2) refreshFolderList()
    }

    // 读"软件内已删除"清单（App 重启后扫描文件夹要跳过它们）
    private fun loadRemovedPaths() {
        removedPaths = mutableSetOf()
        try {
            val raw = requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
                .getString("removed_paths", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                removedPaths.add(arr.getString(i))
            }
        } catch (e: Exception) {
            removedPaths = mutableSetOf()
        }
    }

    // ============ 回收站：查看 / 恢复 / 清空 ============

    // 读回收站（App 重启不丢）
    private fun loadRecycleBin() {
        recycleBin = mutableListOf()
        try {
            val raw = requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
                .getString("recycle_bin", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                recycleBin.add(
                    RecycleEntry(
                        obj.getString("path"),
                        obj.getString("name"),
                        obj.optLong("time", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            recycleBin = mutableListOf()
        }
    }

    // 写回回收站
    private fun saveRecycleBin() {
        val arr = JSONArray()
        for (e in recycleBin) {
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

    // 删除一首歌时放进回收站
    private fun addToRecycleBin(song: Song) {
        recycleBin.add(RecycleEntry(song.path, song.name, System.currentTimeMillis()))
        saveRecycleBin()
    }

    // 回收站弹窗：多选勾要恢复的歌；底部"恢复所选"+"清空回收站"
    private fun showRecycleBinDialog() {
        if (recycleBin.isEmpty()) {
            Toast.makeText(requireContext(), "回收站是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val names = recycleBin.map { e ->
            val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(e.time))
            "${e.name}\n（删除于 $t）"
        }.toTypedArray()
        val checked = BooleanArray(names.size)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("回收站（${recycleBin.size} 首）")
            .setMultiChoiceItems(names, checked) { _, _, _ -> }
            .setPositiveButton("恢复所选") { _, _ ->
                val toRestore = mutableListOf<Int>()
                for (i in checked.indices) if (checked[i]) toRestore.add(i)
                if (toRestore.isEmpty()) {
                    Toast.makeText(requireContext(), "没勾选任何歌曲", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                restoreFromRecycleBin(toRestore)
            }
            .setNeutralButton("清空回收站") { _, _ -> clearRecycleBin() }
            .setNegativeButton("取消", null)
            .show()
        styleDialog(dialog)
    }

    // 恢复勾选的歌：从回收站移除记录，从排除表去掉该路径，重新扫描让它回列表
    private fun restoreFromRecycleBin(indices: List<Int>) {
        for (i in indices.sortedDescending()) {
            val e = recycleBin.removeAt(i)
            removedPaths.remove(e.path)
        }
        saveRecycleBin()
        saveRemovedPaths()
        scanSongsAsync()
        Toast.makeText(requireContext(), "已恢复 ${indices.size} 首歌", Toast.LENGTH_SHORT).show()
    }

    // 清空回收站：清掉全部记录（文件不动，只是不再显示、不再能恢复）
    private fun clearRecycleBin() {
        if (recycleBin.isEmpty()) return
        recycleBin.clear()
        saveRecycleBin()
        Toast.makeText(requireContext(), "回收站已清空", Toast.LENGTH_SHORT).show()
    }

    private fun saveRemovedPaths() {
        val arr = JSONArray()
        for (p in removedPaths) arr.put(p)
        requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
            .edit()
            .putString("removed_paths", arr.toString())
            .apply()
    }

    // 选分类：切页面内容 + 文字/图标变白（选中）或变灰（没选中）
    private fun selectCategory(category: Int) {
        currentCategory = category

        // 歌曲 tab：加粗 + 文字/图标变色
        binding.catSong.typeface =
            if (category == 0) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        binding.catSong.setTextColor(
            if (category == 0) NeonCard.WHITE else NeonCard.SUB
        )
        binding.iconSong.setColorFilter(
            if (category == 0) NeonCard.WHITE else NeonCard.SUB
        )

        // 歌单 tab
        binding.catPlaylist.typeface =
            if (category == 1) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        binding.catPlaylist.setTextColor(
            if (category == 1) NeonCard.WHITE else NeonCard.SUB
        )
        binding.iconPlaylist.setColorFilter(
            if (category == 1) NeonCard.WHITE else NeonCard.SUB
        )

        // 文件夹 tab
        binding.catFolder.typeface =
            if (category == 2) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        binding.catFolder.setTextColor(
            if (category == 2) NeonCard.WHITE else NeonCard.SUB
        )
        binding.iconFolder.setColorFilter(
            if (category == 2) NeonCard.WHITE else NeonCard.SUB
        )

        // 页面内容切换：歌曲=列表+索引条；歌单=歌单页；文件夹=文件夹页
        binding.songSwipe.visibility = if (category == 0) View.VISIBLE else View.GONE
        binding.indexBar.visibility = if (category == 0) View.VISIBLE else View.GONE
        binding.playlistPage.visibility = if (category == 1) View.VISIBLE else View.GONE
        binding.folderPage.visibility = if (category == 2) View.VISIBLE else View.GONE
        // 空提示只属于歌曲页：切走时强制隐藏，避免串门到歌单/文件夹页
        binding.songEmpty.visibility =
            if (category == 0 && songs.isEmpty()) View.VISIBLE else View.GONE

        // 切到歌曲页才刷新歌曲列表（数据版本没变则跳过重建，与扫描完成刷新去重）；切到歌单页刷新歌单列表；切到文件夹页刷新文件夹列表
        if (category == 0) refreshListIfStale()
        // 切到歌曲页时定位到当前播放歌曲；必须在刷新之后调用，覆盖其恢复的旧位置（跳过重建时 adapter 位置本来就对，调用无害）
        if (category == 0) scrollToPlayingRow()
        if (category == 1) refreshPlaylistPage()
        if (category == 2) refreshFolderList()
    }

    // 底部三个按钮：true=弹上来 false=缩小消失（都带回弹过冲，不硬切）
    private fun setBottomButtonsVisible(visible: Boolean) {
        val ids = listOf(R.id.btnLeft, R.id.floatingActionButton2, R.id.btnRight)
        for (id in ids) {
            val btn = requireActivity().findViewById<View>(id) ?: continue
            if (visible) {
                // 弹上来：先缩到 0.3，再放大到 1，冲过头再回弹（Q 弹）
                btn.animate().cancel()
                btn.visibility = View.VISIBLE
                btn.scaleX = 0.3f
                btn.scaleY = 0.3f
                btn.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(350)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            } else {
                // 缩小消失：缩到看不见，带一点过冲回弹，结束再彻底藏起来
                btn.animate().cancel()
                btn.animate()
                    .scaleX(0f)
                    .scaleY(0f)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .withEndAction {
                        btn.visibility = View.GONE
                        btn.scaleX = 1f
                        btn.scaleY = 1f
                    }
                    .start()
            }
        }
    }

    // ============ 歌单页 ============

    // 从 SharedPreferences 读歌单（名字 + 装的歌，本地存储不联网）
    private fun loadPlaylists() {
        val prefs = requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
        val raw = prefs.getString("playlists", "[]")
        playlists = mutableListOf()
        try {
            val arr = JSONArray(raw ?: "[]")
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                if (item is JSONObject) {
                    // 新版：名字 + 歌单里的歌（存歌曲地址）
                    val name = item.getString("name")
                    val songArr = item.optJSONArray("songs") ?: JSONArray()
                    val list = mutableListOf<String>()
                    for (j in 0 until songArr.length()) {
                        list.add(songArr.getString(j))
                    }
                    playlists.add(Playlist(name, list))
                } else if (item is String) {
                    // 历史数据格式：只有名字没有歌 → 转成空歌单（数据不丢）
                    playlists.add(Playlist(item, mutableListOf()))
                }
            }
        } catch (e: Exception) {
            playlists = mutableListOf()
        }
    }

    // 把歌单（名字 + 装的歌）写回 SharedPreferences
    private fun savePlaylists() {
        val arr = JSONArray()
        for (p in playlists) {
            val o = JSONObject()
            o.put("name", p.name)
            val songArr = JSONArray()
            for (s in p.songs) {
                songArr.put(s)
            }
            o.put("songs", songArr)
            arr.put(o)
        }
        requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
            .edit()
            .putString("playlists", arr.toString())
            .apply()
    }

    // 编辑歌单（歌单行三点按钮）：改歌单名，空名/重名不收
    private fun showRenamePlaylistDialog(p: Playlist) {
        val input = EditText(requireContext()).apply {
            setText(p.name)
            hint = "歌单名字"
            setTextColor(NeonCard.WHITE)
            setHintTextColor(NeonCard.SUB)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("编辑歌单")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() -> Toast.makeText(requireContext(), "歌单名不能是空的", Toast.LENGTH_SHORT).show()
                    newName == p.name -> Unit // 没改就不动
                    playlists.any { it.name == newName } -> Toast.makeText(requireContext(), "已经有同名歌单了", Toast.LENGTH_SHORT).show()
                    else -> {
                        // 展开状态标记跟着新名字走，避免改完名展开标记对不上
                        if (expandedPlaylistName == p.name) expandedPlaylistName = newName
                        p.name = newName
                        savePlaylists()
                        refreshPlaylistPage()
                        Toast.makeText(requireContext(), "歌单已改名：$newName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialog(dialog)
    }

    // 新建歌单：弹输入框，空名/重名不收
    private fun showCreatePlaylistDialog() {
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = buildNeonCardRoot()

        // 标题：新建歌单
        val titleTv = TextView(requireContext()).apply {
            text = "新建歌单"
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "起个名字，空名/重名不收"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(subTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // 输入框：白字 + 灰提示字 + 圆角深灰底；宽度限死在 280dp 卡片宽内，两头不超霓虹线
        val input = EditText(requireContext()).apply {
            hint = "歌单名字"
            setTextColor(NeonCard.WHITE)
            setHintTextColor(NeonCard.SUB)
            setBackgroundResource(R.drawable.bg_input_rounded)
            setPadding(dp(16), 0, dp(16), 0)
            maxLines = 1
        }
        root.addView(input, LinearLayout.LayoutParams(dp(280), dp(52)).apply { topMargin = dp(14) })

        // 底部：取消 / 确定（确定字色染绿，新建正向语义，跟添加歌曲一致）
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(
            buildNeonButton("取消", NeonCard.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginEnd = dp(10) }
        )
        btnRow.addView(
            buildNeonButton("确定", NeonCard.GREEN) {
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(requireContext(), "歌单名不能是空的", Toast.LENGTH_SHORT).show()
                    playlists.any { it.name == name } -> Toast.makeText(requireContext(), "已经有同名歌单了", Toast.LENGTH_SHORT).show()
                    else -> {
                        dialog.dismiss()
                        playlists.add(Playlist(name, mutableListOf()))
                        savePlaylists()
                        refreshPlaylistPage()
                        Toast.makeText(requireContext(), "歌单已创建：$name", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginStart = dp(10) }
        )
        root.addView(btnRow, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dialog.setContentView(root)
        dialog.show()
    }

    // 删除歌单：弹多选框，勾选要删的，确定就删（歌单里装的歌只是退出歌单，原文件不动）
    private fun showDeletePlaylistDialog() {
        if (playlists.isEmpty()) {
            Toast.makeText(requireContext(), "还没有歌单可以删除", Toast.LENGTH_SHORT).show()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        val checked = BooleanArray(names.size)  // 默认都不选
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = buildNeonCardRoot()

        // 标题：歌单名省略
        val titleTv = TextView(requireContext()).apply {
            text = "删除歌单"
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "点歌单选中（染红），再点取消"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(subTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // 歌单列表：只显歌单名白字，无勾选框；点击选中染红 #FF9A8A，再点取消
        val listBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        names.forEachIndexed { i, name ->
            val tv = TextView(requireContext()).apply {
                text = name
                textSize = 16f
                setTextColor(NeonCard.WHITE)
                setPadding(dp(24), dp(13), dp(24), dp(13))
                foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
                setOnClickListener {
                    checked[i] = !checked[i]
                    setTextColor(if (checked[i]) NeonCard.RED else NeonCard.WHITE)
                }
            }
            listBox.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 列表高了滚动，最高 230dp，卡片不占满屏（对齐菜单卡片规格）
        val listHeight = minOf(dp(230), dp(48) * names.size)
        val scroll = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(dp(280), listHeight).apply { topMargin = dp(6) })

        // 底部：取消 / 确定（确定字色染红，呼应选中态）
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(
            buildNeonButton("取消", NeonCard.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginEnd = dp(10) }
        )
        btnRow.addView(
            buildNeonButton("确定", NeonCard.RED) {
                dialog.dismiss()
                val toDelete = mutableListOf<String>()
                for (i in names.indices) {
                    if (checked[i]) toDelete.add(names[i])
                }
                if (toDelete.isEmpty()) {
                    Toast.makeText(requireContext(), "没勾选任何歌单", Toast.LENGTH_SHORT).show()
                } else {
                    playlists.removeAll { p -> toDelete.contains(p.name) }
                    // 删的正好是展开的那个：顺手收起
                    if (toDelete.contains(expandedPlaylistName)) expandedPlaylistName = null
                    savePlaylists()
                    refreshPlaylistPage()
                    Toast.makeText(requireContext(), "已删除 ${toDelete.size} 个歌单", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginStart = dp(10) }
        )
        root.addView(btnRow, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dialog.setContentView(root)
        dialog.show()
    }

    // 歌单三点菜单：居中圆角卡片（样式仿歌曲三点菜单），重命名 / 添加歌曲 / 移除歌曲
    private fun showPlaylistMenu(p: Playlist) {
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = NeonCard.cardBackground(requireContext())
            // background 赋值会用 drawable 的 padding(InsetDrawable 的 2dp)覆盖本 padding，
            // 必须放在 background 之后设置，否则卡片内边距被压成 2dp、整体显小
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }

        // 标题：歌名单行省略
        val titleTv = TextView(requireContext()).apply {
            text = p.name
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            titleTv,
            LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "歌单操作"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            subTv,
            LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        )

        // 菜单按钮（统一圆角块状）
        fun addMenuButton(text: String, textColor: Int, bgColor: Int = NeonCard.BUTTON, onClick: () -> Unit) {
            val btn = TextView(requireContext()).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(textColor)
                setPadding(dp(24), 0, dp(24), 0)
                // 背景：默认深灰，红/蓝按钮由 bgColor 传语义色；按压时向白提亮模拟半透明覆盖
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(pressLighten(bgColor))
                            cornerRadius = dp(28).toFloat()
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply {
                            setColor(bgColor)
                            cornerRadius = dp(28).toFloat()
                        }
                    )
                }
                // 前景水波纹，提供按压反馈
                foreground = RippleDrawable(
                    ColorStateList.valueOf(0x22FFFFFF.toInt()),
                    null,
                    null
                )
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            root.addView(
                btn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
                    topMargin = dp(12)
                }
            )
        }

        addMenuButton("重命名歌单", NeonCard.WHITE) { showRenamePlaylistDialog(p) }
        addMenuButton("添加歌曲到歌单", NeonCard.WHITE) { showPickSongsDialog(p.name) }
        addMenuButton("从歌单移除歌曲", NeonCard.WHITE, NeonCard.RED_FILL) { showRemoveSongsDialog(p.name) }

        dialog.setContentView(root)
        dialog.show()
    }

    // 刷新歌单页：清空重建"歌单块"（深灰圆角行 + 底下可展开的歌曲容器）
    private fun refreshPlaylistPage() {
        // 重建前记住外层 ScrollView 滚动位置，避免后台扫描完成刷新把页面弹回顶部
        val scrollView = binding.playlistListContainer.parent as? ScrollView
        val savedY = scrollView?.scrollY ?: 0
        val has = playlists.isNotEmpty()
        binding.playlistListContainer.removeAllViews()
        binding.playlistEmpty.visibility = if (has) View.GONE else View.VISIBLE
        binding.btnDeletePlaylist.visibility = if (has) View.VISIBLE else View.INVISIBLE

        if (!has) return

        for (p in playlists) {
            // 一个歌单块 = 歌单行 + 歌曲容器 + 按钮行（歌曲容器和按钮行平时藏起来）
            val block = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                tag = p.name  // 用歌单名当标记，收起时按标记找
            }
            binding.playlistListContainer.addView(
                block,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            )

            // 歌单行：深灰圆角卡片（和文件夹行一个风格），左边名字，中间歌曲数，右边状态
            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    setColor(0xFF222222.toInt())
                    cornerRadius = dp(22).toFloat()
                }
                // 点整行：展开/收起这个歌单
                setOnClickListener { togglePlaylist(p.name) }
            }
            block.addView(header)

            // 名字：白字，占满剩下的宽度
            val nameTv = TextView(requireContext()).apply {
                text = p.name
                textSize = 16f
                setTextColor(NeonCard.WHITE)
            }
            header.addView(
                nameTv,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            // 歌曲数：灰色小字
            val countTv = TextView(requireContext()).apply {
                text = "${p.songs.size} 首"
                textSize = 13f
                setTextColor(NeonCard.SUB)
                setPadding(dp(8), 0, dp(8), 0)
            }
            header.addView(countTv)

            // 三点按钮：点一下弹歌单操作菜单（重命名 / 添加歌曲 / 移除歌曲）
            val moreBtn = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_music_more)
                setColorFilter(0xFF777777.toInt())
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = "歌单操作"
                setOnClickListener { showPlaylistMenu(p) }
            }
            header.addView(
                moreBtn,
                LinearLayout.LayoutParams(dp(30), dp(30))
            )

            // 状态图标：▸=收起 ▾=展开（和文件夹页一套）
            val stateTv = TextView(requireContext()).apply {
                text = if (expandedPlaylistName == p.name) "▾" else "▸"
                textSize = 16f
                setTextColor(NeonCard.SUB)
                setPadding(dp(8), 0, 0, 0)
            }
            header.addView(stateTv)

            // 歌曲容器：默认藏起来，展开时往里塞歌
            val songContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            block.addView(songContainer)

            // 如果这个歌单本来展开着（比如刚加完歌刷新）：恢复展开，歌也重新填
            if (expandedPlaylistName == p.name) {
                fillPlaylistSongs(p, songContainer)
                songContainer.visibility = View.VISIBLE
                stateTv.text = "▾"
            }
        }

        // 重建完成后恢复滚动位置（post 等布局稳定后再滚）
        scrollView?.post { scrollView?.scrollTo(0, savedY) }
    }

    // 点歌单行：手风琴式切换（只开一个；点开另一个时，旧的自动收起）
    private fun togglePlaylist(name: String) {
        if (expandedPlaylistName == name) {
            // 点自己：收起
            collapsePlaylist(name)
            expandedPlaylistName = null
        } else {
            // 点别人：先收起旧的，再展开新的
            if (expandedPlaylistName != null) collapsePlaylist(expandedPlaylistName!!)
            expandedPlaylistName = name
            expandPlaylist(name)
        }
    }

    // 收起指定歌单（把歌清掉、藏起来、图标换回 ▸）
    private fun collapsePlaylist(name: String) {
        val block = binding.playlistListContainer.findViewWithTag<LinearLayout>(name) ?: return
        // block 结构固定：child[0]=歌单行，child[1]=歌曲容器
        val header = block.getChildAt(0) as? LinearLayout ?: return
        val container = block.getChildAt(1) as? LinearLayout ?: return
        container.removeAllViews()
        container.visibility = View.GONE
        // 歌单行结构固定：child[0]=名字，child[1]=歌曲数，child[2]=三点按钮，child[3]=状态图标
        (header.getChildAt(3) as? TextView)?.text = "▸"
    }

    // 展开指定歌单：把歌单里的歌铺进歌曲容器
    private fun expandPlaylist(name: String) {
        val p = playlists.find { it.name == name } ?: return
        val block = binding.playlistListContainer.findViewWithTag<LinearLayout>(name) ?: return
        val header = block.getChildAt(0) as? LinearLayout ?: return
        val container = block.getChildAt(1) as? LinearLayout ?: return

        fillPlaylistSongs(p, container)
        container.visibility = View.VISIBLE
        // 歌单行结构固定：child[0]=名字，child[1]=歌曲数，child[2]=三点按钮，child[3]=状态图标
        (header.getChildAt(3) as? TextView)?.text = "▾"
    }

    // 把歌单里的歌逐行塞进容器（白字、缩进，点一下直接播；找不到的歌灰显不可点）
    private fun fillPlaylistSongs(p: Playlist, container: LinearLayout) {
        container.removeAllViews()
        // 当前队列是否就是这个歌单：是则把正在播的那一行染绿（用 path 比对，避免索引错位）
        val playingPath = if (listSource == "playlist" && activePlaylistName == p.name)
            PlayerManager.songPaths.getOrNull(PlayerManager.currentIndex) else null
        if (p.songs.isEmpty()) {
            val tip = TextView(requireContext()).apply {
                text = "歌单还是空的，点右侧「⋯」菜单添加歌曲"
                textSize = 14f
                setTextColor(NeonCard.SUB)
                setPadding(dp(40), dp(14), dp(24), dp(14))
            }
            container.addView(tip)
            return
        }
        p.songs.forEachIndexed { index, path ->
            val song = songs.find { it.path == path }
            // 行结构：歌名 + 歌手副行（与主列表一致；找不到的歌只有灰显歌名、不可点）
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(14), dp(24), dp(14))
            }
            val tv = TextView(requireContext()).apply {
                text = song?.displayName ?: playlistSongName(path)
                textSize = 16f
                // 正在播的这一首染绿；还能找到的歌白字可点；找不到的（被删了）灰字不可点
                setTextColor(
                    when {
                        song != null && song.path == playingPath -> NeonCard.GREEN
                        song != null -> NeonCard.WHITE
                        else -> 0xFF555555.toInt()
                    }
                )
            }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (song != null) {
                // 歌手副标题：有歌手显示歌手，读不到固定「未知歌手」占位，行高排版统一
                val artistTv = TextView(requireContext()).apply {
                    text = if (!song.id3Artist.isNullOrBlank()) song.id3Artist else "未知歌手"
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                }
                row.addView(artistTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // 点歌：全局播放器换成这个歌单的歌，直接播这一首
                row.setOnClickListener { playPlaylistSongs(p, index) }
                // 长按歌：弹确认，把这首歌从歌单移除（原文件不动）
                row.setOnLongClickListener {
                    confirmRemoveFromPlaylist(p, song.path)
                    true
                }
            }
            container.addView(row)
        }
    }

    // 歌还在缓存里找得到就显示歌名；找不到了（歌曲被删）就拿地址最后一段顶一下
    private fun playlistSongName(path: String): String {
        val last = Uri.parse(path).lastPathSegment ?: path
        return try {
            java.net.URLDecoder.decode(last, "UTF-8")
        } catch (e: Exception) {
            last
        }
    }

    // 播放歌单：按歌单顺序取还能播的歌交给全局播放器，播指定那首
    private fun playPlaylistSongs(p: Playlist, index: Int) {
        // 只取还在缓存里的歌（被删掉的跳过）
        val list = p.songs.mapNotNull { path -> songs.find { it.path == path } }
        if (list.isEmpty()) return
        // index 是 p.songs（含被删歌）的下标：过滤后要用 path 反查真实播放下标，避免错位
        if (index !in p.songs.indices) return
        val targetPath = p.songs[index]
        val playIndex = list.indexOfFirst { it.path == targetPath }
        if (playIndex < 0) return
        // 五数组全部替换为歌单内容（与 pushToPlayer/fillFolderSongs 保持一致，ID3 显示才能对齐）
        PlayerManager.songNames = list.map { it.name }.toMutableList()
        PlayerManager.songPaths = list.map { it.path }.toMutableList()
        PlayerManager.songDirs = list.map { it.dirPath }.toMutableList()
        PlayerManager.id3Titles = list.map { it.displayName }.toMutableList()
        PlayerManager.id3Artists = list.map { it.id3Artist ?: "" }.toMutableList()
        // 只标记队列来源与在播歌单，不替换歌曲页列表：歌单在歌单 tab 有自己的展开显示区，全部歌曲列表保持不动
        listSource = "playlist"
        activePlaylistName = p.name
        activeFolderUri = null
        syncResumeSource()
        // 逐曲记忆：受播放记忆开关约束——开关开着才读位置续播（>3 秒才续，防止残留
        // 一两秒无意义跳转）；开关关掉按老样子从头放。位置并入 play()：切歌走交叉
        // 淡入时 activePlayer 尚未切到新实例，外部 seekTo 会打在旧歌上，必须由
        // play 内部对真正目标实例 seek
        val trackPos = if (PlayerManager.isResumeEnabled(requireContext()))
            PlayerManager.readTrackPosition(requireContext(), targetPath) else 0
        PlayerManager.play(playIndex, requireContext(), if (trackPos > 3000) trackPos else 0)
        // 重建歌单页（保持展开）：把正在播的那一行刷成绿色高亮
        refreshPlaylistPage()
        Toast.makeText(requireContext(), "正在播放：${list[playIndex].displayName}", Toast.LENGTH_SHORT).show()
    }

    // A 入口：长按一首歌，弹"加入哪个歌单"
    private fun showAddToPlaylistDialog(song: Song) {
        if (playlists.isEmpty()) {
            Toast.makeText(requireContext(), "还没有歌单，先建一个吧", Toast.LENGTH_SHORT).show()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("加入歌单：${song.displayName}")
            .setItems(names) { _, which ->
                addSongToPlaylist(playlists[which], song.path)
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialog(dialog)
    }

    // 把一首歌加进指定歌单（已在里面就不重复加）
    private fun addSongToPlaylist(p: Playlist, path: String) {
        if (p.songs.contains(path)) {
            Toast.makeText(requireContext(), "这首歌已经在「${p.name}」里了", Toast.LENGTH_SHORT).show()
            return
        }
        p.songs.add(path)
        savePlaylists()
        refreshPlaylistPage()
        Toast.makeText(requireContext(), "已加入歌单：${p.name}", Toast.LENGTH_SHORT).show()
    }

    // ===== 自绘霓虹卡片共用件（弹窗 A 方案） =====

    // 深灰 #2C2C2E 圆角 24dp + 整圈霓虹四色描边 的卡片底（与歌单三点菜单同一套）
    private fun buildNeonCardRoot(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = NeonCard.cardBackground(requireContext())
            // background 赋值会用 drawable 的 padding 覆盖本 padding，必须放在 background 之后
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
    }

    // 弹窗底部按钮：深灰胶囊（与菜单按钮同款），字色由调用方传语义色
    private fun buildNeonButton(text: String, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(dp(24), 0, dp(24), 0)
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        setColor(pressLighten(NeonCard.BUTTON))
                        cornerRadius = dp(28).toFloat()
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(NeonCard.BUTTON)
                        cornerRadius = dp(28).toFloat()
                    }
                )
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
            setOnClickListener { onClick() }
        }
    }

    // B 入口：歌单展开后点"添加歌曲"，自绘霓虹卡片多选（无勾选框，点歌名选中染绿，再点取消）
    private fun showPickSongsDialog(playlistName: String) {
        val p = playlists.find { it.name == playlistName } ?: return
        // 只列还没加进这个歌单的歌（已加的不用再选）
        val candidates = songs.filter { it.path !in p.songs }
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "所有歌曲都已经在「$playlistName」里了", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(candidates.size)  // 默认都不选
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = buildNeonCardRoot()

        // 标题：歌名单行省略
        val titleTv = TextView(requireContext()).apply {
            text = "添加歌曲到「$playlistName」"
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "点歌名选中（染绿），再点取消"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(subTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // 歌曲列表：只显歌名白字 #E0E0E0，无勾选框；点击选中染绿 #1ED760，再点取消
        val listBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        candidates.forEachIndexed { i, song ->
            val tv = TextView(requireContext()).apply {
                text = song.displayName
                textSize = 16f
                setTextColor(NeonCard.WHITE)
                setPadding(dp(24), dp(13), dp(24), dp(13))
                foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
                setOnClickListener {
                    checked[i] = !checked[i]
                    setTextColor(if (checked[i]) NeonCard.GREEN else NeonCard.WHITE)
                }
            }
            listBox.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 列表高了滚动，最高 230dp，卡片不占满屏（对齐菜单卡片规格）
        val listHeight = minOf(dp(230), dp(48) * candidates.size)
        val scroll = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(dp(280), listHeight).apply { topMargin = dp(6) })

        // 底部：取消 / 确定（确定字色染绿，呼应选中态）
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(
            buildNeonButton("取消", NeonCard.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginEnd = dp(10) }
        )
        btnRow.addView(
            buildNeonButton("确定", NeonCard.GREEN) {
                dialog.dismiss()
                var count = 0
                for (i in candidates.indices) {
                    if (checked[i]) {
                        p.songs.add(candidates[i].path)
                        count++
                    }
                }
                if (count > 0) {
                    savePlaylists()
                    refreshPlaylistPage()
                    Toast.makeText(requireContext(), "已添加 $count 首歌", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "没选任何歌曲", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginStart = dp(10) }
        )
        root.addView(btnRow, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dialog.setContentView(root)
        dialog.show()
    }

    // 长按歌单里的歌：弹确认，确定就从歌单移除（原文件不动，防止误点）
    private fun confirmRemoveFromPlaylist(p: Playlist, path: String) {
        val song = songs.find { it.path == path }
        val name = song?.name ?: playlistSongName(path)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("移除歌曲")
            .setMessage("把「$name」从「${p.name}」移除？\n（只是退出歌单，歌曲文件不动）")
            .setPositiveButton("移除") { _, _ ->
                removeSongFromPlaylist(p, path)
            }
            .setNegativeButton("取消", null)
            .show()
        styleDialog(dialog)
    }

    // 把一首歌从歌单里移除（原文件不动）
    private fun removeSongFromPlaylist(p: Playlist, path: String) {
        if (!p.songs.remove(path)) return
        savePlaylists()
        refreshPlaylistPage()
        Toast.makeText(requireContext(), "已从「${p.name}」移除", Toast.LENGTH_SHORT).show()
    }

    // B 入口：歌单展开后点"移除歌曲"，自绘霓虹卡片多选（无勾选框，点歌名选中染红，再点取消）
    private fun showRemoveSongsDialog(playlistName: String) {
        val p = playlists.find { it.name == playlistName } ?: return
        if (p.songs.isEmpty()) {
            Toast.makeText(requireContext(), "「$playlistName」里还没有歌", Toast.LENGTH_SHORT).show()
            return
        }
        // 按歌单里的顺序列出来（找不到的歌用地址最后一段顶名字）
        val names = p.songs.map { path ->
            songs.find { it.path == path }?.displayName ?: playlistSongName(path)
        }

        val checked = BooleanArray(names.size)  // 默认都不选
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = buildNeonCardRoot()

        // 标题：歌名单行省略
        val titleTv = TextView(requireContext()).apply {
            text = "从「$playlistName」移除歌曲"
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "点歌名选中（染红），再点取消"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(subTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // 歌曲列表：只显歌名白字 #E0E0E0，无勾选框；点击选中染红 #FF9A8A，再点取消
        val listBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        names.forEachIndexed { i, name ->
            val tv = TextView(requireContext()).apply {
                text = name
                textSize = 16f
                setTextColor(NeonCard.WHITE)
                setPadding(dp(24), dp(13), dp(24), dp(13))
                foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
                setOnClickListener {
                    checked[i] = !checked[i]
                    setTextColor(if (checked[i]) NeonCard.RED else NeonCard.WHITE)
                }
            }
            listBox.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 列表高了滚动，最高 230dp，卡片不占满屏（对齐菜单卡片规格）
        val listHeight = minOf(dp(230), dp(48) * names.size)
        val scroll = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(dp(280), listHeight).apply { topMargin = dp(6) })

        // 底部：取消 / 确定（确定字色染红，呼应选中态）
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(
            buildNeonButton("取消", NeonCard.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginEnd = dp(10) }
        )
        btnRow.addView(
            buildNeonButton("确定", NeonCard.RED) {
                dialog.dismiss()
                var count = 0
                // 倒着删，免得下标错位
                for (i in p.songs.indices.reversed()) {
                    if (checked[i]) {
                        p.songs.removeAt(i)
                        count++
                    }
                }
                if (count > 0) {
                    savePlaylists()
                    refreshPlaylistPage()
                    Toast.makeText(requireContext(), "已移除 $count 首歌", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "没选任何歌曲", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginStart = dp(10) }
        )
        root.addView(btnRow, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dialog.setContentView(root)
        dialog.show()
    }

    // ============ 文件夹页 ============

    // 从 SharedPreferences 读 SAF 文件夹地址（App 重启不丢）
    private fun loadFolders() {
        val prefs = requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
        val raw = prefs.getString("folders", "[]")
        folderUris = mutableListOf()
        try {
            val arr = JSONArray(raw ?: "[]")
            for (i in 0 until arr.length()) {
                folderUris.add(arr.getString(i))
            }
        } catch (e: Exception) {
            folderUris = mutableListOf()
        }
    }

    // 把 SAF 文件夹地址写回 SharedPreferences
    private fun saveFolders() {
        val arr = JSONArray()
        for (uriStr in folderUris) {
            arr.put(uriStr)
        }
        requireContext().getSharedPreferences("jiepi_data", Context.MODE_PRIVATE)
            .edit()
            .putString("folders", arr.toString())
            .apply()
    }

    // 文件夹显示名：取授权目录的名字，取不到就用地址最后一段
    private fun folderDisplayName(uriStr: String, ctx: Context): String {
        val uri = Uri.parse(uriStr)
        val doc = DocumentFile.fromTreeUri(ctx, uri)
        return doc?.name ?: uri.lastPathSegment ?: uriStr
    }

    // 删除文件夹：自绘霓虹卡片多选（无勾选框，点文件夹名选中染红 #FF9A8A，再点取消）
    private fun showDeleteFolderDialog() {
        if (folderUris.isEmpty()) {
            Toast.makeText(requireContext(), "还没有文件夹可以删除", Toast.LENGTH_SHORT).show()
            return
        }
        val names = folderUris.map { folderDisplayName(it, requireContext()) }.toTypedArray()
        val checked = BooleanArray(names.size)  // 默认都不选
        val dialog = Dialog(requireContext())
        dialog.setCanceledOnTouchOutside(true)
        // 去掉系统默认白底方框，露出自己的圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val root = buildNeonCardRoot()

        // 标题：文件夹名省略
        val titleTv = TextView(requireContext()).apply {
            text = "删除文件夹"
            textSize = 17f
            setTextColor(NeonCard.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))

        // 副标题小字
        val subTv = TextView(requireContext()).apply {
            text = "点文件夹选中（染红），再点取消"
            textSize = 12f
            setTextColor(NeonCard.SUB)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(subTv, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // 文件夹列表：只显文件夹名白字，无勾选框；点击选中染红 #FF9A8A，再点取消
        val listBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        names.forEachIndexed { i, name ->
            val tv = TextView(requireContext()).apply {
                text = name
                textSize = 16f
                setTextColor(NeonCard.WHITE)
                setPadding(dp(24), dp(13), dp(24), dp(13))
                foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF.toInt()), null, null)
                setOnClickListener {
                    checked[i] = !checked[i]
                    setTextColor(if (checked[i]) NeonCard.RED else NeonCard.WHITE)
                }
            }
            listBox.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 列表高了滚动，最高 230dp，卡片不占满屏（对齐菜单卡片规格）
        val listHeight = minOf(dp(230), dp(48) * names.size)
        val scroll = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(dp(280), listHeight).apply { topMargin = dp(6) })

        // 底部：取消 / 确定（确定字色染红，呼应选中态）
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(
            buildNeonButton("取消", NeonCard.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginEnd = dp(10) }
        )
        btnRow.addView(
            buildNeonButton("确定", NeonCard.RED) {
                dialog.dismiss()
                val toDelete = mutableListOf<String>()
                for (i in names.indices) {
                    if (checked[i]) toDelete.add(folderUris[i])
                }
                if (toDelete.isEmpty()) {
                    Toast.makeText(requireContext(), "没勾选任何文件夹", Toast.LENGTH_SHORT).show()
                } else {
                    folderUris.removeAll(toDelete)
                    saveFolders()
                    refreshFolderList()
                    // 文件夹没了，歌曲集合跟着对齐（后台扫，界面不卡）
                    scanSongsAsync()
                    Toast.makeText(requireContext(), "已删除 ${toDelete.size} 个文件夹", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, dp(52)).apply { weight = 1f; marginStart = dp(10) }
        )
        root.addView(btnRow, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dialog.setContentView(root)
        dialog.show()
    }

    // 刷新文件夹页：清空重建"文件夹块"（深灰圆角行 + 底下可展开的歌曲容器）
    private fun refreshFolderList() {
        // 重建前记住外层 ScrollView 滚动位置，避免后台扫描完成刷新把页面弹回顶部
        val scrollView = binding.folderListContainer.parent as? ScrollView
        val savedY = scrollView?.scrollY ?: 0
        val has = folderUris.isNotEmpty()
        binding.folderListContainer.removeAllViews()
        binding.folderEmpty.visibility = if (has) View.GONE else View.VISIBLE
        binding.btnDeleteFolder.visibility = if (has) View.VISIBLE else View.INVISIBLE

        if (!has) return

        for (uriStr in folderUris) {
            // 一个文件夹块 = 文件夹行 + 歌曲容器（平时藏起来）
            val block = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                tag = uriStr  // 用地址当标记，收起时按标记找
            }
            binding.folderListContainer.addView(
                block,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            )

            // 文件夹行：深灰圆角卡片（和黑底歌曲区分），左边名字，右边展开/收起图标
            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    setColor(0xFF222222.toInt())
                    cornerRadius = dp(22).toFloat()
                }
                // 点整行：展开/收起这个文件夹
                setOnClickListener { toggleFolder(uriStr) }
            }
            block.addView(header)

            // 名字：白字，占满剩下的宽度
            val nameTv = TextView(requireContext()).apply {
                text = folderDisplayName(uriStr, requireContext())
                textSize = 16f
                setTextColor(NeonCard.WHITE)
            }
            header.addView(
                nameTv,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            // 状态图标：▸=收起 ▾=展开（图标做出来之前先占位）
            val stateTv = TextView(requireContext()).apply {
                text = if (expandedFolderUri == uriStr) "▾" else "▸"
                textSize = 16f
                setTextColor(NeonCard.SUB)
                setPadding(dp(8), 0, 0, 0)
            }
            header.addView(stateTv)

            // 歌曲容器：默认藏起来，展开时往里塞歌
            val songContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            block.addView(songContainer)

            // 如果这个文件夹本来展开着（比如刚扫完歌列表刷新）：恢复展开，歌也重新填
            if (expandedFolderUri == uriStr) {
                fillFolderSongs(songs.filter { it.dirPath == uriStr }, songContainer, uriStr)
                songContainer.visibility = View.VISIBLE
                stateTv.text = "▾"
            }
        }

        // 重建完成后恢复滚动位置（post 等布局稳定后再滚）
        scrollView?.post { scrollView?.scrollTo(0, savedY) }
    }

    // 点文件夹行：手风琴式切换（只开一个；点开另一个时，旧的自动收起）
    private fun toggleFolder(uriStr: String) {
        if (expandedFolderUri == uriStr) {
            // 点自己：收起
            collapseFolder(uriStr)
            expandedFolderUri = null
        } else {
            // 点别人：先收起旧的，再展开新的
            if (expandedFolderUri != null) collapseFolder(expandedFolderUri!!)
            expandedFolderUri = uriStr
            expandFolder(uriStr)
        }
    }

    // 收起指定文件夹（把歌清掉、藏起来、图标换回 ▸）
    private fun collapseFolder(uriStr: String) {
        val block = binding.folderListContainer.findViewWithTag<LinearLayout>(uriStr) ?: return
        // block 结构固定：child[0]=文件夹行，child[1]=歌曲容器
        val header = block.getChildAt(0) as? LinearLayout ?: return
        val container = block.getChildAt(1) as? LinearLayout ?: return
        container.removeAllViews()
        container.visibility = View.GONE
        // 文件夹行结构固定：child[0]=名字，child[1]=状态图标
        (header.getChildAt(1) as? TextView)?.text = "▸"
    }

    // 展开指定文件夹：从缓存里过滤出这个文件夹的歌，铺进歌曲容器
    private fun expandFolder(uriStr: String) {
        val block = binding.folderListContainer.findViewWithTag<LinearLayout>(uriStr) ?: return
        val header = block.getChildAt(0) as? LinearLayout ?: return
        val container = block.getChildAt(1) as? LinearLayout ?: return

        val folderSongs = songs.filter { it.dirPath == uriStr }
        if (folderSongs.isEmpty()) {
            Toast.makeText(requireContext(), "这个文件夹里没有歌曲", Toast.LENGTH_SHORT).show()
            // 空的就不展开了，状态复位
            expandedFolderUri = null
            (header.getChildAt(1) as? TextView)?.text = "▸"
            return
        }
        fillFolderSongs(folderSongs, container, uriStr)
        container.visibility = View.VISIBLE
        (header.getChildAt(1) as? TextView)?.text = "▾"
    }

    // 把文件夹的歌逐行塞进容器（白字、缩进，点一下直接播；长按可加入歌单）
    private fun fillFolderSongs(folderSongs: List<Song>, container: LinearLayout, uriStr: String) {
        container.removeAllViews()
        // 当前队列是否就是这个文件夹：是则把正在播的那一行染绿（用 path 比对，避免索引错位）
        val playingPath = if (listSource == "folder" && activeFolderUri == uriStr)
            PlayerManager.songPaths.getOrNull(PlayerManager.currentIndex) else null
        folderSongs.forEachIndexed { index, song ->
            val tv = TextView(requireContext()).apply {
                text = song.displayName
                textSize = 16f
                setTextColor(if (song.path == playingPath) NeonCard.GREEN else NeonCard.WHITE)
                // 缩进：比文件夹行往里一点，一眼看出是子级
                setPadding(dp(40), dp(14), dp(24), dp(14))
                // 点歌：全局播放器换成这个文件夹的歌，直接播这一首
                setOnClickListener {
                    PlayerManager.songNames = folderSongs.map { it.name }.toMutableList()
                    PlayerManager.songPaths = folderSongs.map { it.path }.toMutableList()
                    PlayerManager.songDirs = folderSongs.map { it.dirPath }.toMutableList()
                    PlayerManager.id3Titles = folderSongs.map { it.displayName }.toMutableList()
                    PlayerManager.id3Artists = folderSongs.map { it.id3Artist ?: "" }.toMutableList()
                    // 只标记队列来源与在播文件夹，不替换歌曲页列表：文件夹有自己的展开显示区，全部歌曲列表保持不动
                    listSource = "folder"
                    activeFolderUri = uriStr
                    activePlaylistName = null
                    syncResumeSource()
                    // 逐曲记忆：受播放记忆开关约束——开关开着才读位置续播（>3 秒才续，防止
                    // 残留一两秒无意义跳转）；开关关掉按老样子从头放。位置并入 play()：切歌
                    // 走交叉淡入时 activePlayer 尚未切到新实例，外部 seekTo 会打在旧歌上，
                    // 必须由 play 内部对真正目标实例 seek
                    val trackPos = if (PlayerManager.isResumeEnabled(requireContext()))
                        PlayerManager.readTrackPosition(requireContext(), song.path) else 0
                    PlayerManager.play(index, requireContext(), if (trackPos > 3000) trackPos else 0)
                    // 重建文件夹页（保持展开）：把正在播的那一行刷成绿色高亮
                    refreshFolderList()
                    Toast.makeText(requireContext(), "正在播放：${song.displayName}", Toast.LENGTH_SHORT).show()
                }
                // 长按：弹"加入哪个歌单"（A 入口：长按加入）
                setOnLongClickListener {
                    showAddToPlaylistDialog(song)
                    true
                }
            }
            container.addView(tv)
        }
    }

    // 歌曲行缓存：歌名 + 歌手副行 + 三点按钮（convertView 复用时直接取，不用再 findViewById）
    private class RowHolder(val nameTv: TextView, val artistTv: TextView, val moreBtn: ImageView)

    // 歌曲列表适配器：歌名白字 + 右侧竖排三点按钮（黑底白字，和播放页一个风格）
    private inner class SongAdapter(private val rows: List<Row>) : BaseAdapter() {

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            var holder = convertView as? LinearLayout
            var nameTv: TextView? = null
            var artistTv: TextView? = null
            var moreBtn: ImageView? = null
            if (holder == null) {
                holder = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setPadding(dp(24), dp(8), dp(6), dp(8))
                }
                // 左列：歌名 + 歌手副行（歌手为空时整行隐藏，只显歌名）
                val textCol = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                }
                nameTv = TextView(requireContext()).apply {
                    textSize = 16f
                    setTextColor(NeonCard.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                artistTv = TextView(requireContext()).apply {
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                textCol.addView(
                    nameTv,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                textCol.addView(
                    artistTv,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                holder.addView(
                    textCol,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                moreBtn = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_music_more)
                    setColorFilter(0xFF777777.toInt())
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    isClickable = true
                    contentDescription = "更多操作"
                }
                holder.addView(
                    moreBtn,
                    LinearLayout.LayoutParams(dp(30), dp(30))
                )
                holder.tag = RowHolder(nameTv!!, artistTv!!, moreBtn!!)
            } else {
                val h = holder.tag as? RowHolder
                nameTv = h?.nameTv
                artistTv = h?.artistTv
                moreBtn = h?.moreBtn
            }
            nameTv?.text = row.text
            // 歌手副标题：有歌手显示歌手，读不到固定「未知歌手」占位，不隐藏、保持行高排版统一
            artistTv?.text = if (!row.artist.isNullOrBlank()) row.artist else "未知歌手"
            artistTv?.visibility = View.VISIBLE
            // 正在播放的那一行：文字用 Spotify 绿（#1ED760）区分，其余保持柔和白（#E0E0E0）
            // 只有队列来源是全部歌曲时才在歌曲页按 currentIndex 高亮；歌单/文件夹播放中全部歌曲列表不高亮
            // 放公共位置：convertView 复用时也要跟着当前播放行重新上色
            val isPlayingRow = !row.isHeader && row.songIndex >= 0 && listSource == "all" && row.songIndex == PlayerManager.currentIndex
            nameTv?.setTextColor(if (isPlayingRow) NeonCard.GREEN else NeonCard.WHITE)
            if (row.songIndex in songs.indices) {
                val song = songs[row.songIndex]
                moreBtn?.setOnClickListener { showSongMenu(song) }
            } else {
                // 空态提示行：没有三点按钮
                moreBtn?.setOnClickListener(null)
            }
            return holder
        }
    }

    // 把系统弹窗改成黑底白字，跟播放器界面一个风格
    private fun styleDialog(dialog: AlertDialog) {
        // 弹窗背景：深灰 #2C2C2E 圆角 24dp 卡片 + 整圈霓虹四色描边（与三点菜单/自绘弹窗同一套）
        dialog.window?.setBackgroundDrawable(NeonCard.cardBackground(requireContext()))
        // 等弹窗内容摆好，再把所有文字统一变白
        dialog.window?.decorView?.post {
            val decor = dialog.window?.decorView ?: return@post
            applyDialogTextColor(decor)
        }
    }

    // 递归找弹窗里所有文字：标题/内容/按钮全变白；输入框再配深灰底；勾选框（含 CheckedTextView 勾框）白框白勾
    private fun applyDialogTextColor(view: View) {
        when (view) {
            // 输入框：白字 + 灰提示字 + 圆角深灰底（新建歌单那个输入框）
            is EditText -> {
                view.setTextColor(NeonCard.WHITE)
                view.setHintTextColor(NeonCard.SUB)
                view.setBackgroundResource(R.drawable.bg_input_rounded)
            }
            // 勾选框（添加歌曲/删除歌单等多选框）：框和勾都染白，去掉绿色填充，只留干净白勾
            is android.widget.CompoundButton -> {
                view.setTextColor(NeonCard.WHITE)
                // 整个勾选框的色调 = 白色（框白、勾白、没有绿色填充）
                view.buttonTintList =
                    android.content.res.ColorStateList.valueOf(NeonCard.WHITE)
            }
            // 多选列表项（setMultiChoiceItems 的 CheckedTextView）：文字染白，勾框图形也染白
            // 注意：CheckedTextView 是 TextView 子类，必须放在 TextView 分支之前
            is android.widget.CheckedTextView -> {
                view.setTextColor(NeonCard.WHITE)
                view.checkMarkTintList =
                    android.content.res.ColorStateList.valueOf(NeonCard.WHITE)
            }
            // 弹窗按钮（确定/取消等）：柔和白字 + 浅灰胶囊底，跟选项按钮同色系
            is android.widget.Button -> {
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
            // 其它文字（标题/内容/按钮/列表项）：统一白字
            is TextView -> view.setTextColor(NeonCard.WHITE)
        }
        // 一层层往下翻，子控件也一起改
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyDialogTextColor(view.getChildAt(i))
            }
        }
    }

    // dp 转像素
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // 页面重新可见（从详情页返回/后台回前台）：把底部三按钮拉回来（状态式接管）。
    // 详情页/EQ 页 onResume 会强制隐藏，一级页接管时也要主动拉回，否则按钮会一直消失；
    // 若用户之前滚动到底部处于隐藏态（bottomButtonsHidden=true），则尊重滚动状态不弹回。
    // 注意：一级页互切（音乐↔我的）时按钮本来就是 VISIBLE，不能无脑调用 setBottomButtonsVisible(true)
    // 否则点击"音乐列表"按钮切页时会看到三个按钮一起 Q 弹一遍（动画被无条件重播）
    override fun onResume() {
        super.onResume()
        if (!bottomButtonsHidden) {
            val btnLeft = requireActivity().findViewById<View>(R.id.btnLeft) ?: return
            // 按钮已被详情页/EQ 页隐藏（GONE）才弹回；本来可见则不动，避免重复 Q 弹
            if (btnLeft.visibility != View.VISIBLE) {
                setBottomButtonsVisible(true)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 页面销毁：取消切歌监听，别让回调打到已销毁的列表上
        PlayerManager.removeSongChangeListener(onSongChangeListener)
        // 歌曲页重建标记失效：下次 view 重建后 selectCategory 必须重新 setAdapter
        refreshedSongVersion = -1
        _binding = null
        // 注意：这里不杀播放器了，歌继续放！
    }
}
