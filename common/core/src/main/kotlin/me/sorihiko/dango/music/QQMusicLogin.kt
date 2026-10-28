package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
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
    @Volatile var progress = "QR code request"; private set
    @Volatile var failureStage = ""; private set
    @Volatile var failureCode = ""; private set
    private val session = AtomicInteger()
    private val lock = Any()
    private val allowedHosts = setOf("ssl.ptlogin2.qq.com", "ssl.ptlogin2.graph.qq.com", "graph.qq.com",
        "open.weixin.qq.com", "lp.open.weixin.qq.com")

    override fun start(channel: LoginChannel) {
        val token: Int
        synchronized(lock) {
            token = session.incrementAndGet(); qrImage = null; state = LoginState.LOADING
            progress = "QR code request"; failureStage = ""; failureCode = ""
        }
        Thread({
            try {
                val transport = MusicSessionTransport(MusicHttp)
                val auth = if (channel == LoginChannel.WECHAT) wechat(token, transport) else qq(token, transport)
                if (auth != null && token == session.get()) {
                    mark(token, "Saving QQ Music account")
                    // Disk I/O never holds the UI's session lock.
                    QQMusicService.credentials.save(auth) { token == session.get() }
                    update(token, LoginState.SUCCESS)
                }
            } catch (e: Exception) {
                if (token == session.get()) {
                    failureStage = progress
                    failureCode = (e as? MusicException)?.diagnosticCode ?: e.javaClass.simpleName
                    DangoLogger.warn("QQ Music QR login failed at {} ({})", failureStage, failureCode)
                    update(token, LoginState.ERROR)
                }
            }
        }, "Dango-QQMusicLogin").apply { isDaemon = true; start() }
    }
    override fun cancel() {
        session.incrementAndGet(); qrImage = null
        if (state != LoginState.SUCCESS) state = LoginState.IDLE
    }
    private fun mark(token: Int, stage: String) { if (token == session.get()) progress = stage }

    private fun update(token: Int, next: LoginState) = synchronized(lock) {
        if (token == session.get()) state = next
    }
    private fun show(token: Int, bytes: ByteArray) = synchronized(lock) {
        if (token == session.get()) {
            qrImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw MusicException("Invalid QR image")
            qrVersion++; state = LoginState.WAITING
        }
    }
    private fun request(transport: MusicTransport, url: String, params: Map<String, String> = emptyMap(), body: String? = null,
                        cookie: String = "", referer: String = "https://y.qq.com/"): HttpReply {
        require(URL(url).protocol == "https" && URL(url).host in allowedHosts)
        val headers = mutableMapOf("Referer" to referer)
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        if (body != null) headers["Content-Type"] = "application/x-www-form-urlencoded"
        val reply = transport.request(url + if (params.isEmpty()) "" else "?" + form(params), body, headers)
        if (reply.code !in 200..399) throw MusicException("QQ Music service is unavailable", "HTTP_${reply.code}")
        return reply
    }
    private fun form(params: Map<String, String>) = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    internal fun query(url: String, name: String): String? = try {
        URL(url).query?.split('&')?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    } catch (_: Exception) { null }

    internal fun callbackArguments(text: String): List<String> {
        val body = Regex("ptuiCB\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return emptyList()
        return Regex("'((?:\\\\.|[^'])*)'").findAll(body).map {
            it.groupValues[1].replace("\\x26", "&").replace("\\u0026", "&")
                .replace("\\x3d", "=").replace("\\/", "/").replace("\\'", "'")
        }.toList()
    }

    private fun qq(token: Int, transport: MusicTransport): JsonObject? {
        val qr = request(transport, "https://ssl.ptlogin2.qq.com/ptqrshow", mapOf("appid" to "716027609", "e" to "2",
            "l" to "M", "s" to "3", "d" to "72", "v" to "4", "daid" to "383", "pt_3rd_aid" to "100497308",
            "t" to Math.random().toString()), referer = "https://xui.ptlogin2.qq.com/")
        val qrsig = QQProtocol.cookies(qr.cookies)["qrsig"] ?: throw MusicException("Unable to create QR session")
        show(token, qr.bytes)
        mark(token, "Waiting for QR confirmation")
        val deadline = System.currentTimeMillis() + 180000
        while (token == session.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (token != session.get()) return null
            val reply = request(transport, "https://ssl.ptlogin2.qq.com/ptqrlogin", mapOf("u1" to "https://graph.qq.com/oauth2.0/login_jump",
                "ptqrtoken" to QQProtocol.hash33(qrsig).toString(), "ptredirect" to "0", "h" to "1", "t" to "1", "g" to "1",
                "from_ui" to "1", "ptlang" to "2052", "action" to "0-0-${System.currentTimeMillis()}", "js_ver" to "20102616",
                "js_type" to "1", "pt_uistyle" to "40", "aid" to "716027609", "daid" to "383", "pt_3rd_aid" to "100497308",
                "has_onekey" to "1"), cookie = "qrsig=$qrsig", referer = "https://xui.ptlogin2.qq.com/")
            val args = callbackArguments(reply.text())
            when (args.firstOrNull()) {
                "66" -> update(token, LoginState.WAITING)
                "67" -> update(token, LoginState.SCANNED)
                "65" -> { update(token, LoginState.EXPIRED); return null }
                "0" -> {
                    if (token != session.get()) return null
                    val callback = args.getOrNull(2) ?: throw MusicException("Invalid QR callback")
                    return QQAuthorization(transport,
                        { code -> QQMusicService.provider.rpc("QQConnectLogin.LoginServer", "QQLogin", json("code" to code), 2) },
                        { stage -> mark(token, stage) }, { token == session.get() }).complete(callback)
                }
                else -> throw MusicException("QQ Music login failed")
            }
        }
        update(token, LoginState.EXPIRED); return null
    }

    private fun wechat(token: Int, transport: MusicTransport): JsonObject? {
        val page = request(transport, "https://open.weixin.qq.com/connect/qrconnect", mapOf("appid" to "wx48db31d50e334801",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
            "response_type" to "code", "scope" to "snsapi_login", "state" to UUID.randomUUID().toString(),
            "href" to "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"))
        val uuid = Regex("/connect/qrcode/([A-Za-z0-9_-]+)").find(page.text())?.groupValues?.get(1)
            ?: throw MusicException("WeChat login is unavailable")
        val qr = request(transport, "https://open.weixin.qq.com/connect/qrcode/$uuid", referer = "https://open.weixin.qq.com/")
        show(token, qr.bytes)
        mark(token, "Waiting for QR confirmation")
        val deadline = System.currentTimeMillis() + 180000
        while (token == session.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (token != session.get()) return null
            val reply = try { request(transport, "https://lp.open.weixin.qq.com/connect/l/qrconnect",
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
                    mark(token, "QQ Music credential exchange")
                    return QQMusicService.provider.rpc("music.login.LoginServer", "Login",
                        json("code" to authCode, "strAppid" to "wx48db31d50e334801"), 1)
                        .apply { if (!has("loginType")) addProperty("loginType", 1) }
                }
            }
        }
        update(token, LoginState.EXPIRED); return null
    }
}
