package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Replaceable provider boundary; the default calls QQ Music directly, without an external service. */
interface QQProvider {
    fun rpc(module: String, method: String, params: JsonObject, loginType: Int? = null): JsonObject
    fun profile(): JsonObject
    fun phoneRpc(method: String, params: JsonObject): JsonObject = throw MusicException("QQ Music phone login is unavailable; use QR login")
}

class QQCredentials(private val file: () -> File) {
    @Synchronized fun read(): JsonObject = try {
        val f = file()
        if (!f.isFile || f.length() > 65536) JsonObject() else QQProtocol.parse(f.readText(Charsets.UTF_8))
    } catch (_: Exception) { JsonObject() }
    fun valid(o: JsonObject) = (o.str("musicid", "str_musicid").toLongOrNull() ?: 0L) > 0L && o.str("musickey").isNotBlank()
    @Synchronized fun save(value: JsonObject) {
        if (!valid(value)) throw MusicException("QQ Music login did not return complete credentials")
        write(value)
    }
    @Synchronized fun clear() = write(JsonObject())
    private fun write(value: JsonObject) {
        val f = file(); f.parentFile.mkdirs()
        val temp = File(f.parentFile, f.name + ".tmp")
        try {
            temp.writeText(value.toString(), Charsets.UTF_8)
            // Best-effort owner-only permissions on Unix; Windows inherits the user's config ACL.
            temp.setReadable(false, false); temp.setWritable(false, false)
            temp.setReadable(true, true); temp.setWritable(true, true)
            try { Files.move(temp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }
}

class QQWebProvider(
    private val credentials: () -> JsonObject,
    private val transport: MusicTransport = MusicHttp,
) : QQProvider {
    override fun phoneRpc(method: String, params: JsonObject): JsonObject {
        require(method == "SendPhoneAuthCode" || method == "Login")
        // Phone authorization has its own public mobile request profile and starts without account cookies.
        val body = json("comm" to json("ct" to 11, "cv" to 20090008, "v" to 20090008,
            "tmeAppID" to "qqmusic", "tmeLoginMethod" to 3, "tmeLoginType" to 0, "format" to "json"),
            "req_0" to json("module" to "music.login.LoginServer", "method" to method, "param" to params))
        val reply = transport.request("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(),
            mapOf("Content-Type" to "application/json", "Referer" to "https://y.qq.com/"))
        if (reply.code !in 200..299) throw MusicException("QQ Music service is unavailable")
        val root = QQProtocol.parse(reply.text())
        if (root.str("code").ifEmpty { "0" } != "0") throw MusicException("QQ Music phone login is unavailable; use QR login")
        return root.obj("req_0") ?: throw MusicException("Invalid QQ Music response")
    }
    override fun rpc(module: String, method: String, params: JsonObject, loginType: Int?): JsonObject {
        val auth = credentials()
        val uin = auth.str("musicid", "str_musicid").ifEmpty { "0" }
        val key = auth.str("musickey")
        val type = loginType ?: auth.str("loginType").toIntOrNull() ?: if (key.startsWith("W_X")) 1 else 2
        val comm = json("ct" to 24, "cv" to 4747474, "format" to "json", "platform" to "yqq.json",
            "uin" to uin, "g_tk" to QQProtocol.hash33(key, 5381))
        if (key.isNotEmpty()) { comm.addProperty("authst", key); comm.addProperty("qq", uin) }
        if (key.isNotEmpty() || loginType != null) comm.addProperty("tmeLoginType", type)
        val body = json("comm" to comm, "req_0" to json("module" to module, "method" to method, "param" to params))
        val headers = mutableMapOf("Content-Type" to "application/json", "Referer" to "https://y.qq.com/")
        if (key.isNotEmpty()) headers["Cookie"] = "uin=$uin; qqmusic_uin=$uin; qqmusic_key=$key; qm_keyst=$key; tmeLoginType=$type"
        val response = transport.request("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), headers)
        if (response.code !in 200..299) throw MusicException("QQ Music service is unavailable")
        val root = QQProtocol.parse(response.text())
        val req = root.obj("req_0") ?: throw MusicException("Invalid QQ Music response")
        if (req.str("code") in listOf("1000", "104401", "104400"))
            throw MusicException("QQ Music session expired; please sign in again")
        if (root.str("code").ifEmpty { "0" } != "0" || req.str("code").ifEmpty { "0" } != "0")
            throw MusicException("QQ Music request rejected; sign in again or retry later")
        return req.obj("data") ?: throw MusicException("Invalid QQ Music response")
    }

    override fun profile(): JsonObject {
        val auth = credentials(); val uin = auth.str("musicid", "str_musicid"); val key = auth.str("musickey")
        val response = transport.request("https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?" +
            "format=json&inCharset=utf-8&outCharset=utf-8&cid=205360838&reqfrom=1&userid=${enc(uin)}&loginUin=${enc(uin)}&g_tk=${QQProtocol.hash33(key, 5381)}",
            headers = mapOf("Referer" to "https://y.qq.com/", "Cookie" to "qqmusic_uin=$uin; uin=$uin; qqmusic_key=$key; qm_keyst=$key"))
        if (response.code !in 200..299) throw MusicException("QQ Music service is unavailable")
        val root = QQProtocol.parse(response.text())
        if (root.str("code") != "0") throw MusicException("QQ Music session expired; please sign in again")
        return root.obj("data") ?: throw MusicException("Invalid QQ Music response")
    }
}

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

object QQMusicService : MusicService {
    override val platform = MusicPlatform.QQ
    internal val credentials = QQCredentials { DangoMusic.instance.clientDir.resolve("qqmusic_credentials.json").toFile() }
    @Volatile var provider: QQProvider = QQWebProvider({ credentials.read() })
    override val loggedIn get() = credentials.valid(credentials.read())
    @Volatile override var profile: UserProfile? = null; private set
    override val login: MusicLogin get() = QQMusicLogin
    override fun initialize() { /* No global cookie jar and no credential file outside clientDir. */ }

    override fun search(keyword: String, limit: Int): List<MusicTrack> {
        val params = json("query" to keyword, "search_type" to 0, "page_num" to limit.coerceIn(1, 100),
            "num_per_page" to limit.coerceIn(1, 100), "page_id" to 1, "page_num_offset" to 1, "highlight" to false,
            "searchid" to UUID.randomUUID().mostSignificantBits.toString().replace("-", ""))
        return QQProtocol.search(provider.rpc("music.adaptor.SearchAdaptor", "do_search_v2", params))
    }
    override fun getTrack(trackId: String): MusicTrack? {
        val data = provider.rpc("music.pf_song_detail_svr", "get_song_detail_yqq", json("song_mid" to trackId))
        return (data.obj("track_info") ?: data.obj("songinfo"))?.let { QQProtocol.track(it) }
    }
    override fun getPlayUrl(track: MusicTrack): AudioSource? {
        require(track.platform == platform)
        // Only ordinary MP3 variants. An empty purl is a permission decision, never a reason to bypass it.
        val data = provider.rpc("music.vkey.GetVkey", "UrlGetVkey", json(
            "uin" to credentials.read().str("musicid", "str_musicid").ifEmpty { "0" },
            "guid" to (UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE).toString(),
            "filename" to listOf("M500${track.mediaId}.mp3", "M800${track.mediaId}.mp3"),
            "songmid" to listOf(track.id, track.id), "songtype" to listOf(0, 0), "ctx" to 0))
        return QQProtocol.playUrl(data)
    }
    override fun getLyrics(track: MusicTrack): Lyrics? {
        val data = provider.rpc("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", json(
            "songMid" to track.id, "songmid" to track.id, "crypt" to 0, "lrc_t" to 0, "qrc" to 0,
            "qrc_t" to 0, "trans" to 0, "trans_t" to 0, "roma" to 0, "type" to 0))
        val lines = QQProtocol.lrc(data)?.let { LyricsProvider.parseLrc(it) } ?: return null
        return if (lines.isEmpty()) null else Lyrics(lines, "QQ Music")
    }
    override fun getUser(): UserProfile? {
        if (!loggedIn) { profile = null; return null }
        val creator = provider.profile().obj("creator") ?: throw MusicException("Invalid QQ Music response")
        val id = creator.str("uin", "musicid").ifEmpty { credentials.read().str("musicid", "str_musicid") }
        profile = UserProfile(platform, id, creator.str("nick", "nickname"), creator.str("headpic", "avatar", "avatarUrl"))
        return profile
    }
    override fun getPlaylists(): List<MusicPlaylist> {
        if (!loggedIn) return emptyList()
        val auth = credentials.read()
        val data = provider.rpc("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", json("uin" to auth.str("musicid", "str_musicid")))
        val result = data.arr("v_playlist").mapNotNull { (it as? JsonObject)?.let { p -> QQProtocol.playlist(p, true) } }.toMutableList()
        if (result.none { it.liked }) result.add(0, MusicPlaylist(platform, "liked", "Liked songs", 0, "", true, true))
        val euin = auth.str("encryptUin")
        if (euin.isNotEmpty()) {
            var offset = 0
            do {
                val fav = provider.rpc("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo", json("uin" to euin, "offset" to offset, "size" to 100))
                val rows = fav.arr("v_list")
                result += rows.mapNotNull { (it as? JsonObject)?.let { p -> QQProtocol.playlist(p, false) } }
                offset += rows.size()
            } while (rows.size() > 0 && fav.str("hasmore") == "1" && offset < 10000)
        }
        return result.distinctBy { it.key }.sortedBy { if (it.liked) 0 else 1 }
    }
    override fun getPlaylistTracks(playlistId: String): List<MusicTrack> {
        val result = linkedMapOf<String, MusicTrack>(); var offset = 0
        do {
            val params = json("disstid" to (playlistId.toLongOrNull() ?: 0L), "dirid" to if (playlistId == "liked") 201 else 0,
                "song_begin" to offset, "song_num" to 200, "tag" to true, "userinfo" to true, "orderlist" to true)
            if (playlistId == "liked") {
                if (!loggedIn) throw MusicException("Please sign in to QQ Music")
                params.addProperty("enc_host_uin", credentials.read().str("encryptUin"))
            }
            val data = provider.rpc("music.srfDissInfo.DissInfo", "CgiGetDiss", params)
            val rows = if (data.get("songlist") != null) data.arr("songlist") else data.arr("songList")
            rows.mapNotNull { (it as? JsonObject)?.let { o -> QQProtocol.track(o) } }.forEach { result[it.key] = it }
            offset += rows.size()
            val total = data.str("total_song_num", "total").toIntOrNull() ?: offset
        } while (rows.size() > 0 && offset < total && offset < 20000)
        return result.values.toList()
    }
    override fun logout() { login.cancel(); QQPhoneLogin.cancel(); credentials.clear(); profile = null }
}
