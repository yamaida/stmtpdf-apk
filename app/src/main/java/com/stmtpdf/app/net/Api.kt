package com.stmtpdf.app.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 与官网的接口调用：统一走 app-api.php（?action=checkout|activate）。
 */
object Api {

    private const val BASE = "https://www.stmtpdf.com"

    data class Result(val ok: Boolean, val body: JSONObject?)

    fun createCheckout(email: String, plan: String, machine: String): Result {
        val payload = JSONObject()
            .put("email", email)
            .put("plan", plan)
            .put("machine_id", machine)
            .put("platform", "android")
        return post("/app-api.php?action=checkout", payload)
    }

    fun activate(order: String, machine: String): Result {
        val payload = JSONObject().put("order", order).put("machine_id", machine)
        return post("/app-api.php?action=activate", payload)
    }

    /** 试用额度台账（服务端权威，重装不重置）。 */
    fun trialStatus(machine: String, appVersion: String): Result {
        val payload = JSONObject()
            .put("machine_id", machine)
            .put("platform", "android")
            .put("app_version", appVersion)
        return post("/app-api.php?action=trial_status", payload)
    }

    fun trialConsume(machine: String, pages: Int, reqId: String, appVersion: String): Result {
        val payload = JSONObject()
            .put("machine_id", machine)
            .put("platform", "android")
            .put("pages", pages)
            .put("req_id", reqId)
            .put("app_version", appVersion)
        return post("/app-api.php?action=trial_consume", payload)
    }

    fun trialRelease(machine: String, pages: Int, reqId: String): Result {
        val payload = JSONObject()
            .put("machine_id", machine)
            .put("platform", "android")
            .put("pages", pages)
            .put("req_id", reqId)
        return post("/app-api.php?action=trial_release", payload)
    }

    fun trialVoid(machine: String, order: String): Result {
        val payload = JSONObject()
            .put("machine_id", machine)
            .put("platform", "android")
            .put("order_ref", order)
        return post("/app-api.php?action=trial_void", payload)
    }

    private fun post(path: String, payload: JSONObject): Result {
        return try {
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            Result(code in 200..299 && json?.optBoolean("ok") == true, json)
        } catch (e: Throwable) {
            Result(false, null)
        }
    }
}
