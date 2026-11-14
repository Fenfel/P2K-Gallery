package ru.mptdroid.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException

/**
 * Switch a classic Motorola phone from modem mode (PID 0x4902) into P2K mode
 * (PID 0x4901) by sending an AT command over the CDC-ACM serial port —
 * exactly what libp2kmoto's test does with `echo "AT+MODE=8" > /dev/ttyACM0`.
 */
object ModeSwitch {

    private const val TAG = "ModeSwitch"
    private const val CLASS_CDC_CONTROL = 2
    private const val CLASS_CDC_DATA = 10

    /** Send one AT command line; returns the accumulated reply text. */
    fun sendAtCommand(usbManager: UsbManager, device: UsbDevice, command: String): String {
        val conn = usbManager.openDevice(device) ?: throw IOException("openDevice failed")
        try {
            var ctrlIfx = -1
            var dataIfx = -1
            var epOut: android.hardware.usb.UsbEndpoint? = null
            var epIn: android.hardware.usb.UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == CLASS_CDC_CONTROL && ctrlIfx < 0) ctrlIfx = i
                if (intf.interfaceClass == CLASS_CDC_DATA && epOut == null) {
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
                        if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                    }
                    dataIfx = i
                }
            }
            if (ctrlIfx < 0 || epOut == null) throw IOException("no CDC serial interface found")

            check(conn.claimInterface(device.getInterface(ctrlIfx), true), "claim ctrl")
            if (dataIfx >= 0) check(conn.claimInterface(device.getInterface(dataIfx), true), "claim data")

            // SET_LINE_CODING: 115200 8N1.
            val cfg = byteArrayOf(
                0x00, 0xC2.toByte(), 0x01, 0x00,   // 115200 LE
                0x00,                              // stop bits 1
                0x00,                              // parity none
                0x08                               // 8 data bits
            )
            val r1 = conn.controlTransfer(
                0x21, 0x20, 0, ctrlIfx, cfg, cfg.size, 3000
            )
            if (r1 < 0) Log.w(TAG, "SET_LINE_CODING ret=$r1")

            // SET_CONTROL_LINE_STATE: DTR+RTS — wakes the AT engine.
            val r2 = conn.controlTransfer(0x21, 0x22, 3, ctrlIfx, null, 0, 3000)
            if (r2 < 0) Log.w(TAG, "SET_CONTROL_LINE_STATE ret=$r2")

            // Drain anything pending.
            drain(conn, epIn)

            val out = (command + "\r\n").toByteArray(Charsets.US_ASCII)
            var written = 0
            while (written < out.size) {
                val n = conn.bulkTransfer(epOut, out, out.size - written, 3000)
                if (n <= 0) throw IOException("AT write failed at $written/$out.size")
                written += n
            }

            // Collect the reply for up to ~4 s.
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                val chunk = drain(conn, epIn)
                if (chunk != null) {
                    sb.append(String(chunk, Charsets.US_ASCII))
                    if (sb.contains("OK") || sb.contains("ERROR")) break
                }
            }
            Log.i(TAG, "AT '$command' -> '${sb.toString().trim()}'")
            return sb.toString()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun drain(
        conn: android.hardware.usb.UsbDeviceConnection,
        epIn: android.hardware.usb.UsbEndpoint?
    ): ByteArray? {
        if (epIn == null) return null
        val buf = ByteArray(epIn.maxPacketSize * 8)
        val n = conn.bulkTransfer(epIn, buf, buf.size, 300)
        return if (n > 0) buf.copyOf(n) else null
    }

    private fun check(ret: Boolean, what: String) {
        if (!ret) throw IOException("claim $what failed")
    }
}
