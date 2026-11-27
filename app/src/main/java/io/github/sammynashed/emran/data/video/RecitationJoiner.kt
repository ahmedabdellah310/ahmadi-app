package io.github.sammynashed.emran.data.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Turns the per-ayah MP3s into one continuous recitation (a 16-bit WAV) for the video.
 *
 * Why: laid end to end as separate items, every join had 25–50 ms of digital silence. Many EveryAyah
 * folders (Dosari, Husary, …) carry no gapless header, so the MP3 decoder's priming delay comes out as
 * ~30 ms of zeros at the start of each ayah. A friend heard it as "a small pause" between ayat
 * (2026-11). Here each ayah is decoded, the near-zero samples at its edges are dropped (never more
 * than [MAX_TRIM_MS], so a reciter's real quiet breath is left alone), a few-ms fade smooths the cut
 * (some files end mid-echo), and everything is written as one track.
 */
object RecitationJoiner {
    private const val SILENT = 8           // |sample| at or below this counts as digital silence
    private const val MAX_TRIM_MS = 200
    private const val FADE_MS = 4

    /**
     * Where to end playback of one ayah file so it doesn't carry the next ayah's clipped first letter
     * (see [dropNextAyahOnset]), in µs; null when the file is fine or can't be read. The answer is kept in
     * a `.cut` file beside it, so each file is decoded once.
     */
    fun playbackEndUs(f: File): Long? {
        val side = File(f.path + ".cut2")
        runCatching { if (side.exists() && side.lastModified() >= f.lastModified()) return side.readText().trim().toLong().takeIf { it > 0 } }
        val v = runCatching {
            val p = decode(f)
            val frames = p.samples.size / p.channels
            val cut = dropNextAyahOnset(p.samples, 0, frames, p.channels, p.rate)
            if (cut < frames) cut * 1_000_000L / p.rate else -1L
        }.getOrNull() ?: return null
        runCatching { side.writeText(v.toString()) }
        return v.takeIf { it > 0 }
    }

    /** The joined file and each ayah's duration in it (µs), in [files] order. */
    class Joined(val file: File, val durationsUs: List<Long>)

    /** Throws when a file can't be decoded or the files don't share one format; the caller then falls back. */
    fun join(files: List<File>, out: File): Joined {
        var rate = 0
        var channels = 0
        val durations = ArrayList<Long>(files.size)
        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44)) // header, filled in at the end
            var dataBytes = 0L
            for (f in files) {
                val pcm = decode(f)
                if (rate == 0) { rate = pcm.rate; channels = pcm.channels }
                if (pcm.rate != rate) throw IllegalStateException("sample rate ${pcm.rate} != $rate in ${f.name}")
                val samples = if (pcm.channels == channels) pcm.samples else remix(pcm.samples, pcm.channels, channels)
                val frames = samples.size / channels
                val maxTrim = rate * MAX_TRIM_MS / 1000
                var first = 0
                while (first < min(frames, maxTrim) && silentFrame(samples, first, channels)) first++
                var last = frames
                while (last > first && frames - last < maxTrim && silentFrame(samples, last - 1, channels)) last--
                last = dropNextAyahOnset(samples, first, last, channels, rate)
                val n = last - first
                fade(samples, first, n, channels, rate * FADE_MS / 1000)
                val bb = ByteBuffer.allocate(n * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in first * channels until last * channels) bb.putShort(samples[i])
                raf.write(bb.array())
                dataBytes += bb.capacity()
                durations += n * 1_000_000L / rate
            }
            raf.seek(0)
            raf.write(wavHeader(dataBytes, rate, channels))
        }
        return Joined(out, durations)
    }

    /**
     * Some EveryAyah files (Yasser Al-Dosari's, e.g. Ad-Duha 1) end in the first letter of the NEXT ayah
     * (the «و» of «والليل»), cut mid-sound: the tail dips quiet, then jumps up in the last few ms. Played
     * in the video it sounds like the reciter starts the next ayah early and gets cut off. Returns the
     * new end: the start of that onset, or [last] when the tail just fades or stays level.
     */
    internal fun dropNextAyahOnset(s: ShortArray, first: Int, last: Int, ch: Int, rate: Int): Int {
        val hop = rate * 5 / 1000
        val frames = (last - first) / hop
        val tail = 400 / 5
        if (frames < tail * 2) return last
        fun db(k: Int): Float {
            var sum = 0.0
            for (i in first + k * hop until first + (k + 1) * hop) {
                var m = 0
                for (c in 0 until ch) m += s[i * ch + c]
                val v = m.toDouble() / ch
                sum += v * v
            }
            return (10 * kotlin.math.log10(sum / hop / (32768.0 * 32768.0) + 1e-12)).toFloat()
        }
        val e = FloatArray(frames) { db(it) }
        val loud = e.sortedArray()[frames * 9 / 10]   // the level of the ayah's own words
        var lo = frames - tail
        for (k in frames - tail until frames) if (e[k] <= e[lo]) lo = k
        val end = (e[frames - 3] + e[frames - 2] + e[frames - 1]) / 3
        // A real ending fades out. A leak is a quiet dip followed by sound that is still LOUD when the file stops.
        if (lo >= frames - 4 || end - e[lo] < 10f || e[lo] > loud - 8f || end < loud - 12f) return last
        var cut = lo
        while (cut + 1 < frames && e[cut + 1] <= e[lo] + 3f) cut++
        cut = (cut - 6).coerceAtLeast(frames - tail) // 30 ms earlier: the letter's soft start is below the plateau
        return first + cut * hop
    }

    private fun silentFrame(s: ShortArray, frame: Int, ch: Int): Boolean {
        for (c in 0 until ch) if (abs(s[frame * ch + c].toInt()) > SILENT) return false
        return true
    }

    private fun fade(s: ShortArray, start: Int, frames: Int, ch: Int, len: Int) {
        val l = min(len, frames / 2)
        for (i in 0 until l) {
            val g = i.toFloat() / l
            for (c in 0 until ch) {
                val a = (start + i) * ch + c
                val b = (start + frames - 1 - i) * ch + c
                s[a] = (s[a] * g).toInt().toShort()
                s[b] = (s[b] * g).toInt().toShort()
            }
        }
    }

    private fun remix(s: ShortArray, from: Int, to: Int): ShortArray {
        val frames = s.size / from
        return ShortArray(frames * to) { i ->
            val f = i / to
            if (from == 1) s[f] else {
                var sum = 0
                for (c in 0 until from) sum += s[f * from + c]
                (sum / from).toShort()
            }
        }
    }

    internal class Pcm(val samples: ShortArray, val rate: Int, val channels: Int)

    internal fun decode(f: File): Pcm {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(inFmt, null, null, 0)
            c.start()
            val outBuf = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var chunk = ByteArray(0)
            while (!outDone) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = c.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT
                        ) throw IllegalStateException("decoder gives non-16-bit PCM for ${f.name}")
                        rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val buf = c.getOutputBuffer(o)!!
                            if (chunk.size < info.size) chunk = ByteArray(info.size)
                            buf.position(info.offset); buf.get(chunk, 0, info.size)
                            outBuf.write(chunk, 0, info.size)
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
            val bytes = outBuf.toByteArray()
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return Pcm(shorts, rate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun wavHeader(dataBytes: Long, rate: Int, ch: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
            .putInt(rate).putInt(rate * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}
