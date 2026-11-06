package cn.wbphone.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 语音输入：说话 → 文字，填进输入框，**不自动发送**。
 *
 * 为什么不让它说完就发：识别错一两个字在「帮我给老师发消息」这种场景里
 * 代价太大。填进输入框，用户扫一眼、改一个字、自己点发送，比省那一次点击值。
 *
 * 引擎是讯飞「语音听写（流式版）」WebSocket API（Iat.kt 是协议层）：
 * 自己用 AudioRecord 录 16k 单声道 PCM，40ms 一帧推给服务端，中间结果实时回调。
 * 不依赖设备的 RecognitionService——这台 vivo 什么识别服务都没有，系统那条路彻底断的，
 * 云端转写也早被证明走不通（chat 网关在报文解析层拒音频）。订阅制 API 自己可控。
 *
 * 麦克风权限由 MainActivity 在 start() 之前拿好（SpeechRecognizer 时代的教训不变：
 * 录音是调用方做的，RECORD_AUDIO 必须在清单里且运行时已授权）。
 */
class VoiceIn(private val act: Activity, private val cb: Callback) {

    interface Callback {
        /** 边说边出的中间结果：整段替换输入框，让用户看到「它在听」。 */
        fun onPartial(text: String)

        /** 最终结果。 */
        fun onFinal(text: String)

        /** 出错给一句人话。 */
        fun onError(message: String)

        /** 麦克风状态变了，界面据此换按钮样式。 */
        fun onListening(listening: Boolean)
    }

    /** 配好听写 API 就算可用——识别能力在云端，跟设备装了什么无关。 */
    val available: Boolean
        get() = Config.IAT_APP_ID.isNotBlank() && Config.IAT_API_KEY.isNotBlank() &&
            Config.IAT_API_SECRET.isNotBlank()

    private val running = AtomicBoolean(false)
    private var socket: SSLSocket? = null
    private var audio: AudioRecord? = null

    /** 手动收尾（用户点 ■）：发完结束帧后拿到的就是最终结果。 */
    private val userStop = AtomicBoolean(false)

    @SuppressLint("MissingPermission") // 权限闸门在 MainActivity.startVoice，进不来这里必然已授权
    fun start() {
        if (!running.compareAndSet(false, true)) return
        userStop.set(false)
        val sessionStart = System.currentTimeMillis()

        val recorder = try {
            val minBuf = AudioRecord.getMinBufferSize(
                Iat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(
                MediaRecorder.AudioSource.MIC, Iat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, Iat.CHUNK_BYTES * 4))
        } catch (e: Exception) {
            fail("麦克风打不开：${e.message?.take(50) ?: "可能被别的应用占用"}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            fail("麦克风初始化失败，检查权限：设置 → 应用 → 月溯 → 权限")
            return
        }
        audio = recorder

        // 网络全部在后台线程：握手 ~几百 ms，之后采集/收发各占一条
        Thread {
            var recorderStarted = false
            try {
                connect().use { sock ->
                    socket = sock
                    sock.soTimeout = 20_000
                    val input = BufferedInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()

                    // ---- 采集线程：等握手成功、发完首帧后才开始读麦克风
                    val capture = Thread {
                        try {
                            recorder.startRecording()
                            recorderStarted = true
                            ui { cb.onListening(true) }
                            val buf = ByteArray(Iat.CHUNK_BYTES)
                            while (running.get() && !userStop.get()) {
                                val n = recorder.read(buf, 0, buf.size)
                                if (n <= 0) break
                                synchronized(output) {
                                    if (running.get()) {
                                        output.write(Iat.clientFrame(
                                            1, Iat.audioFrame(Base64.getEncoder().encodeToString(
                                                buf.copyOf(n))).toByteArray(), newMask()))
                                        output.flush()
                                    }
                                }
                            }
                            // 收尾帧：手动停 → 空音频 status=2；服务端 vad 先停（连接已关）→ 别发了
                            synchronized(output) {
                                if (running.get() && userStop.get()) {
                                    output.write(Iat.clientFrame(1, Iat.endFrame().toByteArray(), newMask()))
                                    output.flush()
                                }
                            }
                        } catch (e: Exception) {
                            // 服务端先结束（vad 收尾）后用户才按 ■ 是正常时序，连接已关，
                            // 这里的 IO 异常直接吞——结果由接收线程负责收尾
                        }
                    }
                    capture.isDaemon = true

                    // ---- 首帧：握手完立刻发（可以先带一段静音占位，音频随采集线程跟上）
                    val first = Iat.clientFrame(
                        1, Iat.startFrame(Config.IAT_APP_ID,
                            Base64.getEncoder().encodeToString(ByteArray(Iat.CHUNK_BYTES))).toByteArray(),
                        newMask())
                    output.write(first)
                    output.flush()
                    capture.start()

                    // ---- 接收循环：结果、错误、服务端结束都从这里出
                    var text = ""
                    var ended = false
                    while (running.get()) {
                        val (opcode, payload) = try {
                            Iat.readFrame(input)
                        } catch (e: SocketTimeoutException) {
                            if (System.currentTimeMillis() - sessionStart > Iat.MAX_SESSION_MS) {
                                text = "" // 会话超时：整场收摊，报错比挂半截好
                                ui { cb.onError("识别超时了，网络或服务没响应") }
                            }
                            break
                        }
                        when (opcode) {
                            1 -> {
                                val reply = Iat.parseReply(String(payload, Charsets.UTF_8))
                                if (!reply.ok) {
                                    ui { cb.onError(Iat.errorText(reply.code)) }
                                    break
                                }
                                // 关键：听写的每条消息只含**新听到的片段**（最后一段往往只剩标点），
                                // 必须累加——整段替换的话，按 ■ 收尾时攒的正文会被最后一个「。」冲掉
                                if (reply.text.isNotEmpty()) {
                                    text += reply.text
                                    ui { cb.onPartial(text) }
                                }
                                if (reply.end) {
                                    ended = true
                                    break
                                }
                            }
                            8 -> break // 服务端主动关
                            9 -> synchronized(output) { // ping → pong
                                output.write(Iat.clientFrame(10, payload, newMask()))
                                output.flush()
                            }
                        }
                    }
                    running.set(false)
                    recorder.stop()
                    capture.join(1500)
                    ui { cb.onListening(false) }
                    if (ended) {
                        if (text.isNotEmpty()) ui { cb.onFinal(text) }
                        else ui { cb.onError("没听清，再说一次") }
                    }
                }
            } catch (e: Exception) {
                if (running.getAndSet(false)) {
                    ui { cb.onError("语音连接断了：${e.message?.take(60) ?: "网络不通"}") }
                }
            } finally {
                try { if (recorderStarted) recorder.stop() } catch (_: Exception) {}
                recorder.release()
                audio = null
                socket = null
                ui { cb.onListening(false) }
            }
        }.start()
    }

    /**
     * 建立 TLS + WebSocket 握手，返回就绪的 socket。HTTP 状态码不是 101 时
     * 把响应体读出来尽量给一句有用的错（401/403 都是鉴权问题）。
     */
    private fun connect(): SSLSocket {
        val url = Iat.authUrl(Config.IAT_APP_ID, Config.IAT_API_KEY, Config.IAT_API_SECRET, Iat.rfc1123Now())
        val sock = SSLSocketFactory.getDefault().createSocket(Iat.HOST, 443) as SSLSocket
        try {
            sock.soTimeout = 10_000
            sock.startHandshake()
            val out = sock.getOutputStream()
            val key = Base64.getEncoder().encodeToString(ByteArray(16) { (Math.random() * 256).toInt().toByte() })
            // 请求行必须带路径：url 是 wss://host/v2/iat?query，截掉 scheme+host 后要补上 PATH
            val req = "GET ${Iat.PATH}${url.substringAfter(Iat.WS_URL)} HTTP/1.1\r\n" +
                "Host: ${Iat.HOST}\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            // 手工读响应头：读到 \r\n\r\n 为止，逐行找状态码
            val head = StringBuilder()
            val reader = sock.getInputStream()
            var prev = 0
            while (head.length < 8192) {
                val b = reader.read()
                if (b < 0) throw java.io.EOFException("握手时连接关闭")
                head.append(b.toChar())
                if (prev == 13 && b == 10 && head.endsWith("\r\n\r\n")) break
                prev = b
            }
            val statusLine = head.toString().lineSequence().firstOrNull().orEmpty()
            val status = statusLine.substringAfter(" ", "").substringBefore(" ").toIntOrNull() ?: 0
            if (status != 101) {
                sock.close()
                throw java.io.IOException(
                    if (status == 401 || status == 403) "鉴权没过（$status），查 APPID / APIKey / APISecret 和手机时间"
                    else "握手被拒（HTTP $status）")
            }
            sock.soTimeout = 20_000
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }

    /** 用户点 ■：立刻收尾，拿目前听到的当最终结果。 */
    fun stop() {
        userStop.set(true)
    }

    /** 直接放弃（切页、退出），不要结果。 */
    fun cancel() {
        running.set(false)
        userStop.set(true)
        runCatching { socket?.close() }
        runCatching { audio?.stop() }
    }

    fun destroy() = cancel()

    private fun newMask(): ByteArray = ByteArray(4) { (Math.random() * 256).toInt().toByte() }

    /** 所有回调都摆回主线程：网络/采集线程里直接碰 UI 会炸，toast 也不是线程安全的。 */
    private fun ui(block: () -> Unit) {
        act.runOnUiThread { if (!act.isFinishing) block() }
    }

    private fun fail(message: String) {
        running.set(false)
        ui { cb.onError(message) }
        ui { cb.onListening(false) }
    }
}
