package com.dmrmap.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt

data class PowerReading(
    /** Power inside the DMR channel, dB relative to full scale. */
    val channelDbfs: Double,
    /** Noise floor scaled to the same bandwidth as the channel, dBFS. */
    val noiseDbfs: Double,
    /** Fraction of raw samples at 0 or 255 (ADC clipping => lower the gain). */
    val clipFraction: Double,
) {
    val snrDb get() = channelDbfs - noiseDbfs
}

/**
 * Measures the power of one narrow channel in a block of RTL-SDR IQ samples.
 *
 * The tuner is centred [offsetHz] away from the target so the dongle's DC spike
 * is outside the channel; the channel therefore sits at -[offsetHz] in baseband.
 */
class ChannelPowerMeter(
    val sampleRate: Int = 1_024_000,
    val fftSize: Int = 16384,
    val offsetHz: Int = 200_000,
    channelHalfWidthHz: Int = 7_000,
) {
    val bytesPerBlock = fftSize * 2

    private val fft = Fft(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / fftSize) }
    private val norm: Double = fftSize * window.sumOf { it * it }
    private val binHz = sampleRate.toDouble() / fftSize

    private val channelBins: IntArray
    private val noiseBins: IntArray
    private val noiseScratch: DoubleArray

    init {
        val center = -offsetHz / binHz
        val half = channelHalfWidthHz / binHz
        channelBins = binRange(center - half, center + half)

        // Noise reference: within ±400 kHz (inside the anti-alias filter's flat part),
        // excluding the DC spike and a guard band around the channel.
        val guard = 15_000 / binHz
        val dcGuard = 5_000 / binHz
        val edge = 400_000 / binHz
        noiseBins = (-edge.roundToInt()..edge.roundToInt())
            .filter { abs(it) > dcGuard && abs(it - center) > guard }
            .map { Math.floorMod(it, fftSize) }
            .toIntArray()
        noiseScratch = DoubleArray(noiseBins.size)
    }

    private fun binRange(from: Double, to: Double): IntArray =
        (from.roundToInt()..to.roundToInt()).map { Math.floorMod(it, fftSize) }.toIntArray()

    /** [iq] holds [bytesPerBlock] interleaved unsigned 8-bit I/Q bytes. */
    fun measure(iq: ByteArray): PowerReading {
        var clipped = 0
        for (i in 0 until fftSize) {
            val iRaw = iq[2 * i].toInt() and 0xFF
            val qRaw = iq[2 * i + 1].toInt() and 0xFF
            if (iRaw == 0 || iRaw == 255 || qRaw == 0 || qRaw == 255) clipped++
            re[i] = (iRaw - 127.5) / 127.5 * window[i]
            im[i] = (qRaw - 127.5) / 127.5 * window[i]
        }
        fft.transform(re, im)

        var channel = 0.0
        for (b in channelBins) channel += binPower(b)

        for ((k, b) in noiseBins.withIndex()) noiseScratch[k] = binPower(b)
        noiseScratch.sort()
        // Median is robust against other carriers sitting in the noise band.
        val noisePerBin = noiseScratch[noiseScratch.size / 2]
        val noise = noisePerBin * channelBins.size

        return PowerReading(toDb(channel), toDb(noise), clipped.toDouble() / fftSize)
    }

    private fun binPower(b: Int) = (re[b] * re[b] + im[b] * im[b]) / norm

    private fun toDb(p: Double) = 10 * log10(p.coerceAtLeast(1e-20))
}
