package cn.wbphone.agent

/**
 * 把底层异常翻成用户能看懂、并且知道下一步做什么的话。
 *
 * 不加这一层的时候，用户看到的原文是这些：
 *
 *   「HTTP 500：{"error":{"code":"internal_error","message":"upstream ...
 *   「unexpected end of stream on com.android.okhttp.internal...
 *   「Failed to connect to /10.0.2.2:443」
 *
 * 三条信息对排查有用，对**用户**没用——他不知道该等、该换网、还是该重新登录，
 * 只能截图来问。所以这里按「能不能自己解决」分类：能等的说等，要动手的说动手。
 *
 * 原文不丢：AgentHost 的日志里仍有完整异常（异常是在 log 之后才被翻译的），
 * 真要排查时那才是依据。
 */
object Errors {

    /** 关键词按「先具体后笼统」排列，命中即返回；都命中不了才回落原文。 */
    fun humanize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return "出了点问题，但没拿到具体原因。再说一次试试。"
        val lower = s.lowercase()

        // 限流：等一等就能自己好，别让对方以为要重装
        if ("429" in s || "限流" in s || "rate limit" in lower || "too many requests" in lower) {
            return "请求太频繁，被服务端限流了。等一两分钟再说「继续」，进度都还在。"
        }
        // 登录：唯一需要用户动手的一类
        if ("未登录" in s || "401" in s || "403" in s || "令牌" in s || "unauthorized" in lower) {
            return "登录状态失效了。去设置里重新登录一下，然后说「继续」接着做。"
        }
        // 服务端：用户什么都做不了，别让他反复重试
        if ("500" in s || "502" in s || "503" in s || "504" in s ||
            "internal server error" in lower || "bad gateway" in lower
        ) {
            return "服务端出错了，不是你这边的问题。等几分钟再说「继续」就行。"
        }
        if ("timeout" in lower || "timed out" in lower || "超时" in s) {
            return "等回应等太久了，网络可能断了。看一下网络再说「继续」，我会从停下的地方接着做。"
        }
        if ("unknownhost" in lower || "unable to resolve" in lower || "no address" in lower) {
            return "解析不到服务器地址，看起来是断网了。连上网络再说「继续」。"
        }
        if ("connection refused" in lower || "failed to connect" in lower || "connectexception" in lower) {
            return "连不上服务器。换个网络（比如 Wi-Fi 换流量）再试一次。"
        }
        if ("ssl" in lower || "handshake" in lower || "certificate" in lower) {
            return "安全连接没建立起来，多半是网络被中间拦截了。换个网络再试。"
        }
        // 已经是中文：那就是我们自己写的说明（压缩摘要、卡死提示、闸门拒绝……），原样给
        if (s.any { it.code in 0x4E00..0x9FFF }) return s
        // 剩下的是没见过的英文异常：留一句原文，至少能拿去搜/发给我
        return "这一步没成功：${s.take(200)}"
    }
}
