package cn.wbphone.agent

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录与令牌。
 *
 * 这条流程不是猜的，是从桌面端 CLI（@genie/agent-cli 2.165.0）的
 * AccountScopedExternalLinkAuthenticationProvider 里还原、并且在 Node 原型里逐条实测过的：
 *
 *   1. POST /v2/plugin/auth/state?platform=workbuddy  → { state, authUrl }
 *   2. 用户在浏览器/WebView 里打开 authUrl 完成登录
 *   3. GET  /v2/plugin/auth/token?state=...           → 每 1s 轮询，直到拿到 accessToken
 *   4. POST /v2/plugin/auth/token/refresh             → X-Refresh-Token 换新令牌
 *
 * 四个端点都是公开的，不校验客户端指纹——所以手机能独立登录，不需要电脑在场。
 */
object Auth {

    private const val PREFIX = "/v2/plugin"
    private const val PLATFORM = "workbuddy"

    /** 未登录时的待定码：不是错误，继续轮询即可。 */
    private const val CODE_TOKEN_PENDING = 11217
    private const val CODE_ACCOUNT_PENDING = 12151

    /**
     * 免鉴权头。服务端靠这几个标记跳过令牌校验，少一个就 401。
     * 名字虽然叫 X-No-Authorization，语义其实是"这条请求不走鉴权链"。
     */
    private val NO_AUTH = mapOf(
        "X-No-Authorization" to "true",
        "X-No-User-Id" to "true",
        "X-No-Enterprise-Id" to "true",
        "X-No-Department-Info" to "true",
    )

    @Volatile private var store: TokenStore? = null

    fun init(context: Context) {
        if (store == null) store = TokenStore(context.applicationContext)
    }

    fun credentials(): Credentials? = store?.load()

    fun accessToken(): String? = store?.load()?.accessToken?.takeIf { it.isNotEmpty() }

    fun save(c: Credentials) {
        store?.save(c)
    }

    fun clear() {
        store?.clear()
    }

    fun isLoggedIn(): Boolean = accessToken() != null

    // ------------------------------------------------------------ 网络端点

    /** 申请一个登录会话。返回 { state, authUrl }。 */
    fun requestAuthState(): JSONObject {
        val json = post("$PREFIX/auth/state?platform=$PLATFORM", NO_AUTH, null)
        if (json.optInt("code", -1) != 0) {
            throw IllegalStateException("申请登录会话失败：code=${json.optInt("code")} ${json.optString("msg")}")
        }
        return json.optJSONObject("data")
            ?: throw IllegalStateException("登录会话响应缺少 data")
    }

    /**
     * 轮询直到用户完成登录。
     * @param onTick 每次轮询回调，用于 UI 显示"等待第 n 次"
     */
    fun pollToken(state: String, timeoutMs: Long = 300_000, onTick: (Int) -> Unit = {}): Credentials {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ticks = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            ticks += 1
            onTick(ticks)

            val json = get("$PREFIX/auth/token?state=${urlEncode(state)}", NO_AUTH)
            val data = json.optJSONObject("data")
            if (data != null) {
                val token = data.optString("accessToken")
                if (token.isNotEmpty()) return toCredentials(data)
            }
            val code = json.optInt("code")
            if (code != CODE_TOKEN_PENDING && code != 0) {
                throw IllegalStateException("登录轮询失败：code=$code ${json.optString("msg")}")
            }
            // code == 0 但没有 token：服务端偶尔先回空 data，继续等
        }
        throw IllegalStateException("登录超时（5 分钟）。重新点一次登录即可，state 一次一换。")
    }

    /** 用 refreshToken 换新的 accessToken。 */
    fun refresh(refreshToken: String): Credentials {
        val headers = NO_AUTH + mapOf(
            "X-Refresh-Token" to refreshToken,
            "X-Auth-Refresh-Source" to "plugin",
        )
        val json = post("$PREFIX/auth/token/refresh", headers, null)
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("刷新响应缺少 data：${json.optString("msg")}")
        if (data.optString("accessToken").isEmpty()) {
            throw IllegalStateException("刷新失败：${json.optString("msg")}")
        }
        val c = toCredentials(data)
        save(c)
        return c
    }

    /** 账号信息（昵称、套餐）。失败不影响主流程。 */
    fun account(token: String): JSONObject? {
        return try {
            val json = get(
                "$PREFIX/auth/account",
                mapOf(
                    "Authorization" to "Bearer $token",
                    "X-No-Enterprise-Id" to "true",
                    "X-No-Department-Info" to "true",
                )
            )
            json.optJSONObject("data")
        } catch (e: Exception) {
            null
        }
    }

    private fun toCredentials(data: JSONObject) = Credentials(
        accessToken = data.optString("accessToken"),
        refreshToken = data.optString("refreshToken"),
        expiresAt = data.optLong("expiresAt", 0L),
    )

    // ------------------------------------------------------------ HTTP

    fun get(path: String, headers: Map<String, String>): JSONObject =
        request("GET", path, headers, null)

    fun post(path: String, headers: Map<String, String>, body: String?): JSONObject =
        request("POST", path, headers, body)

    fun request(method: String, path: String, headers: Map<String, String>, body: String?): JSONObject {
        val conn = (URL(Config.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            for ((k, v) in headers) setRequestProperty(k, v)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            } else if (method == "POST") {
                // 服务端要求 POST 有长度，哪怕为空
                setRequestProperty("Content-Length", "0")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
            } ?: ""
            if (text.isEmpty()) throw IllegalStateException("HTTP $code，响应为空")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalStateException("HTTP $code，响应不是 JSON：${text.take(160)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}

data class Credentials(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
)

/**
 * 令牌落地：Android Keystore 里的 AES-256/GCM 密钥加密后写入私有 SharedPreferences。
 *
 * 为什么不用 EncryptedSharedPreferences：那是 androidx.security 的组件，本工程坚持零第三方依赖。
 * 手写这三十行效果一样——密钥在 TEE/StrongBox 里，导不出来；数据被拷走也解不开。
 */
private class TokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wb_agent_auth", Context.MODE_PRIVATE)

    fun load(): Credentials? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val plain = decrypt(blob)
            val json = JSONObject(plain)
            Credentials(
                accessToken = json.optString("accessToken"),
                refreshToken = json.optString("refreshToken"),
                expiresAt = json.optLong("expiresAt", 0L),
            )
        } catch (e: Exception) {
            // 密钥被系统清掉（换机、恢复出厂、清除凭据）后解不开，只能重新登录。
            clear()
            null
        }
    }

    fun save(c: Credentials) {
        val json = JSONObject()
            .put("accessToken", c.accessToken)
            .put("refreshToken", c.refreshToken)
            .put("expiresAt", c.expiresAt)
        prefs.edit().putString(KEY_BLOB, encrypt(json.toString())).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求解锁才可用：agent 在后台跑的时候也要能刷令牌
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ct64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        return "$iv64:$ct64"
    }

    private fun decrypt(blob: String): String {
        val sep = blob.indexOf(':')
        require(sep > 0) { "密文格式不正确" }
        val iv = Base64.decode(blob.substring(0, sep), Base64.NO_WRAP)
        val ct = Base64.decode(blob.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "wb-agent-token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BLOB = "token_blob"
    }
}

