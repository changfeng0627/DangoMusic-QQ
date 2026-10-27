package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** QQ/WeChat website QR authorization. Intermediate tickets live only in this worker's stack. */
object QQMusicLogin : MusicLogin {
    @Volatile override var state = LoginState.IDLE; private set
    @Volatile override var qrImage: BufferedImage? = null; private set
    @Volatile override var qrVersion = 0; private set
    private val session = AtomicInteger()
    private val lock = Any()
    private val allowedHosts = setOf("ssl.ptlogin2.qq.com", "ssl.ptlogin2.graph.qq.com", "graph.qq.com",
        "open.weixin.qq.com", "lp.open.weixin.qq.com")

    override fun start(channel: LoginChannel) {
        val token: Int
        synchronized(lock) {
            token = session.incrementAndGet(); qrImage = null; state = LoginState.LOADING
        }
        Thread({
            try {
                val auth = if (channel == LoginChannel.WECHAT) wechat(token) else qq(token)
                if (auth != null) synchronized(lock) {
                    if (token == session.get()) {
                        // A scan alone never counts as success: require the music server's complete credential exchange.
                        QQMusicService.credentials.save(auth)
                        state = LoginState.SUCCESS
                    }
                }
            } catch (_: Exception) {
                update(token, LoginState.ERROR)
            }
        }, "Dango-QQMusicLogin").apply { isDaemon = true; start() }
    }
    override fun cancel() = synchronized(lock) {
        session.incrementAndGet(); qrImage = null
        if (state != LoginState.SUCCESS) state = LoginState.IDLE
    }
    private fun update(token: Int, next: LoginState) = synchronized(lock) {
        if (token == session.get()) state = next
    }
    private fun show(token: Int, bytes: ByteArray) = synchronized(lock) {
        if (token == session.get()) {
            qrImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw MusicException("Invalid QR image")
            qrVersion++; state = LoginState.WAITING
        }
    }
    private fun request(url: String, params: Map<String, String> = emptyMap(), body: String? = null,
                        cookie: String = "", referer: String = "https://y.qq.com/"): HttpReply {
        require(URL(url).protocol == "https" && URL(url).host in allowedHosts)
        val headers = mutableMapOf("Referer" to referer)
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        if (body != null) headers["Content-Type"] = "application/x-www-form-urlencoded"
        return MusicHttp.request(url + if (params.isEmpty()) "" else "?" + form(params), body, headers)
    }
    private fun form(params: Map<String, String>) = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    internal fun query(url: String, name: String): String? = try {
        URL(url).query?.split('&')?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    } catch (_: Exception) { null }

    private fun qq(token: Int): JsonObject? {
        val qr = request("https://ssl.ptlogin2.qq.com/ptqrshow", mapOf("appid" to "716027609", "e" to "2",
            "l" to "M", "s" to "3", "d" to "72", "v" to "4", "daid" to "383", "pt_3rd_aid" to "100497308",
            "t" to Math.random().toString()), referer = "https://xui.ptlogin2.qq.com/")
        val qrsig = QQProtocol.cookies(qr.cookies)["qrsig"] ?: throw MusicException("Unable to create QR session")
        show(token, qr.bytes)
        val deadline = System.currentTimeMillis() + 180000
        while (token == session.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (token != session.get()) return null
            val reply = request("https://ssl.ptlogin2.qq.com/ptqrlogin", mapOf("u1" to "https://graph.qq.com/oauth2.0/login_jump",
                "ptqrtoken" to QQProtocol.hash33(qrsig).toString(), "ptredirect" to "0", "h" to "1", "t" to "1", "g" to "1",
                "from_ui" to "1", "ptlang" to "2052", "action" to "0-0-${System.currentTimeMillis()}", "js_ver" to "20102616",
                "js_type" to "1", "pt_uistyle" to "40", "aid" to "716027609", "daid" to "383", "pt_3rd_aid" to "100497308",
                "has_onekey" to "1"), cookie = "qrsig=$qrsig", referer = "https://xui.ptlogin2.qq.com/")
            val args = Regex("'([^']*)'").findAll(reply.text()).map { it.groupValues[1] }.toList()
            when (args.firstOrNull()) {
                "66" -> update(token, LoginState.WAITING)
                "67" -> update(token, LoginState.SCANNED)
                "65" -> { update(token, LoginState.EXPIRED); return null }
                "0" -> {
                    if (token != session.get()) return null
                    val callback = args.getOrNull(2) ?: throw MusicException("Invalid QR callback")
                    val uin = query(callback, "uin") ?: throw MusicException("Invalid QR callback")
                    val sig = query(callback, "ptsigx") ?: throw MusicException("Invalid QR callback")
                    val check = request("https://ssl.ptlogin2.graph.qq.com/check_sig", mapOf("uin" to uin, "pttype" to "1",
                        "service" to "ptqrlogin", "nodirect" to "0", "ptsigx" to sig, "s_url" to "https://graph.qq.com/oauth2.0/login_jump",
                        "ptlang" to "2052", "ptredirect" to "100", "aid" to "716027609", "daid" to "383", "j_later" to "0",
                        "low_login_hour" to "0", "regmaster" to "0", "pt_login_type" to "3", "pt_aid" to "0", "pt_aaid" to "16",
                        "pt_light" to "0", "pt_3rd_aid" to "100497308"), referer = "https://xui.ptlogin2.qq.com/")
                    val cookies = QQProtocol.cookies(check.cookies)
                    val skey = cookies["p_skey"] ?: throw MusicException("QQ authorization did not return a ticket")
                    val nonce = UUID.randomUUID().toString()
                    val authorize = request("https://graph.qq.com/oauth2.0/authorize", body = form(mapOf(
                        "response_type" to "code", "client_id" to "100497308",
                        "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
                        "scope" to "get_user_info,get_app_friends", "state" to nonce, "switch" to "", "from_ptlogin" to "1",
                        "src" to "1", "update_auth" to "1", "openapi" to "1010_1030", "g_tk" to QQProtocol.hash33(skey, 5381).toString(),
                        "auth_time" to System.currentTimeMillis().toString(), "ui" to UUID.randomUUID().toString())),
                        cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    val location = authorize.location ?: throw MusicException("QQ authorization was not completed")
                    val returnedState = query(location, "state")
                    if (returnedState != null && returnedState != nonce) throw MusicException("Invalid OAuth state")
                    val code = query(location, "code") ?: throw MusicException("QQ authorization was not completed")
                    if (token != session.get()) return null
                    return QQMusicService.provider.rpc("QQConnectLogin.LoginServer", "QQLogin", json("code" to code), 2)
                        .apply { if (!has("loginType")) addProperty("loginType", 2) }
                }
                else -> throw MusicException("QQ Music login failed")
            }
        }
        update(token, LoginState.EXPIRED); return null
    }

    private fun wechat(token: Int): JsonObject? {
        val page = request("https://open.weixin.qq.com/connect/qrconnect", mapOf("appid" to "wx48db31d50e334801",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
            "response_type" to "code", "scope" to "snsapi_login", "state" to UUID.randomUUID().toString(),
            "href" to "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"))
        val uuid = Regex("/connect/qrcode/([A-Za-z0-9_-]+)").find(page.text())?.groupValues?.get(1)
            ?: throw MusicException("WeChat login is unavailable")
        val qr = request("https://open.weixin.qq.com/connect/qrcode/$uuid", referer = "https://open.weixin.qq.com/")
        show(token, qr.bytes)
        val deadline = System.currentTimeMillis() + 180000
        while (token == session.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (token != session.get()) return null
            val reply = try { request("https://lp.open.weixin.qq.com/connect/l/qrconnect",
                mapOf("uuid" to uuid, "_" to System.currentTimeMillis().toString()), referer = "https://open.weixin.qq.com/").text()
            } catch (_: java.net.SocketTimeoutException) { continue }
            val code = Regex("wx_errcode\\s*=\\s*(\\d+)").find(reply)?.groupValues?.get(1) ?: continue
            when (code) {
                "408" -> update(token, LoginState.WAITING)
                "404" -> update(token, LoginState.SCANNED)
                "402", "403" -> { update(token, LoginState.EXPIRED); return null }
                "405" -> {
                    val authCode = Regex("wx_code\\s*=\\s*['\"]([^'\"]+)").find(reply)?.groupValues?.get(1)
                        ?: throw MusicException("WeChat authorization was not completed")
                    if (token != session.get()) return null
                    return QQMusicService.provider.rpc("music.login.LoginServer", "Login",
                        json("code" to authCode, "strAppid" to "wx48db31d50e334801"), 1)
                        .apply { if (!has("loginType")) addProperty("loginType", 1) }
                }
            }
        }
        update(token, LoginState.EXPIRED); return null
    }
}
