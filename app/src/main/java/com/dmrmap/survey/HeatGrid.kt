package com.dmrmap.survey

import android.graphics.Color
import kotlin.math.cos
import kotlin.math.floor

/**
 * Aggregates survey points into square cells (~[cellMeters] on a side) and keeps
 * the mean SNR of the active points in each cell.
 */
class HeatGrid(private val cellMeters: Double = 25.0) {

    class Cell(val south: Double, val west: Double, val north: Double, val east: Double) {
        var count = 0
            private set
        var meanSnr = 0.0
            private set
        var maxSnr = Double.NEGATIVE_INFINITY
            private set

        fun add(snr: Double) {
            count++
            meanSnr += (snr - meanSnr) / count
            if (snr > maxSnr) maxSnr = snr
        }
    }

    private val cells = HashMap<Long, Cell>()
    private var dLat = 0.0
    private var dLon = 0.0

    @Synchronized
    fun add(p: SurveyPoint) {
        if (!p.active) return
        if (dLat == 0.0) {
            // Fix the cell size in degrees from the first point; fine over a region.
            dLat = cellMeters / 111_320.0
            dLon = cellMeters / (111_320.0 * cos(Math.toRadians(p.lat)))
        }
        val row = floor(p.lat / dLat).toLong()
        val col = floor(p.lon / dLon).toLong()
        cells.getOrPut((row shl 32) xor (col and 0xFFFFFFFFL)) {
            Cell(row * dLat, col * dLon, (row + 1) * dLat, (col + 1) * dLon)
        }.add(p.snrDb)
    }

    @Synchronized
    fun snapshot(): List<Cell> = cells.values.toList()

    @Synchronized
    fun clear() {
        cells.clear()
        dLat = 0.0
        dLon = 0.0
    }

    companion object {
        const val SNR_MIN = 10.0
        const val SNR_MAX = 40.0

        /** Blue (weak, SNR_MIN) → cyan → green → yellow → red (strong, SNR_MAX). */
        fun color(snr: Double, alpha: Int = 170): Int {
            val t = ((snr - SNR_MIN) / (SNR_MAX - SNR_MIN)).coerceIn(0.0, 1.0)
            val hue = (240.0 * (1 - t)).toFloat()
            return Color.HSVToColor(alpha, floatArrayOf(hue, 1f, 1f))
        }
    }
}
