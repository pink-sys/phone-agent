package cn.wbphone.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：本工程唯一的"手和眼"。
 *
 * 它做三件事：
 *  1. 把当前窗口的无障碍树压成紧凑 JSON（桥会再渲染成 [n] 序号大纲）；
 *  2. 执行点按 / 输入 / 按键 / 滑动 / 启动 App；
 *  3. Android 11+ 时提供截图，覆盖微信、支付类这类自绘界面。
 *
 * 所有方法都在 AgentCore 的单线程执行器里被调用，天然串行——并发点按只会把界面点乱。
 */
class PhoneAccessibilityService : AccessibilityService(), DeviceApi {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 这里抛异常会让系统认定服务不可用，反复几次之后直接把它禁用——
        // 那才是用户看到的"无障碍自己关了"。所以任何一行都不能往外冒异常。
        try {
            instance = this
            AgentHost.log("无障碍服务已就绪：${deviceLabel()}")
        } catch (e: Exception) {
            AgentHost.log("无障碍服务初始化异常：${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.packageName?.let { lastPackage = it.toString() }
            }
        } catch (e: Exception) {
            // 事件监听器里崩溃同样会连累整个服务，这里只记不抛。
        }
    }

    override fun onInterrupt() = Unit

    /** 返回 true：下次还绑定时会走 onRebind，能立刻恢复实例，不用等用户重新进设置。 */
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return try {
            super.onUnbind(intent)
            true
        } catch (e: Exception) {
            true
        }
    }

    override fun onRebind(intent: Intent?) {
        try {
            super.onRebind(intent)
        } catch (e: Exception) {
        }
        instance = this
    }

    override fun onDestroy() {
        instance = null
        try {
            super.onDestroy()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- 读屏

    override fun readScreen(): JSONObject {
        val size = displaySize()
        try {
            val budget = intArrayOf(MAX_NODES)
            val tree = dumpVisibleWindows(budget)
                ?: return fail("读不到当前窗口（可能正在切换界面或屏幕已锁定）", "NO_WINDOW")
            return ok(
                "screen" to JSONObject()
                    .put("width", size[0])
                    .put("height", size[1])
                    .put("package", currentPackage()),
                "tree" to tree
            )
        } finally {
            if (Build.VERSION.SDK_INT < 33) runCatching { rootInActiveWindow?.recycle() }
        }
    }

    /** 读屏诊断日志：进 App 内日志，同时进 logcat（tag YueshuA11y），排查真机问题用。
     *  必须用 Log.i——vivo 的用户版会静默丢掉 Log.d/Log.v，用 d 等于没打。 */
    private fun dbg(msg: String) {
        AgentHost.log(msg)
        android.util.Log.i("YueshuA11y", msg)
    }

    /**
     * 读屏不能只看 rootInActiveWindow。vivo 这类 ROM 键盘弹出时会把活动窗口指到输入法上，
     * 只读活动窗口就只剩键盘、丢掉整个 App；反过来另一些机器上输入法窗口又不活动的，
     * 键盘候选条（联想词）就永远看不见——而「点一下候选词」经常是把字送进输入框
     * 最稳的一条路（微信搜索框 ACTION_SET_TEXT 报成功字却不进，候选词一点就中）。
     *
     * 所以这里把可见的应用窗口和输入法窗口**都**读进来，每个窗口包一层 role=window
     * 的包装节点（package 字段标来源），大纲层渲染成「── 包名 ──」分隔。
     * 应用窗口在前、键盘在后；键盘单独给一个小预算，防止几十个按键挤占应用内容的序号。
     * 只有一个窗口时直接返回它的树，大纲和单窗口版完全一致，不浪费 token。
     *
     * 失败语义（重要，这是踩过坑的）：
     *  - 只有「活动窗口和窗口列表**都**拿不到」（锁屏 / 服务刚重启 / 转场动画中）才算 NO_WINDOW；
     *  - 窗口在但读不出内容（vivo 启动动画的转场窗口是张空快照、界面全是自绘）时，
     *    必须回落成「空大纲」，让模型改用截图——报 NO_WINDOW 会被理解成「屏幕被锁」，
     *    模型就开始按 Home、重启 App，把轮次烧光。旧版正是这样兜底的，别再弄丢。
     */
    private fun dumpVisibleWindows(budget: IntArray): JSONObject? {
        // 转场瞬间（启动/切换动画）活动窗口会短暂为 null。立刻报错会让模型连环误判，
        // 先做几百毫秒的小退避，绝大多数转场自己就结束了。
        var active = rootInActiveWindow
        var wins = runCatching { windows }.getOrNull().orEmpty()
        var retries = 0
        while (active == null && wins.isEmpty() && retries < 4) {
            Thread.sleep(160)
            retries++
            active = rootInActiveWindow
            wins = runCatching { windows }.getOrNull().orEmpty()
        }
        if (active == null && wins.isEmpty()) {
            dbg("读屏失败：重试 $retries 次后活动窗口与窗口列表仍均为空")
            return null
        }

        val ime = imePackage()
        val mine = packageName
        val activePkg = active?.packageName?.toString().orEmpty()
        dbg("读屏：active=${active != null} pkg=$activePkg windows=${wins.size} ime=$ime")

        // 按包名去重：活动窗口几乎总在 windows 列表里再出现一次
        val apps = LinkedHashMap<String, AccessibilityNodeInfo>()
        val keyboards = LinkedHashMap<String, AccessibilityNodeInfo>()
        var skippedSelf = false

        fun add(root: AccessibilityNodeInfo?, isImeWindow: Boolean) {
            // 悬浮窗/转场窗口随时可能被关闭，一次 IPC 调用就可能抛 Stale/DeadObject——
            // 单个窗口读不了只影响它自己，绝不能连累整次读屏。
            runCatching {
                root ?: return
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isEmpty()) return
                if (pkg == mine) { skippedSelf = true; return } // 自己的界面与悬浮卡绝不进大纲，模型会读到自己
                if (isImeWindow || (ime.isNotEmpty() && pkg == ime)) {
                    keyboards[pkg] = root
                } else if (pkg != activePkg || apps.isEmpty() && activePkg == pkg) {
                    // 活动窗口优先占位；后面 windows 列表里同包名的忽略
                    if (!apps.containsKey(pkg)) apps[pkg] = root
                }
            }
        }

        add(active, ime.isNotEmpty() && activePkg == ime)
        for (w in wins) {
            runCatching {
                val t = w.type
                if (t != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    t != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                ) return@runCatching
                add(runCatching { w.root }.getOrNull(),
                    t == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)
            }
        }

        val children = JSONArray()
        val recycled = ArrayList<AccessibilityNodeInfo>()
        for ((pkg, root) in apps) {
            dumpNode(root, 0, budget)?.let {
                children.put(
                    JSONObject().put("role", "window").put("package", pkg)
                        .put("children", JSONArray().put(it))
                )
            }
            if (Build.VERSION.SDK_INT < 33) recycled.add(root)
        }
        val imeBudget = intArrayOf(IME_OUTLINE_NODES)
        for ((pkg, root) in keyboards) {
            dumpNode(root, 0, imeBudget)?.let {
                children.put(
                    JSONObject().put("role", "window").put("package", "$pkg(键盘)")
                        .put("children", JSONArray().put(it))
                )
            }
            if (Build.VERSION.SDK_INT < 33) recycled.add(root)
        }
        if (Build.VERSION.SDK_INT < 33) for (r in recycled) runCatching { r.recycle() }

        dbg("读屏结果：apps=${apps.size} keyboards=${keyboards.size} children=${children.length()} skippedSelf=$skippedSelf")

        // 一个窗口都没读到内容：回落成空树而不是报错（理由见方法注释）。
        // 只有月溯自己在前台时，给模型一句明确标记，它就知道「此刻看见的是自己」。
        if (children.length() == 0) {
            if (skippedSelf) {
                return JSONObject().put("role", "window").put("package", "$mine(月溯自己)")
                    .put("children", JSONArray())
            }
            return JSONObject().put("role", "View").put("children", JSONArray())
        }
        // 单窗口：去掉包装，输出和旧版单树完全一致
        if (children.length() == 1) {
            val only = children.optJSONObject(0)?.optJSONArray("children")?.optJSONObject(0)
            if (only != null) return only
        }
        return JSONObject().put("role", "MultiWindow").put("children", children)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): JSONObject? {
        if (depth > MAX_DEPTH || budget[0] <= 0) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val clickable = node.isClickable
        val editable = node.isEditable || node.className?.toString()?.endsWith("EditText") == true
        val scrollable = node.isScrollable
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dumpNode(child, if (interesting) depth + 1 else depth, budget)?.let { children.put(it) }
            } finally {
                if (Build.VERSION.SDK_INT < 33) runCatching { child.recycle() }
            }
        }

        // 既没有文本也不能操作、又没有可读子节点的容器，对模型没有价值。
        if (!interesting && children.length() == 0) return null
        if (interesting) budget[0] -= 1

        return JSONObject()
            .put("role", shortRole(node))
            .put("text", text)
            .put("desc", desc)
            .put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
            .put("clickable", clickable)
            .put("editable", editable)
            .put("scrollable", scrollable)
            // 这两个标记只给审批闸门用（判断焦点是不是落在密码框上），不进给模型看的大纲
            .put("password", isPasswordField(node))
            .put("focused", node.isFocused)
            .put("children", children)
    }

    private fun isPasswordField(node: AccessibilityNodeInfo): Boolean = try {
        node.isPassword
    } catch (e: Exception) {
        false
    }

    private fun shortRole(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString() ?: return "View"
        return cls.substringAfterLast('.')
    }

    // ---------------------------------------------------------------- 点按

    override fun tap(x: Int, y: Int): JSONObject {
        val size = displaySize()
        if (x < 0 || y < 0 || x > size[0] || y > size[1]) {
            return fail("坐标 ($x, $y) 超出屏幕 ${size[0]}×${size[1]}", "OUT_OF_BOUNDS")
        }
        // 小窗保护：前台应用悬浮成小窗时，点在窗口外面（黑边/桌面/底下露出的月溯）
        // 会把小窗直接最小化——界面「自己跳回月溯」就是这么来的。这里拦下并教模型
        // 先把小窗放大。键盘窗口是另一扇窗，点键盘上的按键必须放行。
        smallWindowBounds(size)?.let { small ->
            val insideIme = imeWindowBounds()?.contains(x, y) == true
            if (!small.contains(x, y) && !insideIme) {
                return fail(
                    "目标应用在小窗里，($x, $y) 在小窗外面——点小窗外面会把它最小化，" +
                        "界面会跳回别的应用。先双击小窗顶部横条（或点小窗右上角的放大按钮）" +
                        "把它放大到全屏，再继续操作。",
                    "SMALL_WINDOW_OUTSIDE",
                )
            }
        }
        // 表面闸门：前台是月溯自己的对话页时，任何点按都是在点自己的聊天界面
        //（可能误触发送、停止按钮）。模型要点的目标永远在别的应用里。
        if (ownMainWindowVisible()) {
            return fail(
                "前台是月溯自己的对话页，点按会落到自己的聊天界面上。" +
                    "先用 phone_launch_app 打开目标应用，再按它当前屏幕的坐标点。",
                "WRONG_SURFACE",
            )
        }

        val root = rootInActiveWindow
        if (root != null) {
            val exact = findClickable(root, x, y)
            // 落点差几十像素、落在输入框边缘外面时，吸附到最近的输入框：
            // 模型估坐标的误差大多在 60px 内，这一步能把「点空了」变成「点上了」。
            // 只吸附输入框，不吸附按钮——空白处一点就误触旁边的按钮比点空更糟。
            val target = exact ?: findNearestEditable(root, x, y, SNAP_RADIUS)
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                val label = labelOf(target)
                val snapped = exact == null
                android.util.Log.i(
                    "YueshuInput",
                    "tap: ($x,$y) node-click '$label' snap=$snapped pkg=${target.packageName}",
                )
                Thread.sleep(180)
                return if (snapped) {
                    ok(
                        "activated" to label,
                        "navigatedTo" to currentPackage(),
                        "note" to "落点偏了一点，已吸附到最近的「$label」中心。",
                    )
                } else {
                    ok("activated" to label, "navigatedTo" to currentPackage())
                }
            }
        }

        // 无障碍拿不到可点节点（自绘界面 / Canvas 绘制），退回手势。
        // 100ms：太快（60ms）的轻点会被部分 WebView 当成误触丢掉。
        val tapped = dispatchPath(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 100)
        Thread.sleep(220)
        android.util.Log.i("YueshuInput", "tap: ($x,$y) gesture=$tapped cur=${currentPackage()}")
        return if (tapped) {
            ok("navigatedTo" to currentPackage())
        } else {
            fail("点按没有被系统接受，可能屏幕已锁定或界面正在切换", "GESTURE_REJECTED")
        }
    }

    /** 从坐标点命中的节点往上找第一个可点击祖先，找不到再往子树上找。 */
    private fun findClickable(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findClickable(child, x, y)
            if (hit != null) return hit
            if (Build.VERSION.SDK_INT < 33) runCatching { child.recycle() }
        }
        return if (node.isClickable) node else null
    }

    /**
     * 找离 (x,y) 最近、且在 [radius] 像素内的输入框。
     *
     * 用途：模型报的坐标常常差几十像素（截图估位置的通病），落点正好在输入框下沿外面，
     * 点按就落空、键盘也不弹。输入框是「点哪里都会聚焦」的控件，吸附过去没有副作用，
     * 比让模型换坐标重试快得多。按钮不能这样做——误触旁边真正会生效的按钮代价太高。
     */
    private fun findNearestEditable(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        radius: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDist = radius * radius
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (node.isEditable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val dx = r.centerX() - x
                    val dy = r.centerY() - y
                    val d = dx * dx + dy * dy
                    if (d <= bestDist) {
                        bestDist = d
                        best = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                if (Build.VERSION.SDK_INT < 33) runCatching { child.recycle() }
            }
        }
        walk(root, 0)
        return best
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = (node.text?.toString() ?: "").trim()
        if (text.isNotEmpty()) return text.take(24)
        val desc = (node.contentDescription?.toString() ?: "").trim()
        if (desc.isNotEmpty()) return desc.take(24)
        return shortRole(node)
    }

    // ---------------------------------------------------------------- 输入

    override fun inputText(text: String, submit: Boolean): JSONObject {
        // 微信整棵控件树对无障碍隐藏，ACTION_SET_TEXT / 节点粘贴在这里都是「返回 true 但字不进」。
        // 路由判断不能用 currentPackage()：vivo 键盘弹出时 rootInActiveWindow 会被
        // 指到输入法上，currentPackage() 返回的是输入法包名，微信路由就永远不命中——
        // 而键盘弹出恰恰是最需要输入的时刻。改用「应用窗口列表里有没有微信」。
        //
        // 微信的输入拆成三个独立工具，模型每步都对着截图来：
        // 这里只负责写剪贴板，长按是 phone_long_press，点「粘贴」是 phone_tap_at。
        if (isWechatForeground()) {
            android.util.Log.i(
                "YueshuInput",
                "input: route=wechat text='${text.take(8)}' root=" +
                    runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() +
                    " cur=${currentPackage()} own=${ownMainWindowVisible()}"
            )
            // 用户拍板的分工：phone_input 只负责「写入」，长按和点粘贴由模型用
            // phone_long_press / phone_tap_at 独立完成——每一步都能对着截图来。
            val now = System.currentTimeMillis()
            val already = text == lastClipboardText && now - lastClipboardAt < 120_000
            if (!already && !setClipboard(text)) return fail("无法写系统剪贴板", "CLIPBOARD_FAIL")
            android.util.Log.i("YueshuInput", "input: clipboard written=${!already}")
            return ok(
                "value" to text,
                "navigatedTo" to currentPackage(),
                "note" to (if (already) "剪贴板已有这段文字，未重复写。" else "文字已写入剪贴板。") +
                    "下一步：phone_long_press(x=输入框中心x, y=输入框中心y) 长按唤出菜单，" +
                    "菜单出现后 phone_screenshot，用 phone_tap_at 点「粘贴」" +
                    "（通常在长按点下方约 216px、x≈110 的第一项）。",
            )
        }

        android.util.Log.i(
            "YueshuInput",
            "input: route=normal text='${text.take(8)}' root=" +
                runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() +
                " cur=${currentPackage()} ime=${imePackage()} own=${ownMainWindowVisible()}"
        )
        var target = findInputTarget()
        if (target != null) {
            val tr = Rect()
            target.getBoundsInScreen(tr)
            android.util.Log.i(
                "YueshuInput",
                "input: target pkg=${target.packageName} cls=${target.className} bounds=$tr"
            )
            // 第一轮：直接写（大多数输入框够用）
            if (writeAndVerify(target, text)) return finishInput(text, submit, null)

            // 第二轮：页面刚跳过来时，第一轮往往写进了**旧页面**的输入框（返回 true，
            // 但新页面是空的）。等它稳一下、重新取焦点、点一次，再写一遍。
            Thread.sleep(SETTLE_WAIT_MS)
            target = findInputTarget() ?: target
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(FOCUS_WAIT_MS)
            target = findInputTarget() ?: target
            if (writeAndVerify(target, text)) return finishInput(text, submit, "点一下输入框后重写")

            // 第三轮：自绘/忽略 ACTION_SET_TEXT 的输入框，走剪贴板 + 节点粘贴
            if (pasteViaClipboard(text) && stableLanded(text)) {
                return finishInput(text, submit, "通过剪贴板写入")
            }
        }

        // 根本找不到输入框（界面上没有可写目标）：没有 SET_TEXT 的落点，直接停手。
        // 微信这类读不到控件树的应用不会走到这里（前面 isWechatForeground 已分流），
        // 但别的自绘应用可能——告诉模型改走三步粘贴流程。
        if (target == null) {
            return fail(
                "界面上找不到可写的输入框。改用三步粘贴流程：" +
                    "phone_input(文字) 写剪贴板 → phone_long_press(输入框中心坐标) 长按唤出菜单 → " +
                    "phone_tap_at 点菜单里的「粘贴」。",
                "INPUT_NOT_TAKEN",
            )
        }

        // 三次都没落进去：留一条能查的现场（logcat 标签 YueshuInput）
        android.util.Log.w(
            "YueshuInput",
            "INPUT_NOT_TAKEN pkg=${target.packageName} cls=${target.className} " +
                "focused=${target.isFocused} editable=${target.isEditable} " +
                "password=${target.isPassword} got='${runCatching { target.text }.getOrNull()}' " +
                "want='${text.take(12)}'",
        )
        return fail(
            "输入没有落进输入框：界面上仍然看不到「${text.take(12)}」。" +
                "先 phone_tap 点一下输入框、确认光标或键盘出现，再调 phone_input；" +
                "两次还不行就停手，直接告诉用户这一步需要他自己输入，不要再重复同样的动作。",
            "INPUT_NOT_TAKEN",
        )
    }

    /** 收尾：需要的话按回车/发送，然后组装结果。 */
    private fun finishInput(text: String, submit: Boolean, note: String?): JSONObject {
        var enterOk = true
        if (submit) enterOk = pressEnter()
        val extra = if (submit && !enterOk) {
            (note?.plus("；") ?: "") + "文本已输入，但回车没被接受，可尝试 phone_tap 发送按钮"
        } else {
            note
        }
        return ok(
            "value" to text, "verified" to true,
            "navigatedTo" to currentPackage(),
            "note" to extra, "submitted" to enterOk,
        )
    }


    /**
     * 找可输入的控件。不能只信 rootInActiveWindow：
     *  - 部分 ROM（这台 vivo 就是）在键盘弹出时会把活动窗口指到输入法上，整棵树里没有输入框；
     *  - 我们自己的审批悬浮窗也在这个列表里，翻窗时要跳过；
     *  - 别的悬浮窗（vivo 小V写作气泡这类）的 findFocus 会吐出**没有类名、不可编辑**的
     *    垃圾节点——不验货直接用，微信真正的搜索框就永远轮不上，INPUT_NOT_TAKEN 全是它。
     * 先看活动窗口，找不到再翻其余窗口；每个候选都必须过 [usableEditable]。
     */
    private fun findInputTarget(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString()
        if (active != null && activePkg != imePackage() && activePkg != packageName) {
            usableEditable(active.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))?.let { return it }
            findEditable(active)?.let { return it }
        }
        val wins = runCatching { windows }.getOrNull() ?: return null
        val ime = imePackage()
        for (w in wins) {
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            val rootPkg = root.packageName?.toString()
            if (rootPkg == packageName) continue // 自己的悬浮窗
            // 输入法的悬浮面板（vivo 悬浮键盘等）不一定挂 TYPE_INPUT_METHOD，
            // 会以普通应用窗口身份混进来——往它的内部编辑框写字等于写进键盘自己的
            // 内存里，还可能连带触发键盘的剪贴板/候选面板，界面整个乱套。
            if (rootPkg != null && rootPkg == ime) continue
            usableEditable(runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())
                ?.let { return it }
            findEditable(root)?.let { return it }
        }
        return null
    }

    /** 输入候选必须验货：可编辑，或至少是个 EditText。悬浮窗的 findFocus 什么都敢给。 */
    private fun usableEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val ok = runCatching {
            n.isEditable || n.className?.toString()?.endsWith("EditText") == true
        }.getOrDefault(false)
        return if (ok) n else null
    }

    private fun imePackage(): String =
        runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )?.substringBefore("/").orEmpty()
        }.getOrDefault("")

    /** 写一次并回读验证；[AccessibilityNodeInfo.ACTION_SET_TEXT] 的返回值不可信。 */
    private fun writeAndVerify(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(FOCUS_WAIT_MS)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // 密码框本来就读不到内容，回读一定为空——这里必须放过，
        // 否则「密码输不进去」这种假故障会让模型反复重输。
        if (node.isPassword) {
            Thread.sleep(VERIFY_WAIT_MS)
            return true
        }
        return stableLanded(text)
    }

    /**
     * 回读两次：`performAction` 返回 true 只代表请求被接受，不代表字真进去了。
     * 隔一段再读一次，是为了排掉「写进了正在退场的旧页面」这种假成功。
     */
    private fun stableLanded(text: String): Boolean {
        Thread.sleep(VERIFY_WAIT_MS)
        if (!landed(text)) return false
        Thread.sleep(SETTLE_WAIT_MS)
        return landed(text)
    }

    /** 回读当前焦点输入框，确认 [text] 真的进去了。 */
    private fun landed(text: String): Boolean {
        val node = findInputTarget() ?: return false
        val got = node.text?.toString().orEmpty().replace(" ", "")
        val want = text.replace(" ", "")
        if (got.isEmpty() || want.isEmpty()) return false
        // 输入法联想态下可能只落了前缀，长文本按前 6 字判定，避免把成功当失败
        return got.contains(want) || (want.length >= 6 && got.contains(want.take(6)))
    }

    private fun pasteViaClipboard(text: String): Boolean {
        if (!setClipboard(text)) return false

        val focused = findInputTarget() ?: return false
        if (!focused.isFocused) focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // 先清空：粘进一个已经半截有字的框会拼成「小小罗」，比没贴更糟
        val clear = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear)
        Thread.sleep(60)
        return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** 写剪贴板。Android 10+ 限制的是「读」，后台「写」始终允许——这是微信这种
     *  自保护应用（控件树整体不可读）唯一的输入通道的入口。 */
    private fun setClipboard(text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("wb-phone", text))
            } catch (_: Exception) {
                // 某些系统在后台禁止写剪贴板，交给下面判定
            }
            done.countDown()
        }
        done.await(1500, TimeUnit.MILLISECONDS)
        // 记账：实现「同一段文字只写一次」的去重
        lastClipboardText = text
        lastClipboardAt = System.currentTimeMillis()
        return true
    }

    // ---------------------------------------------------------------- 长按

    /**
     * 在 (x,y) 坐标长按。微信这类读不到控件树的应用靠它唤出「粘贴」菜单——
     * 写入（phone_input）、长按（本工具）、点粘贴（phone_tap_at）各自独立，
     * 模型每一步都对着截图来，不再做成一锤子买卖的内部固定流程。
     */
    override fun longPress(x: Int, y: Int, durationMs: Int): JSONObject {
        val size = displaySize()
        if (x < 0 || y < 0 || x > size[0] || y > size[1]) {
            return fail("坐标 ($x, $y) 超出屏幕 ${size[0]}×${size[1]}", "OUT_OF_BOUNDS")
        }
        android.util.Log.i("YueshuInput", "longPress: ($x,$y) ${durationMs}ms")
        val hold = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        if (!dispatchPath(hold, durationMs.toLong().coerceIn(500, 2000))) {
            return fail("长按手势没有被系统接受（屏幕可能已锁定或正在切换界面）", "GESTURE_REJECTED")
        }
        Thread.sleep(500)
        return ok(
            "x" to x, "y" to y, "duration" to durationMs,
            "navigatedTo" to currentPackage(),
            "note" to "长按完成。如果菜单出现了（输入框下方一行小字，第一项是「粘贴」），" +
                "phone_screenshot 后用 phone_tap_at 按截图坐标点「粘贴」。",
        )
    }

    /**
     * 月溯自己的主界面（对话页）是否可见。粘贴/点按的坐标手势是盲的：
     * 前台切回了自己时，长按和自动点「粘贴」都会落在自己的对话页上——
     * 字进了月溯的输入框，模型还以为贴进了微信。在动手前拦下。
     */
    private fun ownMainWindowVisible(): Boolean {
        val wins = runCatching { windows }.getOrNull().orEmpty()
        val size = displaySize()
        for (w in wins) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (runCatching { w.root?.packageName?.toString() }.getOrNull() != packageName) continue
            val r = Rect()
            if (runCatching { w.getBoundsInScreen(r) }.isFailure) continue
            if (r.width() >= size[0] * 6 / 10) return true
        }
        return false
    }

    /**
     * 微信是否在前台。不能只看 rootInActiveWindow 的包名（键盘弹出时被 vivo 指到
     * 输入法上，currentPackage() 会返回输入法包名），要翻应用窗口列表——微信的窗口
     * 节点树是空的，但窗口级 packageName 读得到（readScreen 里 apps=1 就是靠它）。
     */
    private fun isWechatForeground(): Boolean {
        if (runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() == WECHAT_PKG) {
            return true
        }
        val wins = runCatching { windows }.getOrNull().orEmpty()
        for (w in wins) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (runCatching { w.root?.packageName?.toString() }.getOrNull() == WECHAT_PKG) return true
        }
        return false
    }

    /** 所有应用窗口里面积最大的那个的屏幕矩形（= 前台应用的窗口）。读不到返回 null。 */
    private fun foregroundWindowBounds(): Rect? {
        val wins = runCatching { windows }.getOrNull().orEmpty()
        var best: Rect? = null
        var bestArea = 0
        for (w in wins) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = Rect()
            if (runCatching { w.getBoundsInScreen(r) }.isFailure) continue
            val area = r.width() * r.height()
            if (area > bestArea) {
                bestArea = area
                best = r
            }
        }
        return best
    }

    /**
     * 前台应用是悬浮小窗时返回窗口矩形，全屏/分屏返回 null。
     * 小窗 = 宽和高都明显小于屏幕（分屏宽度是全宽，不算小窗）。
     */
    private fun smallWindowBounds(size: IntArray): Rect? {
        val r = foregroundWindowBounds() ?: return null
        return if (r.width() < size[0] * 9 / 10 && r.height() < size[1] * 9 / 10) r else null
    }

    /** 输入法键盘窗口的屏幕矩形，键盘没弹出返回 null。 */
    private fun imeWindowBounds(): Rect? {
        val wins = runCatching { windows }.getOrNull().orEmpty()
        for (w in wins) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = Rect()
            if (runCatching { w.getBoundsInScreen(r) }.isFailure) continue
            return r
        }
        return null
    }

    /**
     * 把悬浮小窗放大回全屏：双击小窗顶部横条的中央（vivo 官方手势：「双击小窗顶部横条，
     * 可以在小窗和全屏之间快速切换」）。横条是小窗顶部约 40~56px 的系统装饰条，
     * 不占应用内容区，双击它不会误点应用本身。
     * 放大成功返回 true（调用方自己复查窗口尺寸确认）。
     */
    private fun expandSmallWindow(win: Rect): Boolean {
        val cx = win.centerX().toFloat()
        val barY = (win.top + 26).toFloat()
        if (!dispatchPath(Path().apply { moveTo(cx, barY) }, 70)) return false
        Thread.sleep(130)
        dispatchPath(Path().apply { moveTo(cx, barY) }, 70)
        Thread.sleep(800)
        android.util.Log.i("YueshuInput", "expand: dbl-tap=($cx,$barY)")
        return true
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditable(child)
            if (hit != null) return hit
            if (Build.VERSION.SDK_INT < 33) runCatching { child.recycle() }
        }
        return null
    }

    // ---------------------------------------------------------------- 按键 / 滑动 / 启动

    override fun pressKey(key: String): JSONObject {
        val handled = when (key.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "enter" -> pressEnter()
            else -> false
        }
        if (!handled) return fail("系统没有接受按键 $key", "KEY_REJECTED")
        Thread.sleep(250)
        return ok("navigatedTo" to currentPackage())
    }

    private fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) {
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject {
        val duration = durationMs.coerceIn(80, 3000).toLong()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val done = dispatchPath(path, duration)
        Thread.sleep(260)
        return if (done) {
            ok("scrolled" to true, "navigatedTo" to currentPackage())
        } else {
            fail("滑动没有被系统接受", "GESTURE_REJECTED")
        }
    }

    override fun launchApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return fail("缺少包名", "BAD_REQUEST")
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return fail("找不到包 $pkg，可能没安装或被禁用${similarPkgs(pkg)}", "NO_SUCH_PACKAGE")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            Thread.sleep(600)
            // vivo 会把「后台拉起」的应用收成悬浮小窗（不是全屏）。小窗里输入框窄、
            // 粘贴菜单坐标全变、点窗外还会把窗口最小化——之前微信操作的连环失败都源自它。
            // 启动完检测一次，是小窗就直接双击顶部横条放大回全屏。
            val size = displaySize()
            var note = ""
            val small = smallWindowBounds(size)
            if (small != null) {
                android.util.Log.i("YueshuInput", "launch: small window $small, expanding")
                expandSmallWindow(small)
                val after = smallWindowBounds(size)
                note = if (after == null) {
                    "；应用启动成了悬浮小窗，已自动双击顶部横条放大回全屏，重新截图确认"
                } else {
                    "；应用以悬浮小窗打开且自动放大没成功——先 phone_screenshot 看一眼，" +
                        "双击小窗顶部横条（或点小窗右上角的放大按钮）把它放大到全屏再操作，" +
                        "小窗里点窗口外面会把它最小化"
                }
            }
            ok("package" to pkg, "navigatedTo" to currentPackage(), "note" to note)
        } catch (e: Exception) {
            fail("启动失败：${e.message}", "LAUNCH_FAILED")
        }
    }

    /**
     * 启动失败时给几个真实存在的相近包名。
     *
     * 模型猜包名（com.chaoxing.mooc / com.chaoxing.mobile / com.chaoxing.Mooc…）
     * 一轮只能猜一个，猜 5 次就烧掉 5 轮。把相近的直接列在报错里，
     * 它下一轮就有正确答案可点。
     */
    private fun similarPkgs(pkg: String): String {
        val pm = packageManager
        val installed = pm.queryIntentActivities(
            Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.activityInfo?.packageName }.distinct()
        val head = pkg.split('.').take(2).joinToString(".")
        val tail = pkg.substringAfterLast('.', pkg)
        val near = installed.filter { it != pkg && (it.startsWith(head) || it.contains(tail, ignoreCase = true)) }
        return if (near.isEmpty()) "。可尝试用应用名（如「学习通」）而不是包名重试"
        else "。已安装的相近包：" + near.take(5).joinToString("、")
    }

    // ---------------------------------------------------------------- 截图

    override fun screenshot(x: Int, y: Int, w: Int, h: Int): JSONObject {
        if (Build.VERSION.SDK_INT < 30) {
            return fail("Android 11 以下的无障碍接口不支持截图，请升级系统，或只用读屏与点按", "UNSUPPORTED")
        }
        val latch = CountDownLatch(1)
        var result: JSONObject? = null

        mainHandler.post {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            result = try {
                                val buffer = screenshot.hardwareBuffer
                                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                buffer.close()
                                if (bitmap == null) {
                                    fail("截图解码失败", "SCREENSHOT_DECODE")
                                } else {
                                    // 局部截图：只取模型点名的那一块，后面放大到 1080 宽再发回去。
                                    // 粘贴菜单那种十几像素的小字，整屏图上是糊的，局部放大才看得清。
                                    var rx = 0
                                    var ry = 0
                                    var rw = bitmap.width
                                    var rh = bitmap.height
                                    if (w > 0 && h > 0) {
                                        rx = x.coerceIn(0, bitmap.width - 1)
                                        ry = y.coerceIn(0, bitmap.height - 1)
                                        rw = w.coerceAtMost(bitmap.width - rx)
                                        rh = h.coerceAtMost(bitmap.height - ry)
                                    }
                                    val cropped = if (rx != 0 || ry != 0 ||
                                        rw != bitmap.width || rh != bitmap.height
                                    ) {
                                        Bitmap.createBitmap(bitmap, rx, ry, rw, rh)
                                    } else {
                                        bitmap
                                    }
                                    val scaled = scaleToWidth(cropped, SCREENSHOT_WIDTH)
                                    val ruled = drawRulers(scaled)
                                    val shotW = ruled.width
                                    val shotH = ruled.height
                                    val out = ByteArrayOutputStream()
                                    // JPEG 而不是 PNG：token 成本只取决于像素尺寸，与格式无关，
                                    // 但 JPEG 在同等可读性下体积小得多，上传到模型快一个量级。
                                    ruled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                                    val bytes = out.toByteArray()
                                    // recycle 之后访问 width/height 会抛异常，所以尺寸先存下来
                                    if (ruled !== scaled) ruled.recycle()
                                    if (scaled !== cropped) scaled.recycle()
                                    if (cropped !== bitmap) cropped.recycle()
                                    bitmap.recycle()
                                    ok(
                                        "imageBase64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                                        "mime" to "image/jpeg",
                                        "bytes" to bytes.size,
                                        "width" to shotW,
                                        "height" to shotH,
                                        // 这张图对应屏幕上的哪一块：[rx,ry] 起，rw×rh 大小
                                        "originX" to rx,
                                        "originY" to ry,
                                        "regionWidth" to rw,
                                        "regionHeight" to rh,
                                    )
                                }
                            } catch (e: Exception) {
                                fail("截图处理失败：${e.message}", "SCREENSHOT_FAILED")
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            result = fail("截图失败，系统错误码 $errorCode", "SCREENSHOT_FAILED")
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                result = fail("发起截图失败：${e.message}", "SCREENSHOT_FAILED")
                latch.countDown()
            }
        }

        latch.await(8, TimeUnit.SECONDS)
        return result ?: fail("截图超时", "SCREENSHOT_TIMEOUT")
    }

    /**
     * 在截图上叠加坐标网格和刻度数字。
     *
     * 为什么必须画：模型报的是「图上像素」，但它对绝对像素的估算是粗的——一张 1080×2400 的图，
     * 凭感觉估 y 误差几十上百像素很正常，长按/点按的落点就偏了。图上有了 120px 的格线、
     * 240px 一个刻度数字，模型可以「读刻度 + 数格子」，误差能压到十几像素。
     * 而且数字是画在图里的，无论中转方把图再缩成多大都不会失效——比「在文字里告诉模型
     * 图片尺寸」可靠得多。
     */
    private fun drawRulers(src: Bitmap): Bitmap {
        val out = try {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            null
        } ?: return src
        try {
            val canvas = Canvas(out)
            val line = Paint().apply {
                color = 0x59FF8A00
                strokeWidth = 1f
                isAntiAlias = false
            }
            var gx = GRID_STEP
            while (gx < out.width) {
                canvas.drawLine(gx.toFloat(), 0f, gx.toFloat(), out.height.toFloat(), line)
                gx += GRID_STEP
            }
            var gy = GRID_STEP
            while (gy < out.height) {
                canvas.drawLine(0f, gy.toFloat(), out.width.toFloat(), gy.toFloat(), line)
                gy += GRID_STEP
            }
            val label = Paint().apply {
                color = 0xFF102030.toInt()
                textSize = 17f
                isAntiAlias = true
            }
            val plate = Paint().apply { color = 0xD9FFFFFF.toInt() }
            var lx = GRID_STEP * 2
            while (lx < out.width) {
                val t = lx.toString()
                val tw = label.measureText(t)
                canvas.drawRect(lx + 1f, 1f, lx + 5f + tw, 21f, plate)
                canvas.drawText(t, lx + 3f, 17f, label)
                lx += GRID_STEP * 2
            }
            var ly = GRID_STEP * 2
            while (ly < out.height) {
                val t = ly.toString()
                val tw = label.measureText(t)
                canvas.drawRect(1f, ly + 1f, 5f + tw, ly + 21f, plate)
                canvas.drawText(t, 3f, ly + 16f, label)
                ly += GRID_STEP * 2
            }
        } catch (e: Exception) {
            return src
        }
        return out
    }

    private fun scaleToWidth(bitmap: Bitmap, target: Int): Bitmap {
        if (bitmap.width <= target) return bitmap
        val ratio = target.toFloat() / bitmap.width
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, target, height, true)
    }

    // ---------------------------------------------------------------- 手势基础设施

    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        val latch = CountDownLatch(1)
        val accepted = booleanArrayOf(false)

        // 派手势前先在调用线程同步标记「手势进行中」：胶囊的触摸监听靠它吞掉误触，
        // 不依赖窗口标志的异步生效（updateViewLayout 要等下一帧才送到 WMS，
        // 注入的触摸可能抢在标志生效前到达——真机日志已实锤过这条竞态）。
        FloatStatus.gestureActive = true
        mainHandler.post {
            // 派手势前把状态胶囊对触摸关掉：手势是屏幕级注入，胶囊浮在屏幕上，
            // 撞上输入框/发送键这类目标控件就会把手势截走——点一下直接跳回月溯对话页。
            // setTouchBlocked 在主线程上是同步执行的，标志先于 dispatchGesture 发出。
            FloatStatus.setTouchBlocked(true)
            try {
                val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        accepted[0] = true
                        FloatStatus.setTouchBlocked(false)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        accepted[0] = false
                        FloatStatus.setTouchBlocked(false)
                        latch.countDown()
                    }
                }, null)
                if (!ok) {
                    FloatStatus.setTouchBlocked(false)
                    latch.countDown()
                }
            } catch (e: Exception) {
                FloatStatus.setTouchBlocked(false)
                latch.countDown()
            }
        }

        latch.await(durationMs + 2500, TimeUnit.MILLISECONDS)
        // 超时兜底：回调没来也要恢复胶囊可点，不能让它永远吞触摸
        mainHandler.post { FloatStatus.setTouchBlocked(false) }
        FloatStatus.gestureActive = false
        return accepted[0]
    }

    // ---------------------------------------------------------------- 基础信息

    override fun displaySize(): IntArray {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val wm = getSystemService(WindowManager::class.java)
                val bounds = wm.currentWindowMetrics.bounds
                return intArrayOf(bounds.width(), bounds.height())
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            return intArrayOf(metrics.widthPixels, metrics.heightPixels)
        } catch (e: Exception) {
            // 拿不到真实尺寸时退回资源里的尺寸。比抛异常导致整个服务不可用强得多。
            val m = resources.displayMetrics
            return intArrayOf(m.widthPixels, m.heightPixels)
        }
    }

    override fun deviceLabel(): String =
        "${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    /** 剪贴板去重：同一段文字短时间内只写一次（Android 10 后台读不了剪贴板，只能自己记账）。 */
    private var lastClipboardText = ""
    private var lastClipboardAt = 0L

    private fun currentPackage(): String {
        val fromRoot = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        // 审批悬浮卡属于本应用，偶尔会让 rootInActiveWindow 指向月溯自己。
        // 照单全收的话 Agent 会判定「用户离开了目标应用」，又去重开一遍——
        // 正是悬浮审批要消灭的那个循环，这里兜住。
        if (fromRoot != null && fromRoot != packageName) return fromRoot
        return lastPackage.ifEmpty { fromRoot ?: "" }
    }

    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set

        /** 微信：整棵控件树对无障碍隐藏，ACTION_SET_TEXT 没有落点，输入只能走
         *  「剪贴板 → 长按 → 点粘贴」的三步坐标操作。 */
        private const val WECHAT_PKG = "com.tencent.mm"

        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 900

        /** 键盘窗口在大纲里允许占用的序号数。候选条在树的最前面，24 个足够覆盖；
         *  限制它是不让几十个按键把应用窗口的序号挤掉。 */
        private const val IME_OUTLINE_NODES = 24

        /** 点下去到焦点真正落到输入框的等待。 */
        private const val FOCUS_WAIT_MS = 300L

        /** 写完之后回读的等待（太短会读到写之前的快照）。 */
        private const val VERIFY_WAIT_MS = 180L

        /** 界面跳转的稳定时间：微信搜索这类页面点一下会整页切换。 */
        private const val SETTLE_WAIT_MS = 420L

        /**
         * 截图宽度。实测 1080 宽整屏约 939 tokens，720 宽约 735，320 宽约 194：
         * 每像素成本随尺寸增大反而下降，所以缩太小不划算——图小了模型看不清小字，
         * 反而要多截几张。1080 是「一眼看清界面」和「不至于太贵」的平衡点。
         */
        private const val SCREENSHOT_WIDTH = 1080

        /** 截图上的坐标网格间距（图片像素）。120 约等于 1080 宽屏的 1/9，数格子不容易数错。 */
        private const val GRID_STEP = 120

        /** 落点没命中节点时，向最近的输入框吸附的最大距离（屏幕像素）。 */
        private const val SNAP_RADIUS = 56

        /** JPEG 质量。75 对界面文字仍然清晰，体积约为 PNG 的三分之一。 */
        private const val JPEG_QUALITY = 75
    }
}
