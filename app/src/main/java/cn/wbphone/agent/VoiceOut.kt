package cn.wbphone.agent

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale

/**
 * 朗读：套系统 TextToSpeech，不打包语音包（一个中文离线包几十兆，不值当）。
 *
 * 三件事：
 * 1. **先加工再念**。交给 [VoiceText] 去 Markdown、截断，不会念出星号井号。
 * 2. **异步就绪**。TTS 引擎是绑定的，初始化是回调式的；就绪前来的朗读请求先挂起，
 *    就绪后补上，用户不用点第二次。
 * 3. **念完要回报**。轮末按钮要在「朗读 / 停止」之间切换，靠 UtteranceProgressListener
 *    回到主线程通知；引擎没装中文包时老实说，不假装在念。
 */
class VoiceOut(private val act: Activity) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: String? = null
    private var onDone: (() -> Unit)? = null
    private var seq = 0

    /** 当前是否在念。界面据此切换按钮文案。 */
    var speaking = false
        private set

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finish()
        @Deprecated("兼容旧 API")
        override fun onError(utteranceId: String?) = finish()
        override fun onError(utteranceId: String?, errorCode: Int) = finish()
    }

    private fun finish() {
        // 回调走的是 TTS 的线程，界面操作必须回主线程
        act.runOnUiThread {
            speaking = false
            val cb = onDone
            onDone = null
            cb?.invoke()
        }
    }

    /** 念一段 Markdown 回答。onDone 在念完/出错/被停时都调一次，调用方据此复位按钮。 */
    fun speak(markdown: String, done: () -> Unit) {
        val body = VoiceText.clip(VoiceText.toSpeech(markdown))
        if (body.isBlank()) { done(); return }
        onDone = done
        val engine = tts
        if (engine == null) { pending = body; init(); return }
        if (failed) { onDone = null; toast("这台设备没有可用的语音引擎"); done(); return }
        if (!ready) { pending = body; return }
        speakNow(body)
    }

    /** 轮末「朗读」按钮：在念就停，不在念就开始。返回切换后的状态。 */
    fun toggle(markdown: String, done: () -> Unit): Boolean {
        if (speaking) { stop(); done(); return false }
        speak(markdown, done)
        return true
    }

    fun stop() {
        speaking = false
        pending = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun init() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(act) { status ->
            val e = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                pending = null
                val cb = onDone
                onDone = null
                act.runOnUiThread {
                    toast("这台设备没有可用的语音引擎")
                    cb?.invoke()
                }
                return@TextToSpeech
            }
            val langs = runCatching { e.availableLanguages.map { it.toLanguageTag() } }
                .getOrDefault(emptyList())
            val pick = VoiceText.pickLanguage(langs) ?: "zh-CN"
            val result = runCatching { e.setLanguage(Locale.forLanguageTag(pick)) }.getOrNull()
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                failed = true
                val cb = onDone
                onDone = null
                act.runOnUiThread {
                    toast("系统没装中文语音包，去系统设置的「文字转语音」里下一个")
                    cb?.invoke()
                }
                return@TextToSpeech
            }
            runCatching { e.setSpeechRate(1.05f) }   // 默认语速念长文偏慢
            e.setOnUtteranceProgressListener(listener)
            ready = true
            pending?.let { body -> pending = null; speakNow(body) }
        }
        tts = engine
    }

    private fun speakNow(body: String) {
        val engine = tts ?: return
        speaking = true
        val ok = runCatching {
            engine.speak(body, TextToSpeech.QUEUE_FLUSH, null, "u${++seq}")
        }.getOrDefault(TextToSpeech.ERROR)
        if (ok != TextToSpeech.SUCCESS) {
            speaking = false
            val cb = onDone
            onDone = null
            toast("朗读没启动起来")
            cb?.invoke()
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(act, message, Toast.LENGTH_SHORT).show() }
    }
}
