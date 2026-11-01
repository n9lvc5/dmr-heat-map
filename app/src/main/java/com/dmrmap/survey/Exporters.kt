package com.dmrmap.survey

import java.io.File
import java.io.Writer
import java.util.Locale
import kotlin.math.roundToInt

object Exporters {

    const val CSV_HEADER = "time_ms,lat,lon,accuracy_m,peak_dbfs,noise_dbfs,snr_db,active"

    fun csvLine(p: SurveyPoint): String = String.format(
        Locale.US, "%d,%.7f,%.7f,%.1f,%.1f,%.1f,%.1f,%d",
        p.timeMs, p.lat, p.lon, p.accuracyM, p.peakDbfs, p.noiseDbfs, p.snrDb, if (p.active) 1 else 0,
    )

    /** Skips the header and any malformed line (e.g. a half-written last line). */
    fun readCsv(file: File): List<SurveyPoint> =
        file.readLines().drop(1).mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 8) return@mapNotNull null
            runCatching {
                SurveyPoint(
                    f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toFloat(),
                    f[4].toDouble(), f[5].toDouble(), f[7] == "1",
                )
            }.getOrNull()
        }

    /** Writes a KML document with one coloured placemark per active point. */
    fun writeKml(allPoints: List<SurveyPoint>, title: String, w: Writer) {
        val points = allPoints.filter { it.active }
        // Bucket into 5 dB SNR styles so the KML stays compact.
        val buckets = (HeatGrid.SNR_MIN.toInt()..HeatGrid.SNR_MAX.toInt() step 5).toList()
        w.write("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
<name>$title</name>
""")
        for (b in buckets) {
            val argb = HeatGrid.color(b.toDouble(), 255)
            // KML colours are aabbggrr.
            val abgr = String.format(
                "ff%02x%02x%02x", argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF,
            )
            w.write("<Style id=\"s$b\"><IconStyle><color>$abgr</color><scale>0.6</scale>" +
                "<Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon>" +
                "</IconStyle><LabelStyle><scale>0</scale></LabelStyle></Style>\n")
        }
        for (p in points) {
            val b = buckets.last { it <= p.snrDb.roundToInt().coerceIn(buckets.first(), buckets.last()) }
            w.write(String.format(
                Locale.US,
                "<Placemark><name>%.0f dB</name><description>SNR %.1f dB, peak %.1f dBFS, noise %.1f dBFS</description>" +
                    "<styleUrl>#s%d</styleUrl><Point><coordinates>%.7f,%.7f</coordinates></Point></Placemark>\n",
                p.snrDb, p.snrDb, p.peakDbfs, p.noiseDbfs, b, p.lon, p.lat,
            ))
        }
        w.write("</Document></kml>\n")
    }
}
