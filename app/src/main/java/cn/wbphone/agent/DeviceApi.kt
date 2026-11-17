package cn.wbphone.agent

import org.json.JSONObject

/**
 * 设备能力。由无障碍服务实现，AgentCore 只依赖这个接口，
 * 这样协议层可以脱离 Android 单独测试（参见 tools/ 下的假实现）。
 */
interface DeviceApi {
    fun readScreen(): JSONObject
    fun tap(x: Int, y: Int): JSONObject
    fun inputText(text: String, submit: Boolean = false): JSONObject

    /** 在 (x,y) 坐标长按。微信这类读不到控件树的应用靠它唤出「粘贴」菜单。 */
    fun longPress(x: Int, y: Int, durationMs: Int = 900): JSONObject

    fun pressKey(key: String): JSONObject
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject
    fun launchApp(pkg: String): JSONObject
    /**
     * 截屏。x/y/w/h 全部 >= 0 时只截这一块（用于局部放大看小字、小按钮），否则整屏。
     * 返回里除 width/height（图片像素）外，还要带 originX/originY/regionWidth/regionHeight
     * （这块图对应的真实屏幕区域），调用方靠它把模型报的图片坐标换算回屏幕坐标。
     */
    fun screenshot(x: Int = -1, y: Int = -1, w: Int = -1, h: Int = -1): JSONObject

    /** [width, height]，用于 hello 上报与桥侧算坐标。 */
    fun displaySize(): IntArray
    fun deviceLabel(): String

    /**
     * 用系统 Intent 办一件「一句话的事」：设闹钟、计时器、日历、拨号盘、短信草稿、
     * 分享、导航、打开链接、跳设置页。
     *
     * 为什么不走无障碍点点点：这些全都有系统公开接口，一步就能到；
     * 走 UI 要进设置页翻半天，还要占屏幕租约、烧十几轮 token。
     * args 里的字段由调用方（PhoneTools）先校验，这里只管拼 Intent 和起 Activity。
     */
    fun startIntent(action: String, args: JSONObject): JSONObject
}

internal fun ok(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject().put("ok", true)
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}

internal fun fail(message: String, code: String = "EXEC_FAILED"): JSONObject =
    JSONObject().put("ok", false).put("error", message).put("code", code)
