package me.sorihiko.dango.music

import java.net.HttpCookie
import java.net.URI

/** Per-attempt cookie jar. Never installed globally, persisted, or shared with another account. */
internal class MusicSessionTransport(private val delegate: MusicTransport) : MusicTransport {
    private data class Entry(val cookie: HttpCookie, val domain: String, val path: String, val hostOnly: Boolean)
    private val jar = linkedMapOf<Triple<String, String, String>, Entry>()
    fun cookiesFor(url: String): Map<String, String> {
        val uri = URI(url)
        val host = uri.host.lowercase()
        val path = uri.path.ifEmpty { "/" }
        val result = linkedMapOf<String, String>()
        jar.entries.removeAll { it.value.cookie.hasExpired() }
        // java.net.CookieManager uses the older RFC 2965 domain rule and drops .qq.com
        // cookies at hosts such as ssl.ptlogin2.graph.qq.com. QQ uses RFC 6265 suffix matching.
        jar.values.sortedBy { it.path.length }.forEach { entry ->
            val domainMatches = host == entry.domain || (!entry.hostOnly && host.endsWith("." + entry.domain))
            val pathMatches = path == entry.path || (path.startsWith(entry.path) &&
                (entry.path.endsWith('/') || path.getOrNull(entry.path.length) == '/'))
            if (domainMatches && pathMatches && (!entry.cookie.secure || uri.scheme == "https"))
                result[entry.cookie.name] = entry.cookie.value
        }
        return result
    }
    override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
        val uri = URI(url)
        val cookies = linkedMapOf<String, String>()
        cookies.putAll(cookiesFor(url))
        headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value?.split(';')?.forEach { pair ->
            cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=', "").trim()
        }
        val outgoing = headers.filterKeys { !it.equals("Cookie", true) }.toMutableMap()
        if (cookies.isNotEmpty()) outgoing["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val reply = delegate.request(url, body, outgoing)
        // Java's cookie parser expects separate Set-Cookie entries, including through combining proxies.
        val separate = reply.cookies.flatMap { it.split(Regex(",(?=\\s*[A-Za-z0-9_]+\\s*=)")) }
        for (header in separate) {
            val parsed = try { HttpCookie.parse(header) } catch (_: IllegalArgumentException) { continue }
            for (cookie in parsed) {
                val host = uri.host.lowercase()
                val hostOnly = cookie.domain.isNullOrEmpty()
                val domain = cookie.domain?.trimStart('.')?.lowercase() ?: host
                // This jar is exclusively for QQ/WeChat login, whose approved endpoints are under qq.com.
                if (domain != "qq.com" && !domain.endsWith(".qq.com")) continue
                if (host != domain && (hostOnly || !host.endsWith(".$domain"))) continue
                val path = cookie.path?.takeIf { it.startsWith('/') }
                    ?: uri.path.substringBeforeLast('/', "").ifEmpty { "/" }
                val key = Triple(cookie.name, domain, path)
                if (cookie.hasExpired()) jar.remove(key) else jar[key] = Entry(cookie, domain, path, hostOnly)
                if (jar.size > 128) jar.remove(jar.keys.first())
            }
        }
        return reply
    }
}
