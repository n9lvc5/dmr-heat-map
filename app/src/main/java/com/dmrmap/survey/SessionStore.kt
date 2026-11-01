package com.dmrmap.survey

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session CSVs are written to app storage (one per Start, safe if the app dies).
 * [exportPublic] copies them to Download/DMR Heat Map, next to a combined CSV and
 * KML per frequency, where the user can see them in the Files app.
 */
object SessionStore {

    private val PUBLIC_PATH = Environment.DIRECTORY_DOWNLOADS + "/DMR Heat Map/"

    /** Frequency whose sessions are currently on the map (0 = none). */
    @Volatile private var mapFreqHz = 0L

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "sessions").apply { mkdirs() }

    private fun prefix(freqHz: Long) = "dmr_%.4f_".format(Locale.US, freqHz / 1e6)

    fun newSessionFile(ctx: Context, freqHz: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir(ctx), "${prefix(freqHz)}$stamp.csv")
    }

    fun sessions(ctx: Context, freqHz: Long): List<File> =
        dir(ctx).listFiles { f -> f.name.startsWith(prefix(freqHz)) && f.extension == "csv" }
            ?.sortedBy { it.name }.orEmpty()

    /** Fills the shared map with every session recorded at [freqHz], unless it already shows them. */
    @Synchronized
    fun loadMapIfNeeded(ctx: Context, freqHz: Long) {
        if (mapFreqHz == freqHz) return
        val points = sessions(ctx, freqHz).flatMap { Exporters.readCsv(it) }
        SurveyState.grid.clear()
        points.forEach(SurveyState.grid::add)
        SurveyState.pointCount.value = points.size
        SurveyState.activeCount.value = points.count { it.active }
        SurveyState.gridVersion.value++
        mapFreqHz = freqHz
    }

    /**
     * Archives the sessions at [freqHz] to Download/DMR Heat Map, then deletes the app's
     * copies so they no longer load onto the map. Returns how many sessions were cleared.
     */
    @Synchronized
    fun clear(ctx: Context, freqHz: Long): Int {
        val files = sessions(ctx, freqHz)
        exportPublic(ctx, freqHz) // throws before anything is deleted if the archive fails
        files.forEach { it.delete() }
        SurveyState.grid.clear()
        SurveyState.pointCount.value = 0
        SurveyState.activeCount.value = 0
        SurveyState.gridVersion.value++
        mapFreqHz = freqHz
        return files.size
    }

    /**
     * Copies every session at [freqHz] to Download/DMR Heat Map and writes the combined
     * `dmr_<MHz>_all.csv` / `.kml` there. Returns the combined files' URIs (empty if no sessions).
     */
    fun exportPublic(ctx: Context, freqHz: Long): List<Uri> {
        val files = sessions(ctx, freqHz)
        if (files.isEmpty()) return emptyList()
        for (f in files) writePublic(ctx, f.name, "text/csv") { out -> f.inputStream().use { it.copyTo(out) } }

        val points = files.flatMap { Exporters.readCsv(it) }.sortedBy { it.timeMs }
        val name = prefix(freqHz) + "all"
        val csv = writePublic(ctx, "$name.csv", "text/csv") { out ->
            out.bufferedWriter().apply {
                write(Exporters.CSV_HEADER + "\n")
                points.forEach { write(Exporters.csvLine(it) + "\n") }
                flush()
            }
        }
        val kml = writePublic(ctx, "$name.kml", "application/vnd.google-earth.kml+xml") { out ->
            out.bufferedWriter().apply {
                Exporters.writeKml(points, "DMR %.5f MHz - all sessions".format(Locale.US, freqHz / 1e6), this)
                flush()
            }
        }
        return listOf(csv, kml)
    }

    /** Creates or overwrites [name] in the public folder. */
    private fun writePublic(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, PUBLIC_PATH), null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_PATH)
        }) ?: throw IOException("Could not create $name in Downloads")
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write $name")).use(write)
        return uri
    }
}
