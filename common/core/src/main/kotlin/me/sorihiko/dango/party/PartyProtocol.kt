package me.sorihiko.dango.party

import com.google.gson.JsonObject
import me.sorihiko.dango.music.MusicPlatform
import me.sorihiko.dango.music.MusicTrack

/** Missing platform means legacy NetEase; malformed or unknown platforms are ignored safely. */
object PartyProtocol {
    fun track(o: JsonObject): MusicTrack? { return try {
        val p = o.get("platform")
        val platform = if (p == null || p.isJsonNull) MusicPlatform.NETEASE
            else MusicPlatform.fromId(p.asString) ?: return null
        val id = o.get("trackId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return null
        if (platform == MusicPlatform.NETEASE && id.toLongOrNull() == null) return null
        MusicTrack(platform, id, o.get("name")?.asString ?: "",
            (o.get("artists")?.asString ?: "").split(" / ").filter { it.isNotEmpty() },
            o.get("duration")?.asLong ?: 0L, o.get("cover")?.asString ?: "",
            o.get("mediaId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: id)
    } catch (_: Exception) { null } }
}
