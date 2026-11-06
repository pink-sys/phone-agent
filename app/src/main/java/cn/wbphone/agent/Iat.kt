package cn.wbphone.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞「语音听写（流式版）」协议层：鉴权、请求帧、结果解析、WebSocket 帧编解码。
 *
 * 刻意一个 Android import 都没有——录音（AudioRecord）和连接管理在 VoiceIn.kt，
 * 这里只放纯计算，让 JVM 测试能把鉴权签名、帧格式、结果拼接逐字节验掉。
 *
 * 协议要点（https://www.xfyun.cn/doc/asr/voicedictation/API.html）：
 *  - 接入 wss://iat-api.xfyun.cn/v2/iat，鉴权参数拼在 URL query 上（HMAC-SHA256 签名）；
 *  - 首帧 JSON 带 common/business + data.status=0，之后每帧 data.status=1 附 40ms 音频，
 *    结束发 data.status=2（audio 留空）；
 *  - 音频要求 16kHz / 16bit / 单声道 PCM，每次 ≤1280 字节（40ms），发太快服务端会拒；
 *  - 服务端回 JSON：code=0 成功，data.status=2 表示识别结束；
 *    result.ws[].cw[].w 依次拼接就是转写文本（增量，攒起来即全文）。
 */
object Iat {

    const val HOST = "iat-api.xfyun.cn"
    const val PATH = "/v2/iat"
    const val WS_URL = "wss://$HOST$PATH"

    /** 音频规格：听写只认这一种。 */
    const val SAMPLE_RATE = 16000

    /** 每帧音频字节数：16bit 单声道 40ms = 16000 * 2 * 0.04 = 1280。 */
    const val CHUNK_BYTES = 1280

    /** 静音自动收尾：说停了过这么久没新内容，服务端结束并关连接（秒级，别太短免得切断思考）。 */
    const val VAD_EOS_MS = 3000

    /** 整场识别的硬上限：听写单场最长 60s，到点主动收，别等服务端踢。 */
    const val MAX_SESSION_MS = 55_000

    // ------------------------------------------------------------ 鉴权

    /** RFC1123 格式的 GMT 时间，签名和 query 里必须同一个值。 */
    fun rfc1123Now(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    /**
     * 带鉴权的完整 ws URL。签名原文固定三行（host / date / request-line），
     * 用 APISecret 做 HMAC-SHA256 再 base64，最后整体 authorization 再 base64。
     * date 必须原样进签名、URL 编码后进 query——两处不一致就 401。
     */
    fun authUrl(appId: String, apiKey: String, apiSecret: String, date: String): String {
        val origin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray(StandardCharsets.UTF_8)))
        val authorizationOrigin =
            "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.getEncoder().encodeToString(authorizationOrigin.toByteArray(StandardCharsets.UTF_8))
        // base64 里的 + / = 以及 date 里的空格逗号都必须百分号编码——排障实测：
        // 裸发 +（URLEncoder 风格）会被网关按字面量拼进 query，签名永远对不上；
        // 只有标准 RFC 3986 百分号编码能进到签名校验那一步
        return "$WS_URL?authorization=${urlEncode(authorization)}&date=${urlEncode(date)}&host=$HOST"
    }

    /** RFC 3986 百分号编码：保留字母数字与 -._~，其余全部 %XX。 */
    private fun urlEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 请求帧

    /** 首帧：握手后立刻发，携带第一段音频。business 参数在这里定死中文听写。 */
    fun startFrame(appId: String, firstChunkBase64: String): String =
        """{"common":{"app_id":"$appId"},""" +
            """"business":{"language":"zh_cn","domain":"iat","accent":"mandarin","vad_eos":$VAD_EOS_MS},""" +
            """"data":{"status":0,"format":"audio/L16;rate=$SAMPLE_RATE","encoding":"raw","audio":"$firstChunkBase64"}}"""

    /** 中间帧：只带音频。 */
    fun audioFrame(chunkBase64: String): String =
        """{"data":{"status":1,"format":"audio/L16;rate=$SAMPLE_RATE","encoding":"raw","audio":"$chunkBase64"}}"""

    /** 结束帧：audio 留空。发完服务端把最终结果推回来再关连接。 */
    fun endFrame(): String =
        """{"data":{"status":2,"format":"audio/L16;rate=$SAMPLE_RATE","encoding":"raw","audio":""}}"""

    // ------------------------------------------------------------ 结果解析

    /** 一条服务端消息的解析结果。code != 0 表示协议层错误，text/end 无意义。 */
    data class Reply(val code: Int, val message: String, val text: String, val end: Boolean) {
        val ok: Boolean get() = code == 0
    }

    /** 解析服务端 JSON。不引 JSON 库：报文结构固定，按字段名顺序找值即可。 */
    fun parseReply(json: String): Reply {
        val code = intField(json, "code") ?: -1
        val message = stringField(json, "message") ?: ""
        if (code != 0) return Reply(code, message, "", false)
        val end = "\"status\":2" in json.replace(" ", "")
        val text = StringBuilder()
        var pos = 0
        while (true) {
            // 每个 cw 块取 w 的值，按出现顺序拼接——增量结果攒起来即全文
            val w = nextWValue(json, pos) ?: break
            text.append(w.second)
            pos = w.first
        }
        return Reply(code, message, text.toString(), end)
    }

    /** 找 `"key":<数字>`，找不到返回 null。报文结构固定，不做通用 JSON 解析。 */
    private fun intField(json: String, key: String): Int? {
        val marker = "\"$key\":"
        var i = json.indexOf(marker)
        while (i >= 0) {
            var j = i + marker.length
            while (j < json.length && json[j] == ' ') j++
            val start = j
            while (j < json.length && (json[j] == '-' || json[j] in '0'..'9')) j++
            if (j > start) return json.substring(start, j).toIntOrNull()
            i = json.indexOf(marker, i + 1)
        }
        return null
    }

    /** 找 `"key":"value"`，返回 value（处理基本转义）。 */
    private fun stringField(json: String, key: String): String? {
        val marker = "\"$key\":\""
        val i = json.indexOf(marker)
        if (i < 0) return null
        val sb = StringBuilder()
        var j = i + marker.length
        while (j < json.length) {
            val c = json[j]
            if (c == '\\') {
                j++
                when (json.getOrNull(j)) {
                    'n' -> sb.append('\n')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(json.getOrNull(j) ?: ' ')
                }
                j++
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            j++
        }
        return null
    }

    /** 从 [from] 起找下一个 `"w":"..."`，返回 (结束位置, 词)。找不到返回 null。 */
    private const val W_MARKER = "\"w\":\""

    private fun nextWValue(json: String, from: Int): Pair<Int, String>? {
        var i = json.indexOf(W_MARKER, from)
        if (i < 0) return null
        i += W_MARKER.length // 注意是 5 个字符（含两个引号）——写成字面量 4 会停在收尾引号上读出空词
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\') { // 转义：讯飞的转写词里几乎不出现，稳妥处理常见两种
                i++
                when (json.getOrNull(i)) {
                    'n' -> sb.append('\n')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(json.getOrNull(i) ?: ' ')
                }
                i++
                continue
            }
            if (c == '"') return Pair(i + 1, sb.toString())
            sb.append(c)
            i++
        }
        return null
    }

    /** 服务端错误码翻成人话（对照官方错误码表挑常见的）。 */
    fun errorText(code: Int): String = when (code) {
        10005, 10105 -> "听写 API 的 APPID 不对，检查一下配置"
        10006, 10106, 10107, 10108, 10109, 10110 -> "听写 API 鉴权失败：APIKey / APISecret 或系统时间不对"
        10160 -> "听写请求不合法，可能是音频格式不对"
        10163 -> "听写请求参数非法"
        10165 -> "音频发送太快或太大，被服务端拒了"
        11200 -> "听写服务的免费额度用完了"
        11201 -> "听写服务并发超了，等一秒再试"
        11202 -> "听写服务今日调用量到顶了"
        11203 -> "听写服务请求太频繁，慢一点再试"
        10313, 10114 -> "听写会话无效，重试一次"
        else -> "听写失败（$code）"
    }

    // ------------------------------------------------------------ WebSocket 帧编解码

    /** 客户端帧必须掩码（RFC 6455）。 */
    fun clientFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == 4)
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode) // FIN=1
        val len = payload.size
        when {
            len < 126 -> out.write(0x80 or len)
            len < 65536 -> {
                out.write(0x80 or 126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) out.write(((len.toLong() shr shift) and 0xFF).toInt())
            }
        }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i and 3].toInt())
        return out.toByteArray()
    }

    /** 读一条服务端帧（服务端不掩码，但按协议容错）。返回 (opcode, payload)。 */
    fun readFrame(input: InputStream): Pair<Int, ByteArray> {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) throw java.io.EOFException("连接已关闭")
        val opcode = b0 and 0x0F
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() and 0xFF).toLong() shl 8) or (input.read() and 0xFF).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or (input.read() and 0xFF).toLong() }
        }
        if (len > 16L * 1024 * 1024) throw java.io.IOException("帧过大：$len")
        val mask = ByteArray(4)
        if (masked) { readFully(input, mask, 4) }
        val payload = ByteArray(len.toInt())
        if (len > 0) readFully(input, payload, payload.size)
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Pair(opcode, payload)
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("连接已关闭")
            off += r
        }
    }
}
