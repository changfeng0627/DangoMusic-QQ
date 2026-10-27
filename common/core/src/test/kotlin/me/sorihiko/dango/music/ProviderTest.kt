package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.party.PartyProtocol
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class ProviderTest {
    private fun fixture(name: String) = QQProtocol.parse(javaClass.getResourceAsStream("/music/$name.json")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() })

    @Test fun qqModernSearchMappingPreservesMidAndMediaMid() {
        val t = QQProtocol.search(fixture("qq-search")).single()
        assertEquals(MusicPlatform.QQ, t.platform)
        assertEquals("003alphaMID", t.trackId)
        assertEquals("mediaDifferentMID", t.mediaId)
        assertEquals(213000L, t.durationMs)
        assertEquals(listOf("歌手甲", "Artist B"), t.artists)
        assertTrue(t.coverUrl.contains("albumMID"))
    }
    @Test fun qqLegacyFieldsAreSupported() {
        val t = QQProtocol.track(QQProtocol.parse("""{"songmid":"abc","songname":"Name","interval":2,"albummid":"cover"}"""))!!
        assertEquals("Name", t.name); assertEquals("abc", t.mediaId); assertEquals(2000L, t.durationMs)
    }
    @Test fun numericIdIsNeverSubstitutedForMissingQqMid() {
        assertNull(QQProtocol.track(QQProtocol.parse("""{"id":123,"mid":null,"name":"bad"}""")))
    }
    @Test fun malformedSearchEntriesAreIgnored() {
        assertTrue(QQProtocol.search(QQProtocol.parse("""{"song":{"list":[null,1,{}, {"id":33}]}}""")).isEmpty())
    }
    @Test fun sameIdInDifferentPlatformsHasDifferentCacheIdentity() {
        val a = MusicTrack(MusicPlatform.NETEASE, "123", "", emptyList(), 0, "")
        assertNotEquals(a.key, a.copy(platform = MusicPlatform.QQ).key)
    }
    @Test fun qqPlaybackUsesOnlyServerReturnedMp3Purl() {
        val source = QQProtocol.playUrl(fixture("qq-vkey"))!!
        assertEquals(AudioCodec.MP3, source.codec)
        assertTrue(source.url.startsWith("https://dl.stream.qqmusic.qq.com/M500"))
    }
    @Test fun emptyPurlIsPermissionDenial() {
        assertNull(QQProtocol.playUrl(QQProtocol.parse("""{"sip":["https://dl.stream.qqmusic.qq.com/"],"midurlinfo":[{"purl":"","vkey":"unused"}]}""")))
    }
    @Test fun aacIsNotAdvertisedAsPlayableMp3() {
        assertNull(QQProtocol.playUrl(QQProtocol.parse("""{"sip":["https://dl.stream.qqmusic.qq.com/"],"midurlinfo":[{"purl":"C400a.m4a","filename":"C400a.m4a"}]}""")))
        assertFalse(AudioDecoders.supports(AudioCodec.M4A)); assertFalse(AudioDecoders.supports(AudioCodec.FLAC))
    }
    @Test fun vkeyIsNeverSentToThirdPartyCdn() {
        assertNull(QQProtocol.playUrl(QQProtocol.parse("""{"sip":["https://evil.example/"],"midurlinfo":[{"purl":"test.mp3?vkey=secret"}]}""")))
    }
    @Test fun qqBase64LrcRetainsLineTimingWithoutInventingWordTiming() {
        val text = "[00:01.25]你好\n[00:02.500]World"
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val lines = LyricsProvider.parseLrc(QQProtocol.lrc(json("lyric" to encoded))!!)
        assertEquals(1250L, lines[0].timeMs); assertEquals("你好", lines[0].text)
        assertNull(lines[0].words)
    }
    @Test fun invalidBase64LyricsDegradeToNoLyrics() {
        assertNull(QQProtocol.lrc(json("lyric" to "not base64!")))
    }
    @Test fun neteaseOldAndNewJsonStillParse() {
        val old = NeteaseTrack.parse(fixture("netease-old"))!!
        val modern = NeteaseTrack.parse(fixture("netease-modern"))!!
        assertEquals(old.id, modern.id); assertEquals(old.name, modern.name)
        assertEquals(old.artists, modern.artists); assertEquals(old.durationMs, modern.durationMs)
        assertEquals(old.coverUrl, modern.coverUrl)
    }
    @Test fun neteaseYrcKeepsWordTimeline() {
        val lines = LyricsProvider.parseYrc("[1000,2000](1000,500,0)你(1500,800,0)好")
        assertEquals("你好", lines.single().text); assertEquals(2, lines.single().words!!.size)
    }
    @Test fun legacyNumericPartyIdDefaultsToNetease() {
        val track = PartyProtocol.track(QQProtocol.parse("""{"trackId":123,"name":"legacy"}"""))!!
        assertEquals(MusicPlatform.NETEASE, track.platform); assertEquals("123", track.id)
    }
    @Test fun qqPartyIdRemainsString() {
        val track = PartyProtocol.track(QQProtocol.parse("""{"platform":"qq","trackId":"003alphaMID"}"""))!!
        assertEquals("qq:003alphaMID", track.key)
    }
    @Test fun unknownOrMalformedPartyDataIsSafe() {
        for (raw in listOf("{}", """{"platform":"unknown","trackId":"1"}""", """{"trackId":{}}""", """{"trackId":"abc"}"""))
            assertNull(PartyProtocol.track(QQProtocol.parse(raw)))
    }
    @Test fun likedPlaylistIsExplicitAndDoesNotDependOnListPosition() {
        val p = QQProtocol.playlist(json("dirId" to 201, "tid" to 0, "title" to "我喜欢"), true)!!
        assertTrue(p.liked); assertEquals("liked", p.id)
    }
    @Test fun combinedSetCookieKeepsExpiresCommaAndTicketValues() {
        val cookies = QQProtocol.cookies(listOf("p_skey=abc==; Expires=Wed, 01 Jan 2030 00:00:00 GMT, p_uin=o123; Path=/"))
        assertEquals("abc==", cookies["p_skey"]); assertEquals("o123", cookies["p_uin"])
    }
    @Test fun incompleteCredentialNeverOverwritesExistingCredential() {
        val dir = Files.createTempDirectory("dango-credential-test").toFile()
        try {
            val f = dir.resolve("qqmusic_credentials.json"); val store = QQCredentials { f }
            store.save(json("musicid" to 123, "musickey" to "fixture-only"))
            try { store.save(json("musicid" to 456)); fail("incomplete credential accepted") } catch (_: MusicException) {}
            assertEquals("fixture-only", store.read().str("musickey"))
            assertFalse(dir.resolve("netease_cookie.txt").exists())
            store.clear(); assertFalse(store.valid(store.read()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun providerErrorsDoNotExposeUpstreamSecrets() {
        val transport = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>) =
                HttpReply(200, """{"code":0,"req_0":{"code":403,"message":"secret-ticket"}}""".toByteArray(), emptyList(), null)
        }
        try {
            QQWebProvider({ JsonObject() }, transport).rpc("test", "test", JsonObject())
            fail("server rejection accepted")
        } catch (e: MusicException) { assertFalse(e.message!!.contains("secret-ticket")) }
    }
    @Test fun authenticatedRequestsHaveFixedDestinationAndAnonymousRequestsHaveNoCookie() {
        var requests = 0
        val transport = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
                assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg", url)
                if (requests++ == 0) assertNull(headers["Cookie"]) else assertTrue(headers["Cookie"]!!.contains("qqmusic_uin=123"))
                return HttpReply(200, """{"code":0,"req_0":{"code":0,"data":{}}}""".toByteArray(), emptyList(), null)
            }
        }
        QQWebProvider({ JsonObject() }, transport).rpc("test", "test", JsonObject())
        QQWebProvider({ json("musicid" to 123, "musickey" to "fixture-only") }, transport).rpc("test", "test", JsonObject())
        assertEquals(2, requests)
    }
    @Test fun phoneAndCountryCodeAreValidatedBeforeSendingSms() {
        assertTrue(QQPhoneLogin.validPhone("13800138000", "86"))
        assertFalse(QQPhoneLogin.validPhone("", "86"))
        assertFalse(QQPhoneLogin.validPhone("13800138000\r\n", "86"))
        assertFalse(QQPhoneLogin.validPhone("13800138000", "+86"))
    }
    @Test fun securityChallengeCanOnlyOpenAnHttpsQqPage() {
        assertEquals("https://captcha.qq.com/test", QQPhoneLogin.safeChallenge("https://captcha.qq.com/test"))
        for (url in listOf("http://qq.com/", "https://qq.com.evil.test/", "javascript:alert(1)", "https://user@qq.com/"))
            assertNull(QQPhoneLogin.safeChallenge(url))
    }
    @Test fun smsRequestsUseSeparateAnonymousLoginContext() {
        val transport = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
                val request = QQProtocol.parse(body!!)
                assertEquals("SendPhoneAuthCode", request.obj("req_0")!!.str("method"))
                assertEquals("11", request.obj("comm")!!.str("ct"))
                assertEquals("3", request.obj("comm")!!.str("tmeLoginMethod"))
                assertNull(headers["Cookie"])
                return HttpReply(200, """{"code":0,"req_0":{"code":20276,"data":{"securityURL":"https://captcha.qq.com/test"}}}""".toByteArray(), emptyList(), null)
            }
        }
        val response = QQWebProvider({ json("musicid" to 123, "musickey" to "fixture-only") }, transport)
            .phoneRpc("SendPhoneAuthCode", json("phoneNo" to "fixture"))
        assertEquals("20276", response.str("code"))
    }
    @Test fun oauthCodeParsingDoesNotRequireTrailingAmpersand() {
        assertEquals("fixture", QQMusicLogin.query("https://y.qq.com/portal/wx_redirect.html?code=fixture", "code"))
        assertEquals("a+b", QQMusicLogin.query("https://y.qq.com/?code=a%2Bb&state=state", "code"))
    }
}
