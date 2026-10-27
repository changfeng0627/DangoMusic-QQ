package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.MusicServices
import me.sorihiko.dango.music.MusicPlatform
import me.sorihiko.dango.music.LoginState
import me.sorihiko.dango.music.LoginChannel
import me.sorihiko.dango.music.QQPhoneLogin
import me.sorihiko.dango.music.PhoneState
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/** 扫码登录弹窗：盖在整个播放器上面，开着时其它地方不响应 */
internal class LoginDialog(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val provider get() = MusicServices.forPlatform(loginPlatform)
    private val flow get() = provider.login
    private val hits = mutableListOf<Hit>()
    private var phoneMode = false
    private var country = "86"
    private var phone = ""
    private var otp = ""
    private var focusedField = 1

    companion object {
        // 关掉播放器再打开还在（扫码可能还没完成）
        private var showing = false
        private var loginPlatform = MusicPlatform.NETEASE
        private var channel = LoginChannel.QQ
        private var qrLoc: Texture? = null
        private var qrUploaded = -1
    }

    val visible: Boolean get() = showing

    fun open() {
        showing = true
        phoneMode = false
        QQPhoneLogin.cancel()
        loginPlatform = Library.platform
        channel = LoginChannel.QQ
        qrUploaded = -1
        flow.start(channel)
    }

    fun close() {
        showing = false
        flow.cancel()
        QQPhoneLogin.cancel()
        phone = ""; otp = ""
    }

    /** 新二维码生成后传成贴图（每帧调用，没变化就什么都不做） */
    fun uploadQr() {
        val img = flow.qrImage ?: return
        if (flow.qrVersion == qrUploaded) return
        val textures = DangoClient.gfx.textures
        qrLoc?.let { textures.delete(it) }
        qrLoc = textures.upload("dango_qr", img)
        qrUploaded = flow.qrVersion
    }

    fun draw() {
        val ctx = ui.ctx
        hits.clear()
        val state = flow.state
        if ((!phoneMode && state == LoginState.SUCCESS) || (phoneMode && QQPhoneLogin.state == PhoneState.SUCCESS)) {
            showing = false
            flow.cancel()
            Library.refreshAll()
            return
        }

        ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.45f)))
        val w = if (loginPlatform == MusicPlatform.QQ) 350f else 300f
        val h = if (loginPlatform == MusicPlatform.QQ) 410f else 372f
        val box = LayoutBounds((ui.lw - w) / 2f, (ui.lh - h) / 2f, w, h)
        ctx.drawGlass(box, Theme.GLASS_WINDOW)

        ui.text(I18n.text(if (loginPlatform == MusicPlatform.NETEASE) "Log in to NetEase" else "Log in to QQ Music"), box.x + 20f, box.y + 20f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val closeB = LayoutBounds(box.right - 38f, box.y + 14f, 24f, 24f)
        ctx.drawShape(closeB, ShapeStyle(if (ui.hover(closeB)) Theme.CARD_HOVER else Theme.CARD, 12f))
        ui.text("x", closeB.x + 8.5f, closeB.y + 7f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        hits += closeB to { close() }

        if (loginPlatform == MusicPlatform.QQ) {
            val choices = listOf("QQ QR login", "WeChat QR login", "Phone verification")
            choices.forEachIndexed { i, source ->
                val selected = if (i == 2) phoneMode else !phoneMode && channel == LoginChannel.values()[i]
                val b = LayoutBounds(box.x + 18f + i * 106f, box.y + 50f, 102f, 26f)
                ui.card(b, if (selected) RED.copy(a = 0.3f) else Theme.CARD, 8f)
                val label = I18n.text(source)
                ui.text(label, b.x + (b.width - ui.width(label, 11f)) / 2f, b.y + 8f, 11f, Theme.TEXT_PRIMARY)
                hits += b to {
                    flow.cancel(); QQPhoneLogin.cancel(); otp = ""
                    phoneMode = i == 2
                    if (!phoneMode) { channel = LoginChannel.values()[i]; qrUploaded = -1; flow.start(channel) }
                }
            }
        }
        if (phoneMode) { drawPhone(box); return }
        val qs = 200f
        val qrBox = LayoutBounds(box.x + (w - qs) / 2f, box.y + if (loginPlatform == MusicPlatform.QQ) 96f else 60f, qs, qs)
        ctx.drawShape(qrBox.expand(10f, 10f), ShapeStyle(Color.WHITE, 14f))
        val loc = qrLoc
        val showQr = flow.qrImage != null && loc != null &&
            (state == LoginState.WAITING || state == LoginState.SCANNED || state == LoginState.EXPIRED)
        if (showQr) {
            ctx.pushOpacity(if (state == LoginState.EXPIRED) 0.2f else 1f)
            ctx.drawImage(qrBox, loc!!)
            ctx.popOpacity()
        } else {
            val t = if (state == LoginState.ERROR) I18n.text("获取二维码失败") else I18n.text("正在获取二维码…")
            ui.text(t, qrBox.x + (qs - ui.width(t, 13f)) / 2f, qrBox.y + qs / 2f - 6f, 13f, Color(0.33f, 0.33f, 0.33f, 1f))
        }

        val msg = when (state) {
            LoginState.WAITING -> I18n.text(if (loginPlatform == MusicPlatform.NETEASE) "打开手机网易云音乐 App 扫码" else if (channel == LoginChannel.QQ) "Scan with the QQ app" else "Scan with WeChat")
            LoginState.SCANNED -> I18n.text("已扫码，请在手机上确认登录")
            LoginState.EXPIRED -> I18n.text("二维码已过期")
            LoginState.ERROR -> I18n.text("出错了，请重试")
            else -> I18n.text("请稍候…")
        }
        val msgColor = when (state) {
            LoginState.SCANNED -> Theme.GREEN
            LoginState.ERROR, LoginState.EXPIRED -> RED
            else -> Theme.TEXT_PRIMARY
        }
        ui.text(msg, box.x + (w - ui.width(msg, 14f)) / 2f, qrBox.bottom + 24f, 14f, msgColor)

        if (state == LoginState.EXPIRED || state == LoginState.ERROR) {
            val label = I18n.text("Refresh QR")
            val bw = ui.width(label, 12f, Theme.FONT_MEDIUM) + 28f
            val b = LayoutBounds(box.x + (w - bw) / 2f, qrBox.bottom + 48f, bw, 28f)
            ctx.drawShape(b, ShapeStyle(RED, 14f))
            ui.text(label, b.x + 14f, b.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            hits += b to { qrUploaded = -1; flow.start(channel) }
        }
    }

    fun mouseClicked(mx: Int, my: Int, button: Int) {
        if (button == 0) hits.firstOrNull { it.first.contains(mx, my) }?.second?.invoke()
    }

    fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keys.ESCAPE) { close(); return }
        if (!phoneMode || QQPhoneLogin.busy) return
        if (keyCode == Keys.TAB) { focusedField = (focusedField + 1) % 3; return }
        if (keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER) { QQPhoneLogin.verify(phone, country, otp); return }
        var value = when (focusedField) { 0 -> country; 1 -> phone; else -> otp }
        when {
            keyCode == Keys.BACK -> value = value.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> value += DangoClient.platform.clipboard.filter { it in '0'..'9' }
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> value = ""
            typedChar in '0'..'9' -> value += typedChar
        }
        when (focusedField) { 0 -> country = value.take(4); 1 -> phone = value.take(15); else -> otp = value.take(8) }
    }

    private fun drawPhone(box: LayoutBounds) {
        fun input(label: String, value: String, index: Int, y: Float, width: Float) {
            ui.text(I18n.text(label), box.x + 26f, y, 12f, Theme.TEXT_SECONDARY)
            val b = LayoutBounds(box.x + 26f, y + 18f, width, 30f)
            ui.card(b, if (focusedField == index) Theme.CARD_HOVER else Theme.CARD, 8f)
            val display = if (index == 2) "•".repeat(value.length) else value
            ui.text(display + if (focusedField == index && caretVisible()) "|" else "", b.x + 10f, b.y + 10f, 12f, Theme.TEXT_PRIMARY)
            hits += b to { focusedField = index }
        }
        input("Country code", country, 0, box.y + 94f, 80f)
        input("Phone number", phone, 1, box.y + 152f, box.width - 52f)
        input("Verification code", otp, 2, box.y + 210f, 166f)
        val send = LayoutBounds(box.x + 204f, box.y + 228f, box.width - 230f, 30f)
        val seconds = QQPhoneLogin.cooldownSeconds
        ui.card(send, if (ui.hover(send)) Theme.CARD_HOVER else Theme.CARD, 8f)
        val label = if (seconds > 0) "${seconds}s" else I18n.text("Send code")
        ui.text(label, send.x + (send.width - ui.width(label, 11f)) / 2f, send.y + 10f, 11f, Theme.TEXT_PRIMARY)
        hits += send to { QQPhoneLogin.send(phone, country) }
        val verify = LayoutBounds(box.x + 26f, box.y + 278f, box.width - 52f, 32f)
        ui.card(verify, RED.copy(a = if (QQPhoneLogin.busy) 0.3f else 0.7f), 10f)
        val title = I18n.text(if (QQPhoneLogin.busy) "Verifying..." else "Log in to QQ Music")
        ui.text(title, verify.x + (verify.width - ui.width(title, 12f)) / 2f, verify.y + 10f, 12f, Theme.TEXT_PRIMARY)
        hits += verify to { QQPhoneLogin.verify(phone, country, otp) }
        val lines = ui.wrap2(I18n.text(QQPhoneLogin.message), box.width - 52f, 11f, Theme.FONT_MEDIUM)
        lines.forEachIndexed { i, text -> ui.text(text, box.x + 26f, box.y + 326f + i * 15f, 11f, Theme.TEXT_SECONDARY) }
        QQPhoneLogin.challengeUrl?.let { url ->
            val button = LayoutBounds(box.x + 26f, box.y + 366f, box.width - 52f, 24f)
            val text = I18n.text("Open security verification")
            ui.text(text, button.x, button.y + 6f, 11f, RED)
            hits += button to {
                try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) { }
            }
        }
    }
}
