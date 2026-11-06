package cn.wbphone.agent

import java.io.File

/**
 * 技能包：工作台 `skills/` 目录下每个 .md 一门手艺。
 *
 * 问题：手机上的助手什么都懂一点，但「写周报」「整笔记」「改简历」这类事有明确的套路——
 * 先问什么、按什么顺序做、输出成什么形状。这些套路写进系统提示会撑爆（每轮都重发），
 * 又不该让模型每次从零琢磨。
 *
 * 做法是**渐进披露**（Claude Skills 的那套思路，这里做到手机上够用的最小版）：
 *  1. 系统提示里只放一份索引——每个技能一行：名字、用途、什么时候用（约 40 字）；
 *  2. 模型判断命中，调 `skill_load(name)` 把正文取进来，才付那份 token；
 *  3. 没命中就不加载，跟没有这个功能一样。
 *
 * 为什么是 Markdown 文件而不是代码：用户能在工作台里直接打开、改、删。技能的质量
 * 就是文字的质量，不该锁在 APK 里。内置的 5 个只是首次的种子，改了就按用户的来，
 * 删了也不会自己长回来。
 */
object Skill {

    /** 技能目录（相对工作台根）。 */
    const val DIR = "skills"

    /** 单个技能正文的注入上限。再长它就不是「技艺」而是「一本书」了。 */
    const val MAX_BODY_CHARS = 6000

    /** 索引里每条描述的长度上限。索引是每轮都发的，得抠。 */
    const val MAX_INDEX_DESC = 46

    /** 一次性种多少个内置技能，测试里断言用。 */
    const val BUILTIN_COUNT = 5

    /**
     * 一个技能。`body` 只在 [load] 时读，[scan] 只解析头部——
     * 索引只需要那几行，没必要把 5 个文件全文读进内存。
     */
    data class Item(
        val name: String,
        val title: String,
        val desc: String,
        val whenToUse: String,
        val file: File,
    ) {
        val bytes: Long get() = file.length()
    }

    private fun dir(root: File) = File(root, DIR)

    /** 文件名 → 技能名。只留安全字符，兼作路径穿越的第一道阀门。 */
    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".md").removeSuffix(".markdown")
            .lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5_-]"), "-")
            .trim('-')
        return base.take(32).ifEmpty { "skill" }
    }

    /**
     * 扫一遍技能目录。按文件名排序（稳定，索引顺序不会每次变）。
     * 坏文件、空文件直接跳过——一个读不出来的技能不该拖累整份索引。
     */
    fun scan(root: File): List<Item> {
        val d = dir(root)
        if (!d.isDirectory) return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
            ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { parse(it) }
    }

    private fun parse(file: File): Item? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        if (text.isBlank()) return null
        val (meta, body) = splitFrontMatter(text)

        val name = safeName(meta["name"] ?: file.name)
        // 标题：frontmatter 优先，其次正文第一个 # 行，最后退回文件名
        val title = meta["title"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: body.lineSequence().firstOrNull { it.trimStart().startsWith("# ") }
                ?.trim()?.removePrefix("# ")?.trim()
            ?: name
        // 描述：frontmatter 优先，其次正文第一行不是标题也不是引用的实义行
        val desc = meta["desc"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(">") }
                ?.take(MAX_INDEX_DESC)
            ?: ""
        val whenTo = meta["when"]?.trim().orEmpty()

        return Item(name, title, desc.take(MAX_INDEX_DESC), whenTo, file)
    }

    /**
     * 拆 frontmatter。格式刻意用最土的那种：
     *
     *     ---
     *     name: weekly-report
     *     title: 周报
     *     ---
     *
     * 解析器只要认得 `key: value`，不认 YAML 的嵌套、列表、多行——技能头部本来就只有
     * 这四个字段，引一个 YAML 库（PhonePrompt 那套零依赖原则）是不划算的。
     */
    private fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r')
        if (!trimmed.startsWith("---")) return emptyMap<String, String>() to text
        val afterFirst = trimmed.indexOf('\n')
        if (afterFirst < 0) return emptyMap<String, String>() to text
        // 结尾分隔行必须是单独一行 ---，正文里出现的 --- 不算
        val close = Regex("(?m)^---\\s*$").find(trimmed, afterFirst)
            ?: return emptyMap<String, String>() to text
        val head = trimmed.substring(afterFirst + 1, close.range.first)
        val body = trimmed.substring(close.range.last + 1).trimStart('\n', '\r')

        val meta = LinkedHashMap<String, String>()
        for (line in head.lines()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim().trim('"', '\'')
            if (k.isNotEmpty()) meta[k] = v
        }
        return meta to body
    }

    /** 按名字取正文（已去掉 frontmatter）。没有这个技能返回 null。 */
    fun load(root: File, name: String): Item? = scan(root).firstOrNull { it.name == safeName(name) }

    /** 技能正文。超长截断并在末尾说明——模型要能看出它拿到的不是全部。 */
    fun body(item: Item): String {
        val text = runCatching { item.file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        val body = splitFrontMatter(text).second.trim()
        if (body.length <= MAX_BODY_CHARS) return body
        return body.take(MAX_BODY_CHARS) + "\n\n[技能正文过长，已截断到 ${MAX_BODY_CHARS} 字]"
    }

    /**
     * 拼进系统提示的技能索引。
     *
     * 空目录返回空串（调用方据此不加段落）。整段控制在几百字：它是每轮请求的固定开销，
     * 不是内容本身——所以只写「什么时候该用」，不写怎么做。
     */
    fun index(root: File, disabled: Set<String> = emptySet()): String {
        val items = scan(root).filter { it.name !in disabled }
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## 技能包（命中才加载）\n\n")
        sb.append("有些任务有更靠谱的现成做法，写在技能里。下面是清单，每条只说「叫什么、干什么、什么时候用」。\n")
        sb.append("判断用户这次的事命中某一条，就先 skill_load(name) 把具体步骤取来，再按它做；")
        sb.append("没命中就别加载，正常做。同一轮里已经加载过的技能不要重复加载。\n\n")
        for (it in items) {
            sb.append("- ").append(it.name).append(" — ").append(it.title)
            if (it.desc.isNotEmpty()) sb.append("：").append(it.desc)
            if (it.whenToUse.isNotEmpty()) sb.append("｜用于：").append(it.whenToUse)
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * 首次启动把内置技能种到工作台 `skills/`。
     *
     * 只种一次：写一个 `.seeded` 标记，之后不管文件还在不在都不再动。
     * 「用户删掉的技能下次启动又长回来」是最招人烦的一类设计——尊重删除。
     */
    fun install(root: File): Int {
        val d = dir(root)
        val marker = File(d, ".seeded")
        if (marker.isFile) return 0
        runCatching { d.mkdirs() }
        var written = 0
        for ((name, text) in BUILTIN) {
            val f = File(d, "$name.md")
            if (f.exists()) continue
            // 内置内容已经在定义处 trimIndent 过，这里不再二次处理
            if (runCatching { f.writeText(text, Charsets.UTF_8) }.isSuccess) written++
        }
        runCatching { marker.writeText("1", Charsets.UTF_8) }
        return written
    }

    // ------------------------------------------------------------ 内置技能
    //
    // 每个技能只回答三件事：什么时候用、按什么顺序做、输出成什么形状。
    // 不写「你是一位资深 XX」那类角色铺垫——它不改变输出，只占 token。

    val BUILTIN: List<Pair<String, String>> = listOf(
        "weekly-report" to """
            ---
            name: weekly-report
            title: 周报
            desc: 把这一周零散的进展整理成一份能直接发出去的周报
            when: 写周报/周报/周总结/汇报本周工作/工作总结
            ---
            # 周报

            周报不是流水账，是让读的人三十秒内知道：进展到哪了、卡在哪、下周干什么。

            ## 先补齐三件事

            用户给的素材往往只有零散几句。缺这些就直接问，不要自己编：

            1. **周期**：哪一周（没有就默认本周一至周日）。
            2. **对象**：给谁看。给导师看要写学习与进展；给主管看要写结果与影响；给团队看要写协作与依赖。
            3. **有没有量化**：数量、时长、完成率、对比（比上周多做了几篇、错误率从多少降到多少）。

            用户说「你看着写」时，按「学生/职场新人向导师汇报」的默认口径写，并在末尾用一行说明假设。

            ## 结构（固定这四段，不要加花）

            **一、本周完成** —— 3～5 条，每条一行。写结果不写过程：
            - 差：「继续做数据清洗」
            - 好：「完成 3 万条问卷数据清洗，缺失值从 12% 降到 2%」
            一条里尽量带一个数字。没有数字就把动作写具体（做了什么、产出了什么文件/页面/结论）。

            **二、问题与风险** —— 只写真正卡住的，1～3 条。每条带一句「打算怎么解」。
            没有就写「无」。不要为了凑字数把「时间有点紧」写成一段。

            **三、下周计划** —— 2～4 条，每条可验证（能明确判断做没做完）。

            **四、需要的支持** —— 需要谁给什么、什么时候要。没有就写「暂无」。

            ## 写法

            - 一句话一行，不写「通过……的方式，进一步……」这种公文腔。
            - 不用形容词堆评价（「圆满完成」「效果显著」），用事实。
            - 全文控制在 300～500 字。周报的价值在密度，不在长度。
            - 用户给的内容里如果有明显的成果被埋没了（做完的事写成了「在推进」），把它提到第一条。

            ## 存档

            用户说「存下来/发我」时，用 fs_write 存成 `周报-YYYY-MM-DD.md`（日期取当周周日），
            然后回一句「已存到 周报-…md」。别在对话里重复贴一遍全文。
        """.trimIndent(),

        "study-notes" to """
            ---
            name: study-notes
            title: 笔记整理
            desc: 把课堂笔记、课件、零散摘录整理成能复习的提纲
            when: 整理笔记/复习提纲/知识点梳理/课件总结/考试复习
            ---
            # 笔记整理

            目标是「三天后能靠它自己复习」，不是「把原文抄得更整齐」。

            ## 先判断是哪一种

            - **有原始材料**（用户贴了笔记、发了文件、给了截图）→ 只做重组，不许添加材料里没有的知识点。
              补充的内容必须单独标「补充：」。
            - **只有主题**（「帮我整理一下假设检验」）→ 按课程常规深度写，开头一句说明覆盖范围与深度。

            ## 结构

            按「考点密度」组织，不按教材目录：

            1. **一句话概要**：这章解决什么问题。
            2. **核心概念**：每个概念三行——定义（一句）、为什么需要它、一个最小例子。
            3. **易混对比**：把容易混的两三个概念做成对比（谁解决什么、前提差在哪）。
               这张对比表是复习时最值钱的部分，宁可多花力气。
            4. **公式/流程**：只留会在题里用到的，每个后面跟一句「什么时候用」。
            5. **常见错法**：3 条以内，写「错在哪 → 正确做法」。
            6. **自测三问**：能答出来就算过。

            ## 写法

            - 短句。每条一行，不写成段落。
            - 术语第一次出现时给中文全称 + 英文，之后用简称。
            - 例子要小：能在一行内算完或想完的那种。别用需要背景知识的例子。
            - 不确定的地方明确写「（此处待确认）」——编一个顺滑的解释比留空白害人。

            ## 输出

            需要文件时用 fs_write 存 `笔记-主题.md`；需要打印/发人时用 pdf_write，
            blocks 按 h1/h2/p/bullet 分段，不要把整篇塞成一个 p。
        """.trimIndent(),

        "resume" to """
            ---
            name: resume
            title: 简历
            desc: 把经历改写成一条条有结果、能过筛的简历句
            when: 简历/求职/实习申请/自我介绍/自荐信/改简历
            ---
            # 简历

            筛简历的人平均看一份 20 秒。所以：一页、有数字、每行都在回答「你能干什么」。

            ## 先问清（一次问完，别来回问）

            1. 投什么岗（岗位名 + 行业，一两个就行）。
            2. 现在几年级/几年经验，有没有相关经历（没有就说没有，不编）。
            3. 有没有硬指标：绩点排名、竞赛名次、项目规模、实习时长、证书。

            ## 改写的唯一公式

            **动词 + 做的事 + 用什么方法 + 结果（数字）**

            - 差：「参与了校园二手平台项目，负责前端开发」
            - 好：「独立开发校园二手平台商品页（Vue3），首月上架 420 件商品，页面加载从 3.2s 降到 1.1s」
            - 没有数字时：写清规模与自己的边界——「3 人小组里负责数据清洗，处理 3 万条问卷」

            每条控制在一行半以内。用户原来的句子如果没有结果，就问他结果是什么，
            **不要自己补一个数字**。

            ## 结构（应届 / 无全职经验按这个顺序）

            1. 基本信息：姓名、学校专业年级、电话邮箱、（可选）一句话定位。
            2. 教育：学校、专业、时间、绩点或排名（只在好看时写）。
            3. 项目/实习：2～4 段，每段 2～3 条，倒序。
            4. 技能：分「熟练 / 了解」两档，只写真会的。证书与竞赛并进来。
            5. （可选）自我评价：两句以内，只写和岗位直接相关的，不写「吃苦耐劳」。

            ## 排版（要出 PDF 时）

            - 用 pdf_write：h1 姓名，h2 分节标题，bullet 每条经历，p 用于时间线和说明。
            - 控制在一页：应届生超过一页，说明该删。先删最久远、最不相关的项目。
            - 不要照片、不要彩色装饰条、不要进度条式的技能图（ATS 读不出来）。

            ## 交付

            先给「改了什么、为什么」的三行说明，再给成品。用户可能不同意某些改动，
            让他能一眼看出你动了哪里。
        """.trimIndent(),

        "slide-outline" to """
            ---
            name: slide-outline
            title: 汇报 PPT
            desc: 先把汇报的骨架和每页一句话定下来，再生成演示文稿
            when: 做PPT/演示文稿/汇报材料/答辩/宣讲/分享会
            ---
            # 汇报 PPT

            大多数 PPT 难看，不是设计问题，是**每页想说的东西太多**。所以先定骨架，再谈版式。

            ## 定三件事

            1. **场合与时长的关系**：一页约 1～1.5 分钟。10 分钟 = 8～12 页，别超。
            2. **听众要带走什么**：一句话。所有页都为这一句服务，不为它服务的删掉。
            3. **有没有必须出现的硬内容**：数据、结论、目录要求、评分点。

            ## 骨架（按场合选一个）

            - **汇报/答辩**：封面 → 背景与问题 → 做了什么（方法/过程）→ 结果（数据）→ 结论与不足 → 下一步
            - **方案/提案**：封面 → 现状痛点 → 方案 → 为什么是这个方案（对比）→ 落地计划 → 收益
            - **分享/科普**：封面 → 一个反直觉的问题 → 三个要点（各一页）→ 例子 → 回到开头那句话

            ## 每页的写法

            一页一个论点，标题就是那个论点：

            - 差：「项目进展」
            - 好：「清洗后数据可用率从 88% 提升到 98%」

            要点 3～5 条，每条不超过 20 字，是**证据**不是**目录词**。
            讲稿放心里或备注里，不要塞进正文——正文一多，讲课的人就开始念屏。

            ## 生成

            用 pptx_write：第 1 页是封面（title 是主标题，bullets 给副标题行、汇报人、日期），
            其余页 {title, bullets[]}。版式固定为雾蓝主题，别承诺做不出来的效果。

            生成前把大纲用文字列一遍给用户看（每页一行标题），确认后再写文件——
            改大纲比改 PPT 便宜得多。

            ## 常见坑

            - 把讲稿当要点贴上去 → 用户念也不是、不念也不是。
            - 每页都有小标题一模一样的「背景/现状/分析」→ 读者抓不到论点。
            - 最后一页写「谢谢」→ 换成「结论 + 联系方式」或「下一步请谁做什么」。
        """.trimIndent(),

        "message-draft" to """
            ---
            name: message-draft
            title: 代写消息
            desc: 按对象和场合写微信、邮件、请假、申请这类消息
            when: 帮我回/帮我写个消息/邮件/请假/申请/道歉/催进度/怎么跟XX说
            ---
            # 代写消息

            消息的成败不在文采，在**关系与分寸**：对谁、什么关系、想要什么结果。

            ## 先定三个变量

            1. **对象与关系**：老师/领导/同学/客户/家人。决定称呼、语气、长度。
            2. **目的**：通知、请求、拒绝、道歉、催办、谈条件。目的不同，结构完全不同。
            3. **约束**：有没有截止时间、金额、附件、要不要留文字证据。

            缺哪个就问哪个，一次问完。用户说「随便写」时按「对老师/上级，礼貌但不卑微」写。

            ## 四种最常用的骨架

            - **请假/申请**：称呼 → 我是谁 + 事由（一句）→ 时间范围 → 影响与补救（谁来接、什么时候补）→ 请批准 → 落款
            - **请求帮忙**：称呼 → 直接说请求（别铺垫三段）→ 为什么找你 + 具体要什么 → 时间点 → 表示可以拒绝的余地
            - **催进度**：称呼 → 提一下背景（上次说的哪件事）→ 现在的节点 → 想确认的新时间 → 我能配合什么
            - **道歉/解释**：称呼 → 认错（不解释动机）→ 影响是什么 → 已经/将要怎么补 → 不再犯的具体做法

            ## 写法

            - 微信：短，一段一件事，不用「尊敬的」，不用「此致敬礼」。3～5 行。
            - 邮件：主题行写清「谁 + 什么事 + 时间」；正文四段以内；结尾一句明确的行动请求。
            - 不写「不知当讲不当讲」「打扰您了实在不好意思」这类自我削弱的话。
            - 拒绝时给替代方案，比单纯说「不行」有用十倍。
            - 涉及金额、时间、承诺，写具体数字，不要「尽快」「大概」。

            ## 交付

            给成品，并说明「如果要更客气/更直接，我可以再调」。
            不要一次给三个版本让用户挑——挑版本比写还累。用户明确要多个语气时再给。
        """.trimIndent(),
    )
}
