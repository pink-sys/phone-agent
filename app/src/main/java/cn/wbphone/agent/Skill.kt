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

    /** 内置技能总数，测试里断言用。 */
    const val BUILTIN_COUNT = 9

    /**
     * 种子版本。新增内置技能时 +1，并把新技能名登记到 [NEW_SEEDS]。
     *
     * 为什么要有版本：老用户的 skills/ 里已经有一个 `.seeded` 标记，
     * 只认「有没有标记」的话，后来补的技能永远不会出现在他手机上。
     * 版本化之后，升级时只补「这一版新增的」——用户删掉的旧技能仍然不会复活。
     */
    const val SEED_VERSION = 2

    /** [SEED_VERSION] 这一版新加的技能名。升级播种只认这一份名单。 */
    private val NEW_SEEDS = setOf("doc-format", "slides", "doc-coauthor", "skill-craft")

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
     * 首次启动把内置技能种到工作台 `skills/`，升级时补这一版新增的。
     *
     * 「用户删掉的技能下次启动又长回来」是最招人烦的一类设计——尊重删除。
     * 所以标记里存的是**版本号**而不是「种过了」：
     *  - 首装（没有标记）：九个全种；
     *  - 升级（标记 < [SEED_VERSION]）：只种 [NEW_SEEDS] 里的，删过的旧技能不复活；
     *  - 版本已是最新：什么都不做。
     */
    fun install(root: File): Int {
        val d = dir(root)
        val marker = File(d, ".seeded")
        // 旧标记内容可能是 "1"（也可能被人手改成空），读不出来就按第一版算
        val seeded = if (marker.isFile) {
            runCatching { marker.readText().trim().toIntOrNull() }.getOrNull() ?: 1
        } else 0
        if (seeded >= SEED_VERSION) return 0
        runCatching { d.mkdirs() }
        var written = 0
        for ((name, text) in BUILTIN) {
            if (seeded > 0 && name !in NEW_SEEDS) continue
            val f = File(d, "$name.md")
            if (f.exists()) continue
            // 内置内容已经在定义处 trimIndent 过，这里不再二次处理
            if (runCatching { f.writeText(text, Charsets.UTF_8) }.isSuccess) written++
        }
        runCatching { marker.writeText(SEED_VERSION.toString(), Charsets.UTF_8) }
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

        "doc-format" to """
            ---
            name: doc-format
            title: 文档排版
            desc: 把内容排成能直接交出去的 docx / pdf：层级、留白、表格的取舍
            when: 写报告/方案/说明/总结要出文件、排版、转成 Word 或 PDF
            ---
            # 文档排版

            排版不是装饰，是让读者三秒内找到他要的那一段。下面每一条都直接决定成品像不像样。

            ## 先定三件事，缺了就问

            1. **给谁看**：领导只看结论，同事要看细节，外部要讲措辞。同一份内容给这两种人，结构不一样。
            2. **多长**：一页纸的说明和十页的方案，写法完全不同。用户没说就按「一屏能读完」写。
            3. **要不要能改**：对方要补充修改给 docx_write；只发出去看给 pdf_write；自己留着用 fs_write 存 md。

            ## 结构

            - **结论先行**。第一段就写结果或建议，后面才是过程。不要「随着……的发展」起手。
            - 标题分级只用三层：h1 是文档名（只有一个），h2 是章节（3～6 个），h3 是章节内的小点（能不用就不用）。
            - 段落 3～5 行。超过就拆，或者改成列表。
            - 列表一次不超过 7 条，每条一行。到第 8 条说明该分组了。
            - 表格只在「多维对比」时用（三个以上对象比同一组指标）。两列的信息用列表就够，别做表。

            ## 分寸

            - 字号层级最多三档（标题 / 小标题 / 正文）。第四档读者已经分不出来。
            - 不要画分隔线当装饰，不要用 emoji 当项目符号，不要在标题后面加感叹号堆情绪。
            - 不要写「本文将从三个方面展开」这类导航句——目录能做的事不要让正文做。
            - 数字统一口径：同一份文档里「万元 / 元」「本月 / 近 30 天」只能有一种说法。
            - 引用别人的数据要给来源和时间；没有来源的数字宁可删掉。

            ## 交付前自检

            1. 只读第一段：能不能明白结论？
            2. 标题连起来读一遍，像不像一份提纲？不像就是层级乱了。
            3. 抽查两个数字，跟原始材料对不对得上。
            4. 文件真的生成了、能打开、该长的时候不是一页。

            交付时说一句「已写到 xxx，共 N 页」，不要贴全文。
        """.trimIndent(),

        "slides" to """
            ---
            name: slides
            title: 幻灯片
            desc: 做 pptx：一页一个结论，标题写成句子，删掉一半的字
            when: 做 PPT/幻灯片/汇报材料/答辩/路演，要出 .pptx 文件
            ---
            # 幻灯片

            幻灯片是讲的时候用的提词板，不是给人读的文档。判断标准只有一条：
            观众抬头看一眼，能不能立刻抓到这页要说的事。

            ## 页面

            - **一页一个结论。** 标题写成完整的句子，不写名词短语。
              - 差：「项目进展」
              - 好：「三周完成迁移，故障率降了 40%」
            - 每页要点不超过 5 条，每条不超过 20 字。工具允许 12 条，那是上限不是目标——超过 5 条就该拆页。
            - 页数按时间算：一页约 1.5 分钟。10 分钟的汇报做 6～8 页，不要 20 页。
            - 结构固定三段：封面（讲什么、给谁、什么时候）→ 正文（3～5 页，每页一个论点）→ 结论（要对方做什么）。

            ## 内容

            - 关键数字单独一页，比塞在要点里有力。
            - 同一页的数字必须同口径（都是月、都是万元），不要混着来。
            - 一页里只能有一个「最想让人记住的东西」；想不出是哪个，这页就该删。
            - 详细论证不上面，留给口头讲和附录页。

            ## 不要

            - 不要在标题下加装饰线、色条、边框——一眼就是模板凑出来的。
            - 不要用 emoji 当图标，不要堆感叹号。
            - 不要每页写满字然后念给观众听：页面是要点，讲的时候再展开。
            - 不要 10 页讲 10 个平行的点，观众一个都记不住。

            ## 交付

            pptx_write 一次写完（每页 title + bullets），页数上限 50，多了拆成两个文件。
            交付时说「共 N 页，结构是……」，不要贴全文要点。
        """.trimIndent(),

        "doc-coauthor" to """
            ---
            name: doc-coauthor
            title: 长文档协作
            desc: 一起写方案/报告/论文这类长文：先对齐，再一节一节推进，最后通读
            when: 写方案/提案/论文/长报告，用户说「一起写」「帮我把这个整理成文档」
            ---
            # 长文档协作

            长文一次成型几乎必然返工。正确做法是分节推进，每节都让用户点头。

            ## 第一步：对齐（只问三个问题）

            1. 这是什么文档（方案 / 报告 / 说明 / 论文）？
            2. 谁看，读完要发生什么（批准 / 照着做 / 了解）？
            3. 有没有现成的模板或参考文件？

            三个都答了才动笔。答不上的按最保守的默认写，交付时说明你的假设。

            ## 第二步：搭骨架

            先给 3～5 个章节名，让用户确认结构，再动笔。骨架用 fs_write 存成 md，
            之后每节用 fs_edit 往占位符里填——不要每节整篇重写，浪费且容易丢前面改过的地方。

            ## 第三步：一节一节来

            每节固定这个节奏，不要跳：

            1. 先列 5～10 个「这节可以写什么」的候选点，一行一个，编号。
            2. 让用户挑：留哪些、删哪些、哪两条合并。
            3. 按挑选结果起草，一次给完整的一节，不是一段。
            4. 用户提意见就只改那几处，改完说清「改了哪三处」，不要重贴全文。

            用户说「你看着办」时，先写「最重要、最不确定」的那节，每节写完给一句
            「这节的核心结论是 X，不对我改」。

            ## 第四步：通读（写完 80% 时做）

            从头读一遍，只查四件事：

            - 前后矛盾的结论（前面说增长、后面说下滑）
            - 同一件事写了两遍
            - 空话（「具有重要意义」「进一步提升」这类可以整句删掉）
            - 数字口径不一致

            查到就直接说「第 X 节和第 Y 节冲突，建议统一成……」，不要默默改掉。

            ## 交付

            给文件，附一句「结构 + 还缺什么」。事实、数字、引用请用户复核一次——
            这是他的文档，他签字。
        """.trimIndent(),

        "skill-craft" to """
            ---
            name: skill-craft
            title: 写技能
            desc: 把一套固定做法写成技能文件，以后遇到同类任务自动按它做
            when: 用户说「把这个流程记下来」「以后都这么做」「做个技能」「存成技能」
            ---
            # 写技能

            技能是「这类事该怎么做」的说明书，不是知识库。判断标准只有一条：
            下次遇到同类任务，照着它能做得比自由发挥更好。

            ## 先想清楚三件事

            1. **什么时候用**：一句话写清触发场景（写周报、改简历、做答辩 PPT）。
            2. **按什么顺序做**：步骤要能执行，不要写「要认真分析」这种没法执行的话。
            3. **输出什么形状**：几段、多少字、什么文件、要不要表格。

            ## 文件格式

            存成 `skills/<name>.md`，name 用英文小写加短横线（如 weekly-report）。开头必须有四行：

                ---
                name: weekly-report
                title: 周报
                desc: 一句话说清它干什么（不超过 46 字，索引里只显示这一句）
                when: 写周报/周总结/汇报本周工作
                ---

            正文直接从「怎么做」开始，不写「你是一位资深 XX」这类角色铺垫——它不改变输出，只占 token。

            ## 写作分寸

            - **一个技能只干一件事。**「写周报」和「做 PPT」该拆成两个。
            - 正文控制在 6000 字以内，超了会被截断。写不下说明该拆。
            - 每条规则都要能落地：给「差 / 好」对照，比形容词管用。
            - 只写这个领域特有的东西。通用写作常识不要写进来——索引和正文都在每轮占位置。
            - 有硬性上限的（页数、字数、条数）写明具体数字。

            ## 交付

            用 fs_write 写进 `skills/`，然后告诉用户「已存成技能 xxx，下次说『写周报』就会用」。
            写完在设置页的技能清单里确认能看到它——看不到就是文件名或头部四行写错了。
        """.trimIndent(),
    )
}
