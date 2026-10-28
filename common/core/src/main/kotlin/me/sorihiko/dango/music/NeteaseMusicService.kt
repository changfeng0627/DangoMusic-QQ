package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.LyricsProvider

/** Keep the established NetEase API and login flow behind a small adapter. */
object NeteaseMusicService : MusicService {
    override val platform = MusicPlatform.NETEASE
    override val loggedIn get() = NeteaseApi.loggedIn
    override val login: MusicLogin get() = NeteaseLogin
    override val lastError get() = NeteaseApi.lastSearchError
    override val profile: UserProfile? get() = NeteaseApi.userId.takeIf { it != 0L }?.let {
        UserProfile(platform, it.toString(), NeteaseApi.nickname, NeteaseApi.avatarUrl)
    }

    override fun initialize() { NeteaseApi.ensureCookieFile(); NeteaseApi.refreshCredentials() }
    override fun search(keyword: String, limit: Int) = NeteaseApi.search(keyword, limit).map { it.unified() }
    override fun getTrack(trackId: String) = trackId.toLongOrNull()?.let { NeteaseApi.track(it)?.unified() }
    override fun getPlayUrl(track: MusicTrack) = track.id.toLongOrNull()?.let { NeteaseApi.songUrl(it) }
        ?.let { AudioSource(it.url, AudioCodec.MP3, it.trialOnly) }
    override fun getLyrics(track: MusicTrack) = track.id.toLongOrNull()?.let { LyricsProvider.neteaseLyricById(it) }
    override fun getPlaylists() = NeteaseApi.userPlaylists().mapIndexed { i, p -> p.unified(i == 0 && p.owned) }
    override fun getPlaylistTracks(playlistId: String) = playlistId.toLongOrNull()
        ?.let { NeteaseApi.playlistTracks(it).map { t -> t.unified() } } ?: emptyList()
    override fun getUser(): UserProfile? { NeteaseApi.resolveUser(); return profile }
    override fun download(source: AudioSource) = NeteaseApi.request(source.url)
    override fun recommendations() = NeteaseApi.recommendPlaylists().map { it.unified() }
    override fun charts() = NeteaseApi.toplists().map { it.unified() }
    override fun logout() = NeteaseApi.logout()

    private fun NeteaseTrack.unified() = MusicTrack(platform, id.toString(), name, artists, durationMs, coverUrl)
    private fun NeteasePlaylist.unified(liked: Boolean = false) =
        MusicPlaylist(platform, id.toString(), name, trackCount, coverUrl, owned, liked)
}
