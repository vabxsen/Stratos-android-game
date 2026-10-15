package com.vabxsen.stratos

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tiny synthesizer for the game's sound effects. Every effect is rendered to PCM once at startup,
 * then a low-latency AudioTrack mixes whatever is playing. The track stops whenever it is idle.
 */
class Sfx {

    private enum class Wave { SINE, TRIANGLE }
    private class Tone(val freq: Double, val dur: Double, val wave: Wave, val vol: Double, val at: Double = 0.0)
    private class Voice(val clip: FloatArray) { var pos = 0 }

    @Volatile var muted = false

    private val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000

    private val perfectClips = Array(15) { i ->
        val f = note(i)
        render(Tone(f, 0.6, Wave.SINE, 0.16), Tone(f * 2, 0.35, Wave.TRIANGLE, 0.04, 0.02))
    }
    private val placeClip = render(Tone(150.0, 0.14, Wave.TRIANGLE, 0.14), Tone(95.0, 0.18, Wave.SINE, 0.1))
    private val overClip = render(
        Tone(330.0, 0.4, Wave.SINE, 0.12),
        Tone(247.0, 0.5, Wave.SINE, 0.12, 0.14),
        Tone(165.0, 0.8, Wave.SINE, 0.12, 0.3)
    )

    private val lock = Object()
    private val pending = ArrayList<FloatArray>()
    private var thread: Thread? = null
    @Volatile private var alive = false

    fun perfect(combo: Int) = play(perfectClips[(combo - 1).coerceIn(0, 14)])
    fun place() = play(placeClip)
    fun over() = play(overClip)

    private fun play(clip: FloatArray) {
        if (muted || !alive) return
        synchronized(lock) {
            pending.add(clip)
            lock.notifyAll()
        }
    }

    /** Start the mixer (call from onResume). */
    fun start() {
        if (alive) return
        alive = true
        thread = Thread(::run, "stratos-audio").also { it.start() }
    }

    /** Stop the mixer and release the audio device (call from onPause). */
    fun stop() {
        if (!alive) return
        alive = false
        synchronized(lock) {
            pending.clear()
            lock.notifyAll()
        }
        thread?.join(500)
        thread = null
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, BLOCK * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            alive = false
            return
        }

        val block = FloatArray(BLOCK)
        val voices = ArrayList<Voice>()
        var playing = false
        try {
            while (alive) {
                synchronized(lock) {
                    if (voices.isEmpty() && pending.isEmpty()) {
                        if (playing) { track.stop(); playing = false }
                        while (alive && pending.isEmpty()) lock.wait()
                    }
                    for (clip in pending) voices.add(Voice(clip))
                    pending.clear()
                }
                if (!alive) break
                if (!playing) { track.play(); playing = true }

                block.fill(0f)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = min(BLOCK, v.clip.size - v.pos)
                    for (i in 0 until n) block[i] += v.clip[v.pos + i]
                    v.pos += n
                    if (v.pos >= v.clip.size) it.remove()
                }
                for (i in 0 until BLOCK) block[i] = block[i].coerceIn(-1f, 1f)
                track.write(block, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
        } finally {
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
        }
    }

    // ---------- synthesis (mirrors the original Web Audio envelopes) ----------
    private fun note(i: Int): Double {
        val penta = intArrayOf(0, 2, 4, 7, 9)
        val oct = i / 5
        return 261.63 * 2.0.pow((penta[i % 5] + 12 * oct) / 12.0)
    }

    private fun render(vararg tones: Tone): FloatArray {
        val total = tones.maxOf { it.at + it.dur + 0.05 }
        val out = FloatArray((total * rate).toInt())
        for (t in tones) {
            val start = (t.at * rate).toInt()
            val len = ((t.dur + 0.05) * rate).toInt()
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= out.size) break
                val s = i.toDouble() / rate
                val gain = when {
                    s < ATTACK -> FLOOR * (t.vol / FLOOR).pow(s / ATTACK)
                    s < t.dur -> t.vol * (FLOOR / t.vol).pow((s - ATTACK) / (t.dur - ATTACK))
                    else -> FLOOR
                }
                val phase = t.freq * s
                val wave = when (t.wave) {
                    Wave.SINE -> sin(2 * PI * phase)
                    Wave.TRIANGLE -> {
                        val x = phase - floor(phase)
                        when {
                            x < 0.25 -> 4 * x
                            x < 0.75 -> 2 - 4 * x
                            else -> 4 * x - 4
                        }
                    }
                }
                out[idx] += (gain * wave).toFloat()
            }
        }
        return out
    }

    private companion object {
        const val BLOCK = 256
        const val ATTACK = 0.012
        const val FLOOR = 0.0001
    }
}
