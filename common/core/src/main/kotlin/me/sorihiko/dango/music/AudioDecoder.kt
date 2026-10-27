package me.sorihiko.dango.music

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.Closeable

enum class AudioCodec { MP3, AAC, M4A, FLAC, UNKNOWN }
data class AudioSource(val url: String, val codec: AudioCodec, val trialOnly: Boolean = false)
data class PcmFrame(val samples: ShortArray, val sampleRate: Int, val channels: Int, val durationMs: Float)

/** Frame-based PCM boundary shared by all JDKs/loaders. Additional codecs can implement this interface. */
interface AudioDecoder : Closeable {
    fun seekTo(ms: Long): Long
    fun nextFrame(): PcmFrame?
}

object AudioDecoders {
    fun supports(codec: AudioCodec) = codec == AudioCodec.MP3
    fun open(codec: AudioCodec, bytes: ByteArray): AudioDecoder {
        if (!supports(codec)) throw MusicException("This audio format is not supported; MP3 is required")
        return Mp3Decoder(bytes)
    }
}

class Mp3Decoder(bytes: ByteArray) : AudioDecoder {
    private val stream = Bitstream(ByteArrayInputStream(bytes))
    private val decoder = Decoder()
    override fun seekTo(ms: Long): Long {
        var skipped = 0f
        while (skipped < ms) {
            val h = stream.readFrame() ?: break
            skipped += h.ms_per_frame()
            stream.closeFrame()
        }
        return skipped.toLong()
    }
    override fun nextFrame(): PcmFrame? {
        // A corrupt frame may be skipped, but never treated as successfully decoded audio.
        repeat(32) {
            val h = stream.readFrame() ?: return null
            try {
                val sb = decoder.decodeFrame(h, stream) as SampleBuffer
                return PcmFrame(sb.buffer.copyOf(sb.bufferLength), h.frequency(), sb.channelCount, h.ms_per_frame())
            } catch (_: Exception) {
            } finally {
                stream.closeFrame()
            }
        }
        throw MusicException("Invalid MP3 audio")
    }
    override fun close() = stream.close()
}
