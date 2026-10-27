package me.sorihiko.dango.musicui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.music.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal enum class Page { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS }
internal object ScreenState {
    var page = Page.HOME
    var settingsTab = 0
    var settingsBack = Page.HOME
    var backPage: Page? = null
    var opened: MusicPlaylist? = null
    var coverView = false
    var lastQuery = ""
    var context: GuiCanvas? = null
}

/** Tokens prevent slow results from an old platform/account replacing current UI data. */
internal object Library {
    private val worker = Executors.newFixedThreadPool(3) { r -> Thread(r, "Dango-MusicUI").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    private val searchGeneration = AtomicInteger()
    @Volatile var platform = MusicPlatform.NETEASE; private set
    val service: MusicService get() = MusicServices.forPlatform(platform)
    @Volatile var playlists: List<MusicPlaylist> = emptyList(); private set
    @Volatile var playlistsLoading = false; private set
    @Volatile var status = ""; private set
    @Volatile var searchError = ""; private set
    private val tracksCache = ConcurrentHashMap<String, List<MusicTrack>>()
    private val tracksLoading = ConcurrentHashMap.newKeySet<String>()
    @Volatile var recommend: List<MusicPlaylist>? = null; private set
    @Volatile var recommendLoading = false; private set
    @Volatile var charts: List<MusicPlaylist>? = null; private set
    @Volatile var chartsLoading = false; private set
    @Volatile var searchResults: List<MusicTrack>? = null; private set
    @Volatile var searching = false; private set
    private var lastLoadAt = 0L
    val stale: Boolean get() = System.currentTimeMillis() - lastLoadAt > 5 * 60_000L
    fun tracks(playlist: MusicPlaylist): List<MusicTrack>? = tracksCache[playlist.key]

    fun switchPlatform(next: MusicPlatform) {
        if (next == platform) return
        service.login.cancel()
        if (platform == MusicPlatform.QQ) QQPhoneLogin.cancel()
        platform = next
        ScreenState.opened = null; ScreenState.lastQuery = ""
        ScreenState.backPage = null; ScreenState.page = Page.HOME
        refreshAll()
    }
    private fun error(e: Exception): String =
        if (e is MusicException) e.userMessage else "Music request failed; check network or platform service"

    fun reloadPlaylists() {
        if (playlistsLoading) return
        val token = generation.get(); val provider = service
        playlistsLoading = true; lastLoadAt = System.currentTimeMillis()
        worker.execute {
            try {
                provider.initialize(); provider.getUser()
                val list = provider.getPlaylists()
                if (token == generation.get()) { playlists = list; status = "" }
            } catch (e: Exception) { if (token == generation.get()) status = error(e)
            } finally { if (token == generation.get()) playlistsLoading = false }
        }
    }
    fun loadRecommend() {
        if (recommend != null || recommendLoading) return
        val token = generation.get(); val provider = service
        recommendLoading = true
        worker.execute {
            try {
                val result = provider.recommendations()
                if (token == generation.get()) recommend = result
            } catch (e: Exception) {
                if (token == generation.get()) { recommend = emptyList(); status = error(e) }
            } finally { if (token == generation.get()) recommendLoading = false }
        }
    }
    fun loadCharts() {
        if (charts != null || chartsLoading) return
        val token = generation.get(); val provider = service
        chartsLoading = true
        worker.execute {
            try {
                val result = provider.charts()
                if (token == generation.get()) charts = result
            } catch (e: Exception) {
                if (token == generation.get()) { charts = emptyList(); status = error(e) }
            } finally { if (token == generation.get()) chartsLoading = false }
        }
    }
    fun loadTracks(playlist: MusicPlaylist) {
        val token = generation.get(); val requestKey = "$token:${playlist.key}"
        if (tracksCache.containsKey(playlist.key) || !tracksLoading.add(requestKey)) return
        worker.execute {
            try {
                val result = MusicServices.forPlatform(playlist.platform).getPlaylistTracks(playlist.id)
                if (token == generation.get()) tracksCache[playlist.key] = result
            } catch (e: Exception) {
                if (token == generation.get()) { tracksCache[playlist.key] = emptyList(); status = error(e) }
            } finally { tracksLoading.remove(requestKey) }
        }
    }
    fun search(q: String) {
        val token = generation.get(); val queryToken = searchGeneration.incrementAndGet(); val provider = service
        searching = true; searchResults = null; searchError = ""
        worker.execute {
            try {
                val result = provider.search(q)
                if (token == generation.get() && queryToken == searchGeneration.get()) {
                    searchResults = result; searchError = provider.lastError
                }
            } catch (e: Exception) {
                if (token == generation.get() && queryToken == searchGeneration.get()) {
                    searchResults = emptyList(); searchError = error(e)
                }
            } finally { if (token == generation.get() && queryToken == searchGeneration.get()) searching = false }
        }
    }
    fun softRefresh() {
        reloadPlaylists()
        ScreenState.opened?.let { tracksCache.remove(it.key); loadTracks(it) }
    }
    fun refreshAll() {
        generation.incrementAndGet(); searchGeneration.incrementAndGet()
        playlists = emptyList(); tracksCache.clear(); status = ""; searchError = ""
        recommend = null; charts = null; searchResults = null
        playlistsLoading = false; recommendLoading = false; chartsLoading = false; searching = false
        reloadPlaylists(); loadRecommend()
        if (ScreenState.page == Page.CHARTS) loadCharts()
    }
}
