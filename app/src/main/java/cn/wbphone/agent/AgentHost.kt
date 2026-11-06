package cn.wbphone.agent

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * App 级宿主：持有登录态、一个对话会话、以及唯一的后台工作线程。
 *
 * 与之前 AgentCore 的位置相同，但职责变了——不再维护到电脑的长连接，
 * 而是管理"手机自己当引擎"所需的那几样东西：令牌、模型客户端、工具、循环。
 */
object AgentHost {

    enum class State { LOGGED_OUT, LOGGING_IN, READY, RUNNING, ERROR }

    interface Listener {
        fun onStateChanged(state: State, detail: String)
        fun onLogAppended(line: String)
    }

    private const val MAX_LOG_LINES = 400

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val logLines = ArrayDeque<String>()
    private val logLock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 每个会话一个运行时：自己的 AgentLoop（历史、取消标志、token 计数）+ 忙标志。 */
    class Runtime internal constructor(val id: String, val loop: AgentLoop) {
        @Volatile internal var busy = false
    }

    private val runtimeLock = Any()
    private val runtimes = LinkedHashMap<String, Runtime>()

    // 缓存池而不是单线程：不同会话的任务互不阻塞，才能「同时进行多对话」。
    private val worker = Executors.newCachedThreadPool { r ->
        Thread(r, "agent-loop").apply { isDaemon = true }
    }

    private lateinit var appContext: Context
    private var settings: AgentSettings? = null
    private var toolbox: PhoneToolbox? = null
    private var client: CloudClient? = null

    @Volatile private var state: State = State.LOGGED_OUT
    @Volatile private var detail: String = ""
    @Volatile var accountName: String = ""
        private set

    /** 对话页是否在前台。任务结束后要不要把人拉回来，靠这个判断。 */
    @Volatile private var chatInForeground = false

    private val mainHandler = Handler(Looper.getMainLooper())

    val currentState: State get() = state
    val currentDetail: String get() = detail
    val isRunning: Boolean get() = state == State.RUNNING

    // ------------------------------------------------------------ 生命周期

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        settings = AgentSettings(appContext)
        Auth.init(appContext)

        // 首次启动把内置技能种到工作台的 skills/ 里。只种一次，用户删掉的不再长回来。
        val seeded = runCatching { Skill.install(workDir()) }.getOrDefault(0)
        if (seeded > 0) log("已安装 $seeded 个内置技能包（工作台 skills/）")

        toolbox = PhoneToolbox(
            deviceProvider = { PhoneAccessibilityService.instance },
            resolvePackage = ::resolvePackage,
            // 提示词管住大方向，闸门管住那几条不能出错的线。
            // 判定 → 问人 → 记授权，都在 ApprovalGate 里，与 UI 无关。
            approval = ApprovalGate(ApprovalUi, ApprovalPolicy(), { log(it) }),
            // 工作台目录用应用专属外部目录：不需要任何存储权限，
            // 用户用文件管理器也能看到 agent 写了什么，卸载时一起清掉。
            workbench = Workbench(
                workDir(),
                // 用 lambda 而不是取值：设置里开关一拨，下一次请求就生效，不用重启服务。
                // 走成员方法而不是直接读 settings——lateinit var 在 lambda 里拿不到 smart cast。
                shellEnabled = { isShellOn() },
                // PDF 引擎（PdfDocument 是 Android API，只能在真机注入；桌面测试为 null 时 pdf_write 自动摘除）
                docEngine = PdfDoc.Engine,
            ),
        )

        val tokens = object : TokenSource {
            override fun accessToken(): String? = Auth.accessToken()

            override fun refresh(): Boolean {
                return try {
                    val creds = Auth.credentials() ?: return false
                    if (creds.refreshToken.isEmpty()) return false
                    Auth.refresh(creds.refreshToken)
                    log("令牌已刷新")
                    true
                } catch (e: Exception) {
                    log("刷新令牌失败：${e.message}")
                    false
                }
            }
        }

        val preferred = ModelRegistry.resolve(settings?.model)
        val client = CloudClient(Config.BASE_URL, preferred.id, tokens) { log(it) }
        this.client = client

        // 单会话时代的历史迁移成第一个会话，只做一次。
        val legacy = ChatStore.load(appContext)
        if (legacy != null) {
            if (legacy.length() > 0) {
                sessions.save(LEGACY_SESSION, legacy, SessionStore.titleOf(legacy))
                setCurrentSession(LEGACY_SESSION)
            }
            ChatStore.clear(appContext)
        }

        // 每个会话的历史在自己的 Runtime 里，切到谁谁加载——init 不再预装。
        watchForeground()
        setState(if (Auth.isLoggedIn()) State.READY else State.LOGGED_OUT, "")
        if (Auth.isLoggedIn()) worker.execute { loadAccount() }
    }

    /** 追踪对话页是否在前台——只需要这一个事实。 */
    private fun watchForeground() {
        (appContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (activity is MainActivity) chatInForeground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    if (activity is MainActivity) chatInForeground = false
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        l.onStateChanged(state, detail)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun snapshotLog(): String = synchronized(logLock) { logLines.joinToString("\n") }

    fun clearLog() {
        synchronized(logLock) { logLines.clear() }
    }

    // ------------------------------------------------------------ 登录

    /**
     * 走完整的登录流程。必须在后台线程调用。
     * @param openUrl 由 UI 提供：把 authUrl 打开（WebView 或系统浏览器）
     */
    fun login(openUrl: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        setState(State.LOGGING_IN, "申请登录会话…")
        log("申请登录会话")
        try {
            val data = Auth.requestAuthState()
            val authUrl = data.optString("authUrl")
            val stateToken = data.optString("state")
            if (authUrl.isEmpty() || stateToken.isEmpty()) {
                throw IllegalStateException("登录会话缺少 authUrl 或 state")
            }
            log("登录链接已生成，等待授权")
            openUrl(authUrl)

            setState(State.LOGGING_IN, "等待你在页面上完成登录…")
            val creds = Auth.pollToken(stateToken) { tick ->
                if (tick % 5 == 0) setState(State.LOGGING_IN, "等待授权（已等 ${tick}秒）")
            }
            Auth.save(creds)
            log("登录成功，令牌 ${creds.accessToken.length} 字符")
            loadAccount()
            setState(State.READY, "已登录")
            onDone(true, "登录成功")
        } catch (e: Exception) {
            log("登录失败：${e.message}")
            setState(if (Auth.isLoggedIn()) State.READY else State.LOGGED_OUT, e.message ?: "登录失败")
            onDone(false, e.message ?: "登录失败")
        }
    }

    fun logout() {
        Auth.clear()
        synchronized(runtimeLock) { runtimes.clear() }
        ChatStore.clear(appContext)
        accountName = ""
        setState(State.LOGGED_OUT, "已退出登录")
        log("已退出登录，令牌已删除")
    }

    private fun loadAccount() {
        val token = Auth.accessToken() ?: return
        val account = Auth.account(token) ?: return
        val name = account.optString("nickName")
            .ifEmpty { account.optString("nickname") }
            .ifEmpty { account.optString("name") }
        if (name.isNotEmpty()) {
            accountName = name
            for (l in listeners) l.onStateChanged(state, detail)
        }
    }

    // ------------------------------------------------------------ 对话

    /** 取（或建）一个会话的运行时。建的时候把落盘的历史装回去。 */
    private fun runtime(id: String): Runtime = synchronized(runtimeLock) {
        runtimes[id]?.let { return it }
        val l = AgentLoop(client!!, toolbox!!, Config.MAX_TURNS, memory = {
            // 每次请求现取：这一轮记下的偏好，下一轮就能用上。开关一关立即失效。
            if (settings?.memoryOn == true) Memory.digest(workDir()) else ""
        }, skills = {
            // 只注入清单。正文由模型自己 skill_load，按需付费。
            if (settings?.skillsOn == true) {
                Skill.index(workDir(), settings?.disabledSkills ?: emptySet())
            } else ""
        })
        runCatching { sessions.load(id) }?.getOrNull()?.let { saved ->
            if (saved.length() > 0) l.restoreHistory(saved)
        }
        val rt = Runtime(id, l)
        runtimes[id] = rt
        rt
    }

    private fun currentRuntime(): Runtime = runtime(ensureSessionId())

    /** 某个会话是否有任务在跑。 */
    fun isBusy(id: String?): Boolean =
        id != null && synchronized(runtimeLock) { runtimes[id]?.busy == true }

    /** 正在跑任务的会话 id 集合（抽屉里标「● 任务进行中」用）。 */
    fun runningSessionIds(): Set<String> = synchronized(runtimeLock) {
        runtimes.filterValues { it.busy }.keys.toSet()
    }

    /** 跑一个任务。阻塞，内部自己切线程；不同会话可以同时各跑各的。 */
    fun ask(text: String, listener: AgentLoop.Listener, images: List<String>? = null) {
        val rt = currentRuntime()
        if (rt.busy) {
            listener.onError("这个会话还有任务在跑，先等它结束、点停止，或者开个新会话并行")
            return
        }
        if (!Auth.isLoggedIn()) {
            listener.onError("还没登录，先点「登录 WorkBuddy」")
            return
        }
        rt.busy = true
        // 悬浮状态窗：切到别的应用也能看到它在干什么（没授权/关了开关就空转）
        FloatStatus.taskStart(appContext, rt.id)
        setState(State.RUNNING, "执行中")
        // 流水账先记下用户说了什么：模型上下文怎么压缩、截断，这里都是完整原文
        Transcript.append(appContext, rt.id, Transcript.user(text, !images.isNullOrEmpty()))
        // 任务临开始前再看一眼：上一句话之后可能刚被系统摘掉开关。
        A11yKeeper.check(appContext, force = true)
        worker.execute {
            try {
                rt.loop.run(text, inner(rt, listener), images)
            } catch (e: Exception) {
                listener.onError(e.message ?: "运行失败")
            } finally {
                rt.busy = false
                FloatStatus.taskEnd(appContext, rt.id)
                // 不管是跑完、出错还是中途停了，历史都落一次盘。
                // 注意落的是**这个会话**的盘——跑的时候用户可能已经切去别的会话了。
                try {
                    saveSession(rt.id)
                } catch (e: Exception) {
                    log("保存会话失败：${e.message}")
                }
                synchronized(runtimeLock) {
                    if (runtimes.values.none { it.busy }) {
                        setState(if (Auth.isLoggedIn()) State.READY else State.LOGGED_OUT, "")
                    }
                }
            }
        }
    }

    /** 包一层，把 onFinished/onError 也同步到宿主状态上；带会话身份（token 用量是按会话算的）。 */
    private fun inner(rt: Runtime, t: AgentLoop.Listener) = object : AgentLoop.Listener {
        /** onToolStart 记下的参数，onToolEnd 写流水账时合进去。 */
        private var lastToolArgs = ""
        private var lastToolName = ""

        override fun onText(delta: String) {
            // 模型在吐字 = 在回答。悬浮窗只认状态不认内容，同一句话不会重复刷。
            FloatStatus.status(appContext, "正在回复…")
            t.onText(delta)
        }
        override fun onToolStart(name: String, args: JSONObject) {
            log("→ $name ${args.toString().take(120)}")
            lastToolName = name
            lastToolArgs = args.toString()
            FloatStatus.status(appContext, FloatStatus.toolStatus(name))
            t.onToolStart(name, args)
        }

        override fun onToolEnd(name: String, result: String, ok: Boolean) {
            log("← $name ${if (ok) "完成" else "失败"} ${result.length}字")
            // 流水账：压缩和截断动不到这里，事后想查「它当时点了哪儿、结果如何」全靠这份。
            Transcript.append(
                appContext, rt.id,
                Transcript.tool(name, lastToolArgs, ok, result)
            )
            t.onToolEnd(name, result, ok)
        }

        override fun onImage(dataUrl: String) {
            // 截图图片直接进对话流，不经过悬浮窗
            t.onImage(dataUrl)
        }

        override fun onArtifact(path: String) {
            // 产物卡（fs_write / pdf_write / pptx_write 的产出）。
            // 这层包装的每个回调都必须转发——接口里带默认空实现的尤其危险：
            // 忘了转发不报错，只是 UI 上永远收不到（产物卡不弹就是吃过这个亏）。
            t.onArtifact(path)
        }

        override fun onTurnEnd(turn: Int) {
            // 一轮做完，下一轮又是模型在想——别让悬浮窗停在「正在点按…」
            FloatStatus.status(appContext, "正在思考…")
            t.onTurnEnd(turn)
        }

        override fun onCompact(removed: Int, kept: Int) {
            log("上下文已压缩：折叠 $removed 条旧消息，保留最近 $kept 条")
            // 压缩也记一笔流水账：模型忘了的不代表查不到。
            Transcript.append(appContext, rt.id, Transcript.compact(removed, kept, ""))
            t.onCompact(removed, kept)
        }

        override fun onFinished(finalText: String, turns: Int, toolCalls: Int) {
            val used = "，token 输入 ${rt.loop.promptTokens} / 输出 ${rt.loop.completionTokens}"
            log("任务结束：$turns 轮、$toolCalls 次工具调用$used")
            Transcript.append(appContext, rt.id, Transcript.assistant(finalText))
            t.onFinished(finalText, turns, toolCalls)
            returnToUi(finalText)
        }

        override fun onError(message: String) {
            log("出错：$message")
            Transcript.append(appContext, rt.id, Transcript.error(message))
            t.onError(message)
            returnToUi("任务出错了，回来看看")
        }
    }

    /**
     * 任务结束后把对话页拉回前台。你从微信里交代的事，做完应该叫你回来看，而不是等你想起。
     *
     * Android 10+ 限制后台启动界面：尝试启动不保证成功（vivo 需要在应用信息里
     * 允许「后台弹出界面」）。失败不会抛异常，所以 1.2 秒后还在后台就补一条通知，
     * 点通知也能回来——两条路，至少通一条。
     */
    private fun returnToUi(summary: String) {
        if (settings?.autoReturn != true) return
        if (chatInForeground) return
        mainHandler.post {
            runCatching {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            mainHandler.postDelayed({
                if (!chatInForeground) {
                    KeepAliveService.taskDone(appContext, summary.ifEmpty { "任务完成了" })
                }
            }, 1200)
        }
    }

    fun stop() {
        currentRuntime().loop.cancel()
        // 光置 cancel 标志不够：后台线程此刻可能正阻塞在「等你确认」上，
        // 不把那个等待叫醒，停止按钮要等到超时才生效。
        ApprovalUi.cancelPending()
    }

    // ------------------------------------------------------------ 会话

    private const val KEY_CURRENT_SESSION = "current_session"

    /** 单会话时代迁移过来的那份历史，固定 id，顶在最前面。 */
    private const val LEGACY_SESSION = "legacy"

    /** 任务里真正落盘用。null（还没初始化）时静默跳过。 */
    private val sessions by lazy {
        SessionStore(File(appContext.filesDir, "sessions")).also { it.logger = { msg -> log(msg) } }
    }

    /** 当前会话 id；null 表示还没有任何会话（比如刚清空）。 */
    fun currentSessionId(): String? =
        appContext.getSharedPreferences("agent", Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_SESSION, null)

    private fun setCurrentSession(id: String?) {
        appContext.getSharedPreferences("agent", Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT_SESSION, id).apply()
    }

    /**
     * 保证发请求时一定有会话 id。
     * 删掉当前会话后直接发消息（不点新会话）就是这种状态——没有它，这一轮会被静默丢弃。
     */
    fun ensureSessionId(): String {
        currentSessionId()?.let { return it }
        val id = "s" + System.currentTimeMillis().toString(36)
        setCurrentSession(id)
        return id
    }

    /** 把当前会话的历史落盘。空历史不会留空文件（SessionStore.save 内部处理）。 */
    fun saveCurrentSession() {
        val id = currentSessionId() ?: return
        saveSession(id)
    }

    /** 把指定会话的运行时历史落盘。会话没跑过（无运行时）就跳过——盘上已有落好的。 */
    fun saveSession(id: String) {
        val rt = synchronized(runtimeLock) { runtimes[id] } ?: return
        runCatching { sessions.save(id, rt.loop.exportHistory()) }
            .onFailure { log("保存会话失败：${it.message}") }
    }

    /**
     * 开新会话：当前会话先落盘，然后换一个空 id。
     * 返回新 id，调用方负责清空界面。
     *
     * 当前会话就算在跑也允许开新——任务在自己的 Runtime 里继续，
     * 这正是「同时进行多对话」：旧的跑完落盘，新的直接开聊。
     */
    fun newSession(): String {
        saveCurrentSession()
        val id = "s" + System.currentTimeMillis().toString(36)
        setCurrentSession(id)
        log("已开启新会话")
        return id
    }

    /**
     * 切换会话：保存当前，返回目标会话落盘的历史（调用方拿去重建界面）。
     * 目标读不出来就当空会话，不崩。目标在跑也没关系——它的 Runtime 还在，
     * 切回去后事件会继续流到界面上。
     */
    fun switchSession(id: String): JSONArray? {
        if (id != currentSessionId()) saveCurrentSession()
        setCurrentSession(id)
        return sessions.load(id)
    }

    /** 删会话。正在跑任务的会话不能删（任务还在往里写），删了返回 false。 */
    fun deleteSession(id: String): Boolean {
        if (isBusy(id)) {
            log("会话 $id 有任务在跑，不能删")
            return false
        }
        sessions.delete(id)
        Transcript.delete(appContext, id)
        synchronized(runtimeLock) { runtimes.remove(id) }
        if (id == currentSessionId()) setCurrentSession(null)
        log("已删除会话 $id")
        return true
    }

    /**
     * 从某条回答分叉：把历史 [0..index] 复制进一个新会话，原会话一字不动。
     *
     * index 是界面记下的「这条回答是历史的第几条」。界面重建过、或历史被压缩过，
     * 下标可能对不上——这时按正文内容从尾部找同一条回答（同一段话重复出现的
     * 概率低到可以接受）。找不到就不分叉，绝不乱切一刀。
     */
    fun forkFrom(index: Int, answer: String? = null, title: String? = null): String? {
        val hist = currentHistory() ?: return null
        var at = index
        val ok = at in 0 until hist.length() &&
            hist.optJSONObject(at)?.optString("role") == "assistant" &&
            (answer.isNullOrBlank() ||
                hist.optJSONObject(at)?.optString("content")?.trim() == answer.trim())
        if (!ok) at = if (answer.isNullOrBlank()) -1 else findAnswer(hist, answer)
        if (at < 0) return null

        // 前缀可能停在某个工具调用之后，但一定停在一条完整的 assistant 消息上：
        // tool 消息永远紧跟它的 assistant，不会出现半截配对。
        val prefix = SessionStore.prefixUpTo(hist, at) ?: return null
        if (prefix.length() == 0) return null

        saveCurrentSession()
        val id = "s" + System.currentTimeMillis().toString(36)
        val base = title?.takeIf { it.isNotBlank() && it != "新对话" } ?: SessionStore.titleOf(prefix)
        sessions.save(id, prefix, "$base · 分叉")
        setCurrentSession(id)
        log("已从第 ${at + 1} 条消息分叉出新会话")
        return id
    }

    /** 当前会话最后一条「有正文的 assistant 消息」的下标（轮末分叉锚点）。 */
    fun lastAnswerIndex(): Int {
        val hist = currentHistory() ?: return -1
        for (i in hist.length() - 1 downTo 0) {
            val m = hist.optJSONObject(i) ?: continue
            if (m.optString("role") != "assistant") continue
            if (m.opt("content") is String && m.optString("content").isNotBlank()) return i
        }
        return -1
    }

    private fun findAnswer(hist: JSONArray, answer: String): Int {
        val want = answer.trim()
        for (i in hist.length() - 1 downTo 0) {
            val m = hist.optJSONObject(i) ?: continue
            if (m.optString("role") != "assistant") continue
            if (m.opt("content") !is String) continue
            if (m.optString("content").trim() == want) return i
        }
        return -1
    }

    fun listSessions(): List<SessionStore.Meta> = sessions.list()

    /**
     * 当前会话的完整历史（界面重建用）。
     * 优先取运行时里的活历史（可能比盘上新一轮），取不到再读盘。
     */
    fun currentHistory(): JSONArray? {
        val id = currentSessionId() ?: return null
        synchronized(runtimeLock) { runtimes[id] }?.let { rt ->
            return runCatching { rt.loop.exportHistory() }.getOrNull() ?: sessions.load(id)
        }
        return sessions.load(id)
    }

    /** 兼容旧调用：现在「清空上下文」等于开新会话。 */
    fun resetConversation() {
        newSession()
    }

    fun historySize(): Int = synchronized(runtimeLock) {
        currentSessionId()?.let { runtimes[it]?.loop?.historySize() } ?: 0
    }

    // ------------------------------------------------------------ 技能包

    /** 工作台根目录。取不到外部目录时退回内部存储（卸载时一样会清掉）。 */
    private fun workDir(): File = appContext.getExternalFilesDir(null) ?: appContext.filesDir

    /** 当前有哪些技能（设置页列清单用）。读失败就当没有，不要让设置页崩。 */
    fun skillItems(): List<Skill.Item> = runCatching { Skill.scan(workDir()) }.getOrDefault(emptyList())

    fun skillsOn(): Boolean = settings?.skillsOn == true

    fun setSkillsOn(on: Boolean) {
        settings?.skillsOn = on
    }

    fun disabledSkills(): Set<String> = settings?.disabledSkills ?: emptySet()

    /** 单独开关一个技能。停用的只是「注入索引」，文件还留着，随时能再打开。 */
    fun toggleSkill(name: String): Boolean {
        val s = settings ?: return false
        val off = s.disabledSkills.toMutableSet()
        val nowOn = if (name in off) {
            off.remove(name)
            true
        } else {
            off.add(name)
            false
        }
        s.disabledSkills = off
        return nowOn
    }

    // ------------------------------------------------------------ 模型

    /** 当前模型 id。认不出来的历史值落回默认，不会把野字符串发出去。 */
    fun modelId(): String = ModelRegistry.resolve(settings?.model).id

    fun modelLabel(): String = ModelRegistry.labelOf(modelId())

    /**
     * 切换模型。只影响下一次请求——对话历史和工具定义都不用重发，
     * 所以跑到一半改也没关系（改完这一轮剩下的调用还是用旧模型答完）。
     */
    fun setModel(id: String) {
        val picked = ModelRegistry.resolve(id)
        settings?.model = picked.id
        client?.model = picked.id
        log("模型已切换：${picked.label}${if (picked.credit.isNotEmpty()) "（${picked.credit}）" else ""}")
    }

    /** 上一轮服务端回传的模型名（不是我们请求的那个）。null 表示流里没带。 */
    fun lastServerModel(): String? = synchronized(runtimeLock) {
        currentSessionId()?.let { runtimes[it]?.loop?.lastModel }
    }

    /** 本次任务的用量，给界面底部那行小字用。 */
    fun usageText(): String {
        val l = synchronized(runtimeLock) { currentSessionId()?.let { runtimes[it]?.loop } }
            ?: return ""
        return "输入 ${l.promptTokens} / 输出 ${l.completionTokens} tokens"
    }

    /**
     * 模型自检：用当前配置发一条最小请求，把「我们请求的」和「服务端回传的」摆在一起。
     *
     * 存在的理由很具体：「我换了模型，怎么感觉没变」这句话用代码是回答不了的，
     * 只能让服务端自己说它用了谁。这条请求不带历史、不带工具，不会污染会话。
     * 结果回到主线程，异常也当成结果返回——检查失败本身也是信息。
     */
    fun probeModel(onResult: (String) -> Unit) {
        val c = client
        if (c == null) {
            onResult("宿主还没初始化，稍后再试")
            return
        }
        if (state == State.RUNNING) {
            onResult("当前有任务在跑，等它结束再检查——否则排队也是这一条")
            return
        }
        val requested = c.model
        worker.execute {
            val report = try {
                val msgs = JSONArray().put(
                    JSONObject().put("role", "user").put("content", "只回复两个字：收到")
                )
                val turn = c.stream(msgs, null)
                val served = turn.model
                val credit = turn.usage?.optDouble("credit", 0.0) ?: 0.0
                buildString {
                    append("请求发出：").append(requested).append('\n')
                    append("服务端回传：").append(served ?: "（流里没带 model 字段）").append('\n')
                    turn.usage?.let {
                        append("本次用量：").append(it.optInt("total_tokens", 0))
                            .append(" tokens，扣费 ").append(credit).append('\n')
                    }
                    append('\n')
                    append(
                        when {
                            served == null -> "服务端没说是谁答的，只能以后台账单为准。"
                            served == requested -> "一致。切换确实生效了，这一条就是它答的。"
                            else -> "不一致：请求 $requested，实际 $served。请求被网关换成了别的模型。"
                        }
                    )
                }
            } catch (e: Exception) {
                "检查失败：${e.message ?: e.javaClass.simpleName}"
            }
            log(report.replace('\n', ' '))
            mainHandler.post { onResult(report) }
        }
    }

    /**
     * 经本地网关发一条最小请求。
     *
     * 为什么不复用 probeModel：那条走的是直连，验证的是「令牌还能不能用」；
     * 这条要验证的是「手机上的网关能不能把请求中转出去」——两者失败的处置完全不同。
     */
    fun probeGateway(onResult: (String) -> Unit) {
        if (!LocalGateway.running) {
            onResult("本地网关没在跑。先回设置页，点那张状态卡启动它。")
            return
        }
        val port = LocalGateway.port
        val modelId = modelId()
        worker.execute {
            val started = System.currentTimeMillis()
            val report = try {
                val gatewayClient = CloudClient(
                    "http://127.0.0.1:$port",
                    modelId,
                    object : TokenSource {
                        override fun accessToken(): String? = Auth.accessToken()
                        override fun refresh(): Boolean = false
                    },
                ) { log("网关自检：$it") }
                val msgs = JSONArray().put(
                    JSONObject().put("role", "user").put("content", "只回复两个字：收到")
                )
                val turn = gatewayClient.stream(msgs, null)
                buildString {
                    append("经过：本地网关 127.0.0.1:").append(port).append('\n')
                    append("耗时：").append(System.currentTimeMillis() - started).append(" ms\n")
                    append("模型：").append(modelId).append('\n')
                    append("回复：").append(turn.content.take(40).ifBlank { "（空）" }).append('\n')
                    append("服务端回传：").append(turn.model ?: "（没回传）").append("\n\n")
                    append(
                        if (turn.content.isNotBlank()) "通了。dsh-Go 里照「接入 dsh-Go」那页填就行。"
                        else "网关通了但上游没给内容，看设置页状态卡里的上次转发结论。"
                    )
                }
            } catch (e: Exception) {
                "走网关失败：" + (e.message ?: e.javaClass.simpleName) + "\n\n" +
                    LocalGateway.lastResult + "\n" + LocalGateway.lastDetail
            }
            log(report.replace('\n', ' '))
            mainHandler.post { onResult(report) }
        }
    }

    // ------------------------------------------------------------ 应用名 → 包名

    private val labelCache = HashMap<String, String>()

    /**
     * shell 开关的实时读取。
     *
     * 单独抽成方法是为了给 lambda 用：`settings` 是可空的可变属性，在闭包里直接写
     * `settings.shellOn` 会报「smart cast 不可能」——可变属性在 lambda 里无法保证非空。
     */
    private fun isShellOn(): Boolean = settings?.shellOn == true

    /**
     * 模型给的是「微信」这种中文名，系统要的是包名。
     * 先精确匹配应用名，再退化到包含匹配，最后原样返回让系统自己判断。
     */
    private fun resolvePackage(target: String): String? {
        if (target.contains('.') && !target.contains(' ')) return target // 已经是包名
        synchronized(labelCache) {
            labelCache[target]?.let { return it }
            if (labelCache.isEmpty()) fillLabelCache()
            labelCache[target]?.let { return it }
            val t = target.lowercase()
            val fuzzy = labelCache.entries.firstOrNull {
                val k = it.key.lowercase()
                k.contains(t) || t.contains(k)
            }
            return fuzzy?.value
        }
    }

    private fun fillLabelCache() {
        val pm = appContext.packageManager
        val intents = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),
            0
        )
        for (info in intents) {
            val pkg = info.activityInfo.packageName
            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                continue
            }
            if (label.isNotEmpty()) labelCache[label] = pkg
        }
        log("已索引 ${labelCache.size} 个可启动应用")
    }

    // ------------------------------------------------------------ 状态与日志

    private fun setState(next: State, why: String) {
        state = next
        detail = why
        for (l in listeners) l.onStateChanged(next, why)
    }

    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        synchronized(logLock) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
        for (l in listeners) l.onLogAppended(line)
    }
}
