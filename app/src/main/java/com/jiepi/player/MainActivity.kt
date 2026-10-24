package com.jiepi.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import com.jiepi.player.databinding.ActivityMainBinding
import com.jiepi.player.update.UpdateManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var shakeController: ShakeController? = null

    // 通知权限申请（Android 13+ 运行时权限）：授权后通知栏才显示播放卡/歌词。
    // 拒绝不影响播放，只是通知栏卡片不显示，可在"更多 → 权限展示与说明"里了解并去系统设置开启。
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 首次启动主动申请通知权限（仅 Android 13+ 需要；老系统直接放行）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 摇一摇切歌：加速度传感器全局监听（唯一 Activity，挂这里即全 App 生效）
        shakeController = ShakeController(this).also { it.register() }

        // 恢复上次调好的 EQ/音效（杀进程后不丢）
        PlayerManager.initFx(this)

        // 更新包安装完成后新版本启动，自动清理缓存的旧安装包
        UpdateManager.cleanCachedApk(this)

        // 状态栏：黑底 + 白色图标（时间、电量、信号都变白），固定黑色风格，深浅模式都一样
        // 注意：浅色主题默认图标是深色，黑底上看不见，必须强制改成白色
        window.statusBarColor = android.graphics.Color.parseColor("#000000")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // targetSdk 36 强制 edge-to-edge：窗口内容不再自动避让系统栏。
        // 这里统一监听系统栏 inset，让根布局顶部/底部让出状态栏和导航栏，
        // 三个悬浮按钮额外叠加导航栏高度，避免和系统导航键撞上。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 根布局：顶部避开状态栏，底部避开导航栏（所有 fragment 页面自动避让）
            binding.root.setPadding(0, bars.top, 0, bars.bottom)

            // 悬浮按钮：底部 margin 在原来的 28dp 基础上再叠加导航栏高度
            val navBottom = bars.bottom
            val baseBottom = (28 * resources.displayMetrics.density).toInt()
            listOf(binding.btnLeft, binding.floatingActionButton2, binding.btnRight).forEach { fab ->
                val lp = fab.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                lp.bottomMargin = baseBottom + navBottom
                fab.layoutParams = lp
            }
            insets
        }

        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        // 一级页（左/右）切换选项：不叠页、不重复压栈、把中间详情页踢掉、保留自己
        val navOptionsTop = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, false)
            .build()

        // 左：音乐圈
        binding.btnLeft.setOnClickListener {
            navController.navigate(R.id.navigation_music, null, navOptionsTop)
        }

        // 中：播放——不动栈，直接叠上去，返回能回原来那一级
        binding.floatingActionButton2.setOnClickListener {
            navController.navigate(R.id.navigation_play)
        }

        // 右：我的
        binding.btnRight.setOnClickListener {
            navController.navigate(R.id.navigation_more, null, navOptionsTop)
        }
    }

    // Activity 销毁时注销传感器监听，避免泄漏（杀进程时监听随进程消失，无碍）
    override fun onDestroy() {
        super.onDestroy()
        shakeController?.unregister()
        shakeController = null
    }
}
