// X11DisplayActivity.kt -- This file is part of tiny_container.
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
// along with this program. If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.fct.tc4.R

/**
 * 图形界面显示活动（Termux:X11 与 Termux Desktop 共用）。
 *
 * 直接继承容器内置的 Termux:X11 显示活动，只额外承担两件事：
 *
 *  1. 第一次进入该显示界面时弹出方向选择框（横屏 / 竖屏 / 跟随手机），并把结果记录下来，
 *     之后不再打扰用户（可在图形界面自带设置页的“屏幕方向”里修改）；
 *  2. 按记录的方向接管 `setRequestedOrientation`：
 *     “跟随手机”使用 `SCREEN_ORIENTATION_FULL_SENSOR`，不受系统“自动旋转”开关影响，
 *     手机横过来界面就横过来、手机竖过来界面就竖过来。
 *
 * Termux:X11 自身会在窗口获得焦点时按其内置偏好重置方向，因此这里在 `onWindowFocusChanged`
 * 中先调用 `super`、再应用我们自己的方向，保证以用户选择的模式为准。
 */
class X11DisplayActivity : com.termux.x11.MainActivity() {

    companion object {
        /** 启动时携带的显示方式标识，用于分别记录两种图形界面各自的方向选择 */
        const val EXTRA_DISPLAY_MODE = "com.fct.tc4.extra.DISPLAY_MODE"

        const val MODE_X11 = "x11"
        const val MODE_TERMUX_DESKTOP = "termux-desktop"

        private const val ORIENTATION_LANDSCAPE = "landscape"
        private const val ORIENTATION_PORTRAIT = "portrait"
        private const val ORIENTATION_FOLLOW = "follow"

        /** Termux:X11 自带的屏幕方向偏好键（图形界面设置页中的“屏幕方向”） */
        private const val KEY_FORCE_ORIENTATION = "forceOrientation"

        /** 记录我们已同步过的 Termux:X11 方向值，用于识别用户在设置页里的改动 */
        private const val KEY_OBSERVED_ORIENTATION = "tc4_display_orientation_observed"

        private val ORIENTATION_VALUES =
            arrayOf(ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT, ORIENTATION_FOLLOW)

        private fun preferenceKey(displayMode: String) = "tc4_display_orientation_$displayMode"
    }

    /** 与 Termux:X11 共用同一份默认 SharedPreferences（<包名>_preferences） */
    private lateinit var sharedPreferences: SharedPreferences

    private var displayMode: String = MODE_X11
    private var orientationMode: String = ORIENTATION_FOLLOW

    /** 本次进入是否已经询问过，避免取消后每次焦点变化都弹窗 */
    private var orientationAsked = false
    private var orientationDialog: AlertDialog? = null

    /** 用户在图形界面自带设置里修改“屏幕方向”时，同步我们的记录 */
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (KEY_FORCE_ORIENTATION != key) return@OnSharedPreferenceChangeListener
            val termuxValue = sharedPreferences.getString(KEY_FORCE_ORIENTATION, null)
            val mapped = fromTermuxOrientation(termuxValue) ?: return@OnSharedPreferenceChangeListener
            if (mapped == orientationMode) return@OnSharedPreferenceChangeListener
            applyTermuxOrientation(mapped, termuxValue)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        displayMode = intent?.getStringExtra(EXTRA_DISPLAY_MODE)?.takeIf { it.isNotEmpty() }
            ?: MODE_X11
        sharedPreferences = getSharedPreferences(
            applicationContext.packageName + "_preferences", Context.MODE_PRIVATE
        )
        orientationMode = readOrientationMode()

        super.onCreate(savedInstanceState)

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        // 尽可能早地应用一次，避免界面先按系统方向绘制
        applyOrientation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // super 会按 Termux:X11 内置偏好重置方向，必须在其之后再覆盖
        super.onWindowFocusChanged(hasFocus)
        applyOrientation()

        if (hasFocus) maybeAskOrientation()
    }

    override fun onDestroy() {
        orientationDialog?.dismiss()
        orientationDialog = null
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDestroy()
    }

    // ===================== 方向 =====================

    /** 读取当前显示方式对应的方向：优先我们记录的，其次沿用 Termux:X11 自带偏好 */
    private fun readOrientationMode(): String {
        val termuxValue = sharedPreferences.getString(KEY_FORCE_ORIENTATION, null)
        val observed = sharedPreferences.getString(KEY_OBSERVED_ORIENTATION, null)

        // 用户在图形界面自带设置页里改过“屏幕方向”→ 尊重该改动，两种显示方式一起更新
        if (observed != null && termuxValue != null && termuxValue != observed) {
            val mapped = fromTermuxOrientation(termuxValue)
            if (mapped != null) {
                sharedPreferences.edit()
                    .putString(preferenceKey(MODE_X11), mapped)
                    .putString(preferenceKey(MODE_TERMUX_DESKTOP), mapped)
                    .putString(KEY_OBSERVED_ORIENTATION, termuxValue)
                    .apply()
                return mapped
            }
        }

        return sharedPreferences.getString(preferenceKey(displayMode), null)
            ?: fromTermuxOrientation(termuxValue)
            ?: ORIENTATION_FOLLOW
    }

    /** 把某个方向同时记为“当前显示方式的选择”与“已同步的 Termux:X11 方向值” */
    private fun applyTermuxOrientation(mapped: String, termuxValue: String?) {
        orientationMode = mapped
        sharedPreferences.edit()
            .putString(preferenceKey(displayMode), mapped)
            .putString(KEY_OBSERVED_ORIENTATION, termuxValue)
            .apply()
        applyOrientation()
    }

    private fun fromTermuxOrientation(value: String?): String? = when (value) {
        "landscape", "reverse landscape" -> ORIENTATION_LANDSCAPE
        "portrait", "reverse portrait" -> ORIENTATION_PORTRAIT
        "auto" -> ORIENTATION_FOLLOW
        else -> null
    }

    private fun toTermuxOrientation(value: String): String = when (value) {
        ORIENTATION_LANDSCAPE -> "landscape"
        ORIENTATION_PORTRAIT -> "portrait"
        else -> "auto"
    }

    private fun applyOrientation() {
        val requested = when (orientationMode) {
            ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 跟随手机：FULL_SENSOR 直接使用重力传感器，系统“自动旋转”关闭时依然生效
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (requestedOrientation != requested) requestedOrientation = requested
    }

    private fun selectOrientation(value: String) {
        orientationMode = value

        // 同步到 Termux:X11 自带设置页，避免两处显示不一致
        val termuxValue = toTermuxOrientation(value)
        sharedPreferences.edit()
            .putString(preferenceKey(displayMode), value)
            .putString(KEY_OBSERVED_ORIENTATION, termuxValue)
            .putString(KEY_FORCE_ORIENTATION, termuxValue)
            .apply()

        applyOrientation()
    }

    // ===================== 首次询问 =====================

    private fun maybeAskOrientation() {
        if (orientationAsked) return
        if (isFinishing) return
        orientationAsked = true
        if (sharedPreferences.contains(preferenceKey(displayMode))) return
        showOrientationDialog()
    }

    private fun showOrientationDialog() {
        if (orientationDialog?.isShowing == true) return

        val labels = arrayOf(
            getString(R.string.tc4_display_orientation_landscape),
            getString(R.string.tc4_display_orientation_portrait),
            getString(R.string.tc4_display_orientation_follow)
        )
        val checked = ORIENTATION_VALUES.indexOf(orientationMode).coerceAtLeast(0)

        orientationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.tc4_display_orientation_title)
            .setMessage(R.string.tc4_display_orientation_message)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectOrientation(ORIENTATION_VALUES[which])
                dialog.dismiss()
            }
            .create()
            .also { it.show() }
    }
}
