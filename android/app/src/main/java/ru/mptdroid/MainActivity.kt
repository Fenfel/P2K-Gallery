package ru.mptdroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mptdroid.usb.ModeSwitch
import ru.mptdroid.usb.P2kFileSystem
import ru.mptdroid.usb.P2kTransport
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var list: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var importAllBtn: com.google.android.material.button.MaterialButton
    @Volatile private var importing = false

    /** Jump straight into the camera folder once per connection. */
    @Volatile private var initialNavDone = false
    private val executor = Executors.newSingleThreadExecutor()

    private var transport: P2kTransport? = null
    private var fs: P2kFileSystem? = null
    @Volatile private var modeSwitchInFlight = false

    /** Full recursive listing fetched from the phone; UI shows derived children. */
    private var allEntries: List<ru.mptdroid.usb.PhoneFile> = emptyList()

    /** Current directory path on the phone, e.g. "/a" or "/a/Pictures". */
    private var currentPath = "/"
    private var entries: List<ru.mptdroid.usb.PhoneFile> = emptyList()
    private val adapter = FileAdapter()

    /** Multi-select set of phone paths; non-empty means selection mode is on. */
    private val selected = LinkedHashSet<String>()

    /** 0=newest first, 1=A-Z, 2=Z-A, 3=size desc (persisted in prefs). */
    private var sortMode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        list = findViewById(R.id.fileList)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.emptyView)
        importAllBtn = findViewById(R.id.importAllBtn)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = this.adapter
        sortMode = getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("sort", 0)

        findViewById<View>(R.id.refreshBtn).setOnClickListener {
            if (transport == null) scanDevices() else refresh(forReload = true)
        }
        findViewById<View>(R.id.upBtn).setOnClickListener { goUp() }
        findViewById<View>(R.id.uploadBtn).setOnClickListener { pickUploadTarget() }
        importAllBtn.setOnClickListener { importAllPictures() }
        findViewById<View>(R.id.sortBtn).setOnClickListener { cycleSort() }

        // Force readable label on tonal buttons: custom Material You themes can
        // produce same-hue container/onOnPrimary pairs (beige-on-beige here).
        val bg = com.google.android.material.color.MaterialColors.getColor(
            importAllBtn, com.google.android.material.R.attr.colorPrimaryContainer
        )
        val label = if (ColorUtils.calculateLuminance(bg) > 0.55) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
        importAllBtn.setTextColor(label)
        importAllBtn.iconTint = android.content.res.ColorStateList.valueOf(label)

        findViewById<View>(R.id.downloadSelBtn).setOnClickListener { downloadSelected() }
        findViewById<View>(R.id.deleteSelBtn).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.cancelSelBtn).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.deleteAllBtn).setOnClickListener { confirmDeleteAll() }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selected.isNotEmpty() -> clearSelection()
                    currentPath != "/" -> goUp()
                    else -> finish()
                }
            }
        })

        intent?.let { handleUsbIntent(it) }

        // Move photos imported by older builds into the public gallery folder.
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        } else {
            executor.execute { migrateLegacyImports() }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            executor.execute { migrateLegacyImports() }
        }
    }

    // ---- USB attach / permission -------------------------------------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        connect(device)
                    else toast("No permission for USB device")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Ignore re-attach noise while a session is already live.
                    if (transport == null) intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { connect(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val had = transport != null
                    disconnect()
                    if (had) toast("Phone disconnected")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        if (transport == null) scanDevices()
    }

    /** Enumerate every USB device; connect to Motorola ones, report the rest. */
    private fun scanDevices() {
        val devices = usbManager.deviceList.values
        Log.i("MPTDroid", "scan: ${devices.size} device(s)")
        if (devices.isEmpty()) {
            statusText.text = "No USB devices found"
            toast("No USB devices found")
            return
        }
        for (d in devices) {
            Log.i(
                "MPTDroid",
                "device: vid=0x%04X pid=0x%04X name=%s ifaces=%d".format(
                    d.vendorId, d.productId, d.deviceName, d.interfaceCount
                )
            )
        }
        // Prefer a phone already in P2K mode; otherwise take the modem-mode one.
        val moto = devices.firstOrNull { it.productId == PID_P2K }
            ?: devices.firstOrNull { it.vendorId == MOTOROLA_VID }
        if (moto != null) {
            connect(moto)
        } else {
            val desc = devices.joinToString(", ") {
                "0x%04X:0x%04X".format(it.vendorId, it.productId)
            }
            statusText.text = "No Motorola (0x22B8). USB: $desc"
            toast("No Motorola found; devices: $desc")
        }
    }

    override fun onStop() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun handleUsbIntent(intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            ?: usbManager.deviceList.values.firstOrNull { it.productId == PID_P2K }
            ?: usbManager.deviceList.values.firstOrNull { it.vendorId == MOTOROLA_VID }
            ?: return
        if (usbManager.hasPermission(device)) connect(device) else requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Target our own package explicitly; the system delivers the reply to us.
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    // ---- connection ---------------------------------------------------------

    private fun connect(device: UsbDevice) {
        if (device.vendorId != MOTOROLA_VID) return
        executor.execute {
            try {
                if (transport != null) return@execute   // session already live
                if (!usbManager.hasPermission(device)) {
                    runOnUiThread { requestPermission(device) }
                    return@execute
                }
                when (device.productId) {
                    PID_P2K -> openP2kSession(device)
                    PID_MODEM -> {
                        if (modeSwitchInFlight) return@execute
                        modeSwitchInFlight = true
                        switchToP2kMode(device)
                    }
                    else -> {
                        val msg = "Motorola 0x%04X - unknown mode".format(device.productId)
                        Log.w("MPTDroid", msg)
                        runOnUiThread { statusText.text = msg }
                    }
                }
            } catch (e: Exception) {
                Log.e("MPTDroid", "connect failed", e)
                runOnUiThread { toast("Connection error: ${e.message}") }
            }
        }
    }

    /** Phone enumerates as a CDC modem; send AT+MODE=8 so it reboots into P2K mode. */
    private fun switchToP2kMode(modem: UsbDevice) {
        runOnUiThread { statusText.text = "Switching phone to P2K mode (AT+MODE=8)..." }
        val reply = try {
            ModeSwitch.sendAtCommand(usbManager, modem, "AT+MODE=8")
        } catch (e: Exception) {
            // The phone often drops USB right after accepting the command.
            Log.w("MPTDroid", "AT write ended with: ${e.message}")
            ""
        }
        Log.i("MPTDroid", "mode switch reply: '${reply.trim()}'")
        runOnUiThread {
            statusText.text = "Waiting for phone to restart into P2K mode..."
            toast("Phone will reconnect as P2K - grant USB access")
        }
    }

    private fun openP2kSession(device: UsbDevice) {
        modeSwitchInFlight = false
        val conn = usbManager.openDevice(device) ?: error("Failed to open device")
        val tp = P2kTransport(device, conn)
        tp.open()
        transport = tp
        fs = P2kFileSystem(tp)
        allEntries = emptyList()
        probeVolumes(fs!!)   // USB I/O stays on this executor thread
        runOnUiThread {
            statusText.text = "Connected: ${device.deviceName}"
            refresh()
        }
    }

    private fun disconnect() {
        modeSwitchInFlight = false
        try { transport?.close() } catch (_: Exception) {}
        transport = null; fs = null
        allEntries = emptyList()
        entries = emptyList()
        initialNavDone = false
        currentPath = "/"
        adapter.notifyDataSetChanged()
    }

    // ---- browsing -----------------------------------------------------------

    /**
     * P2K listing is flat full paths ("​/a/Pictures/x.jpg"). Files are shown
     * flattened from the whole subtree below [path] (newest-first by default);
     * sub-directories stay clickable for navigation.
     */
    private fun deriveChildren(path: String): List<ru.mptdroid.usb.PhoneFile> {
        val base = path.trim('/').split('/').filter { it.isNotEmpty() }
        val dirs = LinkedHashMap<String, ru.mptdroid.usb.PhoneFile>()
        val files = ArrayList<ru.mptdroid.usb.PhoneFile>()
        for (e in allEntries) {
            val parts = e.name.trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.size <= base.size) continue
            var matches = true
            for (i in base.indices) {
                if (!parts[i].equals(base[i], ignoreCase = true)) { matches = false; break }
            }
            if (!matches) continue
            val next = parts[base.size]
            if (parts.size == base.size + 1) {
                files += e
            } else {
                // Flat gallery: every jpg in the subtree shows right here...
                files += e
                val virtualPath = "/" + (base + next).joinToString("/") + "/"
                if (!dirs.containsKey(virtualPath))
                    dirs[virtualPath] = ru.mptdroid.usb.PhoneFile(virtualPath, -1, 0xFF)
            }
        }
        return dirs.values.sortedBy { it.name.lowercase() } + sortFiles(files)
    }

    /** Natural (numeric-aware) ordering so IMG_9 < IMG_10; "newest" = biggest number. */
    private val naturalOrder = compareBy<ru.mptdroid.usb.PhoneFile>(
        { it.name.substringAfterLast('/').length },
        { it.name.substringAfterLast('/').uppercase() }
    )

    private fun sortFiles(files: List<ru.mptdroid.usb.PhoneFile>): List<ru.mptdroid.usb.PhoneFile> =
        when (sortMode) {
            1 -> files.sortedWith(naturalOrder)                                    // A-Z
            2 -> files.sortedWith(compareByDescending<ru.mptdroid.usb.PhoneFile> { it.name.lowercase() })  // Z-A
            3 -> files.sortedByDescending { if (it.size >= 0) it.size else -1L }   // largest first
            else -> files.sortedWith(naturalOrder.reversed())                      // newest first
        }

    private fun cycleSort() {
        sortMode = (sortMode + 1) % 4
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putInt("sort", sortMode).apply()
        toast(listOf("Newest first", "A-Z", "Z-A", "Largest first")[sortMode])
        refresh()
    }

    // ---- multi-select ---------------------------------------------------------

    private fun toggleSelect(item: ru.mptdroid.usb.PhoneFile) {
        if (item.isDir) return
        if (!selected.remove(item.name)) selected.add(item.name)
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionBar() {
        val bar = findViewById<View>(R.id.selectionBar)
        if (selected.isEmpty()) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        findViewById<TextView>(R.id.selectionCount).text = "Selected: ${selected.size}"
    }

    private fun downloadSelected() {
        val fss = fs ?: run { toast("Phone not connected"); return }
        val items = allEntries.filter { it.name in selected && !it.isDir }
        if (items.isEmpty()) { clearSelection(); return }
        clearSelection()
        progress.visibility = View.VISIBLE
        executor.execute {
            var ok = 0; var fail = 0
            for ((i, p) in items.withIndex()) {
                runOnUiThread {
                    statusText.text = "Saving ${i + 1}/${items.size}: ${p.name.substringAfterLast('/')}"
                }
                try {
                    val data = fss.readWhole(p.name)
                    if (saveToGallery(safeName(p), data) != null) ok++ else fail++
                } catch (e: Exception) {
                    Log.w("MPTDroid", "save ${p.name} failed: ${e.message}")
                    fail++
                }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                toast(if (fail == 0) "In P2K Gallery: $ok" else "Saved $ok, failed $fail")
            }
        }
    }

    // ---- deletion on the phone ------------------------------------------------

    private fun deleteSelected() {
        val fss = fs ?: run { toast("Phone not connected"); return }
        val items = allEntries.filter { it.name in selected && !it.isDir }
        if (items.isEmpty()) { clearSelection(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${items.size} files from the phone?")
            .setMessage("They are removed on the PHONE permanently. Copies already imported into P2K Gallery stay.")
            .setPositiveButton("Delete") { _, _ ->
                clearSelection()
                progress.visibility = View.VISIBLE
                executor.execute {
                    var ok = 0; var fail = 0
                    for (p in items) {
                        try {
                            fss.fsDelete(p.name)
                            allEntries = allEntries.filterNot { it.name == p.name }
                            ok++
                        } catch (e: Exception) {
                            Log.w("MPTDroid", "delete ${p.name} failed: ${e.message}")
                            fail++
                        }
                    }
                    runOnUiThread {
                        progress.visibility = View.GONE
                        toast(if (fail == 0) "Deleted: $ok" else "Deleted $ok, failed $fail")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAll() {
        val fss = fs ?: run { toast("Phone not connected"); return }
        val photos = allEntries.filter { !it.isDir && isJpg(it.name) }
        if (photos.isEmpty()) { toast("No .jpg files on the phone"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ALL photos?")
            .setMessage("This permanently deletes ${photos.size} .jpg files from the PHONE. Copies already imported into P2K Gallery stay.")
            .setPositiveButton("Delete everything") { _, _ ->
                progress.visibility = View.VISIBLE
                executor.execute {
                    var ok = 0; var fail = 0
                    for (p in photos) {
                        try {
                            fss.fsDelete(p.name)
                            allEntries = allEntries.filterNot { it.name == p.name }
                            ok++
                        } catch (e: Exception) {
                            Log.w("MPTDroid", "delete-all ${p.name} failed: ${e.message}")
                            fail++
                        }
                    }
                    runOnUiThread {
                        progress.visibility = View.GONE
                        toast(if (fail == 0) "Deleted all photos: $ok" else "Deleted $ok, failed $fail")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh(forReload: Boolean = false) {
        val fss = fs ?: run { toast("Phone not connected"); return }
        progress.visibility = View.VISIBLE
        executor.execute {
            try {
                if (allEntries.isEmpty() || forReload) {
                    // One recursive listing; classic P2K has no cwd.
                    val raw = try { fss.listAll() } catch (e: Exception) {
                        Log.w("MPTDroid", "listAll failed: ${e.message}")
                        emptyList()
                    }
                    // UI policy: only .jpg files are ever shown or imported.
                    allEntries = raw.filter { it.isDir || isJpg(it.name) }
                    Log.i("MPTDroid", "phone holds ${raw.size} entries, jpg-only ${allEntries.size}")
                }
                if (!initialNavDone) {
                    initialNavDone = true
                    val hasPics = allEntries.any {
                        it.name.lowercase().startsWith("/a/mobile/pictures")
                    }
                    if (hasPics) {
                        currentPath = "/a/mobile/pictures"
                        Log.i("MPTDroid", "auto-navigated to $currentPath")
                    }
                }
                entries = deriveChildren(currentPath)
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                    progress.visibility = View.GONE
                    findViewById<TextView>(R.id.pathText).text = currentPath
                    emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    statusText.text = "${entries.size} items (${photoCount()} jpg on phone)"
                }
            } catch (e: Exception) {
                Log.e("MPTDroid", "list failed", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    toast("Listing failed: ${e.message}")
                }
            }
        }
    }

    /** Probe volume name + free space once after connect. */
    private fun probeVolumes(fss: P2kFileSystem) {
        try {
            val name = fss.volumeName()
            val free = try { fss.freeSpace(name) } catch (_: Exception) { -1L }
            runOnUiThread {
                statusText.text = "Volume \"$name\", free ${
                    if (free >= 0) formatSize(free) else "?"}"
            }
        } catch (e: Exception) {
            Log.w("MPTDroid", "vol probe failed", e)
        }
    }

    private fun goUp() {
        currentPath = if (currentPath.count { it == '/' } <= 1) "/" else
            currentPath.trimEnd('/').substringBeforeLast("/")
        refresh()
    }

    private fun openEntry(item: ru.mptdroid.usb.PhoneFile) {
        val fss = fs ?: return
        if (item.isDir) {
            currentPath = item.name.trimEnd('/')
            refresh()
            return
        }
        // File: offer preview or download.
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name.substringAfterLast('/'))
            .setItems(arrayOf("Preview", "Download", "Delete")) { _, which ->
                when (which) {
                    0 -> downloadAndPreview(fss, item)
                    1 -> downloadOnly(fss, item)
                    2 -> deleteEntry(fss, item)
                }
            }
            .show()
    }

    // ---- transfers ----------------------------------------------------------

    /** Download every .jpg on the phone (all volumes) into the app media dir. */
    private fun importAllPictures() {
        val fss = fs ?: run { toast("Phone not connected"); return }
        if (importing) { toast("Import already running..."); return }
        val photos = allEntries.filter { !it.isDir && isJpg(it.name) }
        if (photos.isEmpty()) { toast("No .jpg files on the phone"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Import all")
            .setMessage("Download all photos (${photos.size}, .jpg only) from the phone?")
            .setPositiveButton("Download all") { _, _ ->
                importing = true
                importAllBtn.isEnabled = false
                progress.visibility = View.VISIBLE
                executor.execute {
                    var ok = 0; var fail = 0
                    for ((i, p) in photos.withIndex()) {
                        runOnUiThread {
                            statusText.text = "Importing ${i + 1}/${photos.size}: ${p.name.substringAfterLast('/')}"
                        }
                        try {
                            val data = fss.readWhole(p.name)
                            if (saveToGallery(safeName(p), data) != null) ok++ else fail++
                        } catch (e: Exception) {
                            Log.w("MPTDroid", "import ${p.name} failed: ${e.message}")
                            fail++
                        }
                    }
                    runOnUiThread {
                        importing = false
                        importAllBtn.isEnabled = true
                        progress.visibility = View.GONE
                        toast(if (fail == 0) "Imported photos: $ok" else "Import: $ok ok, $fail failed")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- gallery storage ------------------------------------------------------

    /** What landed where after a save; exactly one of uri/file is set. */
    private data class SavedMedia(val name: String, val uri: android.net.Uri?, val file: File?)

    private val galleryRelPath
        get() = "${Environment.DIRECTORY_PICTURES}/P2K Gallery"

    /** Public Pictures/P2K Gallery dir on legacy storage (API <= 28). */
    private fun legacyGalleryDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "P2K Gallery"
        )

    /** Save bytes into the system gallery folder Pictures/P2K Gallery. */
    private fun saveToGallery(baseName: String, data: ByteArray): SavedMedia? {
        val mime = if (isJpg(baseName)) "image/jpeg" else "*/*"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val name = uniqueGalleryNameQ(baseName)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, galleryRelPath)
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: error("no output stream")
                SavedMedia(name, uri, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                Log.w("MPTDroid", "gallery write failed: ${e.message}")
                null
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return null
            val dir = legacyGalleryDir().apply { mkdirs() }
            val name = uniqueName(dir, baseName)
            val out = File(dir, name)
            out.writeBytes(data)
            SavedMedia(name, null, out)
        }
    }

    /** MediaStore has no path collision check — match DISPLAY_NAME instead. */
    private fun uniqueGalleryNameQ(base: String): String {
        if (!galleryHasName(base)) return base
        val stem = base.substringBeforeLast('.', base)
        val ext = base.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val cand = if (ext.isEmpty()) "${stem}_$n" else "${stem}_$n.$ext"
            if (!galleryHasName(cand)) return cand
            n++
        }
    }

    private fun galleryHasName(name: String): Boolean {
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null
        )?.use { return it.count > 0 }
        return false
    }

    /** Avoid clobbering files with the same name in a plain directory. */
    private fun uniqueName(dir: File, base: String): String {
        if (!File(dir, base).exists()) return base
        val stem = base.substringBeforeLast('.', base)
        val ext = base.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val cand = if (ext.isEmpty()) "${stem}_$n" else "${stem}_$n.$ext"
            if (!File(dir, cand).exists()) return cand
            n++
        }
    }

    /** One-off move of previously imported photos out of the app-private dir. */
    private fun migrateLegacyImports() {
        val src = File(filesDir, "media")
        val pending = src.listFiles()?.toList() ?: return
        if (pending.isEmpty()) return
        var moved = 0
        for (f in pending) {
            if (!f.isFile) continue
            val data = try { f.readBytes() } catch (_: Exception) { continue }
            val saved = saveToGallery(f.name, data) ?: break   // retry next launch
            if (saved != null) { f.delete(); moved++ }
        }
        if (moved > 0) runOnUiThread {
            toast("Moved to P2K Gallery: $moved")
        }
    }

    private fun photoCount(): Int = allEntries.count { !it.isDir && isJpg(it.name) }

    private fun isJpg(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg"
    }

    private fun downloadOnly(fss: P2kFileSystem, item: ru.mptdroid.usb.PhoneFile) {
        progress.visibility = View.VISIBLE
        executor.execute {
            try {
                val data = fss.readWhole(item.name)
                val saved = saveToGallery(safeName(item), data)
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (saved != null)
                        toast("Saved to P2K Gallery: ${saved.name} (${data.size} B)")
                    else toast("Could not save to gallery")
                }
            } catch (e: Exception) {
                Log.e("MPTDroid", "download failed", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    toast("Download failed: ${e.message}")
                }
            }
        }
    }

    private fun downloadAndPreview(fss: P2kFileSystem, item: ru.mptdroid.usb.PhoneFile) {
        progress.visibility = View.VISIBLE
        executor.execute {
            try {
                val data = fss.readWhole(item.name)
                // Preview lives in the app-private cache only; deleted on close.
                val dir = File(cacheDir, "preview").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val tmp = File(dir, safeName(item))
                try { tmp.writeBytes(data) } catch (e: Exception) {
                    Log.w("MPTDroid", "preview cache write failed", e)
                }
                runOnUiThread {
                    progress.visibility = View.GONE
                    showPreview(tmp, item.name)
                }
            } catch (e: Exception) {
                Log.e("MPTDroid", "preview download failed", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    toast("Read failed: ${e.message}")
                }
            }
        }
    }

    private fun showPreview(tmp: File, pathOnPhone: String) {
        val bmp = BitmapFactory.decodeFile(tmp.absolutePath)
            ?: run { tmp.delete(); toast("Cannot decode image"); return }
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(pathOnPhone.substringAfterLast('/'))
            .setView(iv)
            .setOnDismissListener { tmp.delete() }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun deleteEntry(fss: P2kFileSystem, item: ru.mptdroid.usb.PhoneFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete?")
            .setMessage(item.name)
            .setPositiveButton("Delete") { _, _ ->
                progress.visibility = View.VISIBLE
                executor.execute {
                    try {
                        fss.fsDelete(item.name)
                        allEntries = allEntries.filterNot { it.name == item.name }
                        runOnUiThread { refresh() }
                    } catch (e: Exception) {
                        Log.e("MPTDroid", "delete failed", e)
                        runOnUiThread { toast("Delete failed: ${e.message}") }
                    } finally {
                        runOnUiThread { progress.visibility = View.GONE }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Upload a local file (from app's media dir or any picked via system UI). */
    private fun pickUploadTarget() {
        val fss = fs ?: run { toast("Phone not connected"); return }
        if (currentPath == "/") { toast("Open a phone folder first"); return }
        val picker = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            .setType("*/*").addCategory(android.content.Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(picker, REQ_UPLOAD) } catch (_: Exception) {
            toast("No file picker available")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UPLOAD || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = queryName(uri) ?: "upload.bin"
        val fss = fs ?: return
        progress.visibility = View.VISIBLE
        executor.execute {
            try {
                val data2 = contentResolver.openInputStream(uri)?.readAllBytes()
                    ?: error("could not read local file")
                val dest = "$currentPath/$name"
                fss.writeWhole(dest, data2)
                runOnUiThread { refresh(); toast("Uploaded: $dest (${data2.size} B)") }
            } catch (e: Exception) {
                Log.e("MPTDroid", "upload failed", e)
                runOnUiThread { toast("Upload failed: ${e.message}") }
            } finally {
                runOnUiThread { progress.visibility = View.GONE }
            }
        }
    }

    private fun queryName(uri: android.net.Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    // ---- helpers --------------------------------------------------------------

    private fun safeName(item: ru.mptdroid.usb.PhoneFile): String =
        item.name.substringAfterLast('/').ifEmpty { "file.bin" }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXT -> "image/*"
        in VIDEO_EXT -> "video/mp4"
        in AUDIO_EXT -> "audio/mpeg"
        else -> "*/*"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    inner class FileAdapter : RecyclerView.Adapter<FileVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return FileVH(v)
        }
        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: FileVH, position: Int) {
            val item = entries[position]
            holder.icon.setImageResource(
                if (item.isDir) R.drawable.ic_folder else R.drawable.ic_image
            )
            holder.name.text = if (item.isDir) "${item.name.trimEnd('/').substringAfterLast('/')}"
                else item.name.substringAfterLast('/')
            val sel = selected.contains(item.name)
            holder.meta.text = when {
                item.isDir -> "folder"
                sel -> "selected"
                item.size >= 0 -> formatSize(item.size)
                else -> "?"
            }
            holder.itemView.isActivated = sel
            holder.itemView.setOnClickListener {
                if (selected.isNotEmpty() && !item.isDir) toggleSelect(item) else openEntry(item)
            }
            holder.itemView.setOnLongClickListener {
                if (!item.isDir) toggleSelect(item)
                true
            }
        }
    }

    class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: android.widget.ImageView = v.findViewById(R.id.fileIcon)
        val name: TextView = v.findViewById(R.id.fileName)
        val meta: TextView = v.findViewById(R.id.fileMeta)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "ru.mptdroid.USB_PERMISSION"
        const val REQ_UPLOAD = 42
        const val REQ_STORAGE = 43
        const val MOTOROLA_VID = 0x22B8
        const val PID_MODEM = 0x4902       // CDC modem (default mode)
        const val PID_P2K = 0x4901         // P2K protocol mode
        val IMAGE_EXT = setOf("jpg", "jpeg", "gif", "png", "bmp")
        val VIDEO_EXT = setOf("mp4", "3gp", "avi", "mpg", "mpeg", "smaf")
        val AUDIO_EXT = setOf("mp3", "mid", "midi", "wav", "amr", "aac", "mmf", "spf")

        fun formatSize(b: Long): String = when {
            b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
            b >= 1_024 -> String.format(Locale.US, "%.0f KB", b / 1_024.0)
            else -> "$b B"
        }
    }
}
