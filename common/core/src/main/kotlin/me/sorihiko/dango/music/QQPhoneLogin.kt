package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

enum class PhoneState { IDLE, SENDING, CODE_SENT, VERIFYING, SUCCESS, CHALLENGE, ERROR }

/** User-initiated SMS only. No SMS, phone number or OTP is persisted or logged. */
object QQPhoneLogin {
    @Volatile var state = PhoneState.IDLE; private set
    @Volatile var message = ""; private set
    @Volatile var challengeUrl: String? = null; private set
    @Volatile private var nextSendAt = 0L
    private val session = AtomicInteger()
    private val lock = Any()
    val cooldownSeconds get() = ((nextSendAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt()
    val busy get() = state == PhoneState.SENDING || state == PhoneState.VERIFYING

    fun cancel() = synchronized(lock) {
        session.incrementAndGet(); state = PhoneState.IDLE; message = ""; challengeUrl = null
    }
    internal fun validPhone(phone: String, area: String) = phone.matches(Regex("[0-9]{6,15}")) && area.matches(Regex("[0-9]{1,4}"))
    internal fun safeChallenge(url: String?): String? = try {
        val uri = URI(url ?: ""); val host = uri.host?.lowercase().orEmpty()
        url?.takeIf { uri.scheme == "https" && (host == "qq.com" || host.endsWith(".qq.com")) && uri.userInfo == null }
    } catch (_: Exception) { null }

    fun send(phone: String, area: String) {
        if (!validPhone(phone, area)) { message = "Enter a valid phone number and country code"; return }
        val token: Int
        synchronized(lock) {
            if (busy || cooldownSeconds > 0) return
            token = session.get(); state = PhoneState.SENDING; message = "Sending verification code..."; challengeUrl = null
            nextSendAt = System.currentTimeMillis() + 60000
        }
        Thread({
            try {
                val reply = QQMusicService.provider.phoneRpc("SendPhoneAuthCode",
                    json("tmeAppid" to "qqmusic", "areaCode" to area, "phoneNo" to phone))
                synchronized(lock) {
                    if (token == session.get()) {
                        when (reply.str("code").toIntOrNull()) {
                            0 -> { state = PhoneState.CODE_SENT; message = "Verification code sent" }
                            20276 -> {
                                state = PhoneState.CHALLENGE; message = "Complete QQ Music security verification in your browser, then retry"
                                challengeUrl = safeChallenge(reply.obj("data")?.str("securityURL"))
                            }
                            100001, 104604 -> { state = PhoneState.ERROR; message = "Too many requests; please try later" }
                            else -> { state = PhoneState.ERROR; message = "QQ Music phone login is unavailable; use QR login" }
                        }
                    }
                }
            } catch (_: Exception) { fail(token, "Unable to send verification code; try QR login") }
        }, "Dango-QQSms").apply { isDaemon = true; start() }
    }

    fun verify(phone: String, area: String, code: String) {
        if (!validPhone(phone, area) || !code.matches(Regex("[0-9]{4,8}"))) {
            message = "Enter a valid phone number and verification code"; return
        }
        val token: Int
        synchronized(lock) {
            if (busy) return
            token = session.get(); state = PhoneState.VERIFYING; message = "Verifying..."
        }
        Thread({
            try {
                val reply = QQMusicService.provider.phoneRpc("Login",
                    json("phoneNo" to phone, "areaCode" to area, "code" to code, "loginMode" to 1))
                val data = reply.obj("data")
                synchronized(lock) {
                    if (token == session.get()) {
                        if (reply.str("code") == "0" && data != null && QQMusicService.credentials.valid(data)) {
                            QQMusicService.credentials.save(data)
                            state = PhoneState.SUCCESS; message = "Logged in"
                        } else { state = PhoneState.ERROR; message = "Verification failed or code expired; try again or use QR login" }
                    }
                }
            } catch (_: Exception) { fail(token, "Verification failed or code expired; try again or use QR login") }
        }, "Dango-QQSmsVerify").apply { isDaemon = true; start() }
    }
    private fun fail(token: Int, text: String) = synchronized(lock) {
        if (token == session.get()) { state = PhoneState.ERROR; message = text }
    }
}
