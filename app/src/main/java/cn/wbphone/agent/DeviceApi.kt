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
}

internal fun ok(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject().put("ok", true)
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}

internal fun fail(message: String, code: String = "EXEC_FAILED"): JSONObject =
    JSONObject().put("ok", false).put("error", message).put("code", code)
