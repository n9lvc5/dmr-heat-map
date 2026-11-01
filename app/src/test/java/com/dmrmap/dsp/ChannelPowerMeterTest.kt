package com.dmrmap.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class ChannelPowerMeterTest {

    private val meter = ChannelPowerMeter()

    /** 8-bit IQ: a complex tone at [toneHz] (baseband) plus Gaussian noise. */
    private fun iq(toneHz: Double, toneAmp: Double, noiseAmp: Double = 0.02): ByteArray {
        val rnd = Random(1)
        val out = ByteArray(meter.bytesPerBlock)
        for (n in 0 until meter.fftSize) {
            val ph = 2 * PI * toneHz * n / meter.sampleRate
            val i = toneAmp * cos(ph) + noiseAmp * rnd.nextGaussian()
            val q = toneAmp * sin(ph) + noiseAmp * rnd.nextGaussian()
            out[2 * n] = (i * 127.5 + 127.5).roundToInt().coerceIn(0, 255).toByte()
            out[2 * n + 1] = (q * 127.5 + 127.5).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test
    fun toneInChannelGivesHighSnr() {
        val r = meter.measure(iq(-meter.offsetHz + 1_000.0, 0.5))
        assertTrue("snr=${r.snrDb}", r.snrDb > 30)
        assertEquals(20 * kotlin.math.log10(0.5), r.channelDbfs, 1.0)
    }

    @Test
    fun toneOutsideChannelGivesNoSnr() {
        val r = meter.measure(iq(+100_000.0, 0.5))
        assertEquals(0.0, r.snrDb, 3.0)
    }

    @Test
    fun noiseOnlyGivesNoSnr() {
        val r = meter.measure(iq(0.0, 0.0))
        assertEquals(0.0, r.snrDb, 3.0)
    }

    @Test
    fun fullScaleSamplesReportClipping() {
        val r = meter.measure(iq(-meter.offsetHz.toDouble(), 1.2))
        assertTrue(r.clipFraction > 0.1)
    }
}
