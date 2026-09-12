// LauncherIcon.kt -- This file is part of tiny_container (Youlong Remake).
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.misc

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标（启动器入口）开关。
 *
 * 清单里 `LauncherAlias` 声明的都是 `android:enabled="false"`，
 * 所以 **安装后桌面和应用列表里都不会出现图标**（游龙改版要求）。
 * 如需恢复图标，可在「设置 → 桌面图标」里打开；关掉即可再次隐藏。
 *
 * ⚠️ 「隐藏桌面图标」**不等于**「应用不能被启动」：
 * 清单里另有一个始终启用的 `InfoAlias`（`MAIN` + `CATEGORY_INFO` + `CATEGORY_DEFAULT`，
 * 不是桌面入口），`PackageManager.getLaunchIntentForPackage()` 会优先命中它，
 * 所以第三方 App（快捷方式创建器、启动器整理工具等）仍然能发现并拉起本应用。
 * 本开关**只**影响 LauncherAlias（= 桌面上那个图标），不要去动 InfoAlias。
 *
 * 注意：`setComponentEnabledSetting` 的状态只在该次安装内有效，
 * 重新安装 / 升级 APK 后会回到清单默认值（隐藏）。
 */
object LauncherIcon {

    /**
     * 与 AndroidManifest.xml 中 <activity-alias android:name=".ui.main.LauncherAlias"> 对应。
     *
     * 注意两点（已用 aapt2 dump xmltree 在构建产物上验证过）：
     * 1) 清单里的相对类名由 AGP 用 **namespace**（com.fct.tc4）展开成全限定类名，
     *    所以这里的类名必须是 com.fct.tc4.*，不能写成 applicationId。
     * 2) 组件的运行时包名用的是 **applicationId**（com.youlong.computer），
     *    即 context.packageName。
     */
    private const val ALIAS_CLASS = "com.fct.tc4.ui.main.LauncherAlias"

    private fun alias(context: Context): ComponentName =
        ComponentName(context.packageName, ALIAS_CLASS)

    /** 读取别名当前是否生效（= 桌面是否显示图标） */
    fun isEnabled(context: Context): Boolean = try {
        context.packageManager.getActivityInfo(alias(context), 0).enabled
    } catch (_: Exception) {
        // 组件不存在（旧版本升级上来）或查询失败：视为隐藏
        false
    }

    /** 开启 / 关闭桌面图标 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        true
    } catch (_: Exception) {
        false
    }
}
