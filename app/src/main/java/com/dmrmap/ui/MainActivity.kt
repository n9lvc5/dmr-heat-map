package com.dmrmap.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dmrmap.R
import com.dmrmap.sdr.RtlTcpClient
import com.dmrmap.survey.HeatGrid
import com.dmrmap.survey.SessionStore
import com.dmrmap.survey.SurveyService
import com.dmrmap.survey.SurveyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private lateinit var map: MapView
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var freq: EditText
    private lateinit var gain: Spinner
    private lateinit var threshold: EditText
    private lateinit var ppm: EditText
    private lateinit var startStop: Button
    private lateinit var center: Button
    /** Following was turned off by dragging the map (not by the button). */
    private var draggedAway = false
    private lateinit var readout: TextView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) launchDriver()
        else toast("Location permission is needed to map the signal")
    }

    private val driver = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startSurvey()
        } else {
            val error = RtlTcpClient.driverError(result.data)
            SurveyState.status.value = error
            // Another client (e.g. SDR Touch) still holds the dongle through the driver.
            if ("BUSY" in error) {
                toast("The dongle is in use by another app (e.g. SDR Touch). " +
                    "Close it, force-stop RTL-SDR driver, and try again.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        freq = findViewById(R.id.freq)
        gain = findViewById(R.id.gain)
        threshold = findViewById(R.id.threshold)
        ppm = findViewById(R.id.ppm)
        startStop = findViewById(R.id.startStop)
        readout = findViewById(R.id.readout)
        center = findViewById(R.id.center)
        map = findViewById(R.id.map)

        gain.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            RtlTcpClient.R820T_GAINS.map { "Gain %.1f dB".format(Locale.US, it / 10.0) },
        )
        freq.setText(prefs.getString("freq", ""))
        gain.setSelection(prefs.getInt("gainIndex", RtlTcpClient.R820T_GAINS.indexOf(297)))
        threshold.setText(prefs.getString("threshold", "10"))
        ppm.setText(prefs.getString("ppm", "0"))

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        // Open over the Washington DC area; Start zooms in and follows the GPS.
        map.controller.setZoom(11.0)
        map.controller.setCenter(GeoPoint(38.9072, -77.0369))
        map.overlays.add(HeatOverlay())
        myLocation = object : MyLocationNewOverlay(GpsMyLocationProvider(this), map) {
            // osmdroid stops following when the map is dragged; reflect that on the button.
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                val wasFollowing = isFollowLocationEnabled
                val handled = super.onTouchEvent(event, mapView)
                if (wasFollowing && !isFollowLocationEnabled) {
                    draggedAway = true
                    updateCenterButton()
                }
                return handled
            }
        }
        map.overlays.add(myLocation)

        startStop.setOnClickListener {
            if (SurveyState.running.value) SurveyService.stop(this) else onStartPressed()
        }
        findViewById<Button>(R.id.export).setOnClickListener { export() }
        findViewById<Button>(R.id.clear).setOnClickListener { confirmClear() }
        center.setOnClickListener { onCenterPressed() }
        findViewById<TextView>(R.id.legend).text =
            "Map colour = SNR: blue %.0f dB → red %.0f+ dB".format(HeatGrid.SNR_MIN, HeatGrid.SNR_MAX)

        // Show earlier sessions at the saved frequency, including ones from before a restart.
        freqHz()?.let { hz ->
            lifecycleScope.launch(Dispatchers.IO) { SessionStore.loadMapIfNeeded(applicationContext, hz) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SurveyState.gridVersion.collect { map.invalidate() } }
                launch {
                    SurveyState.running.collect { running ->
                        startStop.text = if (running) "Stop" else "Start"
                        listOf(freq, gain, threshold, ppm).forEach { it.isEnabled = !running }
                        // Hidden while surveying so Start/Stop and the centre button get the room.
                        listOf(R.id.export, R.id.clear).forEach {
                            findViewById<Button>(it).visibility = if (running) View.GONE else View.VISIBLE
                        }
                        if (running) {
                            map.controller.setZoom(15.0)
                            myLocation.enableFollowLocation()
                        } else {
                            myLocation.disableFollowLocation()
                        }
                        draggedAway = false
                        updateCenterButton()
                    }
                }
                launch {
                    combine(
                        SurveyState.status, SurveyState.live,
                        SurveyState.pointCount, SurveyState.activeCount,
                    ) { status, live, points, active -> render(status, live, points, active) }
                        .collect {}
                }
            }
        }
    }

    private fun render(status: String, live: com.dmrmap.survey.LiveReading?, points: Int, active: Int) {
        val sb = StringBuilder(status)
        if (live != null) {
            sb.append(String.format(
                Locale.US, "\nSignal %6.1f dBFS   Noise %6.1f dBFS   SNR %5.1f dB",
                live.channelDbfs, live.noiseDbfs, live.snrDb,
            ))
            if (live.clipFraction > 0.001) sb.append("\n⚠ Clipping – lower the gain")
        }
        sb.append("\nPoints: $points logged, $active with signal")
        readout.text = sb
        readout.setBackgroundColor(
            if (live?.active == true) 0xFF2E7D32.toInt() else 0xFF37474F.toInt(),
        )
    }

    private fun onStartPressed() {
        val mhz = freq.text.toString().toDoubleOrNull()
        if (mhz == null || mhz < 24 || mhz > 1766) {
            toast("Enter the repeater output frequency in MHz, e.g. 443.4500")
            return
        }
        prefs.edit()
            .putString("freq", freq.text.toString())
            .putInt("gainIndex", gain.selectedItemPosition)
            .putString("threshold", threshold.text.toString())
            .putString("ppm", ppm.text.toString())
            .apply()
        permissions.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ))
    }

    private fun launchDriver() {
        try {
            driver.launch(RtlTcpClient.driverIntent())
        } catch (e: ActivityNotFoundException) {
            toast("Install the \"RTL-SDR driver\" app first")
            startActivity(Intent(
                Intent.ACTION_VIEW, Uri.parse("market://details?id=marto.rtl_tcp_andro"),
            ))
        }
    }

    private fun startSurvey() {
        val hz = freqHz() ?: return
        startForegroundService(Intent(this, SurveyService::class.java)
            .putExtra(SurveyService.EXTRA_FREQ_HZ, hz)
            .putExtra(SurveyService.EXTRA_GAIN, RtlTcpClient.R820T_GAINS[gain.selectedItemPosition])
            .putExtra(SurveyService.EXTRA_PPM, ppm.text.toString().toIntOrNull() ?: 0)
            .putExtra(SurveyService.EXTRA_SNR_THRESHOLD, threshold.text.toString().toDoubleOrNull() ?: 10.0))
    }

    private fun export() {
        val hz = freqHz() ?: return toast("Enter the frequency to export")
        lifecycleScope.launch {
            val uris = try {
                withContext(Dispatchers.IO) { SessionStore.exportPublic(applicationContext, hz) }
            } catch (e: Exception) {
                return@launch toast("Export failed: ${e.message}")
            }
            if (uris.isEmpty()) return@launch toast("No survey recorded at ${freq.text} MHz yet")
            toast("Saved to Download/DMR Heat Map")
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Export survey",
            ))
        }
    }

    /**
     * Following → tap stops centring (Find me). While surveying, Find me / Follow me
     * (after a drag) resume following; while stopped, Find me centres once.
     */
    private fun onCenterPressed() {
        draggedAway = false
        when {
            myLocation.isFollowLocationEnabled -> myLocation.disableFollowLocation()
            SurveyState.running.value -> myLocation.enableFollowLocation()
            else -> findMe()
        }
        updateCenterButton()
    }

    private fun updateCenterButton() {
        center.text = when {
            myLocation.isFollowLocationEnabled -> "Following"
            draggedAway -> "Follow me"
            else -> "Find me"
        }
    }

    /** Centres the map on the current GPS position once, at street level, without following. */
    private fun findMe() {
        val here = myLocation.myLocation ?: return toast("Waiting for a GPS fix…")
        myLocation.disableFollowLocation()
        map.controller.setZoom(15.0)
        map.controller.animateTo(here)
    }

    private fun confirmClear() {
        if (SurveyState.running.value) return toast("Stop the survey before clearing the map")
        val hz = freqHz() ?: return toast("Enter the frequency to clear")
        AlertDialog.Builder(this)
            .setTitle("Clear map for ${freq.text} MHz?")
            .setMessage("This deletes all survey data at this frequency from the app, and the map " +
                "starts empty. It can't be undone in the app.\n\n" +
                "A copy of each session is saved to Download/DMR Heat Map first.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val n = withContext(Dispatchers.IO) { SessionStore.clear(applicationContext, hz) }
                        toast("Cleared $n session(s); copies are in Download/DMR Heat Map")
                    } catch (e: Exception) {
                        toast("Not cleared – could not save to Downloads: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun freqHz(): Long? = freq.text.toString().toDoubleOrNull()?.let { (it * 1e6).roundToLong() }

    override fun onResume() {
        super.onResume()
        map.onResume()
        myLocation.enableMyLocation()
    }

    override fun onPause() {
        myLocation.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
