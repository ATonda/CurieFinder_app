package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var _permissionsReady = false
    private var _pageFinished = false

    companion object {
        const val TAG = "CurieFinder"
        const val ACTION_USB_PERMISSION = "cz.antonin.curiefinder.USB_PERMISSION"
        const val IMPORT_CSV_REQUEST = 42

        const val DIR_LOGS = "logs"
        const val DIR_HEATMAP = "heatmap"
        const val DIR_LAYERS = "layers"
        const val DIR_ROI = "roi"
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CurieService.BROADCAST_DATA -> {
                    val line = intent.getStringExtra(CurieService.EXTRA_DATA) ?: return
                    val escaped = line.replace("\"", "\\\"")
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieData(\"$escaped\")", null)
                    }
                }
                CurieService.BROADCAST_CONNECTED -> {
                    val typ  = intent.getStringExtra(CurieService.EXTRA_TYPE) ?: "BT"
                    val dname = (intent.getStringExtra(CurieService.EXTRA_DEVICE_NAME) ?: "")
                        .replace("'", "\'").replace("\n", " ")
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieConnected('$typ','$dname')", null)
                    }
                }
                CurieService.BROADCAST_ERROR -> {
                    val msg = intent.getStringExtra(CurieService.EXTRA_MSG) ?: ""
                    runOnUiThread {
                        when {
                            msg.startsWith("BT_SELECT:") -> {
                                val devices = msg.removePrefix("BT_SELECT:")
                                webView.evaluateJavascript("window.onBtDeviceSelect('$devices')", null)
                            }
                            msg.startsWith("BT_NOTFOUND:") -> {
                                val name = msg.removePrefix("BT_NOTFOUND:")
                                webView.evaluateJavascript("window.onBtDeviceSelect('')", null)
                                webView.evaluateJavascript("window.onCurieError('Zařízení $name nebylo nalezeno mezi spárovanými')", null)
                            }
                            else -> webView.evaluateJavascript("window.onCurieError('$msg')", null)
                        }
                    }
                }
                CurieService.BROADCAST_GPS -> {
                    val lat = intent.getDoubleExtra(CurieService.EXTRA_LAT, 0.0)
                    val lon = intent.getDoubleExtra(CurieService.EXTRA_LON, 0.0)
                    val acc = intent.getDoubleExtra(CurieService.EXTRA_ACC, 0.0)
                    val alt = intent.getDoubleExtra(CurieService.EXTRA_ALT, 0.0)
                    val bearing = intent.getDoubleExtra(CurieService.EXTRA_BEARING, -1.0)
                    val hasBearing = intent.getBooleanExtra(CurieService.EXTRA_HAS_BEARING, false)
                    val bearingVal = if (hasBearing) bearing else -1.0
                    val speed = intent.getDoubleExtra("speed", -1.0)
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieGPS($lat,$lon,$acc,$alt,$bearingVal,$speed)", null)
                    }
                }
                CurieService.BROADCAST_GPS_ERROR -> {
                    val msg = intent.getStringExtra(CurieService.EXTRA_MSG) ?: ""
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieGPSError('$msg')", null)
                    }
                }
                CurieService.BROADCAST_SAVE_LOG -> {
                    runOnUiThread {
                        webView.evaluateJavascript("if(window.autoSaveLog) window.autoSaveLog();", null)
                    }
                }
                CurieService.BROADCAST_WIZ_SCAN -> {
                    val name = intent.getStringExtra("wiz_name") ?: return
                    val addr = intent.getStringExtra("wiz_addr") ?: ""
                    val rssi = intent.getIntExtra("wiz_rssi", -99)
                    val safeName = name.replace("\"", "\\\"")
                    runOnUiThread {
                        webView.evaluateJavascript("if(window._wizOnBleScan) window._wizOnBleScan(\"$safeName\",\"$addr\",$rssi)", null)
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        sendToService(CurieService.ACTION_CONNECT_USB)
                    } else {
                        runOnUiThread {
                            webView.evaluateJavascript("window.onCurieError('USB povoleni zamitnuto')", null)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        setContentView(webView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

        getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("killed_by_swipe", false).apply()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                _pageFinished = true  // KLÍČOVÉ: nastavit příznak!
                runOnUiThread {
                    val sbh = try {
                        val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
                        if (rid > 0) resources.getDimensionPixelSize(rid) else 0
                    } catch (e: Exception) { 0 }
                    val sbhDp = (sbh / resources.displayMetrics.density).toInt()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript("document.documentElement.style.setProperty('--status-bar-height','${sbhDp}px'); console.log('sbh set: ${sbhDp}dp');", null)
                    }, 300)
                    Log.d(TAG, "Page finished, statusBarHeight=${sbhDp}dp px=${sbh}")
                    if (_permissionsReady) {
                        sendToService(CurieService.ACTION_CONNECT_BT)
                    }
                }
            }
        }
        webView.addJavascriptInterface(AndroidSerial(), "AndroidSerial")

        try {
            val root = getExternalFilesDir("CurieFinder") ?: filesDir
            java.io.File(root, DIR_LOGS).mkdirs()
            java.io.File(root, DIR_HEATMAP).mkdirs()
            java.io.File(root, DIR_LAYERS).mkdirs()
            java.io.File(root, DIR_ROI).mkdirs()
            Log.d(TAG, "Directories created: ${root.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create directories: ${e.message}")
        }

        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter().apply {
            addAction(CurieService.BROADCAST_DATA)
            addAction(CurieService.BROADCAST_CONNECTED)
            addAction(CurieService.BROADCAST_ERROR)
            addAction(CurieService.BROADCAST_GPS)
            addAction(CurieService.BROADCAST_GPS_ERROR)
            addAction(CurieService.BROADCAST_SAVE_LOG)
            addAction(CurieService.BROADCAST_WIZ_SCAN)
            addAction(ACTION_USB_PERMISSION)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReceiver, filter)

        requestAllPermissions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_CSV_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                thread {
                    try {
                        val rootDir = getExternalFilesDir("CurieFinder") ?: filesDir
                        rootDir.mkdirs()
                        var filename = "import_${System.currentTimeMillis()}.csv"
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex >= 0) {
                                filename = cursor.getString(nameIndex)
                            }
                        }
                        val content = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                        val header = String(content.take(200).toByteArray(), Charsets.UTF_8)

                        val targetDir = when {
                            header.contains("# CF_ROI v1") -> java.io.File(rootDir, DIR_ROI).also { it.mkdirs() }
                            header.contains("# CurieFinder Heatmap") -> java.io.File(rootDir, DIR_HEATMAP).also { it.mkdirs() }
                            header.contains("# CurieFinder CSV v1") -> java.io.File(rootDir, DIR_LOGS).also { it.mkdirs() }
                            header.contains("# CurieFinder") -> java.io.File(rootDir, DIR_LOGS).also { it.mkdirs() }
                            else -> getDirForFile(rootDir, filename)
                        }

                        java.io.File(targetDir, filename).writeBytes(content)
                        Log.d(TAG, "CSV imported: $filename to ${targetDir.path}")
                        backupFile(filename, String(content, Charsets.UTF_8))
                        runOnUiThread {
                            webView.evaluateJavascript("window.onCurieImported('$filename')", null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "importCSV error: ${e.message}")
                        val msg = e.message?.replace("'", "") ?: "Chyba importu"
                        runOnUiThread {
                            webView.evaluateJavascript("window.onCurieError('$msg')", null)
                        }
                    }
                }
            }
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(android.Manifest.permission.BLUETOOTH)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            _permissionsReady = true
            requestBackgroundLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            _permissionsReady = true
            requestBackgroundLocation()
            sendToService(CurieService.ACTION_START_GPS)
            runOnUiThread {
                webView.evaluateJavascript("if(window.onCuriePermissionsReady) window.onCuriePermissionsReady()", null)
            }
            if (_pageFinished) {
                sendToService(CurieService.ACTION_CONNECT_BT)
            }
        }
        if (requestCode == 3) {
            sendToService(CurieService.ACTION_START_GPS)
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    3
                )
            }
        }
    }

    private fun sendToService(action: String) {
        Log.d(TAG, "sendToService: $action")
        try {
            val intent = Intent(this, CurieService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendToService ERROR: ${e.message}")
        }
    }

    fun openLocationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
                return
            }
        }
        sendToService(CurieService.ACTION_START_GPS)
    }

    private fun getDirForFile(rootDir: java.io.File, filename: String): java.io.File {
        return when {
            isLogFile(filename) -> java.io.File(rootDir, DIR_LOGS).also { it.mkdirs() }
            isHeatmapFile(filename) -> java.io.File(rootDir, DIR_HEATMAP).also { it.mkdirs() }
            isLayerFile(filename) -> java.io.File(rootDir, DIR_LAYERS).also { it.mkdirs() }
            isRoiFile(filename) -> java.io.File(rootDir, DIR_ROI).also { it.mkdirs() }
            else -> rootDir.also { it.mkdirs() }
        }
    }

    private fun isLogFile(filename: String): Boolean {
        if (!filename.startsWith("CF_")) return false
        if (filename.startsWith("CF_heat_")) return false
        if (filename.startsWith("CF_LAYER_")) return false
        if (filename.startsWith("CF_POI")) return false
        if (filename.startsWith("CF_ROI_")) return false
        return filename.endsWith(".csv")
    }

    private fun isHeatmapFile(filename: String): Boolean {
        return filename.startsWith("CF_heat_") && filename.endsWith(".csv")
    }

    private fun isLayerFile(filename: String): Boolean {
        if (filename.startsWith("CF_LAYER_")) return true
        if (filename.startsWith("CF_POI")) return true
        if (filename == "dulni_dila_filtr.csv") return true
        if (filename.endsWith(".geojson")) return true
        return false
    }

    private fun isRoiFile(filename: String): Boolean {
        return filename.startsWith("CF_ROI_") && filename.endsWith(".roi")
    }

    private fun getBackupDir(filename: String): java.io.File? {
        return try {
            val subDir = when {
                isLogFile(filename) -> "logs"
                isHeatmapFile(filename) -> "heatmaps"
                isRoiFile(filename) -> "roi"
                isLayerFile(filename) && filename.startsWith("CF_POI") -> "poi"
                isLayerFile(filename) -> "layers"
                else -> "logs"
            }
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            )
            val backupDir = java.io.File(docsDir, "CurieFinder/backup/$subDir")
            backupDir.mkdirs()
            backupDir
        } catch (e: Exception) {
            Log.e(TAG, "getBackupDir error: ${e.message}")
            null
        }
    }

    private fun backupFile(filename: String, data: String) {
        try {
            val backupDir = getBackupDir(filename) ?: return
            java.io.File(backupDir, filename).writeText(data)
            Log.d(TAG, "Backup saved: $filename to ${backupDir.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Backup error: ${e.message}")
        }
    }

    private fun deleteBackupFile(filename: String) {
        try {
            val backupDir = getBackupDir(filename) ?: return
            java.io.File(backupDir, filename).delete()
            Log.d(TAG, "Backup deleted: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "deleteBackupFile error: ${e.message}")
        }
    }

    inner class AndroidSerial {

        private fun rootDir() = getExternalFilesDir("CurieFinder") ?: filesDir

        @JavascriptInterface
        fun connect() {
            Log.d(TAG, "connect() called from JS")
            runOnUiThread {
                getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("killed_by_swipe", false).apply()

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

                if (drivers.isNotEmpty()) {
                    val device = drivers[0].device
                    if (!usbManager.hasPermission(device)) {
                        val pi = PendingIntent.getBroadcast(
                            this@MainActivity, device.deviceId,
                            Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        usbManager.requestPermission(device, pi)
                    } else {
                        sendToService(CurieService.ACTION_CONNECT_USB)
                    }
                } else {
                    sendToService(CurieService.ACTION_CONNECT_BT)
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            sendToService(CurieService.ACTION_DISCONNECT)
        }

        @JavascriptInterface
        fun startGPS() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasBg = ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasBg) {
                    runOnUiThread { openLocationSettings() }
                    return
                }
            }
            sendToService(CurieService.ACTION_START_GPS)
        }

        @JavascriptInterface
        fun stopGPS() {
            sendToService(CurieService.ACTION_STOP_GPS)
        }

        @JavascriptInterface
        fun listCSV(): String {
            return try {
                val root = rootDir()
                val files = mutableListOf<String>()
                java.io.File(root, DIR_LOGS).listFiles()
                    ?.filter { it.name.endsWith(".csv") }
                    ?.map { it.name }?.let { files.addAll(it) }
                java.io.File(root, DIR_HEATMAP).listFiles()
                    ?.filter { it.name.endsWith(".csv") }
                    ?.map { it.name }?.let { files.addAll(it) }
                java.io.File(root, DIR_LAYERS).listFiles()
                    ?.filter { it.name.endsWith(".csv") || it.name.endsWith(".geojson") }
                    ?.map { it.name }?.let { files.addAll(it) }
                root.listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".csv") || it.name.endsWith(".geojson")) }
                    ?.map { it.name }?.let { files.addAll(it) }
                org.json.JSONArray(files.distinct().sorted()).toString()
            } catch (e: Exception) { "[]" }
        }

        @JavascriptInterface
        fun listCSVWithSizes(): String {
            return try {
                val root = rootDir()
                val allFiles = mutableListOf<java.io.File>()
                java.io.File(root, DIR_LOGS).listFiles()
                    ?.filter { it.name.endsWith(".csv") }?.let { allFiles.addAll(it) }
                java.io.File(root, DIR_HEATMAP).listFiles()
                    ?.filter { it.name.endsWith(".csv") }?.let { allFiles.addAll(it) }
                val arr = org.json.JSONArray()
                allFiles.sortedByDescending { it.name }.forEach { f ->
                    val obj = org.json.JSONObject()
                    obj.put("name", f.name)
                    obj.put("size", f.length())
                    arr.put(obj)
                }
                arr.toString()
            } catch (e: Exception) { "[]" }
        }

        @JavascriptInterface
        fun readCSV(filename: String): String {
            return try {
                val root = rootDir()
                val primaryDir = getDirForFile(root, filename)
                val primaryFile = java.io.File(primaryDir, filename)
                if (primaryFile.exists()) return primaryFile.readText()
                val searchDirs = listOf(
                    root,
                    java.io.File(root, DIR_LOGS),
                    java.io.File(root, DIR_HEATMAP),
                    java.io.File(root, DIR_LAYERS)
                )
                for (dir in searchDirs) {
                    val f = java.io.File(dir, filename)
                    if (f.exists()) return f.readText()
                }
                ""
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun deleteCSV(filenamesJson: String) {
            try {
                val root = rootDir()
                val filenames = org.json.JSONArray(filenamesJson)
                for (i in 0 until filenames.length()) {
                    val name = filenames.getString(i)
                    val dir = getDirForFile(root, name)
                    java.io.File(dir, name).delete()
                    deleteBackupFile(name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteCSV error: ${e.message}")
            }
        }

        @JavascriptInterface
        fun shareCSV(filenamesJson: String) {
            runOnUiThread {
                try {
                    val filenames = org.json.JSONArray(filenamesJson)
                    val root = rootDir()
                    val uris = ArrayList<android.net.Uri>()
                    for (i in 0 until filenames.length()) {
                        val name = filenames.getString(i)
                        val dir = getDirForFile(root, name)
                        val file = java.io.File(dir, name)
                        if (file.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity, "$packageName.provider", file
                            )
                            uris.add(uri)
                        }
                    }
                    if (uris.isEmpty()) return@runOnUiThread
                    val intent = if (uris.size == 1) {
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/csv"
                            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Sdílet soubory"))
                } catch (e: Exception) {
                    Log.e(TAG, "shareCSV error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun saveImage(base64data: String, filename: String) {
            // Uloží PNG do složky heatmap (FileProvider ji pokrývá) a otevře share dialog
            thread {
                try {
                    val root = rootDir()
                    val dir = java.io.File(root, DIR_HEATMAP).also { it.mkdirs() }
                    val clean = if (base64data.contains(",")) base64data.substringAfter(",") else base64data
                    val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                    val file = java.io.File(dir, filename)
                    file.writeBytes(bytes)
                    Log.d(TAG, "Image saved: $filename → ${file.absolutePath}")
                    runOnUiThread {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity, "$packageName.provider", file
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(android.content.Intent.createChooser(intent, "Sdílet heatmapu"))
                        } catch (e: Exception) {
                            Log.e(TAG, "shareImage error: ${e.message}")
                        }
                        webView.evaluateJavascript("window.onCurieSaved && window.onCurieSaved('$filename')", null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "saveImage error: ${e.message}")
                    val msg = e.message?.replace("'", "") ?: "Chyba uložení"
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieError && window.onCurieError('$msg')", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun captureMap(filename: String) {
            // Nativní zachycení WebView — bez CORS omezení, plné rozlišení
            // Uloží PNG do heatmap/ složky a otevře share dialog
            runOnUiThread {
                try {
                    val bmp = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    webView.draw(canvas)
                    thread {
                        try {
                            val root = rootDir()
                            val dir  = java.io.File(root, DIR_HEATMAP).also { it.mkdirs() }
                            val file = java.io.File(dir, filename)
                            java.io.FileOutputStream(file).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Log.d(TAG, "captureMap saved: $filename → ${file.absolutePath}")
                            // Záloha do backup/heatmaps/
                            try {
                                val backupDir = getBackupDir("CF_heat_.csv")
                                backupDir?.let { bd ->
                                    val bf = java.io.File(bd, filename)
                                    java.io.FileOutputStream(bf).use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    Log.d(TAG, "captureMap backup: ${bf.absolutePath}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "captureMap backup error: ${e.message}")
                            }
                            runOnUiThread {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        this@MainActivity, "$packageName.provider", file
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(android.content.Intent.createChooser(intent, "Sdílet heatmapu"))
                                } catch (e: Exception) {
                                    Log.e(TAG, "captureMap share error: ${e.message}")
                                }
                                webView.evaluateJavascript(
                                    "window.onCurieSaved && window.onCurieSaved('$filename')", null
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "captureMap save error: ${e.message}")
                            val msg = e.message?.replace("'", "") ?: "Chyba uložení"
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.onCurieError && window.onCurieError('$msg')", null
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "captureMap bitmap error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun saveCSV(data: String, filename: String) {
            thread {
                try {
                    val root = rootDir()
                    val dir = getDirForFile(root, filename)
                    java.io.File(dir, filename).writeText(data)
                    Log.d(TAG, "CSV saved: $filename to ${dir.path}")
                    backupFile(filename, data)
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieSaved('$filename')", null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CSV save error: ${e.message}")
                    val msg = e.message?.replace("'", "") ?: "Chyba ulozeni"
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieError('$msg')", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun appendCSV(line: String, filename: String) {
            thread {
                try {
                    val root = rootDir()
                    val dir = getDirForFile(root, filename)
                    java.io.File(dir, filename).appendText(line)
                } catch (e: Exception) {
                    Log.e(TAG, "appendCSV error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Sdílet bod"))
                } catch (e: Exception) {
                    Log.e(TAG, "shareText error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun setBtDevice(name: String) {
            Log.d(TAG, "setBtDevice: $name")
            val intent = Intent(this@MainActivity, CurieService::class.java).apply {
                action = CurieService.ACTION_SET_BT_DEVICE
                putExtra(CurieService.EXTRA_BT_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        @JavascriptInterface
        fun getStatusBarHeight(): Int {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        }

        @JavascriptInterface
        fun getBondedDevices(): String {
            return try {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) return "[]"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return "[]"
                    }
                }

                val devices = adapter.bondedDevices
                    ?.filter { it.name != null }
                    ?.groupBy { it.name }
                    ?.flatMap { (name, list) ->
                        if (list.size == 1) {
                            listOf(name)
                        } else {
                            list.map { "$name (${it.address})" }
                        }
                    }
                    ?: emptyList()

                Log.d(TAG, "getBondedDevices: ${devices.size} devices")
                org.json.JSONArray(devices).toString()
            } catch (e: Exception) {
                Log.e(TAG, "getBondedDevices error: ${e.message}")
                "[]"
            }
        }

        @JavascriptInterface
        fun startBleScan() {
            Log.d(TAG, "startBleScan from JS")
            startService(Intent(this@MainActivity, CurieService::class.java).apply {
                action = CurieService.ACTION_WIZ_SCAN_START
            })
        }

        @JavascriptInterface
        fun stopBleScan() {
            Log.d(TAG, "stopBleScan from JS")
            startService(Intent(this@MainActivity, CurieService::class.java).apply {
                action = CurieService.ACTION_WIZ_SCAN_STOP
            })
        }

        @JavascriptInterface
        fun importCSV() {
            runOnUiThread {
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "text/csv",
                        "text/comma-separated-values",
                        "application/octet-stream",
                        "text/plain"
                    ))
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, IMPORT_CSV_REQUEST)
            }
        }

        @JavascriptInterface
        fun listROI(): String {
            return try {
                val root = rootDir()
                val dir = java.io.File(root, DIR_ROI)
                val files = dir.listFiles()
                    ?.filter { it.name.startsWith("CF_ROI_") && it.name.endsWith(".roi") }
                    ?.sortedBy { it.name }
                    ?.map { f ->
                        val obj = org.json.JSONObject()
                        obj.put("name", f.name)
                        obj.put("size", f.length())
                        obj.put("modified", f.lastModified())
                        obj
                    } ?: emptyList()
                org.json.JSONArray(files).toString()
            } catch (e: Exception) {
                Log.e(TAG, "listROI error: ${e.message}")
                "[]"
            }
        }

        @JavascriptInterface
        fun readROI(filename: String): String {
            return try {
                val root = rootDir()
                val file = java.io.File(java.io.File(root, DIR_ROI), filename)
                if (file.exists()) file.readText() else ""
            } catch (e: Exception) {
                Log.e(TAG, "readROI error: ${e.message}")
                ""
            }
        }

        @JavascriptInterface
        fun saveROI(data: String, filename: String) {
            thread {
                try {
                    val root = rootDir()
                    val dir = java.io.File(root, DIR_ROI).also { it.mkdirs() }
                    java.io.File(dir, filename).writeText(data)
                    Log.d(TAG, "ROI saved: $filename")
                    // Záloha do Documents/CurieFinder/backup/roi/
                    try {
                        val backupDir = getBackupDir(filename)
                        backupDir?.let { java.io.File(it, filename).writeText(data) }
                    } catch (e: Exception) {
                        Log.w(TAG, "ROI backup error: ${e.message}")
                    }
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieSaved && window.onCurieSaved('$filename')", null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "saveROI error: ${e.message}")
                    val msg = e.message?.replace("'", "") ?: "Chyba uložení ROI"
                    runOnUiThread {
                        webView.evaluateJavascript("window.onCurieError && window.onCurieError('$msg')", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun deleteROI(filename: String) {
            try {
                val root = rootDir()
                java.io.File(java.io.File(root, DIR_ROI), filename).delete()
                // Smazat zálohu
                try {
                    val backupDir = getBackupDir(filename)
                    backupDir?.let { java.io.File(it, filename).delete() }
                } catch (e: Exception) {
                    Log.w(TAG, "ROI backup delete error: ${e.message}")
                }
                Log.d(TAG, "ROI deleted: $filename")
            } catch (e: Exception) {
                Log.e(TAG, "deleteROI error: ${e.message}")
            }
        }

        @JavascriptInterface
        fun shareROI(filename: String) {
            runOnUiThread {
                try {
                    val root = rootDir()
                    val file = java.io.File(java.io.File(root, DIR_ROI), filename)
                    if (!file.exists()) return@runOnUiThread
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.provider", file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, filename)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Sdílet oblast ROI"))
                } catch (e: Exception) {
                    Log.e(TAG, "shareROI error: ${e.message}")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript(
            "if(window.clickScheduler){clearInterval(window.clickScheduler);window.clickScheduler=null;} " +
                    "if(window.stopTone) window.stopTone(); " +
                    "if(window.audioCtx){ try{ window.audioCtx.close(); }catch(e){} }",
            null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver) } catch (_: Exception) {}
    }
}