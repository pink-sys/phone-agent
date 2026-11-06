import cn.wbphone.agent.WsClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 把手机侧协议跑在 JVM 上：连真实的 Node 桥，走真实的 WebSocket 帧，
 * 用来验证 WsClient 与握手的正确性——不需要真机，也不需要 Android 模拟器。
 */
private const val TINY_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

fun main(args: Array<String>) {
    val url = args.getOrElse(0) { "ws://127.0.0.1:8795/device?token=test" }
    val welcome = CountDownLatch(1)
    val finished = CountDownLatch(1)
    var calls = 0
    var ws: WsClient? = null

    val listener = object : WsClient.Listener {
        override fun onOpen() {
            println("OPEN")
            val hello = JSONObject()
                .put("type", "hello")
                .put("protocol", 1)
                .put(
                    "device",
                    JSONObject()
                        .put("model", "JVM Harness")
                        .put("android", "14")
                        .put("sdk", 34)
                        .put("width", 1080)
                        .put("height", 2400)
                )
                .put(
                    "tools",
                    JSONArray(listOf("read_screen", "tap", "input_text", "press_key", "swipe", "launch_app", "screenshot"))
                )
            ws?.send(hello.toString())
        }

        override fun onText(text: String) {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "welcome" -> {
                    println("WELCOME ${msg.optString("deviceId")}")
                    welcome.countDown()
                }

                "call" -> {
                    calls += 1
                    val id = msg.optString("id")
                    val method = msg.optString("method")
                    val params = msg.optJSONObject("params") ?: JSONObject()
                    println("CALL $method $params")
                    val result = when (method) {
                        "read_screen" -> screen()
                        "tap" -> JSONObject()
                            .put("ok", true)
                            .put("activated", "设置")
                            .put("navigatedTo", "com.android.settings")

                        "screenshot" -> JSONObject()
                            .put("ok", true)
                            .put("pngBase64", TINY_PNG)
                            .put("width", 1)
                            .put("height", 1)

                        else -> JSONObject().put("ok", true)
                    }
                    val reply = JSONObject()
                        .put("type", "result")
                        .put("id", id)
                        .put("ok", true)
                        .put("result", result)
                    ws?.send(reply.toString())
                }
            }
        }

        override fun onClosed(reason: String) {
            println("CLOSED $reason")
            finished.countDown()
        }

        override fun onError(error: Throwable) {
            println("ERROR ${error.message}")
        }
    }

    val client = WsClient(URI(url), listener)
    ws = client
    try {
        client.connect()
    } catch (e: Exception) {
        println("CONNECT_FAILED ${e.message}")
        exitProcess(1)
    }

    if (!welcome.await(6, TimeUnit.SECONDS)) {
        println("FAIL 没有收到 welcome")
        exitProcess(1)
    }
    // 等驱动器把三条工具调用打完
    finished.await(12, TimeUnit.SECONDS)
    println("CALLS $calls")
    client.close("测试结束")
    println("DEVICE_DONE")
}

private fun node(
    role: String,
    text: String,
    bounds: IntArray,
    clickable: Boolean = false,
    children: List<JSONObject> = emptyList(),
): JSONObject = JSONObject()
    .put("role", role)
    .put("text", text)
    .put("desc", "")
    .put("bounds", JSONArray().put(bounds[0]).put(bounds[1]).put(bounds[2]).put(bounds[3]))
    .put("clickable", clickable)
    .put("editable", false)
    .put("scrollable", false)
    .put("children", JSONArray(children))

/** 一棵和真机 dumpNode 输出结构完全一致的树。 */
private fun screen(): JSONObject {
    val rows = node(
        "LinearLayout", "", intArrayOf(48, 300, 1032, 720),
        children = listOf(
            node("TextView", "网络和互联网", intArrayOf(48, 300, 1032, 440), clickable = true),
            node("TextView", "关于手机", intArrayOf(48, 440, 1032, 580), clickable = true),
            node("TextView", "显示", intArrayOf(48, 580, 1032, 720), clickable = true),
        ),
    )
    val root = node(
        "FrameLayout", "", intArrayOf(0, 0, 1080, 2400),
        children = listOf(node("TextView", "设置", intArrayOf(48, 140, 1032, 260)), rows),
    )
    return JSONObject()
        .put("ok", true)
        .put(
            "screen",
            JSONObject().put("width", 1080).put("height", 2400).put("package", "com.android.settings")
        )
        .put("tree", root)
}
