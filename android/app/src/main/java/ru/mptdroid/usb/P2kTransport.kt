package ru.mptdroid.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.IOException
import kotlin.math.min

/**
 * P2K v0 wire protocol over USB vendor control transfers — a byte-for-byte port
 * of the proven libp2kmoto sequence (Ubuntu p2kmoto 0.1~rc1, src/p2kmoto.c).
 *
 * Command:   control OUT  bmRequestType=0x41 bRequest=0x02 wValue=0 wIndex=8
 *            data stage = pktId(BE16) opcode(BE16) argLen(BE16) 0000 args...
 * Status:    control IN   0xC1/0x00 -> count(BE16) size(BE16); retry while short
 * Data:      control IN   0xC1/0x01 wValue=1 wLength = 2*count + size + 4
 */
class P2kTransport(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {

    var claimedInterface: UsbInterface? = null
        private set

    /** Claim the interface carrying the P2K endpoint (descriptor id 8 on classic phones). */
    fun open() {
        // libp2kmoto's p2k_connect: set_configuration(1) before claiming.
        if (device.configurationCount > 0) {
            val cfg = device.getConfiguration(0)
            try { connection.setConfiguration(cfg) } catch (e: Exception) {
                Log.w(TAG, "setConfiguration(${cfg.id}) failed: ${e.message}")
            }
        }
        // libusb claims by bInterfaceNumber; Android indexes by position. Find the
        // interface whose *id* is 8 ("Motorola Test Command"), not getInterface(8).
        val target = (0 until device.interfaceCount)
            .firstOrNull { device.getInterface(it).id == INTERFACE_P2K }
        if (target != null) {
            val intf = device.getInterface(target)
            if (connection.claimInterface(intf, true)) {
                claimedInterface = intf
                Log.i(TAG, "claimed P2K interface id=$INTERFACE_P2K index=$target (${intf.name})")
                return
            }
        } else {
            Log.w(TAG, "no interface with id=$INTERFACE_P2K; names: " +
                (0 until device.interfaceCount).joinToString {
                    "${device.getInterface(it).id}/${device.getInterface(it).name}"
                })
        }
        // Fallback: claim the first claimable interface (control wIndex stays 8).
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (connection.claimInterface(intf, true)) {
                claimedInterface = intf
                Log.w(TAG, "claimed fallback index $i id=${intf.id} (${intf.name})")
                return
            }
        }
        throw IOException("cannot claim USB interface for P2K")
    }

    fun close() {
        claimedInterface?.let { try { connection.releaseInterface(it) } catch (_: Exception) {} }
        claimedInterface = null
    }

    private var packetId = 0

    /**
     * Run one full P2K exchange and return the raw answer buffer exactly as
     * libp2kmoto hands it to callers (packet header at offset 6, payload from 14).
     */
    fun command(opcode: Int, args: ByteArray): ByteArray {
        val id = nextId()
        val req = ByteArray(8 + args.size)
        req[0] = ((id shr 8) and 0xFF).toByte()
        req[1] = (id and 0xFF).toByte()
        req[2] = ((opcode shr 8) and 0xFF).toByte()
        req[3] = (opcode and 0xFF).toByte()
        req[4] = ((args.size shr 8) and 0xFF).toByte()
        req[5] = (args.size and 0xFF).toByte()
        // req[6..7] stay zero, then args.
        System.arraycopy(args, 0, req, 8, args.size)

        val sent = connection.controlTransfer(
            /* requestType */ 0x41, /* request */ 0x02, /* value */ 0, W_INDEX,
            req, req.size, XFER_TIMEOUT_MS
        )
        if (sent < 0) throw IOException("command write failed (ret=$sent)")

        // Status stage: retry until the phone reports a size.
        val status = ByteArray(STATUS_BUF)
        var n = -1
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        while (true) {
            n = connection.controlTransfer(
                0xC1, /* request */ 0x00, /* value */ 0, W_INDEX,
                status, status.size, POLL_TIMEOUT_MS
            )
            if (n >= 3 || System.currentTimeMillis() > deadline) break
        }
        if (n < 4) throw IOException("status timeout/short (ret=$n)")
        val count = ((status[0].toInt() and 0xFF) shl 8) or (status[1].toInt() and 0xFF)
        val size = ((status[2].toInt() and 0xFF) shl 8) or (status[3].toInt() and 0xFF)
        val bufsize = 2 * count + size + 4
        if (bufsize <= 0 || bufsize > MAX_ANSWER) {
            throw IOException("bad answer size $bufsize (count=$count size=$size)")
        }

        // Data stage.
        val buf = ByteArray(bufsize)
        val got = connection.controlTransfer(
            0xC1, /* request */ 0x01, /* value */ 1, W_INDEX,
            buf, buf.size, XFER_TIMEOUT_MS
        )
        if (got < 0) throw IOException("data read failed (ret=$got)")
        Log.d(TAG, "cmd=0x%04X id=%d count=%d size=%d got=%d".format(opcode, id, count, size, got))

        // Validate packet header like libp2kmoto: LE id at [6..7], type at [8].
        if (got < 9) throw IOException("answer too short ($got): ${hex(buf, min(got, 16))}")
        val answerId = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (answerId and 0xFFF != id) {
            Log.w(TAG, "id mismatch want=$id got=0x%04X".format(answerId))
        }
        return buf.copyOf(got)
    }

    /** Extract the answer type byte; throws on hard error codes. */
    fun answerType(buf: ByteArray): Int {
        if (buf.size < 9) throw IOException("answer too short")
        val type = buf[8].toInt() and 0xFF
        if (type != TYPE_OK && type != TYPE_DATA && type != TYPE_DATA2) {
            throw IOException("phone error 0x%02X: %s".format(type, hex(buf, min(buf.size, 16))))
        }
        return type
    }

    private fun nextId(): Int {
        packetId = (packetId + 1) and 0xFFF
        if (packetId == 0) packetId = 1
        return packetId
    }

    companion object {
        const val TAG = "P2kTransport"
        const val INTERFACE_P2K = 8          // libp2kmoto INTERFACE_TEST_COMMAND
        const val W_INDEX = 0x08             // fixed, as in every known-good client
        const val TYPE_OK = 0x60             // success, no data
        const val TYPE_DATA = 0x80           // success, data follows
        const val TYPE_DATA2 = 0x4A          // seen on some FSAC replies
        const val STATUS_BUF = 34            // sizeof(packetCount) in libp2kmoto
        const val MAX_ANSWER = 0x10000
        const val XFER_TIMEOUT_MS = 8000
        const val POLL_TIMEOUT_MS = 500
        const val STATUS_TIMEOUT_MS = 15_000L

        fun hex(b: ByteArray, n: Int): String {
            val end = minOf(n, b.size)
            val sb = StringBuilder(end * 3)
            for (i in 0 until end) sb.append(String.format("%02X ", b[i]))
            return sb.toString()
        }
    }
}
