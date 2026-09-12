// DesktopKeyboardView.kt -- This file is part of tiny_container.
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

package com.fct.tc4.kbd

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.fct.tc4.R
import kotlin.math.max
import kotlin.math.roundToInt

/** 电脑键盘面板把按键事件注入到哪里。 */
interface KeyboardTarget {
    /** 按下并抬起一个按键，metaState 里带 CTRL/ALT/SHIFT/META 修饰位。 */
    fun sendKeyEvent(keyCode: Int, metaState: Int)

    /** 切回系统（手机）输入法。 */
    fun switchToPhoneKeyboard()

    /** 收起电脑键盘面板。 */
    fun hideDesktopKeyboard()
}

private const val KIND_KEY = 0
private const val KIND_MOD = 1
private const val KIND_ACTION = 2

private const val MOD_CTRL = 0
private const val MOD_SHIFT = 1
private const val MOD_ALT = 2
private const val MOD_META = 3
private const val MOD_COUNT = 4

private const val ACTION_PHONE = "phone"
private const val ACTION_HIDE = "hide"

/** 每行统一的总权重，保证不同行里的单键宽度一致。 */
private const val ROW_UNITS = 17

/** 面板最窄宽度（dp）：屏幕更窄时改为横向滚动，避免按键被压成一条线。 */
private const val MIN_BOARD_WIDTH_DP = 620f

private const val REPEAT_FIRST_DELAY = 420L
private const val REPEAT_INTERVAL = 55L

private class KeyDef(
    val label: String,
    val code: Int = 0,
    val weight: Int = 1,
    val kind: Int = KIND_KEY,
    val modIndex: Int = -1,
    val repeatable: Boolean = false,
    val action: String? = null
)

/**
 * 「电脑键盘」面板。
 *
 * 布局与真正的电脑键盘一致：功能键行（Esc / F1~F12 / Ins / Del）、数字符号行、
 * QWERTY 三行，以及底部带 Ctrl / Win / Alt / Shift 修饰键与方向键的控制行。
 *
 * 修饰键支持两种用法：
 *  - 轻点 = 一次性，只对紧随其后的一个按键生效，然后自动释放；
 *  - 长按 = 锁定，一直生效，再轻点一次取消。
 *
 * 这样 F11（全屏）、F12（开发者工具）、Alt+F4、Ctrl+Alt+T 之类的组合键都能按出来。
 */
class DesktopKeyboardView(
    context: Context,
    private val target: KeyboardTarget
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    private val armed = BooleanArray(MOD_COUNT)
    private val locked = BooleanArray(MOD_COUNT)

    private val defsOf = LinkedHashMap<TextView, KeyDef>()
    private val modViews = HashMap<Int, TextView>()
    private val pressedAt = HashMap<TextView, Long>()
    private val repeats = HashMap<TextView, Runnable>()
    private val pressed = HashSet<TextView>()

    private var boardWidth = 0

    private val bgNormal = rounded(0xFF243043.toInt())
    private val bgPressed = rounded(0xFF3C5074.toInt())
    private val bgArmed = rounded(0xFF2E6BE6.toInt())
    private val bgLocked = rounded(0xFFE07A16.toInt())

    init {
        background = GradientDrawable().apply {
            setColor(0xF00C111A.toInt())
            cornerRadius = dp(10f).toFloat()
            setStroke(dp(1f), 0xFF31405A.toInt())
        }
        isClickable = true
        isFocusable = false
        // 吞掉落在面板上的点击，别穿透到下面正在显示的画面
        setOnClickListener { }
        contentDescription = context.getString(R.string.tc4_kbd_panel_desc)
        addView(buildBoard())
    }

    // ===================== 构建 =====================

    private fun buildBoard(): HorizontalScrollView {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
            layoutParams = LayoutParams(dp(MIN_BOARD_WIDTH_DP), LayoutParams.MATCH_PARENT)
        }
        for (rowDefs in rows()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                weightSum = ROW_UNITS.toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            for (def in rowDefs) row.addView(createKey(def))
            column.addView(row)
        }
        return HorizontalScrollView(context).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(column)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wanted = max(w, dp(MIN_BOARD_WIDTH_DP))
        if (wanted == boardWidth) return
        boardWidth = wanted
        val scroll = getChildAt(0) as? HorizontalScrollView ?: return
        val column = scroll.getChildAt(0) ?: return
        val lp = column.layoutParams
        lp.width = wanted
        column.layoutParams = lp
    }

    private fun createKey(def: KeyDef): TextView {
        val tv = TextView(context)
        tv.text = def.label
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setPadding(dp(1f), 0, dp(1f), 0)
        tv.setTextColor(
            when (def.kind) {
                KIND_ACTION -> 0xFF9ED0FF.toInt()
                KIND_MOD -> 0xFFFFD79A.toInt()
                else -> Color.WHITE
            }
        )
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            tv, 5, 12, 1, TypedValue.COMPLEX_UNIT_SP
        )
        val margin = dp(2f)
        tv.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, def.weight.toFloat()
        ).apply { setMargins(margin, margin, margin, margin) }
        tv.background = bgNormal
        tv.isClickable = true
        tv.isFocusable = false
        tv.isLongClickable = false
        defsOf[tv] = def
        if (def.kind == KIND_MOD && def.modIndex in 0 until MOD_COUNT) modViews[def.modIndex] = tv
        tv.setOnTouchListener { v, ev -> onKeyTouch(v as TextView, ev) }
        return tv
    }

    // ===================== 触摸 / 按键 =====================

    private fun onKeyTouch(view: TextView, ev: MotionEvent): Boolean {
        val def = defsOf[view] ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed.add(view)
                pressedAt[view] = SystemClock.uptimeMillis()
                refreshKeyBackground(view)
                when (def.kind) {
                    KIND_KEY -> {
                        fireKey(def)
                        if (def.repeatable) startRepeat(view, def)
                    }
                    KIND_ACTION -> runAction(def)
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                stopRepeat(view)
                val held = SystemClock.uptimeMillis() - (pressedAt[view] ?: 0L)
                pressed.remove(view)
                pressedAt.remove(view)
                if (def.kind == KIND_MOD) {
                    // 长按 = 锁定，轻点 = 一次性
                    if (held >= ViewConfiguration.getLongPressTimeout()) toggleLock(def.modIndex)
                    else toggleArm(def.modIndex)
                }
                refreshAllKeyBackgrounds()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeat(view)
                pressed.remove(view)
                pressedAt.remove(view)
                refreshKeyBackground(view)
                return true
            }
        }
        return false
    }

    private fun fireKey(def: KeyDef) {
        target.sendKeyEvent(def.code, currentMetaState())
        var changed = false
        for (i in 0 until MOD_COUNT) {
            if (armed[i]) {
                armed[i] = false
                changed = true
            }
        }
        if (changed) refreshAllKeyBackgrounds()
    }

    private fun runAction(def: KeyDef) {
        when (def.action) {
            ACTION_PHONE -> target.switchToPhoneKeyboard()
            ACTION_HIDE -> target.hideDesktopKeyboard()
        }
    }

    private fun startRepeat(view: TextView, def: KeyDef) {
        stopRepeat(view)
        val r = object : Runnable {
            override fun run() {
                if (!pressed.contains(view)) return
                fireKey(def)
                handler.postDelayed(this, REPEAT_INTERVAL)
            }
        }
        repeats[view] = r
        handler.postDelayed(r, REPEAT_FIRST_DELAY)
    }

    private fun stopRepeat(view: TextView) {
        repeats.remove(view)?.let { handler.removeCallbacks(it) }
    }

    // ===================== 修饰键状态 =====================

    private fun isModifierOn(index: Int) =
        index in 0 until MOD_COUNT && (armed[index] || locked[index])

    private fun currentMetaState(): Int {
        var state = 0
        if (isModifierOn(MOD_CTRL)) state = state or KeyEvent.META_CTRL_ON
        if (isModifierOn(MOD_SHIFT)) state = state or KeyEvent.META_SHIFT_ON
        if (isModifierOn(MOD_ALT)) state = state or KeyEvent.META_ALT_ON
        if (isModifierOn(MOD_META)) state = state or KeyEvent.META_META_ON
        return state
    }

    private fun toggleArm(index: Int) {
        if (index !in 0 until MOD_COUNT) return
        if (locked[index]) {
            locked[index] = false
            armed[index] = false
            return
        }
        armed[index] = !armed[index]
    }

    private fun toggleLock(index: Int) {
        if (index !in 0 until MOD_COUNT) return
        locked[index] = !locked[index]
        if (locked[index]) armed[index] = false
    }

    /** 面板重新显示时清掉一次性修饰键，避免“粘住”。 */
    fun resetTransientModifiers() {
        var changed = false
        for (i in 0 until MOD_COUNT) {
            if (armed[i]) {
                armed[i] = false
                changed = true
            }
        }
        if (changed) refreshAllKeyBackgrounds()
    }

    // ===================== 外观 =====================

    private fun refreshAllKeyBackgrounds() {
        for (view in defsOf.keys) refreshKeyBackground(view)
    }

    private fun refreshKeyBackground(view: TextView) {
        val def = defsOf[view] ?: return
        val bg = when {
            def.kind == KIND_MOD && locked[def.modIndex] -> bgLocked
            pressed.contains(view) -> bgPressed
            def.kind == KIND_MOD && armed[def.modIndex] -> bgArmed
            else -> bgNormal
        }
        if (view.background !== bg) view.background = bg
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f).toFloat()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    // ===================== 键位表 =====================

    private fun rows(): List<List<KeyDef>> {
        val phone = context.getString(R.string.tc4_kbd_btn_phone)
        val hide = context.getString(R.string.tc4_kbd_btn_hide)

        val functionRow = ArrayList<KeyDef>()
        functionRow.add(KeyDef("Esc", KeyEvent.KEYCODE_ESCAPE, 2))
        for (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            functionRow.add(KeyDef("F" + (code - KeyEvent.KEYCODE_F1 + 1), code, 1))
        }
        functionRow.add(KeyDef("Ins", KeyEvent.KEYCODE_INSERT, 1))
        functionRow.add(KeyDef("Del", KeyEvent.KEYCODE_FORWARD_DEL, 2, repeatable = true))

        val numberRow = ArrayList<KeyDef>()
        numberRow.add(KeyDef("`", KeyEvent.KEYCODE_GRAVE, 1))
        for (digit in 1..9) numberRow.add(digitKey(digit))
        numberRow.add(digitKey(0))
        numberRow.add(KeyDef("-", KeyEvent.KEYCODE_MINUS, 1))
        numberRow.add(KeyDef("=", KeyEvent.KEYCODE_EQUALS, 1))
        numberRow.add(KeyDef("Home", KeyEvent.KEYCODE_MOVE_HOME, 1))
        numberRow.add(KeyDef("⌫", KeyEvent.KEYCODE_DEL, 3, repeatable = true))

        val qwertyTop = ArrayList<KeyDef>()
        qwertyTop.add(KeyDef("Tab", KeyEvent.KEYCODE_TAB, 2))
        for (ch in "QWERTYUIOP") qwertyTop.add(letterKey(ch))
        qwertyTop.add(KeyDef("[", KeyEvent.KEYCODE_LEFT_BRACKET, 1))
        qwertyTop.add(KeyDef("]", KeyEvent.KEYCODE_RIGHT_BRACKET, 1))
        qwertyTop.add(KeyDef("\\", KeyEvent.KEYCODE_BACKSLASH, 1))
        qwertyTop.add(KeyDef("PgUp", KeyEvent.KEYCODE_PAGE_UP, 2, repeatable = true))

        val qwertyHome = ArrayList<KeyDef>()
        qwertyHome.add(KeyDef("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 2))
        for (ch in "ASDFGHJKL") qwertyHome.add(letterKey(ch))
        qwertyHome.add(KeyDef(";", KeyEvent.KEYCODE_SEMICOLON, 1))
        qwertyHome.add(KeyDef("'", KeyEvent.KEYCODE_APOSTROPHE, 1))
        qwertyHome.add(KeyDef("⏎", KeyEvent.KEYCODE_ENTER, 2, repeatable = true))
        qwertyHome.add(KeyDef("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, 2, repeatable = true))

        val qwertyBottom = ArrayList<KeyDef>()
        qwertyBottom.add(KeyDef("⇧", KeyEvent.KEYCODE_SHIFT_LEFT, 3, KIND_MOD, MOD_SHIFT))
        for (ch in "ZXCVBNM") qwertyBottom.add(letterKey(ch))
        qwertyBottom.add(KeyDef(",", KeyEvent.KEYCODE_COMMA, 1))
        qwertyBottom.add(KeyDef(".", KeyEvent.KEYCODE_PERIOD, 1))
        qwertyBottom.add(KeyDef("/", KeyEvent.KEYCODE_SLASH, 1))
        qwertyBottom.add(KeyDef("⇧", KeyEvent.KEYCODE_SHIFT_RIGHT, 2, KIND_MOD, MOD_SHIFT))
        qwertyBottom.add(KeyDef("End", KeyEvent.KEYCODE_MOVE_END, 2))

        val controlRow = listOf(
            KeyDef("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 2, KIND_MOD, MOD_CTRL),
            KeyDef("Win", KeyEvent.KEYCODE_META_LEFT, 2, KIND_MOD, MOD_META),
            KeyDef("Alt", KeyEvent.KEYCODE_ALT_LEFT, 2, KIND_MOD, MOD_ALT),
            KeyDef("空格", KeyEvent.KEYCODE_SPACE, 2),
            KeyDef("←", KeyEvent.KEYCODE_DPAD_LEFT, 1, repeatable = true),
            KeyDef("↑", KeyEvent.KEYCODE_DPAD_UP, 1, repeatable = true),
            KeyDef("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1, repeatable = true),
            KeyDef("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1, repeatable = true),
            KeyDef(phone, 0, 3, KIND_ACTION, action = ACTION_PHONE),
            KeyDef(hide, 0, 2, KIND_ACTION, action = ACTION_HIDE)
        )

        return listOf(functionRow, numberRow, qwertyTop, qwertyHome, qwertyBottom, controlRow)
    }

    private fun letterKey(ch: Char) =
        KeyDef(ch.toString(), KeyEvent.KEYCODE_A + (ch.code - 'A'.code), 1)

    private fun digitKey(digit: Int) =
        KeyDef(digit.toString(), KeyEvent.KEYCODE_0 + digit, 1)

    override fun onDetachedFromWindow() {
        for (r in repeats.values) handler.removeCallbacks(r)
        repeats.clear()
        pressed.clear()
        super.onDetachedFromWindow()
    }
}
