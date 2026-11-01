package com.dmrmap.sdr

import android.content.Intent
import android.net.Uri
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Talks to an rtl_tcp server. On Android that server is provided by the
 * "RTL-SDR driver" app (marto.rtl_tcp_andro), which owns the USB dongle.
 */
class RtlTcpClient(private val host: String = HOST, private val port: Int = PORT) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /** Tuner type reported in the rtl_tcp header (5 = R820T/R828D). */
    var tunerType = 0
        private set

    fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.receiveBufferSize = 1 shl 20
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 5000
        socket = s
        input = DataInputStream(s.getInputStream().buffered(1 shl 16))
        output = s.getOutputStream()

        // 12-byte header: "RTL0", tuner type, gain count (both big-endian u32).
        val header = ByteArray(12)
        input!!.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0") throw IOException("Not an rtl_tcp server")
        tunerType = ByteBuffer.wrap(header, 4, 4).int
    }

    fun setFrequency(hz: Long) = command(0x01, hz.toInt())
    fun setSampleRate(hz: Int) = command(0x02, hz)
    fun setManualGain(manual: Boolean) = command(0x03, if (manual) 1 else 0)
    fun setGain(tenthsDb: Int) = command(0x04, tenthsDb)
    fun setPpm(ppm: Int) = command(0x05, ppm)
    fun setAgc(on: Boolean) = command(0x08, if (on) 1 else 0)

    /** Blocks until [buf] is filled with interleaved unsigned 8-bit I/Q samples. */
    fun readIq(buf: ByteArray) = input!!.readFully(buf)

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        input = null
        output = null
    }

    private fun command(cmd: Int, param: Int) {
        val b = ByteBuffer.allocate(5).put(cmd.toByte()).putInt(param).array()
        output!!.write(b)
        output!!.flush()
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 14423

        /** R820T/R828D supported gains in tenths of a dB. */
        val R820T_GAINS = intArrayOf(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
            280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
        )

        /** Intent that asks the driver app to open the dongle and start its rtl_tcp server. */
        fun driverIntent(): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("iqsrc://-a $HOST -p $PORT -n 1"))

        /** Error text the driver returns in its result Intent when it fails. */
        fun driverError(data: Intent?): String =
            data?.getStringExtra("detailed_exception_message")
                ?: "RTL-SDR driver did not start (is the dongle plugged in?)"
    }
}
