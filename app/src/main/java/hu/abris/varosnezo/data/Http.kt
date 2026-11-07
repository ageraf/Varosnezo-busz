package hu.abris.varosnezo.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    /** A Transitous és a Wikipedia is kér elérhetőséget a User-Agentben – írd át a sajátodra. */
    const val CONTACT = "https://github.com/ageraf/Varosnezo-busz"

    fun get(url: String): String = request(url, "GET", null, emptyMap())

    fun postJson(url: String, body: String, headers: Map<String, String>): String =
        request(url, "POST", body, headers + ("Content-Type" to "application/json"))

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "VarosnezoBusz/0.1 (prototype; $CONTACT)")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("HTTP $code: ${text.take(300)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
