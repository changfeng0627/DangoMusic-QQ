package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics
import java.awt.image.BufferedImage

enum class MusicPlatform(val platformId: String, val label: String) {
    NETEASE("netease", "NetEase"), QQ("qq", "QQ Music");

    companion object {
        fun fromId(id: String): MusicPlatform? = values().firstOrNull { it.platformId == id }
    }
}

data class MusicTrack(
    val platform: MusicPlatform,
    val id: String,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val coverUrl: String,
    val mediaId: String = id,
) {
    val platformId: String get() = platform.platformId
    val trackId: String get() = id
    val key: String get() = "$platformId:$id"
}

data class MusicPlaylist(
    val platform: MusicPlatform, val id: String, val name: String,
    val trackCount: Int, val coverUrl: String, val owned: Boolean,
    val liked: Boolean = false,
) {
    val key: String get() = "${platform.platformId}:$id"
}

data class UserProfile(val platform: MusicPlatform, val id: String, val name: String, val avatarUrl: String)

enum class LoginState { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }
enum class LoginChannel { QQ, WECHAT }

interface MusicLogin {
    val state: LoginState
    val qrImage: BufferedImage?
    val qrVersion: Int
    fun start(channel: LoginChannel = LoginChannel.QQ)
    fun cancel()
}

/** Each provider owns its HTTP authentication and its local credential file. */
interface MusicService {
    val platform: MusicPlatform
    val loggedIn: Boolean
    val profile: UserProfile?
    val login: MusicLogin
    val lastError: String get() = ""
    fun initialize() {}
    fun search(keyword: String, limit: Int = 50): List<MusicTrack>
    fun getTrack(trackId: String): MusicTrack?
    fun getPlayUrl(track: MusicTrack): AudioSource?
    fun download(source: AudioSource): ByteArray? = MusicHttp.download(source.url)
    fun getLyrics(track: MusicTrack): Lyrics?
    fun getPlaylists(): List<MusicPlaylist>
    fun getPlaylistTracks(playlistId: String): List<MusicTrack>
    fun getUser(): UserProfile?
    fun recommendations(): List<MusicPlaylist> = emptyList()
    fun charts(): List<MusicPlaylist> = emptyList()
    fun logout()
}

object MusicServices {
    fun forPlatform(platform: MusicPlatform): MusicService = when (platform) {
        MusicPlatform.NETEASE -> NeteaseMusicService
        MusicPlatform.QQ -> QQMusicService
    }
}

/** Safe user-facing failure key; never contains a URL, cookie, OAuth code or ticket. */
class MusicException(val userMessage: String, val diagnosticCode: String? = null) : Exception(userMessage)
