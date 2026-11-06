package cn.wbphone.agent

import java.util.Base64

/**
 * 从 JWT 里读出过期时间。
 *
 * 为什么不用服务端返回的 `expiresAt`：那个字段经常是 0 或空（服务端只在刷新时给），
 * 而令牌本身就是 JWT，`exp` 一定在。少一次请求，也少一个"看起来登录了其实过期了"的坑。
 *
 * 刻意不引 Android API：JWT 解析和剩余时间文案都能在 JVM 上直接跑断言。
 */
object TokenInfo {

    /** @return 过期时间（epoch 秒）；不是 JWT 或读不出 exp 时返回 0。 */
    fun expirySeconds(jwt: String): Long {
        val parts = jwt.split('.')
        if (parts.size < 2) return 0L
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = try {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return 0L
        }
        // 只取一个数字，用正则可免掉在纯逻辑层引 org.json。
        return Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    /**
     * 「还有 27 天」这种说法。剩余不足一天按小时算，不足一小时按分钟算——
     * 用户真正要知道的是「现在还能不能直接用」。
     */
    fun remainingText(expirySec: Long, nowSec: Long): String {
        if (expirySec <= 0L) return "读不出有效期"
        val left = expirySec - nowSec
        return when {
            left <= 0L -> "已过期"
            left < 3600L -> "还有 ${(left / 60).coerceAtLeast(1)} 分钟"
            left < 86_400L -> "还有 ${left / 3600} 小时"
            else -> "还有 ${left / 86_400} 天"
        }
    }

    /** 「2026-11-29 19:00」这种绝对时间，配剩余时间一起显示。 */
    fun expiryText(expirySec: Long): String {
        if (expirySec <= 0L) return "未知"
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return f.format(java.util.Date(expirySec * 1000L))
    }
}
