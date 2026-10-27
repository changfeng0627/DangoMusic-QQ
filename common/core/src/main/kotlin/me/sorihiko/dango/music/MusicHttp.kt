package me.sorihiko.dango.music

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class HttpReply(val code: Int, val bytes: ByteArray, val cookies: List<String>, val location: String?) {
    fun text(): String = String(bytes, Charsets.UTF_8)
}

interface MusicTransport {
    fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpReply
}

/** Single-hop requests: authentication callers explicitly control every redirect and cookie destination. */
object MusicHttp : MusicTransport {
    private const val MAX_BYTES = 64 * 1024 * 1024
    override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
        val target = URL(url)
        require(target.protocol == "https" || (target.protocol == "http" && !headers.keys.any { it.equals("Cookie", true) }))
        val conn = target.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val cookies = conn.headerFields.entries.filter { it.key?.equals("Set-Cookie", true) == true }.flatMap { it.value }
            val bytes = if (code in 200..299) conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw MusicException("Music response is too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } else ByteArray(0)
            return HttpReply(code, bytes, cookies, conn.getHeaderField("Location"))
        } finally { conn.disconnect() }
    }

    /** Media requests never carry account cookies, including redirects to CDN hosts. */
    fun download(url: String, accept: String = "*/*"): ByteArray? {
        var current = url
        repeat(6) {
            val reply = request(current, headers = mapOf("Accept" to accept, "Referer" to "https://y.qq.com/"))
            if (reply.code in 300..399 && reply.location != null) current = URL(URL(current), reply.location).toString()
            else return if (reply.code in 200..299) reply.bytes else null
        }
        return null
    }
}
