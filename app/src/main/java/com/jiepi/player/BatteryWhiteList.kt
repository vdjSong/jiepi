package com.jiepi.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 电池白名单厂商跳转：
 * 按 Build.MANUFACTURER 匹配主流厂商的电池/自启动管理页，直接带用户到正确入口；
 * 无匹配或跳转失败一律回退系统标准「电池优化」列表页（无需任何权限）；
 * 仍失败则 Toast 提示手动路径。全程 try-catch，零新增权限。
 *
 * 映射表依据社区方案（dontkillmyapp 类资源核对）：
 * 各厂商组件名随系统版本会变，本表只做“尽量直达”，失效由回退兜底。
 */
object BatteryWhiteList {

    /** 单个厂商入口：目标包名 + 直达 Activity；action 兜底可选（部分厂商新版只认 action） */
    private class Entry(val pkg: String, val activity: String? = null, val action: String? = null)

    // 厂商关键字 → 入口（按主流机型覆盖，未列出的回退系统标准页）
    private val MAP = listOf(
        // 小米 / 红米 / POCO（MIUI 安全中心→自启动）
        "xiaomi" to Entry("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "redmi" to Entry("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "poco" to Entry("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // 华为 / 荣耀（EMUI/HarmonyOS 应用启动管理）
        "huawei" to Entry("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        "honor" to Entry("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        // OPPO / 一加 / 真我（ColorOS 自启动管理）
        "oppo" to Entry("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity", "com.coloros.safecenter.permission.startup"),
        "oneplus" to Entry("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity", "com.coloros.safecenter.permission.startup"),
        "realme" to Entry("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity", "com.coloros.safecenter.permission.startup"),
        // vivo / iQOO（i管家 后台耗电白名单）
        "vivo" to Entry("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        "iqoo" to Entry("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // 三星（One UI 电池后台使用限制）
        "samsung" to Entry("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        // 魅族
        "meizu" to Entry("com.meizu.safe", null, "permission.intent.action.PERM_DETAIL_SETTINGS"),
        // 联想
        "lenovo" to Entry("com.lenovo.safecenter", "com.lenovo.safecenter.startup.StartUpListActivity"),
        // 努比亚
        "nubia" to Entry("com.nubia.security2", null, "com.nubia.security2.SHOW_APP_START_MANAGER"),
        // 华硕
        "asus" to Entry("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        // 索尼
        "sony" to Entry("com.sonymobile.cta", "com.sonymobile.cta.SomcCTAMainActivity")
    )

    /**
     * 打开电池白名单设置页。入口顺序：
     * 1) 按厂商关键字命中且 Activity 可解析 → 直达厂商管理页；
     * 2) 回退系统标准「电池优化」列表页（ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS，无需权限）；
     * 3) 仍失败 → Toast 手动路径。
     */
    fun open(context: Context) {
        val mfr = Build.MANUFACTURER?.lowercase() ?: ""
        val entry = MAP.firstOrNull { mfr.contains(it.first) }?.second

        // 1) 厂商直达（解析成功才算数）
        if (entry != null && launch(context, buildEntryIntent(context, entry))) return

        // 2) 回退系统标准电池优化列表页
        if (launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            toast(context, "在列表中找到「洁癖播放器」，设为不优化即可")
            return
        }

        // 3) 手动路径兜底
        toast(context, "无法打开电池设置，请到系统设置→电池→后台耗电/自启动管理，手动允许本应用")
    }

    /** 构造厂商入口 Intent：优先 Activity 组件，其次 action */
    private fun buildEntryIntent(context: Context, entry: Entry): Intent {
        val it = if (entry.activity != null) {
            Intent().setComponent(ComponentName(entry.pkg, entry.activity))
        } else {
            Intent(entry.action)
        }
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return it
    }

    /** 解析 + 启动；成功返回 true，任何异常一律吞掉走回退 */
    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
