package hu.abris.varosnezo.data

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    /** A Transitous és a Wikipedia is kér elérhetőséget a User-Agentben. */
    const val CONTACT = "https://github.com/ageraf/Varosnezo-busz"
    private const val UA = "VarosnezoBusz/0.1 (prototype; $CONTACT)"

    fun get(url: String): String = stream(url) { it.bufferedReader().readText() }

    /** Nagy válaszokhoz: a hívó adatfolyamként olvassa, nem kell egyben a memóriában tartani. */
    fun <T> stream(url: String, block: (InputStream) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", UA)
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("HTTP $code: ${err.take(300)}")
            }
            return c.inputStream.buffered().use(block)
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
