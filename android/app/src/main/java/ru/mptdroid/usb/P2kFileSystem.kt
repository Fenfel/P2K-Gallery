package ru.mptdroid.usb

import android.util.Log
import java.io.IOException
import kotlin.math.min

/** One entry in a phone directory listing. */
data class PhoneFile(
    val name: String,          // full path as reported by the phone, e.g. /a/Pictures/x.jpg
    val size: Long,            // bytes; -1 unknown
    val attr: Int,             // raw attribute byte from the record
) {
    val isDir get() = (attr and 0x10) != 0 || name.endsWith("/")
}

/**
 * FSAC (filesystem access) commands on top of the P2K transport. Opcode 0x4A.
 * Byte layouts copied from libp2kmoto src/p2kmoto.c (tested against C350/C650).
 * [args] here is exactly what follows the 8-byte frame header; sub-command is
 * a u32BE at args[0..3]. Answer frames carry: count byte at [0], payload size
 * BE16 at [4..5], packet id LE-ish at [6..7], type at [8], then per-command
 * data (file data at offset 14, drive-name string at 15, list records at 18).
 *   0 open | 1 read | 2 write | 3 seek | 4 close | 5 delete
 *   7 file-count | 8 file-list | 0xA vol-name | 0xB vol-space
 */
class P2kFileSystem(private val tp: P2kTransport) {

    private fun u16be(v: Int): ByteArray =
        byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** ASCII name bytes, no terminator — libp2kmoto appends strlen bytes only. */
    private fun rawAscii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    // ---- public API ---------------------------------------------------------

    /** FSAC open (sub 0): sub u32BE + 3 pad + attr byte + ASCII name. */
    @Synchronized
    fun fsOpen(path: String, attr: Int) {
        val args = byteArrayOf(0, 0, 0, 0, 0, 0, 0, attr.toByte()) + rawAscii(path)
        checkOk(tp.command(OPCODE, args))
    }

    /** FSAC read (sub 1): sub u32BE + 2 pad + size u16BE. Data at answer offset 14. */
    @Synchronized
    fun fsRead(size: Int): ByteArray {
        val args = byteArrayOf(0, 0, 0, 1, 0, 0) + u16be(size)
        val buf = tp.command(OPCODE, args)
        if (buf.size < HEADER_DATA) throw IOException("read answer too short (${tpHex(buf)})")
        val take = min(size, buf.size - HEADER_DATA)
        return buf.copyOfRange(HEADER_DATA, HEADER_DATA + take)
    }

    /** FSAC write (sub 2): sub u32BE + 2 pad + size u16BE + raw data. */
    @Synchronized
    fun fsWrite(data: ByteArray, offset: Int, len: Int) {
        val args = byteArrayOf(0, 0, 0, 2, 0, 0) + u16be(len) +
            data.copyOfRange(offset, offset + len)
        checkOk(tp.command(OPCODE, args))
    }

    /** FSAC seek (sub 3): sub u32BE + pos u32BE + whence byte. */
    @Synchronized
    fun fsSeek(pos: Long, whence: Int = SEEK_SET) {
        val args = byteArrayOf(0, 0, 0, 3) +
            byteArrayOf(
                ((pos shr 24) and 0xFF).toByte(), ((pos shr 16) and 0xFF).toByte(),
                ((pos shr 8) and 0xFF).toByte(), (pos and 0xFF).toByte()
            ) + byteArrayOf(whence.toByte())
        checkOk(tp.command(OPCODE, args))
    }

    /** FSAC close (sub 4). */
    @Synchronized
    fun fsClose() {
        try { tp.command(OPCODE, byteArrayOf(0, 0, 0, 4)) } catch (e: Exception) {
            Log.w(TAG, "close ignored: ${e.message}")
        }
    }

    /** FSAC delete (sub 5): sub u32BE + ASCII name. */
    @Synchronized
    fun fsDelete(path: String) {
        checkOk(tp.command(OPCODE, byteArrayOf(0, 0, 0, 5) + rawAscii(path)))
    }

    /** Volume (drive) name; payload string starts at frame offset 15, every-other-byte. */
    @Synchronized
    fun volumeName(): String {
        val buf = tp.command(OPCODE, byteArrayOf(0, 0, 0, 0x0A))
        if (buf.size <= VOLNAME_OFF) throw IOException("volname too short (${tpHex(buf)})")
        return decodePhoneString(buf, VOLNAME_OFF).trim()
    }

    /** Free space on a drive: fixed 512-byte args block, name UTF-16 from index 5. */
    @Synchronized
    fun freeSpace(volName: String): Long {
        val args = ByteArray(512)
        byteArrayOf(0, 0, 0, 0x0B).copyInto(args, 0)
        var ps = 5
        for (c in volName) { args[ps] = c.code.toByte(); args[ps + 1] = 0; ps += 2 }
        val buf = tp.command(OPCODE, args)
        if (buf.size < 18) throw IOException("volspace too short (${tpHex(buf)})")
        return ((buf[14].toLong() and 0xFF) shl 24) or ((buf[15].toLong() and 0xFF) shl 16) or
            ((buf[16].toLong() and 0xFF) shl 8) or (buf[17].toLong() and 0xFF)
    }

    // ---- directory listing ----------------------------------------------------
    // libp2kmoto sequence: fileCount(sub 7, no args) -> count; then repeated
    // fileList(sub 8, no args) batches. Records start at answer offset 0x12,
    // record size = (answerLen - 0x12) / recordsInBatch. Name field is
    // recSize-8 bytes wide; attr byte at +fnameSize+1, owner at +3, u32BE file
    // size at +fnameSize+4. Names are full paths ("/a/Pictures/x.jpg").

    /** Total number of files on the phone (recursive). */
    @Synchronized
    fun fileCount(): Int {
        val buf = tp.command(OPCODE, byteArrayOf(0, 0, 0, 7))
        if (buf.size < 16) {
            Log.w(TAG, "fileCount short answer (${tpHex(buf)})")
            return -1
        }
        val count = ((buf[14].toInt() and 0xFF) shl 8) or (buf[15].toInt() and 0xFF)
        Log.i(TAG, "phone fileCount=$count")
        if (count == 0xFFFF) return -1
        return count
    }

    /** Fetch one fileList batch (sub 8), appending parsed records to [out]. */
    private fun fileListBatch(out: MutableList<PhoneFile>): Int {
        val buf = tp.command(OPCODE, byteArrayOf(0, 0, 0, 8))
        if (buf.size <= RECORDS_OFF) throw IOException("fileList too short (${tpHex(buf)})")
        // Records-in-batch: LE16 at [0x0F..0x10] exactly as libp2kmoto reads it.
        val inRec = (buf[0x0F].toInt() and 0xFF) or ((buf[0x10].toInt() and 0xFF) shl 8)
        if (inRec <= 0) return 0
        val bodyLen = buf.size - RECORDS_OFF
        if (bodyLen % inRec != 0) {
            throw IOException("bad fileList size: body=$bodyLen recs=$inRec (${tpHex(buf)})")
        }
        val recSize = bodyLen / inRec
        if (recSize < 12) throw IOException("absurd record size $recSize")
        val fnameSize = recSize - 8
        var addr = RECORDS_OFF
        repeat(inRec) {
            if (addr + recSize <= buf.size) {
                val name = decodeRecordName(buf, addr, fnameSize)
                val attr = buf[addr + fnameSize + 1].toInt() and 0xFF
                val sizeOff = addr + fnameSize + 4
                val size = ((buf[sizeOff].toLong() and 0xFF) shl 24) or
                    ((buf[sizeOff + 1].toLong() and 0xFF) shl 16) or
                    ((buf[sizeOff + 2].toLong() and 0xFF) shl 8) or
                    (buf[sizeOff + 3].toLong() and 0xFF)
                if (name.isNotEmpty()) out += PhoneFile(name, size, attr)
            }
            addr += recSize
        }
        return inRec
    }

    /** Full recursive listing: fileCount then drain fileList batches. */
    @Synchronized
    fun listAll(): List<PhoneFile> {
        var count = try { fileCount() } catch (e: Exception) { -1 }
        val result = ArrayList<PhoneFile>(if (count > 0) count else 16)
        var guard = 0
        if (count <= 0) {
            // No usable count: drain batches until the phone returns an empty one.
            while (guard++ < MAX_BATCHES) {
                val got = try { fileListBatch(result) } catch (e: Exception) {
                    Log.w(TAG, "fileList batch failed after ${result.size}: ${e.message}")
                    0
                }
                if (got == 0) break
            }
            return result.distinctBy { it.name }
        }
        while (result.size < count && guard++ < MAX_BATCHES) {
            val got = try { fileListBatch(result) } catch (e: Exception) {
                Log.w(TAG, "fileList batch failed after ${result.size}: ${e.message}")
                0
            }
            if (got == 0) break
        }
        return result.distinctBy { it.name }
    }

    /** Read whole file into memory. */
    fun readWhole(path: String, maxSize: Long = MAX_FILE_SIZE): ByteArray {
        fsOpen(path, ATTR_READ)
        try {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val chunk = fsRead(BLOCK)
                if (chunk.isEmpty()) break
                if (out.size() + chunk.size > maxSize) throw IOException("file too large")
                out.write(chunk)
                if (chunk.size < BLOCK) break   // short read => EOF
            }
            return out.toByteArray()
        } finally {
            fsClose()
        }
    }

    /** Write whole file to phone, creating/truncating it. */
    fun writeWhole(path: String, data: ByteArray) {
        fsOpen(path, ATTR_CREATE)
        try {
            var off = 0
            while (off < data.size) {
                val len = min(BLOCK, data.size - off)
                fsWrite(data, off, len)
                off += len
            }
        } finally {
            fsClose()
        }
    }

    // ---- helpers --------------------------------------------------------------

    private fun checkOk(buf: ByteArray) { tp.answerType(buf) }

    /** Decode a directory-record name at [off] within a [width]-byte field. */
    private fun decodeRecordName(b: ByteArray, off: Int, width: Int): String {
        if (off >= b.size) return ""
        val end = min(off + width, b.size)
        // UTF-16LE ("/" 0x00 "a" 0x00 ...): second byte of first pair is NUL.
        if (end - off >= 2 && b[off].toInt() != 0 && b[off + 1].toInt() == 0) {
            val sb = StringBuilder()
            var i = off
            while (i + 1 < end && sb.length < MAX_NAME_CHARS) {
                val c = ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
                if (c == 0) break
                sb.append(c.toChar()); i += 2
            }
            return sb.toString().trim()
        }
        // UTF-16BE (high byte 0x00 for Latin/Cyrillic BMP chars).
        if (end - off >= 2 && b[off].toInt() == 0 && b[off + 1].toInt() != 0) {
            val sb = StringBuilder()
            var i = off
            while (i + 1 < end && sb.length < MAX_NAME_CHARS) {
                val c = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                if (c == 0) break
                sb.append(c.toChar()); i += 2
            }
            return sb.toString().trim()
        }
        // Plain byte string until NUL (libp2kmoto strcpy), cp1251 for Cyrillic.
        val raw = ArrayList<Byte>(MAX_NAME_CHARS)
        var i = off
        while (i < end && raw.size < MAX_NAME_CHARS) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) break
            raw += b[i]; i++
        }
        val bytes = ByteArray(raw.size)
        raw.forEachIndexed { idx, v -> bytes[idx] = v }
        return try {
            String(bytes, java.nio.charset.Charset.forName("windows-1251"))
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /**
     * Decode a phone string at [off]: UTF-16LE pairs until NUL — libp2kmoto
     * reads drive names as every-other byte from answer offset 15 (low bytes).
     */
    private fun decodePhoneString(b: ByteArray, off: Int): String {
        if (off >= b.size) return ""
        val sb = StringBuilder()
        var i = off
        while (i + 1 < b.size && sb.length < MAX_NAME_CHARS) {
            val c = ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
            if (c == 0) break
            sb.append(c.toChar()); i += 2
        }
        return sb.toString()
    }

    private fun tpHex(b: ByteArray): String = P2kTransport.hex(b, min(b.size, 16))

    companion object {
        const val TAG = "P2kFS"
        const val OPCODE = 0x4A
        const val BLOCK = 0x400          // max FSAC block size (1 KiB)
        const val HEADER_DATA = 14       // file data starts here in a read answer
        const val VOLNAME_OFF = 15
        const val RECORDS_OFF = 0x12     // first record after frame header
        const val ATTR_READ = 0x00
        const val ATTR_CREATE = 0x80     // create/truncate flag for open
        const val SEEK_SET = 0
        const val MAX_NAME_CHARS = 130
        const val MAX_BATCHES = 4096
        const val MAX_FILE_SIZE = 40L * 1024 * 1024
    }
}
