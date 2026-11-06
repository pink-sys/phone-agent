package cn.wbphone.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 一个入口，两个状态：没设置完走引导，设置完直接进对话。
 *
 * 引导只在第一次出现，之后每次打开都是对话页——不再有"四张卡片堆一屏"。
 */
class MainActivity : Activity(), AgentHost.Listener {

    private lateinit var settings: AgentSettings
    private lateinit var container: FrameLayout

    private var onboarding: OnboardingUi? = null
    private var chat: ChatUi? = null

    private var accessibilityOn = false
    private var running = false


    private val stream = StringBuilder()

    private var approvalSheet: ApprovalSheet? = null
    private var approvalShownFor = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AgentSettings(this)
        AgentHost.init(this)
        ApprovalUi.init(this)

        // 状态栏取页面渐变的顶端色，和下面那条渐变接得住，不会切成「灰条 + 浅蓝页」
        window.statusBarColor = Ui.BG_TOP
        window.navigationBarColor = Ui.BG_BOTTOM
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        container = FrameLayout(this).apply { background = Ui.screen(this@MainActivity) }
        setContentView(container)

        // 常驻服务先起来：它是无障碍自愈的执行者，开关被关掉时正需要它活着。
        KeepAliveService.ensureRunning(this)
        // 服务可能早就活着而网关被手动关过，这里补一次；不起也没关系，设置页能手动开。
        if (settings.gatewayOn) runCatching { LocalGateway.start(this) }

        route()
        requestNotificationPermission()

        // 从「无障碍被关掉了」那条通知点进来：直接落到开关页，少一步找。
        if (intent?.getBooleanExtra(EXTRA_OPEN_A11Y, false) == true) {
            intent.removeExtra(EXTRA_OPEN_A11Y)
            container.post { openAccessibilityDetails() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_A11Y, false) == true) {
            intent.removeExtra(EXTRA_OPEN_A11Y)
            container.post { openAccessibilityDetails() }
        }
    }

    private val keeperRestored = { runOnUiThread { refreshAccessibility() } }

    override fun onResume() {
        super.onResume()
        AgentHost.addListener(this)
        ApprovalUi.attach(this)
        A11yKeeper.addListener(keeperRestored)
        refreshAccessibility()
        // 从系统设置页回来时，服务往往晚几百毫秒才绑定，补一次。
        container.postDelayed({ refreshAccessibility() }, 600)
    }

    override fun onPause() {
        AgentHost.removeListener(this)
        ApprovalUi.detach(this)
        A11yKeeper.removeListener(keeperRestored)
        super.onPause()
    }

    override fun onDestroy() {
        dismissApproval()
        voice?.shutdown()
        voice = null
        mic?.destroy()
        mic = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 导航

    private fun route() {
        if (!settings.onboardingDone || !Auth.isLoggedIn()) showOnboarding() else showChat()
    }

    private fun showOnboarding() {
        chat = null
        val view = OnboardingUi(
            act = this,
            onLogin = {
                board()?.let { step ->
                    when {
                        step.isLoginDone() -> {
                            step.showStep(2)
                            step.setPermission(ServiceHealth.enabled(this))
                        }
                        step.isLoginBusy() -> toast("正在等你在浏览器里确认…")
                        else -> startLogin()
                    }
                }
            },
            onOpenAccessibility = { openAccessibilitySettings() },
            onOpenAppInfo = { openAppInfo() },
            onAllowBackground = { openBatterySettings() },
            onAutoRestore = { showKeeperGuide() },
            onFinish = {
                settings.onboardingDone = true
                showChat()
            },
        )
        onboarding = view
        container.removeAllViews()
        container.addView(view.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 登录态还在（比如刚才是想重开权限），直接跳到第二步。
        if (Auth.isLoggedIn()) {
            view.showStep(2)
            view.setPermission(ServiceHealth.enabled(this))
        }
    }

    private fun board(): OnboardingUi? = onboarding

    private fun showChat() {
        onboarding = null
        val view = ChatUi(
            act = this,
            onSend = { text, images, payload -> send(text, images, payload) },
            onStop = { AgentHost.stop() },
            onNewChat = { newChat() },
            onSettings = { showSettings() },
            onReconnect = { openAccessibilityDetails() },
            onPickModel = { showModelPicker() },
            onOpenArtifact = { showArtifactPreview(it) },
            onSessions = { showSessions() },
            onWorkspace = { showWorkspace() },
            onCamera = { pickPhoto(true) },
            onGallery = { pickPhoto(false) },
            onFile = { pickFile() },
            onVoice = { toggleVoice() },
            onSpeak = { text -> speakAnswer(text) },
            onStopSpeak = { voiceOut().stop() },
            onFork = { index, answer -> forkHere(index, answer) },
        )
        chat = view
        container.removeAllViews()
        container.addView(view.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val saved = AgentHost.currentHistory()
        if (saved != null && saved.length() > 0) {
            restoreTranscript(view, saved)
        } else {
            view.welcome()
        }
        view.setSessionTitle(currentSessionTitle())
        refreshAccessibility()
    }

    /** 当前会话的标题，给顶栏胶囊用；还没起名（新会话）就显示「新对话」。 */
    private fun currentSessionTitle(): String {
        val cur = AgentHost.currentSessionId()
        return AgentHost.listSessions().firstOrNull { it.id == cur }?.title ?: "新对话"
    }

    /**
     * 从这里分叉：把这条回答之前的全部内容复制进一个新对话，原对话一字不动。
     *
     * 分叉点是界面记下的历史下标；找不到（旧会话、历史被压缩过）就让 AgentHost
     * 按正文找回同一条回答，还找不到就明说没成，绝不切出一条内容不对的会话。
     */
    private fun forkHere(index: Int, answer: String) {
        val id = AgentHost.forkFrom(index, answer, currentSessionTitle())
        if (id == null) {
            toast("没分成：这条回答还没进历史，等这轮结束再试")
            return
        }
        // 新会话的历史 = 分叉点之前的内容，重画就是「接着那段往下聊」的样子
        showChat()
        toast("已分叉到新对话，原对话还在「☰」里")
    }

    /**
     * 把持久化的历史画回消息流。截图的 base64 没存（太大），只留一条说明；
     * 工具卡按 tool_call_id 配对结果，折叠状态与实时跑完一致。
     */
    private fun restoreTranscript(view: ChatUi, history: org.json.JSONArray) {
        val resultsById = HashMap<String, String>()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") {
                resultsById[m.optString("tool_call_id")] = m.optString("content")
            }
        }
        val answers = StringBuilder()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> {
                    val content = m.opt("content")
                    if (content is String) {
                        // 压缩摘要（系统注入的，不是用户说的）：画成居中注记行。
                        // 只显示目标和折叠量，过程行是给模型看的流水账，人看没意义。
                        if (AgentLoop.isSummaryMessage(m)) {
                            // 套娃摘要（旧版 bug 的存量数据）：剥到最内层拿原始任务；
                            // 剥出来还是摘要头（被截断、原始目标丢了）就不显示目标。
                            val goal = AgentLoop.summaryGoalOf(content)
                            val clean = goal.isNotEmpty() && !goal.startsWith(AgentLoop.SUMMARY_PREFIX)
                            view.addNote(
                                if (clean) "—— 早期消息已压缩，任务目标：$goal ——"
                                else "—— 此处早期消息已压缩成摘要 ——"
                            )
                            continue
                        }
                        // 带文件的历史：正文只给过模型，气泡里裁掉，留一句「含文件 xxx」
                        val marker = "\n---\n【用户附上文件："
                        val shown = if (content.contains(marker)) {
                            val head = content.substringBefore(marker).trim()
                            val fname = content.substringAfter(marker, "").substringBefore("】")
                            "$head（含文件 $fname）".trim()
                        } else content
                        if (shown.isNotEmpty()) view.addUser(shown)
                    } else if (content is org.json.JSONArray) {
                        // 多模态消息（用户发的图片 / 屏幕截图）：只还原文字部分，
                        // base64 不进界面也不进气泡，末尾标一句「含图片」让上下文说得通。
                        val text = StringBuilder()
                        var hasImage = false
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            if (part.optString("type") == "text") text.append(part.optString("text"))
                            if (part.optString("type") == "image_url") hasImage = true
                        }
                        val shown = text.toString().trim()
                        if (shown.isNotEmpty()) {
                            view.addUser(if (hasImage) "$shown（含图片）" else shown)
                        }
                    }
                }
                "assistant" -> {
                    val content = m.opt("content")
                    if (content is String && content.isNotEmpty()) {
                        // 带上历史下标：长按菜单的「从这里分叉」按它切历史
                        view.addAssistant(content, i)
                        view.closeAssistant()
                        answers.append(content).append('\n')
                    }
                    val calls = m.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until calls.length()) {
                        val call = calls.optJSONObject(j) ?: continue
                        val fn = call.optJSONObject("function") ?: continue
                        val name = fn.optString("name")
                        val args = runCatching { JSONObject(fn.optString("arguments")) }
                            .getOrDefault(JSONObject())
                        view.addTool(toolLabel(name), compactArgs(args))
                        val result = resultsById[fn.optString("id")] ?: ""
                        view.finishTool(
                            !result.startsWith("{\"ok\":false"),
                            toolSummary(result),
                            prettyResult(result),
                        )
                        // 产物卡也要跟着历史画回来。只补消息不补卡片，
                        // 用户重启 App 后就会发现「文档又看不到预览了」。
                        if (name == "fs_write" || name == "fs_edit") {
                            val p = runCatching { JSONObject(result).optString("path") }
                                .getOrNull().orEmpty()
                            if (!result.startsWith("{\"ok\":false") && p.isNotEmpty()) {
                                view.addArtifact(p)
                            }
                        }
                    }
                }
            }
        }
        // 兜底：上下文压缩可能把 fs_write 的 tool 消息折掉了，光靠结果补不回卡片。
        // 把历史里所有回答扫一遍，认出工作台里真实存在的文件补卡——
        // 「闪退重启后产物卡消失」就是这么来的，光修渲染不够，入口也得还在。
        revealMentionedArtifacts(answers.toString())
    }

    // ---------------------------------------------------------------- 登录

    private fun startLogin() {
        board()?.setLoginWaiting(true)
        Thread {
            AgentHost.login(
                openUrl = { url ->
                    runOnUiThread {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            toast("打不开浏览器，请手动访问：\n$url")
                        }
                    }
                },
                onDone = { ok, message ->
                    runOnUiThread {
                        if (ok) {
                            settings.lastAccountName = AgentHost.accountName
                            board()?.setLoginDone(AgentHost.accountName)
                        } else {
                            board()?.setLoginWaiting(false)
                            toast(message)
                        }
                    }
                }
            )
        }.start()
    }

    // ---------------------------------------------------------------- 对话

    /**
     * 发送。气泡里显示的是 [text]；[filePayload] 不为空时表示带了文件附件，
     * 给模型的历史用 payload（含文件正文），界面上只留用户那句话。
     * [images] 是这次附带的照片（data URL，可多张）。
     */
    private fun send(text: String, images: List<String>? = null, filePayload: String? = null) {
        val view = chat ?: return
        // ensureSessionId：删掉当前会话后直接发消息也能拿到 id，和 ask 里用的是同一个
        val sid = AgentHost.ensureSessionId()
        // 只挡「这个会话」：别的会话在跑不影响这里开新一轮（并行多对话）。
        if (AgentHost.isBusy(sid)) {
            toast("这个会话还在跑，先点停止，或者开个新会话并行")
            return
        }
        val toModel = filePayload ?: text
        // 发图问话不需要操控手机（「这张图里是什么」），无障碍关了也放行；
        // 但只要模型想动手机，工具会报错——所以纯问图/发文件才走这条捷径。
        val withAttachment = !images.isNullOrEmpty() || filePayload != null
        if (!withAttachment && !accessibilityOn) {
            // 授权过的话，这大概率是刚被系统摘掉：给一次机会让 keeper 写回来，别急着让人去翻设置。
            if (A11yKeeper.granted(this)) {
                A11yKeeper.check(this, force = true)
                view.addAssistant("无障碍刚被系统关掉了，我正在把它写回来。\n两秒后再发一次就行。")
            } else {
                view.addAssistant("无障碍没开，我现在看不到你的屏幕，也没法点。开一下再试。")
                view.setOffline(true)
            }
            return
        }
        // 开关开着、服务还没绑上：系统刚把它拉起来，再等一会儿就能用。
        // 这时候直接跑任务，每个工具都会报 NO_ACCESSIBILITY，白白烧一轮 token。
        if (!withAttachment && !connected()) {
            A11yKeeper.check(this, force = true)
            view.addAssistant("无障碍开关是开的，但服务还没就绪。等一秒钟再发一次就行。")
            return
        }
        stream.setLength(0)
        view.addUser(text)
        if (withAttachment) view.clearPendingImage()
        view.resetFooter()
        view.setRunning(true, "正在准备…")
        running = true
        uiSession = sid
        AgentHost.ask(toModel, sessionListener(sid), images)
    }

    // ---------------------------------------------------------------- 附件

    /** 拍照 / 相册：拿系统界面选图，回来后压成 data URL 挂到待发区。 */
    private fun pickPhoto(camera: Boolean) {
        val intent = if (camera) AttachPicker.cameraIntent(this) else AttachPicker.galleryIntent()
        if (intent == null) {
            toast("这台设备上没有可用的相机应用")
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, if (camera) AttachPicker.REQ_CAMERA else AttachPicker.REQ_GALLERY)
        } catch (e: Exception) {
            toast("打不开：${e.message ?: "没有可用的应用"}")
        }
    }

    /** 文件：系统文件选择器，md/txt/代码/docx/pptx/pdf 都从这进。 */
    private fun pickFile() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(AttachPicker.fileIntent(), AttachPicker.REQ_FILE)
        } catch (e: Exception) {
            toast("打不开文件选择器：${e.message ?: "没有可用的应用"}")
        }
    }

    @Deprecated("startActivityForResult 是旧 API，但工程不引 androidx，够用且零依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uris: List<android.net.Uri> = when (requestCode) {
            AttachPicker.REQ_CAMERA -> listOfNotNull(AttachPicker.lastCamera())
            // 多选：clipData 里是全部勾选的；单选/老机型回退到 data.data
            AttachPicker.REQ_GALLERY -> {
                val list = ArrayList<android.net.Uri>()
                val clip = data?.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { list.add(it) }
                    }
                }
                if (list.isEmpty()) data?.data?.let { list.add(it) }
                list
            }
            AttachPicker.REQ_FILE -> listOfNotNull(data?.data)
            else -> return
        }
        if (uris.isEmpty()) return
        // 解码 / 抽取是纯 CPU 活，放主线程会卡一下（大文件能卡半秒）
        Thread {
            if (requestCode == AttachPicker.REQ_FILE) {
                receiveFile(uris.first())
            } else {
                val picked = ArrayList<AttachPicker.Picked>()
                for (u in uris) {
                    if (picked.size >= MAX_ATTACH_IMAGES) {
                        runOnUiThread { toast("一次最多带 $MAX_ATTACH_IMAGES 张，已取前 $MAX_ATTACH_IMAGES 张") }
                        break
                    }
                    AttachPicker.pick(this, u)?.let { picked.add(it) }
                }
                runOnUiThread {
                    if (picked.isEmpty()) {
                        toast("这些图都读不出来，换几张试试")
                    } else {
                        chat?.setPendingImages(picked)
                        if (picked.size == 1) toast("读好了，补一句话就能发")
                    }
                }
            }
            AttachPicker.cleanup(this)
        }.start()
    }

    /**
     * 文件选好了：拷进 cache → 按类型抽内容 → 挂到待发区。
     * 文本类直读，docx/pptx 抽 Markdown，PDF 渲染成图走视觉通道；
     * 其余格式明说支持范围，不让模型收一份乱码。
     */
    private fun receiveFile(uri: android.net.Uri) {
        val name = AttachPicker.displayName(this, uri, "文件")
        val local = AttachPicker.copyToCache(this, uri, name)
        if (local == null) {
            runOnUiThread { toast("文件太大或读不了（上限 20MB）") }
            return
        }
        val kind = FileText.route(name)
        if (kind == "pdf") {
            renderPdf(local, name)
            AttachPicker.cleanup(this)
            return
        }
        val ex = FileText.extract(local, name)
        runOnUiThread {
            when {
                ex.kind == "unsupported" -> toast(ex.text)
                ex.text.isBlank() -> toast("这个文件里读不出文字，换一份试试")
                else -> {
                    chat?.setPendingFile(name, ex.text)
                    toast("读好了，补一句话就能发")
                }
            }
        }
        AttachPicker.cleanup(this)
    }

    /**
     * PDF 没有本地文字抽取，用系统的 PdfRenderer 把页渲染成位图，竖着拼成一张长图
     * 走视觉通道。页数在 [MAX_PDF_PAGES] 以内直接整本拼；超过就先问用户要哪几页——
     * 拼图越拼越长，视觉模型会把长图整体压糊，与其悄悄丢页不如让用户圈范围。
     */
    private fun renderPdf(file: java.io.File, name: String) {
        val total = try {
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
            }
        } catch (e: Exception) {
            runOnUiThread { toast("PDF 打不开：${e.message?.take(60) ?: "文件可能损坏"}") }
            return
        }
        if (total <= MAX_PDF_PAGES) {
            renderPdfPages(file, name, (1..total).toList(), total)
            return
        }
        runOnUiThread { askPdfRange(file, name, total) }
    }

    /**
     * 超过单次上限的 PDF：弹输入框让用户挑页码范围（1 基，"7-12" 或 "1-3,8"）。
     * 填错不猜意图，提示重选；取消就把这次的缓存清掉。
     */
    private fun askPdfRange(file: java.io.File, name: String, total: Int) {
        val pad = dp(20)
        val box = android.widget.EditText(this).apply {
            setText("1-$MAX_PDF_PAGES")
            setSingleLine(true)
            textSize = 15f
            setPadding(pad, pad / 2, pad, pad / 2)
            setSelectAllOnFocus(true)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(box)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("选页码范围")
            .setMessage("《$name》共 $total 页。页数太多拼成一张长图会被压糊，一次最多带 $MAX_PDF_PAGES 页——填页码或范围（如 7-12，也可写 1-3,8）。")
            .setView(wrap)
            .setPositiveButton("发送") { _, _ ->
                val pages = FileText.parsePages(box.text.toString(), total, MAX_PDF_PAGES)
                if (pages == null) {
                    toast("页码没看懂（例：7-12），重新发一次文件再选")
                    AttachPicker.cleanup(this)
                } else {
                    Thread { renderPdfPages(file, name, pages, total) }.start()
                }
            }
            .setNegativeButton("取消") { _, _ -> AttachPicker.cleanup(this) }
            .setOnCancelListener { AttachPicker.cleanup(this) }
            .show()
    }

    /** 把选定页渲染拼图。pages 是 1 基页码，来自整本或用户圈的范围。 */
    private fun renderPdfPages(file: java.io.File, name: String, pages: List<Int>, total: Int) {
        try {
            val pfd = android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val n = pages.size
            var stitched: android.graphics.Bitmap? = null
            var canvas: android.graphics.Canvas? = null
            var y = 0
            for (pageNo in pages) {
                renderer.openPage(pageNo - 1).use { page ->
                    val scale = PDF_PAGE_WIDTH.toFloat() / page.width
                    val w = PDF_PAGE_WIDTH
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val pageBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    pageBmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(pageBmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    stampPageNo(pageBmp, pageNo)
                    if (stitched == null) {
                        stitched = android.graphics.Bitmap.createBitmap(
                            w, h * n, android.graphics.Bitmap.Config.ARGB_8888)
                        stitched!!.eraseColor(android.graphics.Color.WHITE)
                        canvas = android.graphics.Canvas(stitched!!)
                    }
                    canvas!!.drawBitmap(pageBmp, 0f, y.toFloat(), null)
                    y += h
                    pageBmp.recycle()
                }
            }
            renderer.close()
            pfd.close()
            val bmp = stitched
            if (bmp == null) {
                runOnUiThread { toast("这个 PDF 一页都渲染不出来，可能是加密的") }
                return
            }
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            val dataUrl = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            val thumb = android.graphics.Bitmap.createScaledBitmap(
                bmp, 220, (220f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            val span = if (n == 1) "第 ${pages[0]} 页" else "第 ${pages.first()}-${pages.last()} 页"
            val label = if (n < total) "$name · $span（共 $total 页）" else name
            runOnUiThread {
                chat?.setPendingImages(listOf(AttachPicker.Picked(dataUrl, thumb, label)))
                if (n < total) toast("带了 $n 页（共 $total 页），要别的页重新发一次文件选范围")
            }
        } catch (e: Exception) {
            runOnUiThread { toast("PDF 打不开：${e.message?.take(60) ?: "文件可能损坏"}") }
        }
    }

    /** 每页右上角盖一枚「第 N 页」角标：模型引用页码、用户核对范围都靠它。 */
    private fun stampPageNo(bmp: android.graphics.Bitmap, pageNo: Int) {
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88000000.toInt()
        }
        val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val label = "第 $pageNo 页"
        val rf = android.graphics.RectF(
            bmp.width - fg.measureText(label) - 46f, 18f, bmp.width - 18f, 66f)
        val c = android.graphics.Canvas(bmp)
        c.drawRoundRect(rf, 24f, 24f, bg)
        c.drawText(label, rf.centerX(), rf.centerY() - (fg.ascent() + fg.descent()) / 2f, fg)
    }

    /**
     * 给每次任务配一个「认会话」的监听器：只有事件属于**当前显示的会话**才画到界面上。
     * 后台会话的事件不进界面——它们留在自己 Runtime 的历史里，切回去时整段恢复。
     * 没有这层，两个会话并行时对方的回复会串到眼前这个对话里。
     */
    private fun sessionListener(sid: String): AgentLoop.Listener = object : AgentLoop.Listener {
        private val mine: Boolean get() = AgentHost.currentSessionId() == sid && uiSession == sid

        override fun onText(delta: String) = post { if (mine) uiOnText(delta) }
        override fun onToolStart(name: String, args: JSONObject) = post { if (mine) uiOnToolStart(name, args) }
        override fun onToolEnd(name: String, result: String, ok: Boolean) = post { if (mine) uiOnToolEnd(result, ok) }
        override fun onImage(dataUrl: String) = post { if (mine) uiOnImage(dataUrl) }

        // 每轮结束就落一次盘：App 中途被杀（闪退、系统回收）最多丢当前这轮，
        // 不会再像之前那样「闪退一次，整段对话和产物卡全没了」。
        override fun onTurnEnd(turn: Int) {
            AgentHost.saveSession(sid)
        }

        override fun onCompact(removed: Int, kept: Int) = post {
            if (mine) toast("较早的 $removed 条消息已折叠，保留最近 $kept 条")
        }

        override fun onArtifact(path: String) {
            AgentHost.saveSession(sid)
            post { if (mine) chat?.addArtifact(path) }
        }

        override fun onFinished(finalText: String, turns: Int, toolCalls: Int) = post {
            if (mine) {
                uiOnFinished(finalText)
            } else {
                toast("后台会话完成了，去「☰」里看结果")
            }
        }

        override fun onError(message: String) = post {
            if (mine) {
                uiOnError(message)
            } else {
                toast("后台会话出错了：$message")
            }
        }
    }

    private fun uiOnText(delta: String) {
        stream.append(delta)
        chat?.appendAssistant(delta)
        chat?.setRunningText("正在回复…")
    }

    private fun uiOnToolStart(name: String, args: JSONObject) {
        chat?.addTool(toolLabel(name), compactArgs(args))
        chat?.setRunningText(toolRunningText(name))
    }

    private fun uiOnToolEnd(result: String, ok: Boolean) {
        chat?.finishTool(ok, toolSummary(result), prettyResult(result))
    }

    private fun uiOnImage(dataUrl: String) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return
        val payload = dataUrl.substring(comma + 1)
        // 解码 1080 宽的 JPEG 要几十毫秒，别放在主线程。
        Thread {
            val bitmap = runCatching {
                val bytes = Base64.decode(payload, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap != null) runOnUiThread { chat?.addImage(bitmap) }
        }.start()
    }

    private fun uiOnError(message: String) {
        stream.setLength(0)
        chat?.addAssistant("这一步没成：$message")
        chat?.setRunning(false)
        running = false
    }

    private fun uiOnFinished(finalText: String) {
        val streamed = stream.toString()
        stream.setLength(0)
        if (streamed.isEmpty() && finalText.isNotEmpty()) {
            chat?.addAssistant(finalText)
        } else if (finalText.length > streamed.length && finalText.startsWith(streamed)) {
            chat?.appendAssistant(finalText.substring(streamed.length))
        }
        // 这一轮的回答刚进历史，把下标钉到气泡上——之后长按分叉就按它切
        chat?.bindAnswerIndex(AgentHost.lastAnswerIndex())
        chat?.closeAssistant()
        // 兜底：模型说「已整理成 xxx.md」但这一轮没走 fs_write（或卡片因别的原因没弹），
        // 只要文件真的在工作台里，就补一张预览卡——用户要的是能点开的东西，不是一句话。
        revealMentionedArtifacts(finalText.ifEmpty { streamed })
        chat?.setRunning(false)
        // 会话标题在落盘时才定（取第一条 user 消息），跑完这一轮顺手回填顶栏
        chat?.setSessionTitle(currentSessionTitle())
        chat?.showRoundSummary(AgentHost.lastServerModel(), AgentHost.modelLabel(), AgentHost.usageText())
        // 开了「语音朗读」就把这轮答案念出来。关着不动——办公室/地铁里自动出声是打扰。
        if (settings.voiceOn) {
            chat?.lastAnswerText()?.takeIf { it.length >= 8 }?.let { speakAnswer(it) }
        }
        running = false
    }

    // ---------------------------------------------------------------- 语音

    private var voice: VoiceOut? = null
    private var mic: VoiceIn? = null
    private var micListening = false

    private fun voiceOut(): VoiceOut = voice ?: VoiceOut(this).also { voice = it }

    private val micCallback = object : VoiceIn.Callback {
        override fun onPartial(text: String) {
            // 边说边填：用户能看见它在听，也能提前发现听错
            chat?.setVoiceText(text)
        }

        override fun onFinal(text: String) {
            chat?.setVoiceText(text)
            toast("识别完了，改一改就能发")
        }

        override fun onError(message: String) = toast(message)

        override fun onListening(listening: Boolean) {
            micListening = listening
            chat?.setListening(listening)
        }
    }

    /**
     * 点麦克风。语音输入走讯飞听写 API（VoiceIn 自己录音自己转写），
     * 不依赖设备的 RecognitionService——这台 vivo 什么识别服务都没有，
     * 云端转写也被 chat 网关拒过（HTTP 400 code 11101）。剩下唯一的闸门是麦克风权限。
     */
    private fun toggleVoice() {
        if (micListening) {
            mic?.stop()
            return
        }
        startVoice()
    }

    private fun startVoice() {
        val engine = mic ?: VoiceIn(this, micCallback).also { mic = it }
        if (!engine.available) {
            explainNoVoice()
            return
        }
        if (!micGranted()) {
            // 系统权限框回来后才真正开始听（见 onRequestPermissionsResult）
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
            return
        }
        engine.start()
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 设置页「语音输入」那一行的副标题：直接说清缺什么，别让用户猜。 */
    private fun voiceStatusText(): String = when {
        Config.IAT_APP_ID.isBlank() -> "未配置听写 API · 暂时用键盘话筒"
        micGranted() -> "可用 · 点输入框旁的 🎤"
        else -> "缺麦克风权限 · 点这里授权"
    }

    /** 设置页点「语音输入」：按缺的那一环给出下一步，而不是一句泛泛的提示。 */
    private fun voiceCheck() {
        when {
            Config.IAT_APP_ID.isBlank() -> explainNoVoice()
            !micGranted() -> runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
            else -> toast("语音输入可用：回对话页点输入框旁的 🎤 说话，说完点 ■")
        }
    }

    /**
     * 听写 API 没配置（或想引导键盘话筒）时的说明框。重点不是解释为什么，
     * 而是**给出当下就能用的一条路**：输入法自带的话筒。
     * 那套识别跟着输入法走（讯飞/搜狗/百度输入法都有），识别结果直接写进输入框。
     */
    private fun explainNoVoice() {
        AlertDialog.Builder(this)
            .setTitle("这台手机没有语音识别服务")
            .setMessage(
                "月溯不做离线识别，声音要靠系统里的「语音转文字」服务，这台设备没装。\n\n" +
                    "· 用键盘话筒（立刻可用）：点下面的按钮弹出键盘，再按输入法上的 🎤 说，字会直接进输入框；\n" +
                    "· 或者装一个带识别能力的应用（Google、讯飞语记等），回来再点 🎤。"
            )
            .setPositiveButton("打开键盘") { _, _ -> chat?.focusInput() }
            .setNegativeButton("知道了", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // 从设置页点的「语音输入」只更新状态，不回对话页偷偷开始听
            if (settingsShowing) {
                toast("麦克风权限已开，回对话页点 🎤 就能说")
                showSettings()
            } else {
                startVoice()
            }
        } else {
            toast("没给麦克风权限，语音输入用不了：设置 → 应用 → 月溯 → 权限 → 麦克风")
        }
    }

    private fun speakAnswer(text: String) {
        chat?.setSpeaking(true)
        voiceOut().speak(text) { runOnUiThread { chat?.setSpeaking(false) } }
    }

    /** 当前在界面上显示的会话；事件过滤用它，防止后台会话的回复串到眼前的对话里。 */
    private var uiSession: String? = null

    /** 二进制产物：预览器读不了，给说明卡 + 分享。 */
    private val BINARY_ARTIFACTS = setOf(
        "pdf", "pptx", "docx", "xlsx", "zip", "png", "jpg", "jpeg", "webp", "gif", "mp3", "mp4",
    )

    /**
     * 开新会话。当前会话就算在跑也放行：任务在自己的 Runtime 里继续，
     * 跑完自动落盘——这就是「同时进行多对话」的入口。
     */
    private fun newChat() {
        AgentHost.newSession()
        chat?.welcome()
        chat?.resetFooter()
        toast("已开启新会话；旧的就算在跑也会继续跑完")
    }

    /**
     * 会话抽屉：从左边滑出，照 dsh-Go 的侧边栏布局——
     *
     * ```
     * (logo) 月溯               (✎ 新对话)
     * (🔍 搜索对话…                  )
     * [全部] [今天] [更早]
     * 今天
     *   浏览器打开学习通网页版登录
     * 昨天
     *   GitHub热门项目
     *   …
     * ─────────────────────────────
     * ▢ 工作台            ( ⚙ )
     * ```
     *
     * 列表按「今天 / 昨天 / 更早」分组；搜索框实时过滤标题；点行切换、
     * 长按删除；底部一排是工作台入口和设置——设置收进抽屉，
     * 是 dsh-Go 的放法，聊天页顶栏因此不用再挂 ⋯。
     */
    private fun showSessions() {
        val metas = AgentHost.listSessions()
        val cur = AgentHost.currentSessionId()
        val busyIds = AgentHost.runningSessionIds()

        val sheet = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = android.widget.FrameLayout(this)
        // 遮罩色和 dsh-Go 一样带一点蓝，不用纯黑
        scrim.setBackgroundColor(0x590B111F)
        scrim.setOnClickListener { sheet.dismiss() }

        val drawer = Ui.column(this)
        // 左侧贴屏幕边，右侧两角大圆
        drawer.background = Ui.solidRadii(this, Ui.CARD, intArrayOf(0, dp(22), dp(22), 0))
        drawer.setPadding(dp(14), dp(16), dp(14), dp(12))

        // ---- 头部：标志 + 名字 + 新对话主按钮
        val head = Ui.row(this)
        head.addView(Ui.logo(this, 36, 12, 13f))
        val nameCol = Ui.column(this)
        nameCol.setPadding(dp(10), 0, 0, 0)
        nameCol.addView(Ui.text(this, "月溯", 16.5f, Ui.INK, true))
        nameCol.addView(Ui.text(this, "${metas.size} 个会话", 11f, Ui.INK3).apply {
            setPadding(0, dp(3), 0, 0)
        })
        head.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val newBtn = Ui.text(this, "✎ 新对话", 13f, Color.WHITE, true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = Ui.solid(this@MainActivity, Ui.BRAND, 20)
            setOnClickListener {
                sheet.dismiss()
                newChat()
            }
        }
        head.addView(newBtn)
        drawer.addView(head)

        // ---- 搜索框
        val search = android.widget.EditText(this).apply {
            hint = "搜索对话"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Ui.INK)
            setHintTextColor(Ui.INK4)
            background = Ui.solid(this@MainActivity, Ui.SOFT, 20)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setSingleLine(true)
        }
        drawer.addView(search, Ui.margin(this, top = 12))

        // ---- 过滤 chips：全部 / 今天 / 更早
        var filter = "全部"
        val chipsRow = Ui.row(this)
        chipsRow.setPadding(0, dp(10), 0, dp(2))
        val listBox = Ui.column(this)
        val chipViews = LinkedHashMap<String, TextView>()

        fun renderList() {
            listBox.removeAllViews()
            val q = search.text.toString().trim()
            val picked = metas.filter { m ->
                (q.isEmpty() || m.title.contains(q, true)) && when (filter) {
                    "今天" -> m.updatedAt >= todayStart()
                    "更早" -> m.updatedAt < todayStart()
                    else -> true
                }
            }
            var lastSection = ""
            for (m in picked) {
                val section = when {
                    m.updatedAt >= todayStart() -> "今天"
                    m.updatedAt >= todayStart() - 86_400_000L -> "昨天"
                    else -> "更早"
                }
                if (filter == "全部" && section != lastSection) {
                    listBox.addView(Ui.text(this, section, 11.5f, Ui.INK4, true).apply {
                        setPadding(dp(6), dp(12), dp(6), dp(4))
                    })
                    lastSection = section
                }
                val isCur = m.id == cur
                val row = Ui.row(this)
                row.setPadding(dp(12), dp(11), dp(12), dp(11))
                row.background = if (isCur) Ui.solid(this@MainActivity, Ui.BRAND_BG, 12) else null
                if (isCur) {
                    row.addView(View(this).apply {
                        background = Ui.solid(this@MainActivity, Ui.BRAND, 2)
                    }, LinearLayout.LayoutParams(dp(3), dp(30)).apply { rightMargin = dp(10) })
                }
                val text = Ui.column(this)
                val title = Ui.text(this, m.title, 14f, if (isCur) Ui.ON_BRAND_BG else Ui.INK, isCur)
                title.maxLines = 1
                title.ellipsize = android.text.TextUtils.TruncateAt.END
                text.addView(title)
                val isBusy = m.id in busyIds
                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINESE)
                    .format(java.util.Date(m.updatedAt))
                val tag = when {
                    isBusy -> " · ● 任务进行中"
                    isCur -> " · 当前"
                    else -> ""
                }
                text.addView(Ui.text(this, "${m.count} 条消息 · $time$tag",
                    11f, when {
                        isBusy -> Ui.OK
                        isCur -> Ui.BRAND
                        else -> Ui.INK3
                    }).apply { setPadding(0, dp(3), 0, 0) })
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener {
                    if (m.id == cur) {
                        sheet.dismiss()
                        return@setOnClickListener
                    }
                    val saved = AgentHost.switchSession(m.id)
                    chat?.clear()
                    val h = saved
                    if (h != null && h.length() > 0) restoreTranscript(chat!!, h) else chat?.welcome()
                    chat?.setSessionTitle(currentSessionTitle())
                    chat?.resetFooter()
                    // 目标会话要是正在跑，停止条跟着点亮；流式回复会继续画到界面上
                    val busy = AgentHost.isBusy(m.id)
                    uiSession = m.id
                    running = busy
                    chat?.setRunning(busy)
                    sheet.dismiss()
                }
                row.setOnLongClickListener {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("删除这个会话？")
                        .setMessage("「${m.title}」的${m.count} 条消息会一起删掉，不可恢复。")
                        .setPositiveButton("删除") { _, _ ->
                            if (!AgentHost.deleteSession(m.id)) {
                                toast("这个会话还有任务在跑，停了再删")
                            } else {
                                if (isCur) {
                                    chat?.welcome()
                                    chat?.resetFooter()
                                }
                                toast("已删除")
                                sheet.dismiss()
                                showSessions()
                            }
                        }
                        .setNegativeButton("算了", null)
                        .show()
                    true
                }
                listBox.addView(row, Ui.margin(this, top = 2, bottom = 2))
            }
            if (picked.isEmpty()) {
                listBox.addView(Ui.text(this,
                    if (q.isEmpty()) "还没有历史会话。说完第一句话，这里就有了。"
                    else "没有匹配「$q」的会话",
                    12.5f, Ui.INK3).apply { setPadding(dp(6), dp(18), dp(6), dp(18)) })
            }
        }

        fun syncChips() {
            for ((label, chip) in chipViews) {
                val on = label == filter
                chip.background = if (on) Ui.solid(this, Ui.BRAND_BG, 16)
                else Ui.solid(this, Ui.GLASS_SOFT, 16)
                chip.setTextColor(if (on) Ui.ON_BRAND_BG else Ui.INK2)
            }
        }

        for (label in listOf("全部", "今天", "更早")) {
            val chip = filterChip(label, label == filter) {
                filter = label
                syncChips()
                renderList()
            }
            chipViews[label] = chip
            chipsRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(7) })
        }
        drawer.addView(chipsRow)

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = renderList()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        val scroll = android.widget.ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listBox)
        }
        drawer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(4) })

        // ---- 底部：工作台 + 设置（都是文字，同字号同高度）
        drawer.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val foot = Ui.row(this)
        foot.gravity = android.view.Gravity.CENTER_VERTICAL
        foot.setPadding(dp(8), dp(8), dp(4), dp(4))
        val ws = Ui.text(this, "工作台", 13.5f, Ui.INK, true).apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener {
                sheet.dismiss()
                showWorkspace()
            }
        }
        foot.addView(ws, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 设置直接用文字，跟左边「工作台」同字号同高度——图标画得再像也不如两个字清楚
        foot.addView(Ui.text(this, "设置", 13.5f, Ui.INK, true).apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener {
                sheet.dismiss()
                showSettings()
            }
        })
        drawer.addView(foot)

        scrim.addView(drawer, android.widget.FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.84f).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // 点抽屉本身不穿透到遮罩
        drawer.setOnClickListener { }
        sheet.setContentView(scrim)
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        renderList()
        syncChips()
        sheet.show()
    }

    /** 今天 0 点的时间戳，抽屉里「今天 / 昨天 / 更早」分组的分界。 */
    private fun todayStart(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 抽屉里的过滤 chip，选中态由 [showSessions] 里的 syncChips 统一刷。 */
    private fun filterChip(label: String, on: Boolean, onClick: () -> Unit): TextView =
        Ui.text(this, label, 12.5f, if (on) Ui.ON_BRAND_BG else Ui.INK2, true).apply {
            setPadding(dp(13), dp(7), dp(13), dp(7))
            background = Ui.solid(this@MainActivity,
                if (on) Ui.BRAND_BG else Ui.GLASS_SOFT, 16)
            setOnClickListener { onClick() }
        }

    /**
     * 模型自检。点一下就知道「切换到底生效没有」——结果里并排放着
     * 我们请求的模型和服务端回传的模型，不一致会直接说不一致。
     */
    private fun checkModel() {
        toast("发一条最小请求确认…")
        AgentHost.probeModel { report ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("模型自检")
                    .setMessage(report)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun checkGateway() {
        toast("经本地网关发一条…")
        AgentHost.probeGateway { report ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("网关自检")
                    .setMessage(report)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------- AgentLoop 回调（经 sessionListener 过滤后到这里）

    /**
     * 从这轮的回答里认出工作台里真实存在的文件，补产物卡。
     *
     * 只认「带常见文本扩展名的文件名」，且必须能在工作台（最多三层）里找到同名文件；
     * 已经有卡的不重复出。宁可少认，不可把不存在的路径画成卡片——点开报「文件不见了」
     * 比没有卡片更糟。
     */
    private fun revealMentionedArtifacts(text: String) {
        if (text.isBlank()) return
        val view = chat ?: return
        val dir = getExternalFilesDir(null) ?: filesDir
        val rx = Regex(
            """[\p{L}\p{N}_.\-/]+\.(?:md|txt|json|csv|py|js|ts|html|log|ya?ml|pdf|pptx|docx|xlsx|zip|png|jpe?g|webp)""",
            RegexOption.IGNORE_CASE
        )
        for (m in rx.findAll(text)) {
            val name = m.value.trimStart('/')
            if (name.length > 120 || view.hasArtifact(name)) continue
            val guess = java.io.File(dir, name)
            val hit = if (guess.isFile) guess else dir.walkTopDown().maxDepth(3)
                .firstOrNull { it.isFile && it.name == guess.name }
            if (hit == null || !hit.isFile) continue
            view.addArtifact(hit.relativeTo(dir).path.replace('\\', '/'))
        }
    }

    override fun onStateChanged(state: AgentHost.State, detail: String) {
        runOnUiThread {
            // 「在跑」看的是**当前会话**：别的会话在后台跑，不该把这里的停止条点亮。
            val busy = AgentHost.isBusy(AgentHost.currentSessionId())
            chat?.setRunning(busy)
            if (!busy) running = false
            KeepAliveService.refresh(this)
        }
    }

    override fun onLogAppended(line: String) = Unit

    // ---------------------------------------------------------------- 无障碍状态

    private fun refreshAccessibility() {
        val enabled = ServiceHealth.enabled(this)
        accessibilityOn = enabled
        // 系统里是开着的，说明用户确实要用这个能力。记下来，之后掉了才敢自动写回。
        if (enabled) settings.a11yWanted = true
        chat?.setOffline(!enabled)
        onboarding?.let { if (it.currentStep() == 2) it.setPermission(enabled) }
        // 常驻服务不再"无障碍开着才起"：它是自愈的手，开关被关掉时正需要它活着。
        KeepAliveService.ensureRunning(this)
        A11yKeeper.check(this, force = true)
    }

    /**
     * 开关开着但服务没绑上，是最容易误判的状态：显示已开启，点下去全失败。
     * 这种情况多说一句，比让任务跑到一半报错强。
     */
    private fun connected(): Boolean = ServiceHealth.connected()

    private fun openAccessibilitySettings() {
        if (!ServiceHealth.enabled(this) && !connected()) {
            toast("在列表里找到「月溯」打开开关")
        }
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("打不开设置页，请手动到 设置 → 无障碍") }
    }

    /**
     * 直接打开本应用的无障碍详情页（整页只有一个开关）。
     * 这是 Android 8 起才有的入口，老系统上退回列表页。
     */
    private fun openAccessibilityDetails() {
        val detail = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(detail) }.isSuccess) {
            toast("把「月溯」的开关打开即可")
            return
        }
        openAccessibilitySettings()
    }

    private fun openAppInfo() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }.onFailure { toast("打不开应用信息页") }
    }

    private fun openBatterySettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        toast("把这个应用设为「不优化」，在系统里锁定后台；vivo 再到应用信息里允许「后台弹出界面」")
    }

    // ---------------------------------------------------------------- 审批

    /**
     * 由 [ApprovalUi] 在主线程调用。必须在这台手机上点——任务就发生在这儿，
     * 让用户在别处确认等于把最后一道防线架在离现场最远的地方。
     */
    fun showApproval(request: ApprovalRequest, onDecision: (ApprovalDecision) -> Unit) {
        if (approvalShownFor == request.id && approvalSheet?.isShowing() == true) return
        dismissApproval()
        approvalShownFor = request.id
        val sheet = ApprovalSheet(this, request, onDecision)
        approvalSheet = sheet
        // Activity 正在销毁时 show() 会抛 BadTokenException。闸门不该因为界面没了就崩：
        // 它还有超时兜底，等不到回应按拒绝处理。
        runCatching { sheet.show() }.onFailure {
            approvalSheet = null
            toast("这一步需要你确认，但界面没能弹出来")
        }
    }

    fun dismissApproval() {
        val sheet = approvalSheet ?: return
        approvalSheet = null
        runCatching { sheet.dismiss() }
    }

    // ---------------------------------------------------------------- 设置

    /** 设置页正盖在对话页上；返回键先收设置页，不直接退 App。 */
    private var settingsShowing = false

    /**
     * 设置页：整页自绘，照 dsh-Go 的设置版式——
     * 顶部返回圆钮 + 大标题，下面是账户卡 + 分组卡片（AI / 权限 / 通用），
     * 每行一个彩色圆徽章 + 标题 + 当前值 + ›。系统 AlertDialog 的白底方角
     * 和这套雾蓝界面不是一路的，而且 12 行列表塞进对话框也没法分组。
     */
    private fun showSettings() {
        settingsShowing = true
        container.removeAllViews()
        container.addView(buildSettingsPage(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closeSettings() {
        settingsShowing = false
        reattachChat()
    }

    override fun onBackPressed() {
        if (artifactShowing) closeArtifact()
        else if (workspaceShowing) closeWorkspace()
        else if (settingsShowing) closeSettings()
        else super.onBackPressed()
    }

    private fun buildSettingsPage(): View {
        val page = Ui.column(this)
        page.background = Ui.screen(this)

        // ---- 顶栏：返回圆钮 + 大标题
        val head = Ui.row(this)
        head.setPadding(dp(14), dp(14), dp(14), dp(4))
        head.addView(Ui.text(this, "←", 17f, Ui.INK).apply {
            gravity = android.view.Gravity.CENTER
            contentDescription = "返回"
            background = Ui.solid(this@MainActivity, Ui.GLASS, 20)
            setOnClickListener { closeSettings() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(Ui.text(this, "设置", 20f, Ui.INK, true).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(head)

        val scroll = android.widget.ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = Ui.column(this)
        body.setPadding(dp(16), dp(8), dp(16), dp(24))

        // ---- 账户卡：头像 + 账号名 + 一行运行状态
        val account = Ui.column(this)
        account.background = Ui.solid(this, Ui.CARD, 18, Ui.LINE, 1)
        val who = Ui.row(this)
        who.setPadding(dp(14), dp(13), dp(14), dp(11))
        who.addView(Ui.logo(this, 46, 23, 16f))
        val whoText = Ui.column(this)
        whoText.setPadding(dp(12), 0, 0, 0)
        val accountName = AgentHost.accountName.ifBlank { "未登录" }
        whoText.addView(Ui.text(this, accountName, 15.5f, Ui.INK, true))
        whoText.addView(Ui.text(this,
            if (accountName == "未登录") "登录后才能对话" else "已登录 · 网关就绪",
            11.5f, Ui.INK3).apply { setPadding(0, dp(3), 0, 0) })
        who.addView(whoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        account.addView(who)
        account.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val status = Ui.row(this)
        status.setPadding(dp(16), dp(11), dp(14), dp(11))
        status.addView(View(this).apply {
            background = Ui.circle(this@MainActivity, if (accessibilityOn) Ui.OK else Ui.WARN, 7)
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(9) })
        status.addView(Ui.text(this,
            "手机操控 " + (if (accessibilityOn) "已开启" else "未开启") +
                " · 命令行 " + (if (settings.shellOn) "开" else "关"),
            12f, Ui.INK3))
        account.addView(status)
        body.addView(account, Ui.margin(this, bottom = 18))

        // ---- AI
        body.addView(settingsGroup("AI", listOf(
            settingsRow("✦", Ui.BRAND_BG, Ui.ON_BRAND_BG, "模型",
                ModelRegistry.resolve(AgentHost.modelId()).summary()) { showModelPicker() },
            settingsRow("⇅", Ui.BRAND_BG, Ui.ON_BRAND_BG, "模型自检",
                "发一条最小请求") { checkModel() },
            settingsRow("◇", Ui.BRAND_BG, Ui.ON_BRAND_BG, "网关自检",
                "验 dsh-Go 那条路") { checkGateway() },
            settingsRow("⊞", Ui.BRAND_BG, Ui.ON_BRAND_BG, "接入 dsh-Go",
                "本地网关") { showDshGoSetup() },
        )), Ui.margin(this, bottom = 18))

        // ---- 权限
        body.addView(settingsGroup("权限", listOf(
            settingsRow("◉", Ui.OK_BG, Ui.OK, "手机操控",
                if (accessibilityOn) "已开启" else "未开启") { openAccessibilityDetails() },
            settingsRow("◈", Ui.BRAND_BG, Ui.ON_BRAND_BG, "悬浮状态窗",
                if (!settings.floatOn) "关"
                else if (FloatStatus.permitted(this)) "开" else "需要授权") {
                if (!settings.floatOn) {
                    settings.floatOn = true
                    if (!FloatStatus.permitted(this)) FloatStatus.openPermissionPage(this)
                    toast("切到别的应用也能看到它在干什么")
                } else {
                    settings.floatOn = false
                    FloatStatus.refresh(this)
                    toast("已关闭悬浮状态窗")
                }
                if (settingsShowing) showSettings()
            },
            // 跟「悬浮状态窗」分开：那个是看进度用的、可以关；这个是确认框能不能
            // 直接浮在目标应用上，关掉就得每次切回月溯点允许。
            settingsRow("◇", Ui.WARN_BG, Ui.WARN, "审批悬浮卡",
                if (ApprovalOverlay.permitted(this)) "已授权 · 点这里试一下"
                else "未授权 · 每次都要切回月溯") {
                if (ApprovalOverlay.permitted(this)) {
                    // 就地自检：不用真跑一个任务，也能确认卡片能不能浮起来、按钮能不能点。
                    // 排查过「卡片一闪就没 / 一直卡在等待审批」，这类问题肉眼两秒就能看出来。
                    val demo = ApprovalRequest(
                        id = -1L,
                        action = "demo",
                        rule = "demo",
                        reason = "试一下：审批卡直接浮在当前应用上面，点按钮不用切回来",
                        target = "示例 · 微信",
                        critical = false,
                        timeoutSeconds = 5,
                    )
                    val ok = runCatching { ApprovalOverlay.show(this, demo) {} }.getOrDefault(false)
                    toast(if (ok) "卡片应该已经浮在屏幕底部，5 秒后自动消失" else "挂不上，看看是不是权限被系统收回了")
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ ApprovalOverlay.dismiss() }, 5000)
                } else {
                    FloatStatus.openPermissionPage(this)
                    toast("打开「显示在应用上层」后返回，确认框就会浮在目标应用上")
                }
            },
            settingsRow("◎", Ui.BRAND_BG, Ui.BRAND, "长期记忆",
                if (!settings.memoryOn) "关"
                else {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val n = Memory.entries(dir).size
                    if (n == 0) "暂无记忆" else "已记 $n 条"
                }) {
                if (settings.memoryOn) {
                    settings.memoryOn = false
                    toast("已关闭：新会话不再带长期记忆（文件还在）")
                    if (settingsShowing) showSettings()
                } else {
                    settings.memoryOn = true
                    toast("已开启：新会话会带上记下的偏好和事实")
                    if (settingsShowing) showSettings()
                }
            },
            settingsRow("◈", Ui.BRAND_BG, Ui.BRAND, "技能包",
                if (!settings.skillsOn) "关"
                else {
                    val all = AgentHost.skillItems()
                    val on = all.count { it.name !in settings.disabledSkills }
                    when {
                        all.isEmpty() -> "暂无"
                        on == all.size -> "已启用 ${all.size} 个"
                        else -> "已启用 $on / ${all.size}"
                    }
                }) { showSkills() },
            settingsRow("↺", Ui.OK_BG, Ui.OK, "自动恢复无障碍",
                if (A11yKeeper.granted(this)) "已授权" else "未授权") { showKeeperGuide() },
            settingsRow("❯", Ui.WARN_BG, Ui.WARN, "执行命令（shell）",
                if (settings.shellOn) "已打开" else "已关闭") {
                toggleShell()
                if (settingsShowing) showSettings()   // 值变了，整页重画
            },
        )), Ui.margin(this, bottom = 18))

        // ---- 语音
        body.addView(settingsGroup("语音", listOf(
            settingsRow("◍", Ui.BRAND_BG, Ui.BRAND, "自动朗读回答",
                if (settings.voiceOn) "每轮都念" else "关") {
                settings.voiceOn = !settings.voiceOn
                toast(
                    if (settings.voiceOn) "以后每轮回答自动念出来（长回答只说重点）"
                    else "已关闭自动朗读，想听点回答下面的「朗读」"
                )
                if (settingsShowing) showSettings()
            },
            settingsRow("🎤", Ui.BRAND_BG, Ui.ON_BRAND_BG, "语音输入",
                voiceStatusText()) { voiceCheck() },
        )), Ui.margin(this, bottom = 18))

        // ---- 通用
        body.addView(settingsGroup("通用", listOf(
            settingsRow("▢", Ui.SOFT2, Ui.INK2, "工作台目录",
                "文件写在这里") { closeSettings(); showWorkspace("settings") },
            settingsRow("⇄", Ui.SOFT2, Ui.INK2, "任务后自动回对话页",
                if (settings.autoReturn) "开" else "关") {
                settings.autoReturn = !settings.autoReturn
                toast(if (settings.autoReturn) "做完会自动回到对话页" else "已关闭，做完不再自动回来")
                if (settingsShowing) showSettings()
            },
            settingsRow("✎", Ui.SOFT2, Ui.INK2, "开启新会话",
                "旧的保留在抽屉里") {
                closeSettings()
                newChat()
            },
            settingsRow("≡", Ui.SOFT2, Ui.INK2, "查看运行日志") { showLog() },
        )), Ui.margin(this, bottom = 18))

        // ---- 危险区
        body.addView(settingsGroup("账户", listOf(
            settingsRow("⎋", Ui.SOFT2, Ui.DANGER, "退出登录") { confirmLogout() },
        )))

        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    /** 设置页的一行：彩色圆徽章 + 标题 + 当前值 + ›。 */
    private fun settingsRow(
        icon: String, iconBg: Int, iconFg: Int,
        title: String, value: String = "", onClick: () -> Unit,
    ): View {
        val row = Ui.row(this)
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        val badge = Ui.text(this, icon, 14f, iconFg).apply {
            gravity = android.view.Gravity.CENTER
            background = Ui.solid(this@MainActivity, iconBg, 17)
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(11) })
        row.addView(Ui.text(this, title, 14f, Ui.INK),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotEmpty()) {
            row.addView(Ui.text(this, value, 12f, Ui.INK3).apply {
                setPadding(0, 0, dp(7), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(140)
            })
        }
        row.addView(Ui.text(this, "›", 15f, Ui.INK4))
        row.setOnClickListener { onClick() }
        return row
    }

    /** 设置页的一个分组：小节标题 + 一张圆角卡，行间用发丝线分隔。 */
    private fun settingsGroup(title: String, rows: List<View>): View {
        val wrap = Ui.column(this)
        wrap.addView(Ui.text(this, title, 12f, Ui.INK3, true).apply {
            setPadding(dp(6), 0, 0, dp(7))
        })
        val card = Ui.column(this)
        card.background = Ui.solid(this, Ui.CARD, 16, Ui.LINE, 1)
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                card.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            card.addView(r)
        }
        wrap.addView(card)
        return wrap
    }

    // ---------------------------------------------------------------- 工作台

    /**
     * 工作台目录说明。
     *
     * 一旦允许模型读写文件，用户第一反应必然是「写到哪去了」。不告诉他，等于在他手机里
     * 留了一个看不见的抽屉——那比不给这个能力更糟。
     */
    /** 工作台整页正盖在对话页上；返回键先收工作台。 */
    private var workspaceShowing = false

    /** 工作台是从哪进来的（chat / settings），返回时回到原处。 */
    private var workspaceFrom = "chat"

    /**
     * 工作台：整页文件浏览（原来是个 AlertDialog，只有一坨文字路径）。
     *
     * 目录里每个文件一行卡片：文件名 + 大小 + 改动时间 + 删除钮，点开直接进阅读页预览——
     * 「能点开的东西」比一段路径文字有用得多。顶部保留复制路径和清空两个操作。
     */
    private fun showWorkspace(from: String = "chat") {
        workspaceFrom = from
        workspaceShowing = true
        container.removeAllViews()
        container.addView(buildWorkspacePage(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closeWorkspace() {
        workspaceShowing = false
        if (workspaceFrom == "settings") {
            showSettings()
        } else {
            reattachChat()
        }
    }

    /** 把对话页装回容器（设置/工作台等整页关闭时共用）。 */
    private fun reattachChat() {
        container.removeAllViews()
        chat?.root?.let {
            container.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildWorkspacePage(): View {
        val dir = getExternalFilesDir(null) ?: filesDir
        val files = dir.walkTopDown().filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .take(500)
            .toList()

        val page = Ui.column(this)
        page.background = Ui.screen(this)

        // ---- 顶栏：返回圆钮 + 大标题 + 复制路径
        val head = Ui.row(this)
        head.setPadding(dp(14), dp(14), dp(14), dp(4))
        head.addView(Ui.text(this, "←", 17f, Ui.INK).apply {
            gravity = android.view.Gravity.CENTER
            contentDescription = "返回"
            background = Ui.solid(this@MainActivity, Ui.GLASS, 20)
            setOnClickListener { closeWorkspace() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(Ui.text(this, "工作台", 20f, Ui.INK, true).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.textButton(this, "复制路径", Ui.INK2, 12.5f) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("path", dir.absolutePath))
            toast("路径已复制")
        })
        page.addView(head)

        val scroll = android.widget.ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = Ui.column(this)
        body.setPadding(dp(16), dp(8), dp(16), dp(24))

        body.addView(Ui.text(this,
            "${dir.absolutePath.replace("/storage/emulated/0", "内部存储")} · 共 ${files.size} 个文件",
            10.5f, Ui.INK4).apply { setPadding(dp(2), dp(4), dp(2), dp(12)) })

        if (files.isEmpty()) {
            val empty = Ui.column(this)
            empty.gravity = android.view.Gravity.CENTER
            empty.setPadding(dp(20), dp(60), dp(20), dp(20))
            empty.addView(Ui.text(this, "还是空的", 16f, Ui.INK2, true))
            empty.addView(Ui.text(this, "让助手写点什么，文件就会出现在这里。\n比如「写一个 todo.md」。",
                12.5f, Ui.INK3).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            body.addView(empty)
        } else {
            for (f in files) {
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                val row = Ui.row(this)
                row.setPadding(dp(13), dp(11), dp(13), dp(11))
                row.background = Ui.solid(this, Ui.CARD, 14, Ui.LINE, 1)

                val text = Ui.column(this)
                val name = Ui.text(this, rel, 13f, Ui.INK, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
                text.addView(name)
                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINESE)
                    .format(java.util.Date(f.lastModified()))
                text.addView(Ui.text(this, "${humanSize(f.length())} · $time", 11f, Ui.INK3)
                    .apply { setPadding(0, dp(3), 0, 0) })
                row.addView(text, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // 删除钮：视觉小但热区 44dp；先确认再删，误触一次长按或点到不会直接丢文件
                row.addView(Ui.text(this, "🗑", 13f, Ui.INK4).apply {
                    gravity = android.view.Gravity.CENTER
                    contentDescription = "删除 $rel"
                    setOnClickListener { confirmDeleteFile(f, rel) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                row.addView(Ui.text(this, "›", 14f, Ui.INK4))

                row.setOnClickListener { showArtifactPreview(rel) }
                body.addView(row, Ui.margin(this, bottom = 8))
            }
        }

        // ---- 底部危险操作：清空
        if (files.isNotEmpty()) {
            body.addView(Ui.textButton(this, "清空工作台", Ui.DANGER, 13f) {
                confirmWipeWorkspace(dir)
            }, Ui.margin(this, top = 10))
        }

        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    /** 文件大小的说法：B / KB / MB，一位小数都嫌多就去掉。 */
    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(java.util.Locale.CHINESE, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(java.util.Locale.CHINESE, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---------------------------------------------------------------- 产物预览

    /** 阅读页正盖在对话页上；返回键先收阅读页，不直接退 App。 */
    private var artifactShowing = false

    /**
     * 点产物卡片：整页阅读视图（照 dsh-Go 的预览版式）。
     *
     * Markdown 渲染成排版（标题/表格/引用/列表/代码井），不是 Markdown 的
     * 文件整篇进等宽代码井。超 40 万字符只读前段，末尾注明后面还有多少。
     */
    private fun showArtifactPreview(path: String) {
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = java.io.File(dir, path)
        if (!f.exists() || !f.isFile) {
            toast("文件不见了：$path")
            return
        }
        // 二进制产物分三路：PDF/图片本机真渲染；pptx/docx/zip 是 zip 包着 XML，
        // 解出正文转 Markdown 走阅读视图；其余给说明卡 + 分享。
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_ARTIFACTS) {
            artifactShowing = true
            container.removeAllViews()
            if (ext == "pdf" || ext in BinaryPreview.IMAGE_EXT) {
                container.addView(
                    BinaryPreview.build(this, f, path,
                        onClose = { closeArtifact() },
                        onShare = { shareArtifact(f, path) }),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                return
            }
            if (ext in setOf("pptx", "docx", "zip")) {
                val md = when (ext) {
                    "pptx" -> OfficeText.pptxToMarkdown(f)
                    "docx" -> OfficeText.docxToMarkdown(f)
                    else -> OfficeText.zipToMarkdown(f)
                }
                if (md != null) {
                    container.addView(
                        ArtifactReader.build(
                            this, path, md, null,
                            onClose = { closeArtifact() },
                            onCopy = {
                                val cm = getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("content", md))
                                toast("已复制提取的文本")
                            },
                            onShare = { shareArtifact(f, path) },
                        ),
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                    return
                }
            }
            val sizeKb = f.length() / 1024
            val desc = when (ext) {
                "pptx" -> "PPT 演示文稿，可在 WPS / PowerPoint 里编辑"
                else -> "二进制文件"
            }
            container.addView(
                ArtifactReader.build(
                    this, path, "# $path\n\n这是一个 **$desc**，共 $sizeKb KB。\n\n" +
                        "预览器暂不支持直接显示，点下面的「分享」用 WPS、浏览器或其他应用打开。\n",
                    null,
                    onClose = { closeArtifact() },
                    onCopy = { toast("二进制文件没有可复制的内容") },
                    onShare = { shareArtifact(f, path) },
                ),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            return
        }
        var tail: String? = null
        val content = try {
            val cap = 400_000
            if (f.length() > cap) {
                val head = f.inputStream().use { input ->
                    val buf = ByteArray(cap)
                    val n = input.read(buf)
                    String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
                }
                tail = "…后面还有，共 ${f.length()} 字节"
                head
            } else {
                f.readText()
            }
        } catch (e: Exception) {
            toast("读不出来，可能是二进制文件")
            return
        }

        artifactShowing = true
        container.removeAllViews()
        container.addView(
            ArtifactReader.build(
                this, path, content, tail,
                onClose = { closeArtifact() },
                onCopy = {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("content", content))
                    toast("已复制 ${content.length} 个字符")
                },
                onShare = { shareArtifact(f, path) },
            ),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun closeArtifact() {        artifactShowing = false
        if (workspaceShowing) {
            // 从工作台点进来的预览：返回键回到工作台列表，不跳回对话
            container.removeAllViews()
            container.addView(buildWorkspacePage(), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            reattachChat()
        }
    }

    /** 通过只读 provider 把文件交给别的应用（WPS、浏览器、文件管理器）。 */
    private fun shareArtifact(f: java.io.File, path: String) {
        val uri = android.net.Uri.Builder()
            .scheme("content")
            .authority("$packageName.artifacts")
            .encodedPath("/" + path.trimStart('/'))
            .build()
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 没有应用能打开这个类型：退到系统分享面板，让用户自己挑
            val share = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "打开 $path"))
        }
    }

    /**
     * shell 开关。
     *
     * 关闭直接关，开启要过一个说人话的确认——这是唯一一个能力超出沙箱的开关，
     * 值得多问一句。默认关，不是不信任模型，是让用户知道自己给了什么。
     */
    private fun toggleShell() {
        if (settings.shellOn) {
            settings.shellOn = false
            toast("已关闭：助手不能再执行命令")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("允许执行命令？")
            .setMessage(
                "打开后，助手可以在工作台目录里执行 shell 命令，批量处理文件会快很多。\n\n" +
                    "它没有 root 权限，碰不到系统和其他应用的数据。但命令能做的事比读写文件多，" +
                    "建议先用几天文件工具，觉得信得过再打开。"
            )
            .setPositiveButton("打开") { _, _ ->
                settings.shellOn = true
                toast("已打开。下一次对话生效")
            }
            .setNegativeButton("算了", null)
            .show()
    }

    /** 删单个文件：确认一次再动手，删完好回列表。 */
    private fun confirmDeleteFile(f: java.io.File, rel: String) {
        AlertDialog.Builder(this)
            .setTitle("删除这个文件？")
            .setMessage("$rel\n\n删了就找不回来了。")
            .setPositiveButton("删除") { _, _ ->
                if (f.delete()) toast("已删除 $rel") else toast("没删掉：文件可能被占用")
                // 整页重画：计数、空状态、列表都要跟着变
                if (workspaceShowing) showWorkspace(workspaceFrom)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmWipeWorkspace(dir: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("清空工作台？")
            .setMessage("会删掉助手写在这个目录里的所有文件，不可撤销。")
            .setPositiveButton("删掉") { _, _ ->
                val n = dir.walkTopDown().count { it.isFile }
                dir.deleteRecursively()
                dir.mkdirs()
                toast("已删除 " + n + " 个文件")
                // 整页重画：列表和空状态要跟着变
                if (workspaceShowing) showWorkspace(workspaceFrom)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 接入 dsh-Go

    /**
     * 把 WorkBuddy 网关交给 dsh-Go 用的三样东西：Base URL、API Key、模型 ID。
     *
     * 不改 dsh-Go 一行代码的理由：它的「自定义渠道」就是标准 OpenAI 协议，
     * `normalizeBaseUrl` 对 OpenAI 风格地址只去尾斜杠、不会硬塞 `/v1`，
     * 所以 `…/v2` 正好拼成 `/v2/chat/completions`；API Key 也只校验可打印 ASCII，
     * JWT 直接能过。真正会过期的是令牌，所以这一页把到期时间摆在明面上。
     */
    private fun showDshGoSetup() {
        val token = Auth.accessToken()
        if (token == null) {
            toast("还没登录，先登录再来配置")
            return
        }
        val expiry = TokenInfo.expirySeconds(token)
        val now = System.currentTimeMillis() / 1000L
        val modelIds = ModelRegistry.OPTIONS.joinToString("\n") { it.id }
        val local = GatewayPlan.localBaseUrl(LocalGateway.port)

        val box = Ui.column(this)
        box.setPadding(dp(2), dp(10), dp(2), dp(2))

        box.addView(sectionTitle("推荐：走本地网关"))
        box.addView(
            Ui.text(
                this,
                "dsh-Go 装官方版，不用改。设置 › 模型 › 添加渠道，协议选 OpenAI，填下面三项。\n" +
                    "请求先到本机网关，再由它按官方客户端的形态发出去——直连会被服务端判成" +
                    "「非官方渠道」（11128），走这一层才稳。",
                12.5f, Ui.INK2
            ).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            Ui.margin(this, bottom = 10)
        )

        box.addView(fieldRow("Base URL", local, local, "填在「Base URL」，末尾别加斜杠"))
        box.addView(fieldRow("API Key", "wb-local", "wb-local", "随便填一个非空值即可，令牌由本应用自动带上"))
        box.addView(
            fieldRow(
                "模型 ID",
                "hy3（免费，先用它试通）· glm-5.3-flash · deepseek-v4.1-flash …",
                modelIds,
                "现在「获取模型」能直接拉到整份清单；拉不到就手动添加 hy3"
            )
        )

        box.addView(gatewayStatusCard())

        LocalGateway.lastCaptureFile?.let { f ->
            box.addView(
                fieldRow(
                    "上次 dsh-Go 请求原文",
                    "（点一下复制，最多前 400KB）",
                    try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" },
                    "排查 11128 用：把复制到的东西发回来即可"
                )
            )
        }

        box.addView(sectionTitle("备用：直连网关（令牌会过期）"))
        box.addView(
            Ui.text(
                this,
                "只有在本地网关起不来时再用。Base URL 填 $DSH_GO_BASE，API Key 填下面这串，" +
                    "它 ${TokenInfo.expiryText(expiry)} 过期（${TokenInfo.remainingText(expiry, now)}）。",
                11.5f, if (expiry > 0 && expiry - now < 3 * 86_400L) Ui.WARN else Ui.INK3
            ).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            Ui.margin(this, bottom = 8)
        )
        box.addView(fieldRow("直连 API Key", token, token, "点一下复制整串令牌"))

        AlertDialog.Builder(this)
            .setTitle("接入 dsh-Go")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("复制全部") { _, _ -> copy("dsh-Go 配置", dshGoText(local, modelIds)) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun sectionTitle(text: String): View =
        Ui.text(this, text, 12f, Ui.INK, true).apply {
            setPadding(0, dp(6), 0, dp(6))
        }

    /**
     * 网关状态卡：运行中/没起来、端口、上次转发结论。
     * 出问题时这一张卡就能说清是「没起来」「没收到请求」还是「上游拒绝」——不必再猜。
     */
    private fun gatewayStatusCard(): View {
        val on = LocalGateway.running
        val card = Ui.column(this)
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = Ui.solid(this, if (on) Ui.OK_BG else Ui.WARN_BG, 10, if (on) Ui.LINE else Ui.WARN_LINE, 1)
        card.addView(
            Ui.text(
                this,
                if (on) "本地网关：运行中 · 127.0.0.1:${LocalGateway.port}" else "本地网关：没起来（点这里启动）",
                11.5f, if (on) Ui.INK else Ui.WARN, true
            )
        )
        // 副标题必须与标题同源：没在运行时就不该出现「已监听」，哪怕上一次确实监听成功过。
        card.addView(
            Ui.text(
                this,
                if (on) LocalGateway.lastResult
                else "当前没在运行 · 点这张卡启动，dsh-Go 里填 " + GatewayPlan.localBaseUrl(GatewayPlan.PORT),
                10.5f, Ui.INK3
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        if (on) {
            card.addView(
                Ui.text(
                    this,
                    "请求体档位 ${LocalGateway.level}：" + GatewayPlan.levelNote(LocalGateway.level),
                    10f,
                    if (LocalGateway.level == 1) Ui.INK2 else Ui.WARN
                ).apply {
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(dp(2).toFloat(), 1f)
                }
            )
        }
        if (LocalGateway.lastDetail.isNotEmpty()) {
            card.addView(
                Ui.text(this, "上次上游报错：" + LocalGateway.lastDetail, 10f, Ui.WARN).apply {
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 5
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
        }
        card.setOnClickListener {
            if (LocalGateway.running) {
                settings.gatewayOn = false
                LocalGateway.stop()
                toast("已停止本地网关")
            } else {
                settings.gatewayOn = true
                KeepAliveService.ensureRunning(this)
                LocalGateway.start(this)
                toast("已启动：" + GatewayPlan.localBaseUrl(LocalGateway.port))
            }
        }
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
    }

    /** 一行可复制的字段：标题 + 值（等宽、最多三行），点整块复制。 */
    private fun fieldRow(label: String, shown: String, copyValue: String, hint: String): View {
        val card = Ui.column(this)
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = Ui.solid(this, Color.WHITE, 10, Ui.LINE, 1)
        card.addView(Ui.text(this, label, 11f, Ui.INK3, true))
        card.addView(Ui.text(this, shown, 11.5f, Ui.INK).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, 0)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (hint.isNotEmpty()) {
            card.addView(Ui.text(this, hint, 10.5f, Ui.INK3).apply { setPadding(0, dp(4), 0, 0) })
        }
        card.setOnClickListener { copy(label, copyValue) }
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun copy(label: String, value: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, value))
        }.onSuccess { toast("已复制$label") }.onFailure { toast("复制失败，长按选中试一下") }
    }

    private fun dshGoText(localBase: String, modelIds: String): String = buildString {
        appendLine("dsh-Go › 设置 › 模型 › 添加渠道（兼容协议选 OpenAI）")
        appendLine("Base URL：$localBase")
        appendLine("API Key：wb-local（随便填，令牌由本机网关自动带）")
        appendLine("模型 ID：")
        append(modelIds)
    }

    /**
     * 选模型。清单和桌面端那个下拉框一致，倍率只作参考——真实扣费看服务端返回的 usage。
     *
     * 17 项塞不进系统的单行单选列表（后半截会被省略号吃掉），所以每行自己画成两行：
     * 上为模型名，下为一句话说明，右侧挂倍率；选中项左边带一条品牌色竖线。
     * 这里不重启会话：切换只影响下一次请求，历史原样带过去。
     */
    /**
     * 模型选择器：底部升起的自绘卡片（照会话抽屉的弹层质感）。
     * 原来是系统 AlertDialog——方角白底、列表贴边、选中态只有底色没有标记，
     * 和这套雾蓝界面明显不是一路的。
     */
    private fun showModelPicker() {
        val options = ModelRegistry.OPTIONS
        var current = ModelRegistry.indexOf(AgentHost.modelId())
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)

        val scrim = Ui.column(this)
        scrim.setBackgroundColor(0x660B111F)
        scrim.gravity = android.view.Gravity.BOTTOM

        val card = Ui.column(this)
        card.background = Ui.solidRadii(this, Ui.CARD, intArrayOf(dp(22), dp(22), 0, 0))
        card.setPadding(dp(18), dp(16), dp(18), dp(18))

        // ---- 标题行：选择模型 + 关闭
        val head = Ui.row(this)
        head.addView(Ui.text(this, "选择模型", 17f, Ui.INK, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(this, "关闭", 13f, Ui.INK3).apply {
            setPadding(dp(10), dp(6), dp(2), dp(6))
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(head)

        card.addView(Ui.text(this,
            "倍率是相对基准价的系数，真实扣费以服务端返回的用量为准。需要「看着屏幕点」的任务，选标注了「多模态」的更稳。",
            11.5f, Ui.INK3).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(12))
        })

        // ---- 列表：每行一张圆角卡，选中的带主色竖条 + ✓
        val rows = HashMap<Int, LinearLayout>()
        val listHolder = Ui.column(this)
        val scroll = android.widget.ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listHolder)
        }

        fun renderRow(position: Int) {
            val o = options[position]
            val picked = position == current
            // 行结构固定为 [竖条 | 文字列 | 价签]，重画只换内容和底色，不重建视图
            val row = rows.getOrPut(position) {
                Ui.row(this@MainActivity).also { r ->
                    r.addView(View(this@MainActivity), LinearLayout.LayoutParams(
                        dp(3), dp(30)).apply { rightMargin = dp(11) })
                    r.addView(Ui.column(this@MainActivity), LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }
            row.setPadding(dp(12), dp(11), dp(12), dp(11))
            row.background = if (picked) Ui.solid(this@MainActivity, Ui.BRAND_BG, 14)
            else Ui.solid(this@MainActivity, Ui.CARD, 14, Ui.LINE, 1)

            // 竖条未选中时也占位同宽，避免整行文字左右跳
            row.getChildAt(0).background =
                Ui.solid(this@MainActivity, if (picked) Ui.BRAND else Color.TRANSPARENT, 2)

            val body = row.getChildAt(1) as LinearLayout
            body.removeAllViews()
            body.addView(Ui.text(this@MainActivity, o.label, 14.5f,
                if (picked) Ui.BRAND else Ui.INK, true))
            if (o.note.isNotEmpty()) {
                body.addView(Ui.text(this@MainActivity, o.note, 11.5f, Ui.INK3)
                    .apply { setPadding(0, dp(2), 0, 0) })
            }

            val cost = when {
                o.credit.isEmpty() -> ""
                o.credit == "x0.00" -> "免费"
                else -> o.credit
            }
            while (row.childCount > 2) row.removeViewAt(row.childCount - 1)
            row.addView(
                Ui.text(this@MainActivity, if (picked) "✓ $cost" else cost, 11.5f,
                    if (picked) Ui.BRAND else Ui.INK3, picked).apply {
                    setPadding(dp(10), 0, 0, 0)
                }
            )
            row.setOnClickListener {
                val old = current
                current = position
                AgentHost.setModel(o.id)
                chat?.setModel(o)
                toast("已切换到 ${o.label}，下一句话就生效")
                // 让选中态先被看见，再关。
                if (old != position) {
                    renderRow(old)
                    renderRow(position)
                    scroll.postDelayed({ dialog.dismiss() }, 220)
                } else {
                    dialog.dismiss()
                }
            }
        }

        for (i in options.indices) {
            renderRow(i)
            listHolder.addView(rows[i]!!, Ui.margin(this, bottom = 7))
        }

        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.52f).toInt()
        ))

        scrim.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrim.setOnClickListener { dialog.dismiss() }
        card.setOnClickListener { }

        dialog.setContentView(scrim)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }

    /**
     * 自动恢复的授权说明。这条权限只能从电脑授一次，所以要把命令准备好、
     * 能一键复制，而不是让用户自己去拼包名。
     */
    fun showKeeperGuide() {
        val command = A11yKeeper.grantCommand(packageName)
        if (A11yKeeper.granted(this)) {
            AlertDialog.Builder(this)
                .setTitle("自动恢复已授权")
                .setMessage(
                    "开关被系统关掉时，它会在两秒内自己写回去；重启手机后也会补回来。\n\n" +
                        "想彻底停用，先在设置里关掉这个功能，再去系统设置里关无障碍——" +
                        "否则你手动关掉，它会把开关又写回来。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val body = TextView(this).apply {
            text = "有些系统（尤其国产 ROM）在你清理后台时会直接把无障碍开关关掉。" +
                "应用没有办法自己重新打开它——除非拿到一个特殊权限。\n\n" +
                "这条权限要用一条命令授予，一次就行，升级不丢：\n\n" +
                command + "\n\n" +
                "电脑用数据线连上手机、打开 USB 调试，在命令行里执行上面这条。\n" +
                "没有电脑的话，装 Shizuku 也能给同样的权限。\n\n" +
                "授权之后：开关掉了两秒内自己写回，你不用察觉；重启手机也会补回来。"
            textSize = 13.5f
            setTextColor(Ui.INK2)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle("让它自己把开关写回来")
            .setView(body)
            .setPositiveButton("复制命令") { _, _ -> copyToClipboard("wb-a11y-grant", command) }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 技能包面板：看清单、单独开关。
     *
     * 这里不做内容编辑——技能就是工作台 `skills/` 里的 Markdown，改内容该在能看清全文的
     * 地方改（工作台或文件管理器）。手机上塞一个编辑器，编辑两行就得滑三屏，是帮倒忙。
     */
    private fun showSkills() {
        val items = AgentHost.skillItems()
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)

        val scrim = Ui.column(this)
        scrim.setBackgroundColor(0x660B111F)
        scrim.gravity = android.view.Gravity.BOTTOM
        scrim.setOnClickListener { dialog.dismiss() }

        val card = Ui.column(this)
        card.background = Ui.solidRadii(this, Ui.CARD, intArrayOf(dp(22), dp(22), 0, 0))
        card.setPadding(dp(18), dp(16), dp(18), dp(18))
        // 点卡片不该把卡片关掉（点击事件别冒泡到遮罩）
        card.isClickable = true

        val head = Ui.row(this)
        head.addView(Ui.text(this, "技能包", 17f, Ui.INK, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(this, "关闭", 13f, Ui.INK3).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(head)

        card.addView(Ui.text(this,
            "模型平时只看到「名字 + 什么时候用」的清单，命中才加载正文——所以技能多也不占平时的额度。",
            11.5f, Ui.INK3).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(12))
        })

        // ---- 总开关
        val master = Ui.row(this)
        master.setPadding(dp(13), dp(12), dp(13), dp(12))
        master.background = Ui.solid(this, Ui.SOFT, 14)
        master.addView(Ui.text(this, "启用技能包", 14.5f, Ui.INK),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        master.addView(Ui.text(this, if (AgentHost.skillsOn()) "开" else "关", 13f,
            if (AgentHost.skillsOn()) Ui.BRAND else Ui.INK3, true))
        master.setOnClickListener {
            AgentHost.setSkillsOn(!AgentHost.skillsOn())
            if (settingsShowing) showSettings()
            dialog.dismiss()
            showSkills()
        }
        card.addView(master, Ui.margin(this, bottom = 10))

        // ---- 清单
        val list = Ui.column(this)
        if (items.isEmpty()) {
            list.addView(Ui.text(this, "还没有技能。往工作台的 skills/ 目录放一个 .md 就会出现在这里。",
                12.5f, Ui.INK3).apply { setPadding(dp(2), dp(10), dp(2), dp(10)) })
        }
        for (item in items) {
            val on = item.name !in AgentHost.disabledSkills()
            val row = Ui.row(this)
            row.setPadding(dp(13), dp(11), dp(13), dp(11))
            row.background = Ui.solid(this, if (on) Ui.CARD else Ui.SOFT, 14, Ui.LINE, 1)

            val textBox = Ui.column(this)
            textBox.addView(Ui.text(this, item.title, 14.5f, if (on) Ui.INK else Ui.INK3, true))
            if (item.desc.isNotEmpty()) {
                textBox.addView(Ui.text(this, item.desc, 11.5f, Ui.INK3).apply {
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            textBox.addView(Ui.text(this, "${item.name} · ${item.bytes / 1024} KB", 10.5f, Ui.INK3).apply {
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.text(this, if (on) "已启用" else "已停用", 11.5f,
                if (on) Ui.BRAND else Ui.INK3, on))
            row.setOnClickListener {
                val nowOn = AgentHost.toggleSkill(item.name)
                toast(if (nowOn) "已启用「${item.title}」" else "已停用「${item.title}」，清单里不再出现")
                if (settingsShowing) showSettings()
                dialog.dismiss()
                showSkills()
            }
            list.addView(row, Ui.margin(this, bottom = 8))
        }

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // 超过 4 个才限高滚动，否则底部留一大片空白更难看
            if (items.size > 4) height = dp(340)
        })

        card.addView(Ui.text(this,
            "工作台/skills/ · 放一个 .md 就是新技能，改文件内容立即生效",
            10.5f, Ui.INK3).apply { setPadding(0, dp(10), 0, 0) })

        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("命令已复制，到电脑上粘贴执行")
    }

    private fun showLog() {
        val body = TextView(this).apply {
            text = AgentHost.snapshotLog().ifEmpty { "还没有记录" }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFC8D0DC.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF12141A.toInt())
            addView(body)
        }
        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("会删掉本机保存的令牌，下次要重新登录。对话上下文也会清空。")
            .setPositiveButton("退出") { _, _ ->
                AgentHost.logout()
                settings.onboardingDone = false
                showOnboarding()
            }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        // 不给也行：审批框回到 App 仍会弹，只是切走时收不到提醒，常驻通知也不会显示。
        runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
    }

    // ---------------------------------------------------------------- 文案

    private fun toolLabel(name: String): String = when (name) {
        "phone_status" -> "设备状态"
        "phone_read_screen" -> "读屏"
        "phone_tap" -> "点按"
        "phone_tap_at" -> "坐标点按"
        "phone_long_press" -> "坐标长按"
        "phone_input" -> "输入"
        "phone_press_key" -> "按键"
        "phone_swipe" -> "滑动"
        "phone_launch_app" -> "启动应用"
        "phone_screenshot" -> "截图"
        "fs_list" -> "列目录"
        "fs_read" -> "读文件"
        "fs_write" -> "写文件"
        "fs_edit" -> "改文件"
        "fs_search" -> "搜索内容"
        "web_fetch" -> "抓网页"
        "pdf_write" -> "生成 PDF"
        "pptx_write" -> "生成 PPT"
        "docx_write" -> "生成 Word"
        "memory_save" -> "记到长期记忆"
        "skill_load" -> "加载技能"
        else -> name
    }

    private fun toolRunningText(name: String): String = when (name) {
        "phone_read_screen" -> "正在读屏…"
        "phone_screenshot" -> "正在截图…"
        "phone_tap", "phone_tap_at" -> "正在点按…"
        "phone_long_press" -> "正在长按…"
        "phone_launch_app" -> "正在打开应用…"
        "phone_input" -> "正在输入…"
        "fs_list" -> "正在列目录…"
        "fs_read" -> "正在读文件…"
        "fs_write" -> "正在写文件…"
        "fs_edit" -> "正在改文件…"
        "fs_search" -> "正在搜索…"
        "web_fetch" -> "正在抓网页…"
        "pdf_write" -> "正在生成 PDF…"
        "pptx_write" -> "正在生成 PPT…"
        "docx_write" -> "正在生成 Word…"
        "memory_save" -> "正在记下…"
        "skill_load" -> "正在翻技能…"
        else -> "正在执行…"
    }

    private fun compactArgs(args: JSONObject): String {
        if (args.length() == 0) return ""
        val parts = ArrayList<String>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = args.optString(key)
            parts.add(if (key == "text" || key == "value") "已填写" else value.take(28))
        }
        return parts.joinToString(" · ")
    }

    /** 把工具返回的 JSON 压成一句话。给模型看的原文仍然在展开后的详情里。 */
    private fun toolSummary(result: String): String = try {
        val obj = JSONObject(result)
        if (!obj.optBoolean("ok", true)) {
            listOf(obj.optString("error"), obj.optString("code"))
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
                .ifEmpty { "失败" }
        } else {
            val parts = ArrayList<String>()
            val keys = obj.keys()
            while (keys.hasNext() && parts.size < 2) {
                val key = keys.next()
                if (key == "ok") continue
                when (val value = obj.opt(key)) {
                    is String -> if (value.isNotEmpty() && value.length <= 40) parts.add(value)
                    is Int -> parts.add("$key $value")
                    is Boolean -> if (value) parts.add(key)
                }
            }
            parts.joinToString(" · ").ifEmpty { "完成" }
        }
    } catch (e: Exception) {
        result.replace("\n", " ").take(60)
    }

    private fun prettyResult(result: String): String =
        if (result.length > 600) result.take(600) + "\n…（还有 ${result.length - 600} 字）" else result

    // ---------------------------------------------------------------- 小工具

    private fun post(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_NOTIFY = 1001

        /** PDF 附件：拼图里最多带的页数与单页渲染宽度（px）。 */
        private const val MAX_PDF_PAGES = 6
        private const val PDF_PAGE_WIDTH = 1000

        /** 一次最多带几张照片：每张图对模型都是上千 token，多了烧上下文还不聚焦。 */
        private const val MAX_ATTACH_IMAGES = 6

        /** 麦克风权限的请求码：听写 API 自己录音，授权后才开始听。 */
        private const val REQ_MIC = 1004

        /** 通知点进来时要直接落到无障碍开关页。 */
        const val EXTRA_OPEN_A11Y = "open_a11y"

        /**
         * 给 dsh-Go 填的 Base URL。它自己会拼 `/chat/completions`，
         * 拼出来正好是我们验证过的 `/v2/chat/completions`。
         */
        private const val DSH_GO_BASE = Config.BASE_URL + "/v2"
    }
}
