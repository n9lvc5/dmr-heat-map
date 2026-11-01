package com.dmrmap.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import com.dmrmap.survey.HeatGrid
import com.dmrmap.survey.SurveyState
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max

/** Paints each grid cell as a coloured square (blue = weak … red = strong). */
class HeatOverlay : Overlay() {
    private val paint = Paint().apply { style = Paint.Style.FILL }
    private val nw = Point()
    private val se = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        for (cell in SurveyState.grid.snapshot()) {
            proj.toPixels(GeoPoint(cell.north, cell.west), nw)
            proj.toPixels(GeoPoint(cell.south, cell.east), se)
            // Keep cells visible when zoomed far out.
            val cx = (nw.x + se.x) / 2f
            val cy = (nw.y + se.y) / 2f
            val hw = max((se.x - nw.x) / 2f, 3f)
            val hh = max((se.y - nw.y) / 2f, 3f)
            paint.color = HeatGrid.color(cell.meanSnr)
            c.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint)
        }
    }
}
