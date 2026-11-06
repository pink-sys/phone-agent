package cn.wbphone.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 本地网关的 HTTP 字节处理：读请求行/头/体，拼响应头。
 *
 * 单独抽出来是为了能在 JVM 上测——socket 这类代码在真机上错了，表现是
 * dsh-Go 里一句「出错了」，根本看不出是头没读完还是长度算错。
 * 这里只碰 InputStream/字符串，不碰 Android，也不碰 Socket。
 */
object GatewayWire {

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /** 请求行 + 头。读不到请求行（连接被空关）返回 null。 */
    fun readHead(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                // 头名统一小写：不同客户端的大小写习惯不一样，查的时候不想每次记住
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return Request(parts[0].uppercase(), parts[1], headers, "")
    }

    /** 读 body。支持 Content-Length 与 chunked 两种；都没有就当空体。 */
    fun readBody(input: InputStream, headers: Map<String, String>): String {
        val te = headers["transfer-encoding"]?.lowercase() ?: ""
        if (te.contains("chunked")) return readChunked(input)
        val len = headers["content-length"]?.toIntOrNull() ?: return ""
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    fun readChunked(input: InputStream): String {
        val all = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)   // 结尾的空行
                break
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            all.write(buf, 0, read)
            readLine(input)       // chunk 数据后面的 CRLF
        }
        return all.toString("UTF-8")
    }

    /** 读到 \n 为止。不用 BufferedReader，免得它把后面的 body 一起吞进缓冲。 */
    fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= 8192) return sb.toString()
            sb.append(b.toChar())
        }
    }

    /**
     * 响应头。
     * @param length 给了就带 Content-Length；SSE 传 null，用「关连接」界定结尾。
     */
    fun head(status: Int, contentType: String, length: Int?): String = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Cache-Control: no-cache\r\n")
        append("X-Accel-Buffering: no\r\n")
        if (length != null) append("Content-Length: ").append(length).append("\r\n")
        append("Connection: close\r\n\r\n")
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    fun jsonError(message: String, type: String = "invalid_request_error"): String =
        """{"error":{"message":"${escape(message)}","type":"$type"}}"""

    fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
}
