package com.maxrave.media3.visualizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.maxrave.domain.data.player.AudioBands
import com.maxrave.domain.data.player.AudioBandsBus
import com.maxrave.media3.audio.Fft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the PCM this sink has just consumed and publishes bass, mid and treble loudness to
 * [AudioBandsBus] for the gear visualizer. It never changes what is played: the delegate's return
 * values and exceptions pass through untouched, and nothing is analysed unless the visualizer is on.
 */
@OptIn(UnstableApi::class)
internal class VisualizerAudioSink(
    delegate: AudioSink,
) : ForwardingAudioSink(delegate) {
    private var format: Format? = null
    private var failed = false
    private val analyzer = BandAnalyzer()

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        super.configure(audioSinkConfig)
        format = audioSinkConfig.format.takeIf { it.sampleMimeType == MimeTypes.AUDIO_RAW }
        format?.let { analyzer.configure(it.sampleRate) }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val start = buffer.position()
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val current = format
        if (current != null && !failed && AudioBandsBus.enabled.value) {
            try {
                val end = buffer.position()
                if (end > start) {
                    val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    view.limit(end)
                    view.position(start)
                    analyzer.consume(view, current.pcmEncoding, current.channelCount)
                }
            } catch (e: Exception) {
                // A visualizer must never stop audio.
                failed = true
            }
        }
        return handled
    }

    override fun flush() {
        analyzer.clear()
        super.flush()
    }

    override fun reset() {
        format = null
        analyzer.clear()
        super.reset()
    }
}

/** Mixes PCM down to mono, runs a 1024-point FFT every 512 samples and reduces it to three bands. */
internal class BandAnalyzer {
    private val size = 1024
    private val fft = Fft(size)
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    private val ring = FloatArray(size)
    private var fill = 0
    private var sampleRate = 44_100
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var lastPublishNanos = 0L

    fun configure(rate: Int) {
        if (rate > 0) sampleRate = rate
        clear()
    }

    fun clear() {
        fill = 0
    }

    fun consume(
        view: ByteBuffer,
        encoding: Int,
        channels: Int,
    ) {
        val bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
        if (channels <= 0) return
        val frameBytes = bytesPerSample * channels
        while (view.remaining() >= frameBytes) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += if (bytesPerSample == 2) view.getShort() / 32768f else view.getFloat()
            }
            push(sum / channels)
        }
    }

    private fun push(sample: Float) {
        ring[fill++] = sample
        if (fill == size) {
            analyze()
            System.arraycopy(ring, size / 2, ring, 0, size / 2)
            fill = size / 2
        }
    }

    private fun analyze() {
        for (i in 0 until size) {
            real[i] = ring[i] * window[i]
            imaginary[i] = 0.0
        }
        fft.forward(real, imaginary)
        val binHz = sampleRate.toDouble() / size
        bass = follow(bass, normalise(bandDb(20.0, 250.0, binHz), -50f, -12f))
        mid = follow(mid, normalise(bandDb(250.0, 4_000.0, binHz), -58f, -22f))
        treble = follow(treble, normalise(bandDb(4_000.0, 16_000.0, binHz), -66f, -32f))
        val now = System.nanoTime()
        if (now - lastPublishNanos >= 16_000_000L) {
            lastPublishNanos = now
            AudioBandsBus.bands.value = AudioBands(bass, mid, treble)
        }
    }

    private fun bandDb(
        lowHz: Double,
        highHz: Double,
        binHz: Double,
    ): Float {
        val first = maxOf(1, (lowHz / binHz).toInt())
        val last = minOf(size / 2 - 1, (highHz / binHz).toInt())
        if (last < first) return -120f
        var sumSquares = 0.0
        for (k in first..last) {
            sumSquares += real[k] * real[k] + imaginary[k] * imaginary[k]
        }
        val rms = sqrt(sumSquares / (last - first + 1))
        val amplitude = rms * 4.0 / size
        return (20.0 * log10(amplitude + 1e-9)).toFloat()
    }

    private fun normalise(
        db: Float,
        low: Float,
        high: Float,
    ): Float = ((db - low) / (high - low)).coerceIn(0f, 1f)

    /** Quick to rise, slower to fall, so a kick reads as a hit and not a flicker. */
    private fun follow(
        current: Float,
        target: Float,
    ): Float = if (target > current) current + (target - current) * 0.6f else current + (target - current) * 0.15f
}
