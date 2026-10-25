package com.misaka.timelog.cloud

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端同步的 HTTP 客户端 —— **只用 `HttpURLConnection`**，不引 OkHttp / Retrofit。
 *
 * ## 为什么不引库
 *
 * 整个 App 只有**一个**接口（`POST /tl/sync`），一次请求一个 JSON。
 * OkHttp 会给安装包加 ~1MB、给构建加一个依赖；而这里需要的东西
 * `HttpURLConnection` 全都有。等哪天接口多了、要复用连接池了再说。
 *
 * ## ⚠️ 地址**不写死**，由设置页填
 *
 * 云同步连的是「你自己的后端」，不是作者的后端 —— 所以地址不能写死在代码里。
 * 在设置页「服务器地址」里填一个以 `/api` 结尾的地址（比如
 * `https://你的环境-你的appid.ap-shanghai.app.tcloudbase.com/api`），
 * token 填后端约定的那一串，两者都对了才连得上。
 *
 * 没填地址时【同步】按钮会直接告诉你「先填服务器地址」，
 * 而不是发一个注定失败的请求 —— 「填错了」和「网断了」必须能分辨。
 */
object SyncClient {

    private const val TIMEOUT_MS = 25_000

    /**
     * 把用户在设置里填的地址整理成可用的形式。
     *
     * 容忍两种手滑：① 结尾多了斜杠（`.../api/`）② 忘了写 `/api`。
     * 这两种都很常见，与其让用户对着 404 猜，不如这里顺手补上。
     */
    fun normalizeBase(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        while (s.endsWith("/")) s = s.dropLast(1)
        if (!s.endsWith("/api")) s += "/api"
        return s
    }

    /**
     * 一次请求的结果。
     *
     * `code = 0` 表示**没连上**（网络/超时/DNS），这时 [error] 有话说；
     * `code > 0` 表示服务端真的回了，看 [body] 里的 `msg`。
     * 这两类必须分开 —— 「网断了」和「token 错了」对用户是两种完全不同的处境。
     */
    data class Result(
        val code: Int,
        val body: JSONObject?,
        val raw: String,
        val error: String?,
    ) {
        val ok: Boolean get() = code in 200..299 && body?.optBoolean("ok") == true

        /** 给用户看的一句话。**不能只说「失败」** —— 那等于什么都没说。 */
        fun message(): String {
            val msg = body?.optString("msg").orEmpty()
            return when {
                error != null -> error
                msg.isNotEmpty() -> msg
                code == 401 -> "token 不对（AI 的只读 token 也不能用来写）"
                code != 0 && code !in 200..299 -> "服务端返回 $code"
                else -> raw.take(120)
            }
        }
    }

    /**
     * `POST /tl/sync`。
     *
     * ⚠️ 刻意**不设置** `Accept-Encoding` —— Android 的 `HttpURLConnection`
     * 自己会加 gzip 并透明解压，手动设了反而要自己解一遍（然后忘了解 → 乱码）。
     */
    fun postSync(baseUrl: String, json: String, token: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            val url = normalizeBase(baseUrl)
            if (url.isEmpty()) {
                return Result(0, null, "", "还没有填服务器地址")
            }
            conn = (URL(url + "/tl/sync").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
            }.orEmpty()
            val body = try {
                if (raw.isBlank()) null else JSONObject(raw)
            } catch (e: Exception) {
                null
            }
            Result(code = code, body = body, raw = raw, error = null)
        } catch (e: Exception) {
            // 没连上：网络断了、超时、DNS 挂了、明文 HTTP 被拦……全落在这里
            Result(code = 0, body = null, raw = "", error = friendly(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun friendly(e: Exception): String {
        val name = e.javaClass.simpleName
        val msg = e.message ?: ""
        return when {
            msg.contains("timeout", ignoreCase = true) || name.contains("Timeout") ->
                "连不上（超时 25 秒）—— 检查手机网络"
            msg.contains("Unable to resolve host", ignoreCase = true) ->
                "解析不了云端域名 —— 检查网络或 DNS"
            msg.contains("Cleartext", ignoreCase = true) ->
                "系统拦了明文 HTTP 请求"
            else -> "连不上：$name ${msg.take(80)}"
        }
    }
}
