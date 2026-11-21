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

    /**
     * 日志文件的上限。
     *
     * 内存里只留 400 行，落盘多留一些——出问题时要看的是「崩之前那几分钟」，
     * 而崩溃恰恰会把内存里的日志一起带走，所以磁盘这份才是唯一能事后看的。
     */
    private const val MAX_LOG_BYTES = 256 * 1024

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

        // 先把上次运行的日志捞回来：崩溃之后那几分钟的记录，只有磁盘上有。
        restoreLog()

        // 首次启动把内置技能种到工作台的 skills/ 里。只种一次，用户删掉的不再长回来。
        val seeded = runCatching { Skill.install(workDir()) }.getOrDefault(0)
        if (seeded > 0) log("已安装 $seeded 个内置技能包（工作台 skills/）")

        // 工作台目录用应用专属外部目录：不需要任何存储权限，
        // 用户用文件管理器也能看到 agent 写了什么，卸载时一起清掉。
        val bench = Workbench(
            workDir(),
            // 用 lambda 而不是取值：设置里开关一拨，下一次请求就生效，不用重启服务。
            // 走成员方法而不是直接读 settings——lateinit var 在 lambda 里拿不到 smart cast。
            shellEnabled = { isShellOn() },
            // PDF 引擎（PdfDocument 是 Android API，只能在真机注入；桌面测试为 null 时 pdf_write 自动摘除）
            docEngine = PdfDoc.Engine,
            // 文本截图引擎（Bitmap/Canvas 同理只能真机注入；null 时 text_shot 自动摘除）
            shotEngine = TextShot,
            // 图表引擎（布局在纯 JVM 的 ChartText，这里只画像素；null 时 chart_write 摘除）
            chartEngine = ChartShot,
            // Python 引擎：proot + Alpine rootfs（so 在 jniLibs，rootfs 首次使用时下载）。
            // 引擎常驻注入（isPyOn 挡住开关），status/下载由引擎自己管。
            pyEngine = PyRuntime(appContext, workDir()),
            pyEnabled = { isPyOn() },
            // 搜索降级链路写进运行日志（「为什么这次全是必应」只能靠这一行回答）
            logger = { log(it) },
        )

        toolbox = PhoneToolbox(
            deviceProvider = { PhoneAccessibilityService.instance },
            resolvePackage = ::resolvePackage,
            // 提示词管住大方向，闸门管住那几条不能出错的线。
            // 判定 → 问人 → 记授权，都在 ApprovalGate 里，与 UI 无关。
            approval = ApprovalGate(ApprovalUi, ApprovalPolicy(), { log(it) }),
            workbench = bench,
            // 派子代理：用同一个模型客户端，但走一套全新的、只有只读工具的小循环。
            // 中间过程留在那个循环里，主线只拿到结论——这就是「隔离」的全部含义。
            delegate = { goal, focus, onProgress, isCancelled ->
                val c = client
                if (c == null) {
                    ToolResult(
                        "{\"ok\":false,\"code\":\"NO_CLIENT\",\"error\":\"模型客户端还没就绪，这次没有派出去\"}"
                    )
                } else {
                    // 流水账不用在这里补：task 本身也是一次工具调用，收尾时
                    // onToolEnd 会把「名字 + 参数 + 结果摘要」整体记一条。
                    SubAgent.run(
                        client = c,
                        bench = bench,
                        goal = goal,
                        focus = focus,
                        onProgress = onProgress,
                        parentCancelled = isCancelled,
                    )
                }
            },
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
        // 变量名不用 client：上面 delegate 里那个 client 指的是属性（初始化时才赋值），
        // 同名会让人以为两边是同一个东西，其实一个是延迟可见的字段。
        val cloud = CloudClient(
            baseUrl = Config.BASE_URL,
            model = preferred.id,
            tokens = tokens,
            log = { log(it) },
            // 每轮现取：设置里改了档位，下一句话就该按新档发。
            effort = { effortParam() },
            onEffortRejected = { body ->
                // 把「哪个模型不认」记下来，同一个模型之后就别再白打一次注定失败的请求。
                // 服务端原话原样进日志——换模型要不要再试，全靠这句话判断。
                log("思考深度参数被拒：$body")
                val id = this.client?.model ?: ""
                if (id.isNotEmpty()) settings?.effortRejectedFor = id
                // 流水账也记一笔：这条能解释「低档那一轮为什么反而更慢」——
                // 它其实是标准档，外加一次白跑的往返。
                runCatching {
                    Transcript.append(appContext, ensureSessionId(), TranscriptText.effortReject(body))
                }
            },
        )
        this.client = cloud

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
        val l = AgentLoop(client!!, toolbox!!, Config.MAX_TURNS, memory = { topic ->
            // 每次请求现取：这一轮记下的偏好，下一轮就能用上。开关一关立即失效。
            // topic 是本轮用户说的最近几句话，记忆按它挑相关性，不再无脑取最近 20 条。
            if (settings?.memoryOn == true) Memory.digest(workDir(), topic) else ""
        }, skills = {
            // 只注入清单。正文由模型自己 skill_load，按需付费。
            if (settings?.skillsOn == true) {
                Skill.index(workDir(), settings?.disabledSkills ?: emptySet())
            } else ""
        }, tone = {
            // 人情味开关：开着就带语气段，关了整段不出现（不是留个「语气：正常」占位）。
            // 每次请求现取——设置里一拨，下一条消息就生效，不用重建会话。
            if (settings?.emotionOn == true) PhonePrompt.WARM_TONE else ""
        }, owner = id, saveNote = { name, text ->
            // 图说全文落盘：消息里只挂前几百字，压缩后靠这个路径 fs_read 拿回细节。
            // 写失败不抛——落不了盘只是少一份备份，图说本身还挂在消息里。
            runCatching {
                val dir = java.io.File(workDir(), "images").apply { mkdirs() }
                java.io.File(dir, name).writeText(text)
                "images/" + name
            }.getOrNull()
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

    /**
     * 跑一个任务。阻塞，内部自己切线程；不同会话可以同时各跑各的。
     *
     * [displayText] 是界面上那条用户气泡的原文；带文件附件时 [text] 是含文件正文的
     * payload（给模型的），流水账里记 payload 会把整份文件画成用户的话——
     * 恢复会话、导出流水账都按 displayText 来。
     * [photoFiles] 是这次附带照片落盘后的文件名（filesDir/photos/ 下），流水账按名回显。
     */
    fun ask(
        text: String,
        listener: AgentLoop.Listener,
        images: List<String>? = null,
        displayText: String? = null,
        photoFiles: List<String> = emptyList(),
    ) {
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
        Transcript.append(appContext, rt.id, TranscriptText.user(displayText ?: text, !images.isNullOrEmpty(), photoFiles))
        // 任务临开始前再看一眼：上一句话之后可能刚被系统摘掉开关。
        A11yKeeper.check(appContext, force = true)
        worker.execute {
            try {
                rt.loop.run(text, inner(rt, listener), images)
            } catch (e: Exception) {
                listener.onError(e.message ?: "运行失败")
            } finally {
                rt.busy = false
                // 交还手机：不管跑完、出错还是被停，都得放——否则别的会话再也碰不了屏幕，
                // 而用户看到的只是「另一个会话在操作手机」，永远等不到。
                PhoneLease.release(rt.id)
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

        override fun onText(delta: String) {
            // 模型在吐字 = 在回答。悬浮窗只认状态不认内容，同一句话不会重复刷。
            FloatStatus.status(appContext, "正在回复…")
            t.onText(delta)
        }

        // 思考增量也必须转发。接口里它是带默认空实现的——漏了这一行不会编译报错，
        // 只会安静地把思考流吞掉，界面表现就是「怎么一直看不到思考」。
        override fun onThinking(delta: String) {
            FloatStatus.status(appContext, "正在思考…")
            t.onThinking(delta)
        }
        override fun onToolStart(name: String, args: JSONObject) {
            log("→ $name ${args.toString().take(120)}")
            FloatStatus.status(appContext, FloatStatus.toolStatus(name))
            t.onToolStart(name, args)
        }

        /**
         * 子代理的进度也要进悬浮窗：那是最可能被用户看见的地方（他多半已经切走了）。
         * 不转发的话，悬浮窗在十几轮里一直挂着「正在派子代理查…」，看着像死了。
         */
        override fun onSubProgress(note: String) {
            FloatStatus.status(appContext, note)
            t.onSubProgress(note)
        }

        override fun onToolEnd(name: String, args: JSONObject, result: String, ok: Boolean) {
            log("← $name ${if (ok) "完成" else "失败"} ${result.length}字")
            // 流水账：压缩和截断动不到这里，事后想查「它当时点了哪儿、结果如何」全靠这份。
            // args 随调用带来——并行执行时 start/end 会交错，不能靠「最后一次 start」猜归属。
            Transcript.append(
                appContext, rt.id,
                TranscriptText.tool(name, args.toString(), ok, result)
            )
            t.onToolEnd(name, args, result, ok)
        }

        override fun onImage(dataUrl: String) {
            // 截图图片直接进对话流，不经过悬浮窗
            t.onImage(dataUrl)
        }

        /** 网络层退避重发。流水账记一笔，事后能对上「这段时间为什么是空白的」。 */
        override fun onRetry(why: String) {
            log("网络重试：$why")
            Transcript.append(appContext, rt.id, TranscriptText.retry(why))
            t.onRetry(why)
        }

        /** 这一轮作废重来：已显示的半截要撤掉。 */
        override fun onRestart() {
            log("回复中断，已撤掉半截内容重发")
            t.onRestart()
        }

        override fun onExtras(chars: Int, preview: String) {
            // 背景资料（记忆/技能索引）变了：记一笔原文摘要。
            // 「问主题四却答主题三」这种问题，只有这条能证明当时到底被喂了什么。
            if (chars == 0) {
                log("背景资料已清空（记忆/技能都不再注入）")
            } else {
                log("随请求附带背景资料 $chars 字：${preview.take(120)}")
            }
            Transcript.append(appContext, rt.id, TranscriptText.extras(chars, preview))
            t.onExtras(chars, preview)
        }

        override fun onArtifact(path: String) {
            // 产物卡（fs_write / pdf_write / pptx_write 的产出）。
            // 这层包装的每个回调都必须转发——接口里带默认空实现的尤其危险：
            // 忘了转发不报错，只是 UI 上永远收不到（产物卡不弹就是吃过这个亏）。
            t.onArtifact(path)
        }

        override fun onOpenHtml(path: String) {
            // html_preview 的全屏渲染：同样必须转发，默认空实现漏了不报错
            t.onOpenHtml(path)
        }

        override fun onTurnTiming(turn: CloudClient.Turn) {
            // 耗时拆解进流水账，同时在诊断日志里留一行——用户说「感觉没变快」时，
            // 这里能直接看出是网络、预填充还是模型在思考。
            val u = turn.usage
            Transcript.append(
                appContext,
                rt.id,
                TranscriptText.turn(
                    headerMs = turn.headerMs,
                    firstTokenMs = turn.firstTokenMs,
                    totalMs = turn.totalMs,
                    inChars = turn.inputChars,
                    inTokens = u?.optInt("prompt_tokens", 0) ?: 0,
                    outTokens = u?.optInt("completion_tokens", 0) ?: 0,
                    toolCalls = turn.toolCalls.size,
                    model = turn.model,
                    reasoningChars = turn.reasoningChars,
                    reasoningFirstMs = turn.reasoningFirstMs,
                    connectMs = turn.connectMs,
                    cacheHit = turn.cacheHitTokens,
                    cacheMiss = turn.cacheMissTokens,
                    effort = turn.reasoningEffort ?: "",
                    usageRaw = u?.toString() ?: "",
                )
            )
            log(
                "本轮 %.1fs（连接 %.1fs，首包 %.1fs，首字 %.1fs，输入 %d / 输出 %d tokens%s%s%s）".format(
                    turn.totalMs / 1000.0,
                    turn.connectMs / 1000.0,
                    turn.headerMs / 1000.0,
                    turn.firstTokenMs / 1000.0,
                    u?.optInt("prompt_tokens", 0) ?: 0,
                    u?.optInt("completion_tokens", 0) ?: 0,
                    // 缓存命中是「输入里有多少不用重新算」。没报就写「未知」，
                    // 别写成 0%——那会把「上游没说」误读成「一点没命中」。
                    when {
                        turn.cacheHitTokens < 0 -> "，缓存未知"
                        else -> "，缓存 %d/%d（%d%%）".format(
                            turn.cacheHitTokens,
                            turn.cacheHitTokens + turn.cacheMissTokens.coerceAtLeast(0),
                            if (turn.cacheHitTokens + turn.cacheMissTokens > 0)
                                turn.cacheHitTokens * 100 / (turn.cacheHitTokens + turn.cacheMissTokens) else 0,
                        )
                    },
                    if (turn.reasoningChars > 0) "，思考 ${turn.reasoningChars} 字" else "",
                    if (turn.reasoningEffort != null) "，深度 ${Think.labelOfEffort(turn.reasoningEffort)}" else "",
                )
            )
        }

        override fun onTurnEnd(turn: Int) {
            // 一轮做完，下一轮又是模型在想——别让悬浮窗停在「正在点按…」
            FloatStatus.status(appContext, "正在思考…")
            t.onTurnEnd(turn)
        }

        override fun onCompact(removed: Int, kept: Int) {
            log("上下文已压缩：折叠 $removed 条旧消息，保留最近 $kept 条")
            // 压缩也记一笔流水账：模型忘了的不代表查不到。
            Transcript.append(appContext, rt.id, TranscriptText.compact(removed, kept, ""))
            t.onCompact(removed, kept)
        }

        override fun onFinished(finalText: String, turns: Int, toolCalls: Int) {
            val used = "，token 输入 ${rt.loop.promptTokens} / 输出 ${rt.loop.completionTokens}"
            log("任务结束：$turns 轮、$toolCalls 次工具调用$used")
            Transcript.append(appContext, rt.id, TranscriptText.assistant(finalText))
            t.onFinished(finalText, turns, toolCalls)
            returnToUi(finalText)
        }

        override fun onError(message: String) {
            log("出错：$message")
            Transcript.append(appContext, rt.id, TranscriptText.error(message))
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

    /**
     * 某个会话的流水账，渲染成人能读的文本。
     *
     * 这份东西是「模型上下文被压缩掉了、但事情确实发生过」的唯一证据：
     * 用户原话、每次工具调用的参数与结果、每轮耗时拆解、压缩与重试。
     * 之前只有 adb pull 能看，等于没有——现在从会话列表长按就能取。
     */
    fun transcriptText(id: String): String =
        runCatching { TranscriptText.render(Transcript.read(appContext, id)) }.getOrDefault("")

    /**
     * 把流水账导出成工作台里的 Markdown，返回文件名；失败返回 null。
     *
     * 为什么不直接复制到剪贴板就够了：一段几十 KB 的记录粘到微信里根本没法读，
     * 存成文件才能整份发出来（也是排查问题时最有用的东西）。
     */
    fun exportTranscript(id: String): String? {
        val text = transcriptText(id)
        if (text.isBlank()) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val name = "流水账-$stamp.md"
        return runCatching {
            val f = java.io.File(workDir(), name)
            f.writeText(text, Charsets.UTF_8)
            name
        }.getOrNull()
    }

    /** 删会话。正在跑任务的会话不能删（任务还在往里写），删了返回 false。 */
    fun deleteSession(id: String): Boolean {
        if (isBusy(id)) {
            log("会话 $id 有任务在跑，不能删")
            return false
        }
        sessions.delete(id)
        Transcript.delete(appContext, id)
        // 随会话落盘的照片一起清（文件名以会话 id 开头，见 MainActivity.savePhotoFiles）
        runCatching {
            val photos = File(appContext.filesDir, "photos")
            photos.listFiles()?.forEach { f ->
                if (f.name.startsWith("$id-")) f.delete()
            }
        }
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

    // ------------------------------------------------------------ 长期记忆（管理页用）

    /**
     * 记忆管理页的四个动作。UI 不碰路径、不碰文件格式——
     * 它只需要「给我列出来」「删这条」「记一条」「全清掉」。
     */
    fun memoryEntries(): List<String> = runCatching { Memory.entries(workDir()) }.getOrDefault(emptyList())

    fun memoryOn(): Boolean = settings?.memoryOn == true

    fun setMemoryOn(on: Boolean) {
        settings?.memoryOn = on
    }

    /** 删一条。返回 false 表示这条已经不在了（页面拿到了旧列表），不是失败。 */
    fun forgetMemory(entry: String): Boolean =
        runCatching { Memory.remove(workDir(), entry) }.getOrDefault(false)

    /** 手动记一条。返回 null 表示没记（空白或跟最近那条重复）。 */
    fun rememberManually(text: String): String? =
        runCatching { Memory.append(workDir(), text) }.getOrNull()

    fun clearMemory(): Boolean = runCatching { Memory.clear(workDir()) }.getOrDefault(false)

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

    // ------------------------------------------------------------ 思考深度

    fun thinkLevel(): Int = settings?.thinkLevel ?: Config.THINK_LEVEL

    /**
     * 换档位。顺手清掉「这个模型不认」的记录——拒绝的是参数名，但换了档位值得再试一次，
     * 而且下一次失败只花一次往返，换来的是又能省几秒。真要还是不行，客户端会再记一次。
     */
    fun setThinkLevel(level: Int) {
        val s = settings ?: return
        s.thinkLevel = level
        s.effortRejectedFor = ""
        log("思考深度已切到「${Think.label(level)}」")
    }

    /** 当前模型是不是已经明确拒绝过思考深度参数（拒绝过就不再发，免得每轮白跑一次）。 */
    fun effortUnsupported(): Boolean {
        val s = settings ?: return false
        return s.effortRejectedFor.isNotEmpty() && s.effortRejectedFor == modelId()
    }

    /**
     * 这次请求该带的 reasoning_effort。null = 不带。
     *
     * 三重闸门：标准档本来就不带；已知该模型拒绝过就不带；其余按档位映射。
     */
    private fun effortParam(): String? {
        val s = settings ?: return null
        if (s.effortRejectedFor.isNotEmpty() && s.effortRejectedFor == (client?.model ?: s.model)) return null
        return Think.effort(s.thinkLevel)
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

    /** 同 [isShellOn]：lambda 里拿不到 settings 的 smart cast，包一层。 */
    private fun isPyOn(): Boolean = settings?.pyOn == true

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
        // 落盘放在锁外：写文件比加锁慢得多，别让 UI 线程的 log 卡在别人的 IO 后面
        appendLogFile(line)
        for (l in listeners) l.onLogAppended(line)
    }

    private val logFile: File
        get() = File(appContext.filesDir, "logs/agent.log")

    /**
     * 追加一行到日志文件。
     *
     * 满了就把前面截掉，只留后半——日志要回答的是「最近发生了什么」，
     * 不是归档。无上限的话，一个跑了几天的 App 会攒出几十 MB 没人看的东西。
     */
    private fun appendLogFile(line: String) {
        if (!::appContext.isInitialized) return
        runCatching {
            val f = logFile
            f.parentFile?.mkdirs()
            if (f.length() > MAX_LOG_BYTES) {
                val kept = f.readLines().takeLast(MAX_LOG_LINES * 2)
                f.writeText(kept.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        }
    }

    /**
     * 上次运行留下的日志读到内存里。
     *
     * 直接原因：崩溃最需要日志，而崩溃正好把内存那 400 行一起带走。
     * 落盘之后重开还能看到上一次的最后几步，用户反馈问题时也能一键复制整份发过来。
     */
    private fun restoreLog() {
        runCatching {
            val f = logFile
            if (!f.exists()) return
            val kept = f.readLines().takeLast(MAX_LOG_LINES / 2).filter { it.isNotBlank() }
            if (kept.isEmpty()) return
            synchronized(logLock) { logLines.addAll(kept) }
            log("—— 以上是上次运行的记录 ——")
        }
    }
}
