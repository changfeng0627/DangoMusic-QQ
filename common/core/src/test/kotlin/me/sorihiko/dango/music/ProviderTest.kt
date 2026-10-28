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
    @Test fun authorizedMp3WithEmptySipUsesQqMusicCdn() {
        for (sip in listOf("", "\"sip\":[],", "\"sip\":[\"\"],")) {
            val source = QQProtocol.playUrl(QQProtocol.parse("""{$sip"midurlinfo":[{"result":0,"filename":"M500fixture.mp3","purl":"M500fixture.mp3?vkey=fixture-only","vkey":"fixture-only"}]}"""))!!
            assertEquals("https://isure.stream.qqmusic.qq.com/M500fixture.mp3?vkey=fixture-only", source.url)
            assertEquals(AudioCodec.MP3, source.codec)
        }
    }
    @Test fun missingCdnNeverMakesAnEmptyOrRejectedPurlPlayable() {
        assertNull(QQProtocol.playUrl(QQProtocol.parse("""{"sip":[],"midurlinfo":[{"result":0,"purl":""}]}""")))
        assertNull(QQProtocol.playUrl(QQProtocol.parse("""{"sip":[],"midurlinfo":[{"result":104003,"filename":"a.mp3","purl":"a.mp3?vkey=stale"}]}""")))
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
    @Test fun qqPartyKeepsSeparateMediaMid() {
        val track = PartyProtocol.track(QQProtocol.parse("""{"platform":"qq","trackId":"songMID","mediaId":"fileMID"}"""))!!
        assertEquals("fileMID", track.mediaId)
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
    @Test fun renderCredentialPresenceDoesNotReadOrLockTheFile() {
        val dir = Files.createTempDirectory("dango-render-credential-test").toFile()
        var accesses = 0
        try {
            val store = QQCredentials { accesses++; dir.resolve("qqmusic_credentials.json") }
            repeat(100) { assertFalse(store.present) }
            assertEquals(0, accesses)
            store.save(json("musicid" to 123, "musickey" to "fixture-only"))
            val afterSave = accesses
            repeat(100) { assertTrue(store.present) }
            assertEquals(afterSave, accesses)
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancelledCredentialWriteDoesNotReplaceTheAccount() {
        val dir = Files.createTempDirectory("dango-cancelled-credential-test").toFile()
        try {
            val store = QQCredentials { dir.resolve("qqmusic_credentials.json") }
            store.save(json("musicid" to 123, "musickey" to "old-fixture"))
            store.save(json("musicid" to 456, "musickey" to "cancelled-fixture")) { false }
            assertEquals("123", store.read().str("musicid"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun qqCallbackArgumentsHandleJavascriptEscapes() {
        val args = QQMusicLogin.callbackArguments("ptuiCB('0','0','https://qq.com/?uin=123\\x26ptsigx=fixture','0','OK','name\\'s');")
        assertEquals("0", args[0])
        assertEquals("https://qq.com/?uin=123&ptsigx=fixture", args[2])
        assertEquals("name's", args[5])
    }
    @Test fun confirmationPerformsCompleteCookieAndOauthExchange() {
        var requests = 0
        val stages = mutableListOf<String>()
        val transport = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
                requests++
                if (requests == 1) {
                    assertTrue(url.startsWith("https://ssl.ptlogin2.graph.qq.com/check_sig?"))
                    assertNull(headers["Cookie"])
                    return HttpReply(302, ByteArray(0), listOf("p_skey=fixture-skey; Expires=Wed, 01 Jan 2030 00:00:00 GMT, p_uin=o123; Path=/"), "https://graph.qq.com/oauth2.0/login_jump")
                }
                assertEquals("https://graph.qq.com/oauth2.0/authorize", url)
                assertEquals("https://graph.qq.com", headers["Origin"])
                assertEquals("https://graph.qq.com/", headers["Referer"])
                assertTrue(headers["Cookie"]!!.contains("p_skey=fixture-skey"))
                val nonce = QQMusicLogin.query("https://qq.com/?$body", "state")!!
                return HttpReply(302, ByteArray(0), emptyList(), "https://y.qq.com/portal/wx_redirect.html?code=fixture-code&state=$nonce")
            }
        }
        val auth = QQAuthorization(transport, { code ->
            assertEquals("fixture-code", code)
            json("musicid" to 123, "musickey" to "fixture-only")
        }, { stages += it }).complete("https://qq.com/?uin=123&ptsigx=fixture-ticket")
        assertEquals("123", auth.str("musicid")); assertEquals("2", auth.str("loginType"))
        assertEquals(2, requests); assertEquals("QQ Music credential exchange", stages.last())
    }
    @Test fun qrSessionRetainsCookiesAcrossConfirmationAndTicketRedirect() {
        var requests = 0
        val delegate = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
                when (requests++) {
                    0 -> return HttpReply(200, ByteArray(0), listOf("pt_login_sig=poll-fixture; Domain=.qq.com; Path=/; Secure"), null)
                    1 -> {
                        assertTrue(headers["Cookie"]!!.contains("pt_login_sig=poll-fixture"))
                        return HttpReply(302, ByteArray(0), emptyList(), "https://graph.qq.com/oauth2.0/login_jump")
                    }
                    2 -> {
                        assertEquals("https://graph.qq.com/oauth2.0/login_jump", url)
                        assertTrue(headers["Cookie"]!!.contains("pt_login_sig=poll-fixture"))
                        return HttpReply(200, ByteArray(0), listOf("p_skey=redirect-fixture; Domain=graph.qq.com; Path=/; Secure, p_uin=o123; Domain=graph.qq.com; Path=/; Secure"), null)
                    }
                    else -> {
                        assertEquals("https://graph.qq.com/oauth2.0/authorize", url)
                        assertTrue(headers["Cookie"]!!.contains("p_skey=redirect-fixture"))
                        val state = QQMusicLogin.query("https://qq.com/?$body", "state")!!
                        return HttpReply(302, ByteArray(0), emptyList(), "https://y.qq.com/?code=fixture&state=$state")
                    }
                }
            }
        }
        val transport = MusicSessionTransport(delegate)
        transport.request("https://ssl.ptlogin2.qq.com/ptqrlogin")
        val auth = QQAuthorization(transport, { json("musicid" to 123, "musickey" to "fixture") })
            .complete("https://qq.com/?uin=123&ptsigx=fixture")
        assertEquals("123", auth.str("musicid"))
        assertEquals(4, requests)
    }
    @Test fun loginCookieJarHonorsDomainPathSecureAndAttemptBoundaries() {
        val delegate = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>) =
                HttpReply(200, ByteArray(0), listOf("ticket=fixture; Domain=graph.qq.com; Path=/oauth2.0/; Secure"), null)
        }
        val transport = MusicSessionTransport(delegate)
        transport.request("https://graph.qq.com/oauth2.0/login_jump")
        assertEquals("fixture", transport.cookiesFor("https://graph.qq.com/oauth2.0/authorize")["ticket"])
        for (url in listOf("https://graph.qq.com/other", "https://evil.test/oauth2.0/", "https://y.qq.com/oauth2.0/", "http://graph.qq.com/oauth2.0/"))
            assertTrue(transport.cookiesFor(url).isEmpty())
        assertTrue(MusicSessionTransport(delegate).cookiesFor("https://graph.qq.com/oauth2.0/").isEmpty())
    }
    @Test fun ticketRedirectRejectsAnUnrelatedHostBeforeSendingCookies() {
        var requests = 0
        val transport = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>): HttpReply {
                requests++
                return HttpReply(302, ByteArray(0), emptyList(), "https://evil.test/ticket")
            }
        }
        try {
            QQAuthorization(transport, { fail("unexpected exchange"); JsonObject() })
                .complete("https://qq.com/?uin=123&ptsigx=fixture")
            fail("unsafe redirect accepted")
        } catch (e: MusicException) { assertEquals("TICKET_REDIRECT_HOST", e.diagnosticCode) }
        assertEquals(1, requests)
    }
    @Test fun missingTicketHasSafeUsefulDiagnosticAndDoesNotExchangeCode() {
        val transport = object : MusicTransport {
            override fun request(url: String, body: String?, headers: Map<String, String>) = HttpReply(302, ByteArray(0), emptyList(), null)
        }
        try {
            QQAuthorization(transport, { fail("exchange called without ticket"); JsonObject() })
                .complete("https://qq.com/?uin=123&ptsigx=fixture-ticket")
            fail("missing ticket accepted")
        } catch (e: MusicException) {
            assertEquals("MISSING_P_SKEY", e.diagnosticCode)
            assertFalse(e.message!!.contains("fixture-ticket"))
        }
    }
}
