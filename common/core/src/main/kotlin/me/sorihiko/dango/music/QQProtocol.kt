package me.sorihiko.dango.music

import com.google.gson.*
import java.net.URL
import java.util.Base64

internal fun JsonObject.str(vararg keys: String): String {
    for (key in keys) {
        val e = get(key)
        if (e != null && e.isJsonPrimitive) return e.asString
    }
    return ""
}
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray()
internal fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
    pairs.forEach { (k, v) -> add(k, jsonValue(v)) }
}
private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray().apply { value.forEach { add(jsonValue(it)) } }
    else -> JsonPrimitive(value.toString())
}

/** Tolerant mapping at the protocol boundary, strict identity/permission handling. */
object QQProtocol {
    fun parse(text: String): JsonObject = try { JsonParser().parse(text).asJsonObject }
        catch (_: Exception) { throw MusicException("Invalid QQ Music response") }

    fun track(raw: JsonObject): MusicTrack? {
        val o = raw.obj("songInfo") ?: raw.obj("songinfo") ?: raw
        val mid = o.str("mid", "songmid")
        if (mid.isBlank()) return null
        val album = o.obj("album")
        val albumMid = album?.str("mid", "pmid")?.ifEmpty { o.str("albummid") } ?: o.str("albummid")
        val cover = album?.str("picUrl", "cover")?.takeIf { it.isNotBlank() }
            ?: albumMid.takeIf { it.isNotEmpty() }?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" } ?: ""
        val artists = o.arr("singer").mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotEmpty() } }
        return MusicTrack(MusicPlatform.QQ, mid, o.str("title", "name", "songname"), artists,
            (o.str("interval").toLongOrNull() ?: 0).coerceAtLeast(0) * 1000, cover,
            o.obj("file")?.str("media_mid")?.takeIf { it.isNotBlank() } ?: mid)
    }

    fun search(data: JsonObject): List<MusicTrack> {
        val result = data.obj("body") ?: data
        val songs = result.obj("item_song") ?: result.obj("song") ?: result.obj("songlist") ?: result
        val list = when {
            songs.get("items") is JsonArray -> songs.arr("items")
            songs.get("list") is JsonArray -> songs.arr("list")
            songs.get("songlist") is JsonArray -> songs.arr("songlist")
            else -> result.arr("songlist")
        }
        return list.mapNotNull { (it as? JsonObject)?.let { o -> track(o) } }.distinctBy { it.key }
    }

    fun playlist(o: JsonObject, owned: Boolean): MusicPlaylist? {
        val liked = o.str("dirid", "dirId") == "201"
        val id = o.str("tid", "dissid", "id", "disstid").takeIf { it.isNotEmpty() && it != "0" }
            ?: if (liked) "liked" else return null
        return MusicPlaylist(MusicPlatform.QQ, id, o.str("title", "dissname", "name", "dirName"),
            o.str("songnum", "songNum", "song_cnt").toIntOrNull() ?: 0,
            o.str("picurl", "cover", "logo", "picUrl"), owned, liked)
    }

    fun playUrl(data: JsonObject): AudioSource? {
        for (item in data.arr("midurlinfo")) {
            val o = item as? JsonObject ?: continue
            if (o.str("result").let { it.isNotEmpty() && it != "0" }) continue
            val purl = o.str("purl"); if (purl.isBlank()) continue
            val filename = o.str("filename").ifEmpty { purl.substringBefore('?').substringAfterLast('/') }
            if (!filename.endsWith(".mp3", true)) continue
            val base = data.arr("sip").firstOrNull { it.isJsonPrimitive && it.asString.startsWith("https://") }?.asString
                ?: data.arr("sip").firstOrNull { it.isJsonPrimitive && it.asString.isNotBlank() }?.asString
                // Current authenticated vkey replies can return result=0 and an authorized
                // purl while leaving sip empty. Use QQ Music's normal CDN, retaining that
                // exact server-issued path and vkey; an empty/denied purl still never plays.
                ?: "https://isure.stream.qqmusic.qq.com/"
            val url = try { URL(URL(base), purl).toString() } catch (_: Exception) { continue }
            val host = URL(url).host.lowercase()
            // A vkey is used only on QQ Music's own CDN, never on a third-party resolver.
            if (host != "qq.com" && !host.endsWith(".qq.com") && host != "qqmusic.qq.com" && !host.endsWith(".qqmusic.qq.com")) continue
            return AudioSource(if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url, AudioCodec.MP3)
        }
        return null
    }

    fun lrc(data: JsonObject): String? {
        val raw = data.str("lyric", "lrc").takeIf { it.isNotBlank() } ?: return null
        val decoded = if (raw.trimStart().startsWith("[")) raw else try {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        } catch (_: Exception) { return null }
        return decoded.replace("&#58;", ":").replace("&#46;", ".").replace("&#10;", "\n")
            .replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&")
    }

    fun hash33(text: String, initial: Int = 0): Int {
        var hash = initial
        text.forEach { hash += (hash shl 5) + it.code }
        return hash and 0x7fffffff
    }

    /** Split proxy-combined headers without splitting the comma inside Expires. */
    fun cookies(headers: List<String>): Map<String, String> = headers.flatMap {
        it.split(Regex(",(?=\\s*[A-Za-z0-9_]+\\s*=)"))
    }.mapNotNull {
        val kv = it.substringBefore(';').trim(); val i = kv.indexOf('=')
        if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
    }.toMap()
}
