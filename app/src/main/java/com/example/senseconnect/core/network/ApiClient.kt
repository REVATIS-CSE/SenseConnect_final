package com.example.senseconnect.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val statusCode: Int?, message: String) : IOException(message)

/**
 * Minimal JSON-over-HTTPS client for the SenseConnect backend, built on HttpURLConnection so the
 * app needs no extra networking dependency. All calls run on [Dispatchers.IO].
 */
class ApiClient(baseUrl: String) {

    val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    suspend fun get(path: String): JSONObject = request("GET", path, null)

    suspend fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(baseUrl + path.removePrefix("/"))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SenseConnect-Android")
            }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw ApiException(code, message?.takeIf { it.isNotBlank() } ?: "Server returned HTTP $code")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        // Generous timeouts: a Render free instance can take ~30-50 s to wake from sleep.
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
