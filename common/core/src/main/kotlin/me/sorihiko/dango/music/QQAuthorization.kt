package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.util.UUID
import java.net.URL
import me.sorihiko.dango.DangoLogger

/** The post-confirmation OAuth exchange is isolated so its real redirect/cookie flow can be tested. */
internal class QQAuthorization(
    private val transport: MusicTransport,
    private val exchange: (String) -> JsonObject,
    private val stage: (String) -> Unit = {},
    private val active: () -> Boolean = { true },
) {
    fun complete(callback: String): JsonObject {
        check(active())
        stage("QQ ticket validation")
        val uin = QQMusicLogin.query(callback, "uin")?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: throw MusicException("Invalid QR callback", "CALLBACK_UIN")
        val sig = QQMusicLogin.query(callback, "ptsigx")?.takeIf { it.isNotBlank() }
            ?: throw MusicException("Invalid QR callback", "CALLBACK_TICKET")
        val checkParams = mapOf("uin" to uin, "pttype" to "1", "service" to "ptqrlogin", "nodirect" to "0",
            "ptsigx" to sig, "s_url" to "https://graph.qq.com/oauth2.0/login_jump", "ptlang" to "2052", "ptredirect" to "100",
            "aid" to "716027609", "daid" to "383", "j_later" to "0", "low_login_hour" to "0", "regmaster" to "0",
            "pt_login_type" to "3", "pt_aid" to "0", "pt_aaid" to "16", "pt_light" to "0", "pt_3rd_aid" to "100497308")
        val checkReply = transport.request("https://ssl.ptlogin2.graph.qq.com/check_sig?" + form(checkParams),
            headers = mapOf("Referer" to "https://xui.ptlogin2.qq.com/"))
        if (checkReply.code !in 200..399) throw MusicException("QQ authorization did not return a ticket", "HTTP_${checkReply.code}")
        val cookies = QQProtocol.cookies(checkReply.cookies).toMutableMap()
        if (transport is MusicSessionTransport) cookies.putAll(transport.cookiesFor("https://graph.qq.com/"))
        var reply = checkReply
        var current = "https://ssl.ptlogin2.graph.qq.com/check_sig"
        // Some QQ sessions set the graph-domain ticket only on the login_jump redirect.
        repeat(5) {
            if (cookies["p_skey"].isNullOrEmpty() && reply.code in 300..399 && reply.location != null) {
                check(active())
                val next = URL(URL(current), reply.location)
                if (next.protocol != "https" || next.host !in setOf("ssl.ptlogin2.graph.qq.com", "graph.qq.com", "ptlogin4.y.qq.com", "y.qq.com"))
                    throw MusicException("QQ authorization did not return a ticket", "TICKET_REDIRECT_HOST")
                current = next.toString()
                reply = transport.request(current, headers = mapOf("Referer" to "https://xui.ptlogin2.qq.com/"))
                cookies.putAll(QQProtocol.cookies(reply.cookies))
                if (transport is MusicSessionTransport) cookies.putAll(transport.cookiesFor("https://graph.qq.com/"))
            }
        }
        if (cookies["p_skey"].isNullOrEmpty()) {
            DangoLogger.warn("QQ ticket validation: HTTP {}, cookie names {}", reply.code,
                cookies.keys.filter { it.matches(Regex("[A-Za-z0-9_]{1,64}")) }.sorted().joinToString(","))
        }
        val skey = cookies["p_skey"]?.takeIf { it.isNotEmpty() }
            ?: throw MusicException("QQ authorization did not return a ticket", "MISSING_P_SKEY")
        check(active())
        stage("QQ OAuth authorization")
        val nonce = UUID.randomUUID().toString()
        val form = form(mapOf("response_type" to "code", "client_id" to "100497308",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "get_user_info,get_app_friends", "state" to nonce, "switch" to "", "from_ptlogin" to "1", "src" to "1",
            "update_auth" to "1", "openapi" to "1010_1030", "g_tk" to QQProtocol.hash33(skey, 5381).toString(),
            "auth_time" to System.currentTimeMillis().toString(), "ui" to UUID.randomUUID().toString()))
        val authorization = transport.request("https://graph.qq.com/oauth2.0/authorize", form,
            mapOf("Referer" to "https://graph.qq.com/", "Origin" to "https://graph.qq.com",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }))
        if (authorization.code !in 200..399) throw MusicException("QQ authorization was not completed", "HTTP_${authorization.code}")
        val location = authorization.location ?: throw MusicException("QQ authorization was not completed", "OAUTH_NO_REDIRECT")
        val returnedState = QQMusicLogin.query(location, "state")
        if (returnedState != null && returnedState != nonce) throw MusicException("Invalid OAuth state", "OAUTH_STATE")
        val code = QQMusicLogin.query(location, "code") ?: throw MusicException("QQ authorization was not completed", "OAUTH_NO_CODE")
        check(active())
        stage("QQ Music credential exchange")
        return exchange(code).apply { if (!has("loginType")) addProperty("loginType", 2) }
    }
    private fun form(params: Map<String, String>) = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
}
