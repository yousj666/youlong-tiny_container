// DisplayKeyboard.kt -- This file is part of tiny_container.
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

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import com.fct.tc4.R
import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.shared.termux.extrakeys.ExtraKeysView
import kotlin.math.roundToInt

/**
 * 「电脑键盘模式」的接管器。
 *
 * 图形界面是由容器内置的第三方组件（AVNC 的 `VncActivity`、Termux:X11 的 `MainActivity`）
 * 提供的，我们无法继承它们，因此这里通过 [Application.ActivityLifecycleCallbacks]
 * 在界面进入前台后把它们的「键盘按钮」接管掉：
 *
 *  - AVNC（VNC 显示方式）：点工具栏里的键盘按钮弹出选择框，让用户在「电脑键盘」和
 *    「手机键盘」之间二选一；长按该按钮则直接铺出「电脑键盘」；
 *  - Termux:X11 / Termux Desktop：点键盘按钮（或我们挂的悬浮键盘按钮）先弹出一个选择框，
 *    让用户在「电脑键盘」和「手机键盘」之间二选一；
 *  - 选「手机键盘」= 原来的行为（系统输入法）；
 *  - 选「电脑键盘」= 在画面底部铺一屏仿电脑键盘，带 F1~F12、方向键、Ctrl/Win/Alt/Shift
 *    等修饰键，可以按出 F11（全屏）、F12（开发者工具）、Alt+F4 这类组合键。
 *
 * 因为第三方界面可能在运行中重建自己的工具栏，这里用 `OnGlobalLayoutListener`
 * 反复（带节流）确认接管是否还在，避免被它们悄悄改回去。
 */
object DisplayKeyboard : Application.ActivityLifecycleCallbacks {

    private const val TAG = "TC4KBD"
    private const val KEYBOARD_KEY_NAME = "KEYBOARD"
    private const val HOOK_THROTTLE_MS = 250L

    /**
     * 自愈轮询间隔。宿主界面（AVNC / Termux:X11）可能在任意时刻重建工具栏，
     * 并把它自己的监听器装回去，而 `OnGlobalLayoutListener` 只在布局发生变化时才触发，
     * 光靠它不一定能抢回来。所以界面回到前台后再补一条低频轮询，
     * 保证「键盘按钮」的接管不会被永久丢失。
     */
    private const val SELF_HEAL_MS = 600L

    private var installed = false

    private val attached = HashMap<Activity, Attached>()

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    // ===================== 生命周期 =====================

    override fun onActivityResumed(activity: Activity) {
        if (!isDisplayActivity(activity)) return
        val state = stateOf(activity) ?: return
        applyHooks(state)
        startSelfHealing(state)
    }

    override fun onActivityDestroyed(activity: Activity) {
        val state = attached.remove(activity) ?: return
        stopSelfHealing(state)
        runCatching { state.content.viewTreeObserver.removeOnGlobalLayoutListener(state.layoutListener) }
        state.panel?.let { panel -> runCatching { state.content.removeView(panel) } }
        state.floatingButton?.let { button -> runCatching { state.content.removeView(button) } }
        state.panel = null
        state.floatingButton = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) {
        stopSelfHealing(attached[activity])
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun isDisplayActivity(activity: Activity): Boolean =
        activity is com.gaurav.avnc.ui.vnc.VncActivity || activity is com.termux.x11.MainActivity

    /** 取得（必要时创建）某个界面实例的接管状态 */
    private fun stateOf(activity: Activity): Attached? {
        attached[activity]?.let { return it }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val state = Attached(activity, content) { applyHooks(it) }
        attached[activity] = state
        content.viewTreeObserver.addOnGlobalLayoutListener(state.layoutListener)
        return state
    }

    // ===================== 接管键盘按钮 =====================

    private fun applyHooks(state: Attached, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - state.lastHookAt < HOOK_THROTTLE_MS) return
        state.lastHookAt = now
        when (val activity = state.activity) {
            is com.gaurav.avnc.ui.vnc.VncActivity -> {
                hookVnc(activity, state)
                // AVNC 的工具栏按钮在不同版本里不一定能接管到（布局/资源差异），
                // 所以这里跟 Termux:X11 一样无条件挂一个悬浮键盘按钮，保证一定有入口。
                installFloatingButton(state)
            }
            is com.termux.x11.MainActivity -> {
                hookX11(activity, state)
                // 附加按键栏不一定会被 inflate 出来，所以悬浮按钮要无条件挂上
                installFloatingButton(state)
            }
        }
    }

    /**
     * 界面在前台期间低频复查一次接管状态。
     *
     * 代价极小（一次 `findViewById` + 两次字段赋值），但能保证宿主界面无论什么时候
     * 重建工具栏、把监听器改回它自己的实现，我们都能在 1 秒内重新接管回来。
     */
    private fun startSelfHealing(state: Attached) {
        if (state.healRunnable != null) return
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                applyHooks(state, force = true)
                handler.postDelayed(this, SELF_HEAL_MS)
            }
        }
        state.healHandler = handler
        state.healRunnable = runnable
        handler.postDelayed(runnable, SELF_HEAL_MS)
    }

    private fun stopSelfHealing(state: Attached?) {
        state ?: return
        state.healRunnable?.let { runnable -> state.healHandler?.removeCallbacks(runnable) }
        state.healRunnable = null
        state.healHandler = null
    }

    /**
     * VNC：接管工具栏里的 `keyboard_btn`。
     *
     * 点一下 = 弹「电脑键盘 / 手机键盘」二选一（AVNC 原生是直接呼出系统输入法，
     * 用户要求改成先问一句）；长按 = 直接铺出「电脑键盘」面板。
     *
     * 另外 AVNC 原本的处理器除了 `showKeyboard()` 还会顺手 `close()` 收掉工具栏抽屉
     * （见 `Toolbar.initialize$lambda$0`）。我们替换掉监听器后就少了这一步，抽屉会一直
     * 摊着不回去，所以这里补上 `closeAvncDrawer()`，保持和原生一样的手感。
     *
     * 工具栏是 AVNC 自己按需初始化的，而且可能在我们接管之后又被它重建一次
     * （监听器会被覆盖回去），所以这里每次布局过后都重新装一遍 —— `setOnClickListener`
     * 只是一次字段赋值，代价可以忽略，换来的是接管不会被悄悄丢失。
     */
    private fun hookVnc(activity: com.gaurav.avnc.ui.vnc.VncActivity, state: Attached) {
        val button = state.vncButton?.takeIf { it.isAttachedToWindow }
            ?: findVncKeyboardButton(activity)?.also { state.vncButton = it }
        if (button == null) {
            // 找不到原生按钮时静默放弃：applyHooks 里挂的悬浮按钮仍然可用
            if (!state.vncWarnLogged) {
                state.vncWarnLogged = true
                Log.w(TAG, "VNC: 未找到工具栏键盘按钮，改用悬浮按钮兜底")
            }
            return
        }
        val listener = state.vncListener
            ?: View.OnClickListener {
                closeAvncDrawer(activity)
                showChooser(activity, state)
            }.also {
                state.vncListener = it
                Log.i(TAG, "VNC: 已接管工具栏键盘按钮")
            }
        button.setOnClickListener(listener)

        // 长按 = 直接铺出「电脑键盘」面板
        val longListener = state.vncLongListener
            ?: View.OnLongClickListener {
                closeAvncDrawer(activity)
                showPanel(activity, state)
                true
            }.also { state.vncLongListener = it }
        button.setOnLongClickListener(longListener)
    }

    /**
     * 收起 AVNC 的工具栏抽屉。
     *
     * AVNC 的原生处理器在处理完键盘按钮后会调 `Toolbar.close()`；我们只换了按钮的点击
     * 监听器，拿不到那个 `Toolbar` 实例，所以退一步直接在视图树里找它内部的 `DrawerLayout`
     * 调 `closeDrawers()`。这里刻意用类名字符串 + 反射，避免依赖 AVNC 内部的类型
     * （`androidx.drawerlayout` 对我们是运行时可见、编译期不一定可见）。
     */
    private fun closeAvncDrawer(activity: Activity) {
        val drawer = findView(activity.window.decorView) {
            it.javaClass.name == "androidx.drawerlayout.widget.DrawerLayout"
        } ?: return
        runCatching { drawer.javaClass.getMethod("closeDrawers").invoke(drawer) }
    }

    /**
     * 找 AVNC 工具栏里的键盘按钮。
     *
     * AVNC 的界面我们继承不了，只能靠「找控件 + 换监听器」这条路，而它的布局/资源在
     * 不同版本里会有出入，所以这里准备了几条互相独立的查找路径，命中任意一条即可：
     *
     *  1. 反射 AVNC 自己的 `R$id` —— AGP 合并资源后会把最终 id 写进这个类，运行时读出来
     *     就是本应用资源表里真正的值，比按名字查更可靠；
     *  2. 按资源名查（依次用应用包名、AVNC 包名，最后一次传 `null` 在本应用资源表里全局搜）；
     *  3. 按 `desc_keyboard_btn` 的本地化文案比对内容描述；
     *  4. 兜底：布局里 `keyboard_btn` 就排在「小键盘」按钮（`virtual_keys_btn`）前面，
     *     取它的前一个可见 ImageButton 兄弟。
     */
    private fun findVncKeyboardButton(activity: Activity): View? {
        val decor = activity.window?.decorView ?: return null
        val res = activity.resources

        // 1) 反射 AVNC 的 R 类
        avncResId("keyboard_btn", "id")?.let { id ->
            decor.findViewById<View>(id)?.let { return it }
        }

        // 2) 按资源名查
        for (pkg in arrayOf(activity.packageName, "com.gaurav.avnc", null)) {
            val id = res.getIdentifier("keyboard_btn", "id", pkg)
            if (id != 0) decor.findViewById<View>(id)?.let { return it }
        }

        // 3) 按内容描述找
        val descId = avncResId("desc_keyboard_btn", "string")
            ?: res.getIdentifier("desc_keyboard_btn", "string", activity.packageName).takeIf { it != 0 }
        if (descId != null) {
            val desc = runCatching { activity.getString(descId) }.getOrNull()
            if (!desc.isNullOrEmpty()) {
                findView(decor) { v ->
                    v is ImageButton && v.contentDescription?.toString() == desc
                }?.let { return it }
            }
        }

        // 4) 取「小键盘」按钮的前一个兄弟
        val virtualKeysId = avncResId("virtual_keys_btn", "id")
            ?: res.getIdentifier("virtual_keys_btn", "id", activity.packageName).takeIf { it != 0 }
        val virtualKeys = virtualKeysId?.let { decor.findViewById<View>(it) }
        val parent = virtualKeys?.parent as? ViewGroup
        if (parent != null) {
            for (i in parent.indexOfChild(virtualKeys) - 1 downTo 0) {
                val sibling = parent.getChildAt(i)
                if (sibling is ImageButton && sibling.visibility == View.VISIBLE) return sibling
            }
        }
        return null
    }

    /** 读取 `com.gaurav.avnc.R$<type>` 里的字段值（合并后的真实资源 id） */
    private fun avncResId(name: String, type: String): Int? = runCatching {
        Class.forName("com.gaurav.avnc.R\$" + type)
            .getField(name)
            .getInt(null)
            .takeIf { it != 0 }
    }.getOrNull()

    /** Termux:X11 / Termux Desktop：把附加按键栏的客户端换成我们的包装类 */
    private fun hookX11(activity: com.termux.x11.MainActivity, state: Attached) {
        val view = findView(activity.window.decorView) { it is ExtraKeysView } as? ExtraKeysView ?: return
        val client = state.x11Client
            ?: X11ExtraKeysClient(activity) { showChooser(activity, state) }
                .also { state.x11Client = it }
        view.setExtraKeysViewClient(client)
    }

    /**
     * Termux:X11 的附加按键栏默认是收起的（`additionalKbdVisible` 默认为 false），
     * 也就是说那里根本没有一个看得见的键盘按钮。所以这里自己挂一个小的悬浮键盘按钮，
     * 保证 Termux:X11 / Termux Desktop 两种显示方式也有稳定的入口。
     */
    private fun installFloatingButton(state: Attached) {
        state.floatingButton?.let { existing ->
            if (existing.isAttachedToWindow) return
            // 宿主界面换了内容视图，按钮被摘掉了，重新挂回去
            runCatching { (existing.parent as? ViewGroup)?.removeView(existing) }
            runCatching { state.content.addView(existing, existing.layoutParams) }
            return
        }
        val activity = state.activity
        val size = (activity.resources.displayMetrics.density * 44f).roundToInt()
        val margin = (activity.resources.displayMetrics.density * 4f).roundToInt()
        val button = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_tc4_keyboard)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(margin, margin, margin, margin)
            background = GradientDrawable().apply {
                setColor(0xC8101826.toInt())
                cornerRadius = size / 2f
                setStroke((activity.resources.displayMetrics.density * 1f).roundToInt(), 0xB0516A8C.toInt())
            }
            alpha = 0.65f
            contentDescription = activity.getString(R.string.tc4_kbd_open_desc)
            setOnClickListener { showChooser(activity, state) }
        }
        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            marginEnd = margin
        }
        state.floatingButton = button
        state.content.addView(button, params)
    }

    private fun findView(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findView(root.getChildAt(i), match)
                if (found != null) return found
            }
        }
        return null
    }

    // ===================== 弹窗与面板 =====================

    private fun showChooser(activity: Activity, state: Attached) {
        if (activity.isFinishing || activity.isDestroyed) return
        val items = arrayOf(
            activity.getString(R.string.tc4_kbd_mode_desktop),
            activity.getString(R.string.tc4_kbd_mode_phone)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.tc4_kbd_mode_title)
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                if (which == 0) showPanel(activity, state) else switchToPhoneKeyboard(activity, state)
            }
            .setNegativeButton(R.string.tc4_btn_cancel, null)
            .show()
    }

    private fun showPanel(activity: Activity, state: Attached) {
        hideSoftKeyboard(activity)
        val existing = state.panel
        if (existing != null) {
            existing.resetTransientModifiers()
            existing.visibility = View.VISIBLE
            existing.bringToFront()
            state.floatingButton?.visibility = View.GONE
            return
        }
        val target = ActivityTarget(activity, state)
        val panel = DesktopKeyboardView(activity, target)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            panelHeightPx(activity)
        )
        params.gravity = Gravity.BOTTOM
        state.target = target
        state.panel = panel
        state.floatingButton?.visibility = View.GONE
        state.content.addView(panel, params)
        // 抬一层，避免被宿主界面里带 elevation 的控件盖住
        panel.elevation = activity.resources.displayMetrics.density * 12f
        // 有些宿主界面在视图结构变化时会把输入法又顶出来，这里补一刀
        panel.postDelayed({ if (panel.visibility == View.VISIBLE) hideSoftKeyboard(activity) }, 350L)
    }

    private fun hidePanel(state: Attached) {
        state.panel?.visibility = View.GONE
        state.floatingButton?.visibility = View.VISIBLE
    }

    /** 面板高度：屏幕高的 46%，并限制在 150~260dp 之间，保证六行按键都按得下去 */
    private fun panelHeightPx(activity: Activity): Int {
        val metrics = activity.resources.displayMetrics
        val wanted = (metrics.heightPixels * 0.46f).toInt()
        val minHeight = (metrics.density * 150f).toInt()
        val maxHeight = (metrics.density * 260f).toInt()
        return wanted.coerceIn(minHeight, maxHeight)
    }

    private fun hideSoftKeyboard(activity: Activity) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        activity.currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }

    private fun switchToPhoneKeyboard(activity: Activity, state: Attached) {
        // 先收起电脑键盘，免得和系统输入法抢地方
        hidePanel(state)
        when (activity) {
            is com.termux.x11.MainActivity ->
                com.termux.x11.MainActivity.toggleKeyboardVisibility(activity)

            is com.gaurav.avnc.ui.vnc.VncActivity -> activity.showKeyboard()
        }
    }

    // ===================== 内部类型 =====================

    private class Attached(
        val activity: Activity,
        val content: ViewGroup,
        onLayout: (Attached) -> Unit
    ) {
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { onLayout(this) }
        var panel: DesktopKeyboardView? = null
        var floatingButton: ImageButton? = null
        var target: ActivityTarget? = null
        var x11Client: X11ExtraKeysClient? = null
        var vncListener: View.OnClickListener? = null
        var vncLongListener: View.OnLongClickListener? = null
        var vncButton: View? = null
        var vncWarnLogged = false
        var lastHookAt = 0L
        var healHandler: Handler? = null
        var healRunnable: Runnable? = null
    }

    /** 把面板按键转成宿主界面的按键事件 */
    private class ActivityTarget(
        private val activity: Activity,
        private val state: Attached
    ) : KeyboardTarget {

        override fun sendKeyEvent(keyCode: Int, metaState: Int) {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            // 和真实外接键盘走同一条路：Activity.dispatchKeyEvent
            activity.dispatchKeyEvent(down)
            activity.dispatchKeyEvent(up)
        }

        override fun switchToPhoneKeyboard() = DisplayKeyboard.switchToPhoneKeyboard(activity, state)

        override fun hideDesktopKeyboard() = DisplayKeyboard.hidePanel(state)
    }

    /**
     * Termux:X11 附加按键栏的客户端包装：只拦截「键盘」键，其余按键全部原样转交给
     * Termux:X11 自己的 `mExtraKeys`，不复刻它的任何逻辑。
     */
    private class X11ExtraKeysClient(
        private val activity: com.termux.x11.MainActivity,
        private val onKeyboardKey: () -> Unit
    ) : ExtraKeysView.IExtraKeysView {

        override fun onExtraKeyButtonClick(view: View?, button: ExtraKeyButton?, btn: Button?) {
            if (button != null && KEYBOARD_KEY_NAME.equals(button.key, ignoreCase = true)) {
                onKeyboardKey()
                return
            }
            // `mExtraKeys` 一定是 Termux:X11 自己的实现，不会是我们这个包装类，无需自检
            activity.mExtraKeys?.onExtraKeyButtonClick(view, button, btn)
        }

        override fun performExtraKeyButtonHapticFeedback(
            view: View?,
            button: ExtraKeyButton?,
            btn: Button?
        ): Boolean =
            activity.mExtraKeys?.performExtraKeyButtonHapticFeedback(view, button, btn) ?: true
    }
}
