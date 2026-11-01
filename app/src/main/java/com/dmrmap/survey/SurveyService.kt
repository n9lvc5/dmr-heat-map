package com.dmrmap.survey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.dmrmap.dsp.ChannelPowerMeter
import com.dmrmap.sdr.RtlTcpClient
import com.dmrmap.ui.MainActivity
import java.io.Writer
import kotlin.math.max

/**
 * Foreground service: reads IQ from the RTL-SDR driver, measures channel power,
 * and pairs the strongest reading in each second with the GPS fix.
 */
class SurveyService : Service(), LocationListener {

    private var sdrThread: Thread? = null
    @Volatile private var stopping = false
    private var client: RtlTcpClient? = null
    private var csv: Writer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var snrThreshold = 10.0
    private var sessionFreqHz = 0L

    // Accumulated between GPS fixes; guarded by `this`.
    private var peakDbfs = Double.NEGATIVE_INFINITY
    private var noiseSum = 0.0
    private var noiseCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (SurveyState.running.value || intent == null) return START_NOT_STICKY

        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        val freqHz = intent.getLongExtra(EXTRA_FREQ_HZ, 0)
        val gain = intent.getIntExtra(EXTRA_GAIN, 297)
        val ppm = intent.getIntExtra(EXTRA_PPM, 0)
        snrThreshold = intent.getDoubleExtra(EXTRA_SNR_THRESHOLD, 10.0)

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dmrmap:survey").apply { acquire() }

        openSessionFile(freqHz)
        SurveyState.running.value = true
        SurveyState.status.value = "Connecting to RTL-SDR…"

        @Suppress("MissingPermission") // Activity only starts us once location is granted.
        (getSystemService(LOCATION_SERVICE) as LocationManager)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())

        stopping = false
        sdrThread = Thread({ runSdr(freqHz, gain, ppm) }, "sdr").apply { start() }
        return START_NOT_STICKY
    }

    private fun openSessionFile(freqHz: Long) {
        // Earlier sessions at this frequency stay on the map; a new file is started for this one.
        SessionStore.loadMapIfNeeded(this, freqHz)
        sessionFreqHz = freqHz
        csv = SessionStore.newSessionFile(this, freqHz).bufferedWriter()
            .apply { write(Exporters.CSV_HEADER + "\n"); flush() }
    }

    private fun runSdr(freqHz: Long, gain: Int, ppm: Int) {
        val meter = ChannelPowerMeter()
        val buf = ByteArray(meter.bytesPerBlock)
        val c = RtlTcpClient()
        client = c
        try {
            c.connect()
            c.setSampleRate(meter.sampleRate)
            c.setFrequency(freqHz + meter.offsetHz)
            c.setPpm(ppm)
            c.setAgc(false)
            c.setManualGain(true)
            c.setGain(gain)
            SurveyState.status.value = "Receiving – waiting for GPS"

            var liveMax = Double.NEGATIVE_INFINITY
            var liveBlocks = 0
            while (!stopping) {
                c.readIq(buf)
                val r = meter.measure(buf)
                synchronized(this) {
                    peakDbfs = max(peakDbfs, r.channelDbfs)
                    noiseSum += r.noiseDbfs
                    noiseCount++
                }
                // ~10 updates/s for the on-screen meter, showing the max of each slice.
                liveMax = max(liveMax, r.channelDbfs)
                if (++liveBlocks >= 6) {
                    SurveyState.live.value = LiveReading(
                        liveMax, r.noiseDbfs, r.clipFraction, liveMax - r.noiseDbfs >= snrThreshold,
                    )
                    liveMax = Double.NEGATIVE_INFINITY
                    liveBlocks = 0
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                SurveyState.status.value = "SDR error: ${e.message}"
                stopSelf()
            }
        } finally {
            c.close()
        }
    }

    override fun onLocationChanged(loc: Location) {
        val point: SurveyPoint
        synchronized(this) {
            if (noiseCount == 0) return // no SDR data since last fix
            point = SurveyPoint(
                loc.time, loc.latitude, loc.longitude, loc.accuracy,
                peakDbfs, noiseSum / noiseCount, false,
            ).let { it.copy(active = it.snrDb >= snrThreshold) }
            peakDbfs = Double.NEGATIVE_INFINITY
            noiseSum = 0.0
            noiseCount = 0
        }
        csv?.apply { write(Exporters.csvLine(point) + "\n"); flush() }
        SurveyState.pointCount.value++
        if (point.active) {
            SurveyState.activeCount.value++
            SurveyState.grid.add(point)
            SurveyState.gridVersion.value++
        }
        SurveyState.status.value = "Logging"
    }

    override fun onDestroy() {
        stopping = true
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        client?.close() // unblocks readIq
        sdrThread?.join(2000)
        csv?.close()
        csv = null
        if (sessionFreqHz != 0L) {
            // Publish to Download/DMR Heat Map so the user can find it without pressing Export.
            val app = applicationContext
            val hz = sessionFreqHz
            Thread { runCatching { SessionStore.exportPublic(app, hz) } }.start()
        }
        wakeLock?.release()
        SurveyState.running.value = false
        SurveyState.live.value = null
        if (!SurveyState.status.value.startsWith("SDR error")) SurveyState.status.value = "Stopped"
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Survey", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SurveyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("DMR survey running")
            .setContentText("Logging signal strength with GPS")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_FREQ_HZ = "freq_hz"
        const val EXTRA_GAIN = "gain"
        const val EXTRA_PPM = "ppm"
        const val EXTRA_SNR_THRESHOLD = "snr_threshold"
        private const val ACTION_STOP = "com.dmrmap.STOP"
        private const val CHANNEL_ID = "survey"
        private const val NOTIFICATION_ID = 1

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, SurveyService::class.java))
    }
}
