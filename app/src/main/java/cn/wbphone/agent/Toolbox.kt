package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * agent loop 眼里「工具」的全部样子。
 *
 * 为什么抽这一层：主对话用的是 [PhoneToolbox]（手机 + 工作台 + 闸门），而子代理
 * 只需要一个只读的小工具箱（[ReadOnlyToolbox]）。loop 本身不该关心背后是谁在干活——
 * 它只要「有哪些工具」「执行一个」「任务开始」「要不要收手」这四件事。
 *
 * 抽出来的另一个好处：这四件事全在纯 JVM 里，可以在桌面上跑真断言，
 * 不需要真机、不需要那个几百兆的测试框架。
 */
interface Toolbox {

    /** 这一轮该暴露给模型的工具定义（OpenAI function calling 格式）。 */
    fun schemas(): JSONArray

    /**
     * 执行一个工具调用。失败也返回文本（带 ok:false），不要抛异常——
     * 抛出去模型看不到原因，只会重试同样的调用。
     *
     * @param owner 会话身份，用来申请手机所有权（见 [PhoneLease]）。空串表示不参与。
     * @param onProgress 这个工具自己的进度。只有 task（派子代理）会用到：
     *   子代理一跑就是十几轮，界面不能只停在「派子代理…」上不动。
     * @param isCancelled 用户点了停止。工具里如果还套着别的循环（子代理），
     *   必须靠它跟着停——否则屏幕上已经停了，钱还在另一个循环里继续花。
     */
    fun execute(
        name: String,
        args: JSONObject,
        owner: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): ToolResult

    /** 每个任务开始调一次：清序号表、清审批授权、清「已加载技能」。 */
    fun beginTask(owner: String)

    /** 连续被拒太多次，loop 该收手了。 */
    fun shouldAbort(): Boolean = false
}
