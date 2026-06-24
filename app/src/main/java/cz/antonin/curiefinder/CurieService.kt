package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlin.concurrent.thread

class CurieService : Service(), SerialInputOutputManager.Listener {

    companion object {
        const val TAG = "CurieService"
        const val CHANNEL_ID = "curiefinder_channel"
        const val NOTIF_ID = 1

        const val ACTION_CONNECT_BT    = "cz.antonin.curiefinder.CONNECT_BT"
        const val ACTION_CONNECT_USB   = "cz.antonin.curiefinder.CONNECT_USB"
        const val ACTION_DISCONNECT    = "cz.antonin.curiefinder.DISCONNECT"
        const val ACTION_START_GPS     = "cz.antonin.curiefinder.START_GPS"
        const val ACTION_STOP_GPS      = "cz.antonin.curiefinder.STOP_GPS"
        const val ACTION_STOP_SERVICE  = "cz.antonin.curiefinder.STOP_SERVICE"
        const val ACTION_SET_BT_DEVICE = "cz.antonin.curiefinder.SET_BT_DEVICE"
        const val ACTION_WIZ_SCAN_START = "cz.antonin.curiefinder.WIZ_SCAN_START"
        const val ACTION_WIZ_SCAN_STOP  = "cz.antonin.curiefinder.WIZ_SCAN_STOP"

        const val BROADCAST_DATA      = "cz.antonin.curiefinder.DATA"
        const val BROADCAST_CONNECTED = "cz.antonin.curiefinder.CONNECTED"
        const val BROADCAST_ERROR     = "cz.antonin.curiefinder.ERROR"
        const val BROADCAST_GPS       = "cz.antonin.curiefinder.GPS"
        const val BROADCAST_GPS_ERROR = "cz.antonin.curiefinder.GPS_ERROR"
        const val BROADCAST_SAVE_LOG  = "cz.antonin.curiefinder.SAVE_LOG"
        const val BROADCAST_WIZ_SCAN  = "cz.antonin.curiefinder.WIZ_SCAN"

        const val EXTRA_DATA        = "data"
        const val EXTRA_TYPE        = "type"
        const val EXTRA_MSG         = "msg"
        const val EXTRA_LAT         = "lat"
        const val EXTRA_LON         = "lon"
        const val EXTRA_ACC         = "acc"
        const val EXTRA_ALT         = "alt"
        const val EXTRA_BEARING     = "bearing"
        const val EXTRA_HAS_BEARING = "has_bearing"
        const val EXTRA_BT_NAME     = "bt_name"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val PREF_BT_NAME      = "bt_device_name"

        val CURIEFINDER_NAMES = setOf("CurieFinder", "CurieBT")
        fun isCurieFinderName(name: String?): Boolean =
            name != null && CURIEFINDER_NAMES.any { name.startsWith(it, ignoreCase = true) }

        val RAYSID_NAMES = setOf("Raysid", "Dozik", "RN_BLE", "RN487")
        fun isRaysidName(name: String?): Boolean =
            name != null && RAYSID_NAMES.any { name.startsWith(it, ignoreCase = true) }

        val RADIACODE_NAMES = setOf("RadiaCode", "RC-10")
        fun isRadiaCodeName(name: String?): Boolean =
            name != null && RADIACODE_NAMES.any { name.startsWith(it, ignoreCase = true) }

        val RADPRO_HINT_NAMES = setOf("HMSoft", "HM-10", "BT05", "MLT-BT05", "JDY-08", "JDY-09")
        fun isRadProHintName(name: String?): Boolean =
            name != null && RADPRO_HINT_NAMES.any { name.startsWith(it, ignoreCase = true) }

        // RadPro USB VIDs (decimal)
        val RADPRO_USB_VIDS = setOf(0x0483, 0x4348, 0x1A86, 0x10C4)  // STM32, WCH, CH340, CP210x
    }

    private var btSocket: BluetoothSocket? = null
    private var readingActive = false
    private var usbPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val sb = StringBuilder()
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var gpsRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRssiUpdate = 0L
    private val RSSI_INTERVAL_MS = 5000L
    private var lastVbatUpdate = 0L
    private val VBAT_INTERVAL_MS = 5500L   // ESP posílá VBAT každých 5s — přijmout každých 5.5s

    private var lastPktMs = 0L
    private var pktCount = 0
    private val MIN_PKT_INTERVAL_MS = 150L

    private var raysidBle: RaysidBleManager? = null
    @Volatile private var isRaysidMode = false
    @Volatile private var isNusMode    = false
    private var nusBleManager: NusBleManager? = null
    private var radProManager: RadProManager? = null
    @Volatile private var isRadProMode = false
    private var radiaCodeManager: RadiaCodeManager? = null
    @Volatile private var isRadiaCodeMode = false
    @Volatile private var btConnecting = false

    // BLE scan
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanActive = false
    private val BLE_SCAN_TIMEOUT_MS = 15000L
    @Volatile private var isScanning = false
    // Cíl aktuálního scanu — callback ignoruje ostatní typy zařízení
    enum class ScanTarget { RAYSID, RADIACODE, RADPRO, ALL }
    @Volatile private var bleScanTarget = ScanTarget.ALL

    // ── USB permission receiver (pro RadProManager callback) ──────────────────
    @Volatile private var radProUsbPending = false

    // ── Auto-connect: spustí se jednou při startu service ────────────────────
    @Volatile private var autoConnectDone = false
    // ── Debounce pro setBtDevice — poslední požadované jméno ─────────────────
    @Volatile private var pendingDeviceName = ""
    private val switchLock = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Připojování..."))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CurieFinder:WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
        Log.d(TAG, "Service created, wakelock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
        val killed = prefs.getBoolean("killed_by_swipe", false)
        val isFromApp = intent?.action == ACTION_CONNECT_BT || intent?.action == ACTION_CONNECT_USB

        if (isFromApp) {
            prefs.edit().putBoolean("killed_by_swipe", false).apply()
        } else if (killed) {
            Log.d(TAG, "Service restart prevented after swipe")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_CONNECT_BT -> {
                Log.d(TAG, "ACTION_CONNECT_BT: btConnecting=$btConnecting isRaysidMode=$isRaysidMode isRadProMode=$isRadProMode readingActive=$readingActive isScanning=$isScanning radProUsbPending=$radProUsbPending autoConnectDone=$autoConnectDone")
                // Přijímáme jen první volání — spustí auto-connect sekvenci jednou za běh service
                if (!autoConnectDone && !btConnecting && !isRaysidMode && !isNusMode && !isRadProMode && !isRadiaCodeMode && !readingActive && !radProUsbPending) {
                    autoConnectDone = true
                    btConnecting = true
                    thread { autoConnect() }
                }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d(TAG, "USB_DEVICE_ATTACHED — zkouším RadPro USB")
                if (!isRadProMode && !radProUsbPending) {
                    stopBleScan()
                    thread {
                        Thread.sleep(500)
                        if (tryConnectRadProUsb()) {
                            Log.d(TAG, "USB_DEVICE_ATTACHED: RadPro USB připojen")
                        }
                    }
                }
            }
            ACTION_CONNECT_USB  -> thread { connectUSB() }
            ACTION_DISCONNECT   -> disconnect()
            ACTION_START_GPS    -> startGps()
            ACTION_STOP_GPS     -> stopGps()
            ACTION_SET_BT_DEVICE -> {
                val name = intent.getStringExtra(EXTRA_BT_NAME) ?: return START_NOT_STICKY
                getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
                    .edit().putString(PREF_BT_NAME, name).apply()
                Log.d(TAG, "BT device set to: '$name'")
                if (name.isEmpty()) {
                    stopBleScan()
                    btConnecting = false
                    autoConnectDone = false
                    pendingDeviceName = ""
                    Log.d(TAG, "BT device cleared — going offline")
                } else {
                    // Uložit jako poslední požadavek — debounce zabrání duplicitním vláknům
                    pendingDeviceName = name
                    // Okamžitě zastavit scan + označit autoConnect jako hotový
                    // (aby případný connect() z JS nespustil nový autoConnect)
                    autoConnectDone = true
                    stopBleScan()
                    if (switchLock.compareAndSet(false, true)) {
                        thread {
                            Thread.sleep(150)
                            val target = pendingDeviceName
                            // Reset pending ihned po přečtení — uvolní isSwitchRequested pro switch vlákno
                            pendingDeviceName = ""
                            Log.d(TAG, "BT device switch → $target")
                            stopCurrentConnection()
                            btConnecting = true
                            try { connectSavedDevice(target) }
                            finally {
                                btConnecting = false
                                switchLock.set(false)
                            }
                        }
                    } else {
                        Log.d(TAG, "BT device switch pending — debounced: $name")
                    }
                }
            }
            ACTION_WIZ_SCAN_START -> {
                Log.d(TAG, "WIZ_SCAN_START")
                thread { startWizBleScan() }
            }
            ACTION_WIZ_SCAN_STOP -> {
                Log.d(TAG, "WIZ_SCAN_STOP")
                stopWizBleScan()
            }
            ACTION_STOP_SERVICE -> {
                saveLogIfActive()
                stopEverything()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveLogIfActive() {
        broadcast(BROADCAST_SAVE_LOG)
        Thread.sleep(500)
    }

    private fun stopEverything() {
        readingActive = false
        radProUsbPending = false
        stopBleScan()
        raysidBle?.disconnect()
        raysidBle = null
        isRaysidMode = false
        isNusMode    = false
        nusBleManager?.disconnect()
        nusBleManager = null
        radProManager?.disconnect()
        radProManager = null
        isRadProMode = false
        radiaCodeManager?.disconnect()
        radiaCodeManager = null
        isRadiaCodeMode = false
        try { usbIoManager?.stop() } catch (_: Exception) {}
        usbIoManager = null
        try { usbPort?.close() } catch (_: Exception) {}
        usbPort = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        stopGps()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID)
        // Zrušit všechny notifikace appky — odstraní badge na ikoně
        nm.cancelAll()
        Log.d(TAG, "Service stopped completely")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed – saving log and stopping")
        getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("killed_by_swipe", true).apply()
        saveLogIfActive()
        stopEverything()
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    // ── BLE Scan ─────────────────────────────────────────────────────────────

    // startBleScanForRaysid → nahrazeno startBleScanRaysid()

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        isScanning = false
        if (bleScanActive) {
            bleScanActive = false
            try { bleScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
            try { bleScanner?.stopScan(radProScanCallback) } catch (_: Exception) {}
            bleScanner = null
            Log.d(TAG, "BLE scan stopped")
        }
    }

    // ── Wizard BLE scan — hledání nových zařízení ────────────────────────────
    @Volatile private var wizScanActive = false
    private var wizScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val wizSeenAddresses = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    private fun startWizBleScan() {
        if (wizScanActive) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        wizScanner = adapter.bluetoothLeScanner ?: return
        wizScanActive = true
        wizSeenAddresses.clear()
        Log.d(TAG, "WIZ BLE scan started")
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        wizScanner?.startScan(null, settings, wizScanCallback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopWizBleScan()
        }, 10000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopWizBleScan() {
        if (!wizScanActive) return
        wizScanActive = false
        try { wizScanner?.stopScan(wizScanCallback) } catch (_: Exception) {}
        wizScanner = null
        Log.d(TAG, "WIZ BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    private val wizScanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            val addr = device.address
            if (addr in wizSeenAddresses) return
            wizSeenAddresses.add(addr)
            Log.d(TAG, "WIZ scan: name=$name addr=$addr rssi=${result.rssi}")
            broadcast(BROADCAST_WIZ_SCAN) {
                putExtra("wiz_name", name)
                putExtra("wiz_addr", addr)
                putExtra("wiz_rssi", result.rssi)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "WIZ BLE scan failed: $errorCode")
            wizScanActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            Log.d(TAG, "BLE scan result: name=$name addr=${device.address} rssi=${result.rssi}")
            if (result.rssi <= -99) return

            val target = bleScanTarget
            when {
                isRaysidName(name) && (target == ScanTarget.RAYSID || target == ScanTarget.ALL) -> {
                    Log.d(TAG, "Raysid found: $name ${device.address}")
                    stopBleScan()
                    connectRaysid(device, name ?: "Raysid")
                }
                isRadiaCodeName(name) && (target == ScanTarget.RADIACODE || target == ScanTarget.ALL) -> {
                    Log.d(TAG, "RadiaCode found: $name ${device.address}")
                    stopBleScan()
                    connectRadiaCode(device, name ?: "RadiaCode")
                }
                isRadProHintName(name) && (target == ScanTarget.RADPRO || target == ScanTarget.ALL) -> {
                    Log.d(TAG, "RadPro hint found: $name ${device.address}")
                    stopBleScan()
                    connectRadPro(device, name ?: "RadPro")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            bleScanActive = false
            Log.e(TAG, "BLE scan failed errorCode=$errorCode")
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BLE scan selhal ($errorCode)") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRaysid(device: BluetoothDevice, name: String) {
        Log.d(TAG, "connectRaysid: $name ${device.address}")
        isRaysidMode = true
        raysidBle = RaysidBleManager(
            context      = this,
            onLine       = { line ->
                broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, line) }
            },
            onConnect    = {
                saveRealDeviceName(name)
                broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, "BLE"); putExtra(EXTRA_DEVICE_NAME, name) }
                updateNotification("$name připojen")
                android.os.Handler(android.os.Looper.getMainLooper()).post { startGps() }
            },
            onDisconnect = { reason ->
                isRaysidMode = false
                autoConnectDone = false
                broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, reason) }
                updateNotification("Odpojeno")
            }
        )
        raysidBle!!.connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectRadiaCode(device: BluetoothDevice, name: String) {
        Log.d(TAG, "connectRadiaCode: $name ${device.address}")
        isRadiaCodeMode = true
        radiaCodeManager = RadiaCodeManager(
            context      = this,
            onLine       = { line ->
                broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, line) }
            },
            onConnect    = {
                saveRealDeviceName(name)
                broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, "BLE"); putExtra(EXTRA_DEVICE_NAME, name) }
                updateNotification("$name připojen")
                android.os.Handler(android.os.Looper.getMainLooper()).post { startGps() }
            },
            onDisconnect = { reason ->
                isRadiaCodeMode = false
                autoConnectDone = false
                radiaCodeManager = null
                broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, reason) }
                updateNotification("Odpojeno")
            }
        )
        radiaCodeManager!!.connect(device)
    }

    // ── RadPro BLE scan ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startBleScanForRadPro() {
        if (isScanning || isRadProMode) { Log.d(TAG, "startBleScanForRadPro: already scanning or connected, ignored"); return }

        // Nejdřív zkusit USB — pokud USB zařízení nalezeno (i čekání na permission), nespouštět BLE
        if (tryConnectRadProUsb()) {
            Log.d(TAG, "RadPro USB found — BLE scan skipped")
            return
        }

        // USB není k dispozici — jdeme na BLE
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT nepodporovan") }; return
        }
        if (!adapter.isEnabled) {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT vypnuto") }; return
        }
        bleScanner = adapter.bluetoothLeScanner ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BLE scanner nedostupny") }; return
        }
        bleScanTarget = ScanTarget.RADPRO
        isScanning = true
        bleScanActive = true
        updateNotification("Hledám RadPro BLE...")
        Log.d(TAG, "BLE scan started for RadPro")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, radProScanCallback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (bleScanActive) {
                stopBleScan()
                broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "RadPro nenalezen (timeout)") }
            }
        }, BLE_SCAN_TIMEOUT_MS)
    }

    /**
     * Zkusí najít FNIRSI GC-01 na USB.
     * Pokud zařízení nalezeno (i bez permission) → vrátí true a spustí USB flow.
     * Pokud zařízení není → vrátí false → volající spustí BLE scan.
     */
    private fun tryConnectRadProUsb(): Boolean {
        val usbMgr = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbMgr.deviceList.values.firstOrNull { isRadProUsbDevice(it) }
        if (device == null) {
            Log.d(TAG, "tryConnectRadProUsb: no USB device found")
            return false
        }
        Log.d(TAG, "tryConnectRadProUsb: found ${device.productName} VID=${device.vendorId}")
        connectRadProUsb()
        return true
    }

    private fun isRadProUsbDevice(device: UsbDevice): Boolean {
        if (device.vendorId !in RADPRO_USB_VIDS) return false
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_CDC_DATA ||
                iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_COMM) return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private val radProScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            Log.d(TAG, "RadPro scan: name=$name addr=${device.address} rssi=${result.rssi}")
            if (isRadProHintName(name) && result.rssi > -99) {
                Log.d(TAG, "RadPro hint device found: $name")
                stopBleScan()
                connectRadPro(device, name ?: "RadPro")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            bleScanActive = false
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "RadPro BLE scan selhal ($errorCode)") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRadPro(device: BluetoothDevice, name: String) {
        Log.d(TAG, "connectRadPro BLE: $name ${device.address}")
        // isRadProMode se nastaví true až v onConnect callbacku
        radProManager = RadProManager(
            context      = this,
            onLine       = { line ->
                broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, line) }
            },
            onConnect    = { transport ->
                isRadProMode = true
                saveRealDeviceName(name)
                broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, transport); putExtra(EXTRA_DEVICE_NAME, name) }
                updateNotification("$name připojen ($transport)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { startGps() }
            },
            onDisconnect = { reason ->
                isRadProMode = false
                radProUsbPending = false
                radProManager = null
                autoConnectDone = false
                broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, reason) }
                updateNotification("Odpojeno")
            }
        )
        radProManager!!.connectBle(device)
    }

    private fun connectRadProUsb() {
        Log.d(TAG, "connectRadProUsb — spouštím RadProManager.connectUsb()")
        radProUsbPending = true
        // isRadProMode se nastaví true až v onConnect callbacku
        radProManager = RadProManager(
            context      = this,
            onLine       = { line ->
                broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, line) }
            },
            onConnect    = { transport ->
                radProUsbPending = false
                isRadProMode = true
                broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, transport) }
                updateNotification("FNIRSI připojen ($transport)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { startGps() }
                Log.d(TAG, "RadPro USB connected OK, transport=$transport")
            },
            onDisconnect = { reason ->
                radProUsbPending = false
                isRadProMode = false
                radProManager = null
                Log.d(TAG, "RadPro USB disconnected: $reason")
                // Po USB timeoutu zkusit znovu připojit
                val isTimeout = reason.contains("Timeout", ignoreCase = true)
                if (isTimeout) {
                    Log.d(TAG, "RadPro USB timeout — auto-reconnect za 2s")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!isRadProMode && !radProUsbPending) {
                            Log.d(TAG, "RadPro USB auto-reconnect")
                            connectRadProUsb()
                        }
                    }, 2000)
                } else {
                    broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, reason) }
                    updateNotification("Odpojeno")
                }
            }
        )
        radProManager!!.connectUsb()
    }

    // ── SPP (klasický BT) ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectNusBle(savedName: String) {
        // Formát: "BLE:jméno:MAC" kde MAC = XX:XX:XX:XX:XX:XX
        val withoutPrefix = savedName.removePrefix("BLE:")
        // Najít MAC — posledních 17 znaků pokud odpovídá formátu XX:XX:XX:XX:XX:XX
        val macRegex = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        val macMatch = macRegex.find(withoutPrefix)
        val mac  = macMatch?.value ?: ""
        val name = if (mac.isNotEmpty()) withoutPrefix.removeSuffix(":$mac") else withoutPrefix
        Log.d(TAG, "connectNusBle: name=$name mac=$mac")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        // Najít zařízení podle MAC nebo jména v bonded
        val device = if (mac.isNotEmpty()) {
            try { adapter.getRemoteDevice(mac) } catch (e: Exception) { null }
        } else {
            adapter.bondedDevices?.firstOrNull { it.name == name }
        }
        if (device == null) {
            Log.e(TAG, "NUS device not found: $name $mac")
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "NUS zařízení $name nenalezeno") }
            return
        }
        nusBleManager = NusBleManager(
            context = this,
            onLine = { line ->
                broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, line) }
            },
            onConnect = {
                isNusMode = true
                Log.d(TAG, "NUS connected: $name")
                saveRealDeviceName(name)
                broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, "NUS"); putExtra(EXTRA_DEVICE_NAME, name) }
                updateNotification("NUS: $name")
            },
            onDisconnect = { reason ->
                isNusMode = false
                nusBleManager = null
                Log.d(TAG, "NUS disconnected: $reason")
                // "Odpojeno" = záměrné odpojení uživatelem → reset autoConnectDone
                // ostatní důvody = NusBleManager zkusil reconnect a vzdal to → informovat JS
                if (reason == "Odpojeno") {
                    autoConnectDone = false
                } else {
                    autoConnectDone = false
                    broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, reason) }
                }
                updateNotification("Odpojeno")
            }
        )
        nusBleManager!!.connect(device)
    }

    // ── Nová logika připojení ─────────────────────────────────────────────────

    /**
     * Zastaví aktuální BT/BLE spojení bez broadcastu — interní použití před přepnutím zařízení.
     */
    private fun stopCurrentConnection() {
        readingActive = false
        isRaysidMode = false
        isNusMode = false
        isRadProMode = false
        radProUsbPending = false
        try { raysidBle?.disconnect() } catch (_: Exception) {}
        raysidBle = null
        try { nusBleManager?.disconnect() } catch (_: Exception) {}
        nusBleManager = null
        try { radProManager?.disconnect() } catch (_: Exception) {}
        radProManager = null
        try { radiaCodeManager?.disconnect() } catch (_: Exception) {}
        radiaCodeManager = null
        isRadiaCodeMode = false
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
    }

    /**
     * Auto-connect při startu aplikace:
     * 1. Zkus poslední uložené zařízení
     * 2. Selže → tiše projdi všechna spárovaná (SPP) + BLE scan paralelně
     * 3. Vše selže → zobraz wizard
     */

    /** Uloží skutečné jméno zařízení do prefs — přepíše "⚡ Hledat..." pokud tam bylo */
    private fun saveRealDeviceName(name: String) {
        if (name.isEmpty()) return
        val prefs = getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_BT_NAME, name).apply()
        Log.d(TAG, "saveRealDeviceName: $name")
    }

    private fun autoConnect() {
        try {
            val prefs = getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString(PREF_BT_NAME, null)
            Log.d(TAG, "autoConnect: savedName=$savedName")

            // 1. Zkus poslední uložené zařízení
            if (!savedName.isNullOrEmpty()) {
                if (isSwitchRequested(savedName)) { Log.d(TAG, "autoConnect: switch before saved device"); return }
                val ok = connectSavedDevice(savedName)
                if (ok) return
                if (isSwitchRequested(savedName)) { Log.d(TAG, "autoConnect: switch after saved device failed"); return }
                Log.d(TAG, "autoConnect: saved device failed")
            }

            if (isConnectedAny()) return

            // 2. Žádné uložené zařízení nebo připojení selhalo → wizard
            // NESPOUŠTÍME tiché zkoušení jiných SPP zařízení — uživatel musí vybrat sám
            Log.d(TAG, "autoConnect: no saved device or connect failed → wizard")
            showWizard()
        } finally {
            btConnecting = false
        }
    }

    private fun isConnectedAny(): Boolean =
        isRaysidMode || isNusMode || isRadProMode || isRadiaCodeMode || readingActive

    /**
     * Vrátí true pokud uživatel mezitím vybral JINÉ zařízení než currentName.
     * Vždy předávat currentName — nikdy nevolat bez parametru.
     */
    private fun isSwitchRequested(currentName: String): Boolean {
        val pending = pendingDeviceName
        // Přerušit pouze pokud pending není prázdný A liší se od aktuálního cíle
        return pending.isNotEmpty() && pending != currentName
    }

    /**
     * Připojí konkrétně uložené zařízení podle jména.
     * Vrací true pokud připojení proběhlo (nebo bylo zahájeno — BLE).
     */
    @SuppressLint("MissingPermission")
    private fun connectSavedDevice(savedName: String): Boolean {
        Log.d(TAG, "connectSavedDevice: $savedName")

        if (savedName.startsWith("BLE:")) {
            Log.d(TAG, "connectSavedDevice: BLE NUS → $savedName")
            connectNusBle(savedName)
            // NUS connect je async — čekáme na výsledek
            val deadline = System.currentTimeMillis() + 12000L
            while (System.currentTimeMillis() < deadline) {
                if (isNusMode) return true
                Thread.sleep(300)
            }
            Log.d(TAG, "connectSavedDevice: NUS timeout")
            nusBleManager?.disconnect()
            nusBleManager = null
            return false
        }

        if (isRaysidName(savedName) || savedName == "⚡ Hledat Raysid BLE") {
            Log.d(TAG, "connectSavedDevice: Raysid BLE scan")
            startBleScanRaysid()
            val deadline = System.currentTimeMillis() + BLE_SCAN_TIMEOUT_MS + 1000L
            while (System.currentTimeMillis() < deadline) {
                if (isRaysidMode) return true
                Thread.sleep(300)
            }
            Log.d(TAG, "connectSavedDevice: Raysid timeout")
            stopBleScan()
            return false
        }

        if (isRadiaCodeName(savedName) || savedName == "⚡ Hledat RadiaCode BLE") {
            Log.d(TAG, "connectSavedDevice: RadiaCode BLE scan")
            startBleScanRadiaCode()
            val deadline = System.currentTimeMillis() + BLE_SCAN_TIMEOUT_MS + 1000L
            while (System.currentTimeMillis() < deadline) {
                if (isRadiaCodeMode) return true
                Thread.sleep(300)
            }
            Log.d(TAG, "connectSavedDevice: RadiaCode timeout")
            stopBleScan()
            return false
        }

        if (isRadProHintName(savedName) || savedName == "⚡ Hledat RadPro BLE/USB" || savedName == "⚡ Hledat RadPro BLE") {
            Log.d(TAG, "connectSavedDevice: RadPro USB+BLE")
            startBleScanForRadPro()
            val deadline = System.currentTimeMillis() + BLE_SCAN_TIMEOUT_MS + 1000L
            while (System.currentTimeMillis() < deadline) {
                if (isRadProMode) return true
                Thread.sleep(300)
            }
            Log.d(TAG, "connectSavedDevice: RadPro timeout")
            stopBleScan()
            return false
        }

        // SPP
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val device = adapter?.bondedDevices?.firstOrNull { it.name == savedName }
        if (device == null) {
            Log.d(TAG, "connectSavedDevice: SPP device not found: $savedName")
            return false
        }
        val ok = trySppConnect(device)
        if (ok) {
            // Ještě jednou zkontrolovat — přepnutí mohlo přijít během posledního pokusu
            if (isSwitchRequested(savedName)) {
                Log.d(TAG, "connectSavedDevice: switch after connect — closing $savedName")
                try { btSocket?.close() } catch (_: Exception) {}
                btSocket = null
                return false
            }
            broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, "BT"); putExtra(EXTRA_DEVICE_NAME, savedName) }
            updateNotification("$savedName připojen")
            android.os.Handler(android.os.Looper.getMainLooper()).post { startGps() }
            startSppReadLoop()
        }
        return ok
    }

    /**
     * Pokusí se připojit SPP socket — 3 pokusy, vrací true při úspěchu.
     * Nespouští read loop — volající to udělá sám.
     */
    @SuppressLint("MissingPermission")
    private fun trySppConnect(device: android.bluetooth.BluetoothDevice): Boolean {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        for (attempt in 1..3) {
            // Přerušit pokud uživatel mezitím vybral jiné zařízení
            if (isSwitchRequested(device.name ?: "")) {
                Log.d(TAG, "trySppConnect: switch requested — aborting ${device.name}")
                return false
            }
            var socket: BluetoothSocket? = null
            try {
                socket = if (attempt <= 2)
                    device.createRfcommSocketToServiceRecord(uuid)
                else
                    device.createInsecureRfcommSocketToServiceRecord(uuid)
                socket.connect()
                btSocket = socket
                Log.d(TAG, "SPP connected: ${device.name} attempt $attempt")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "SPP attempt $attempt failed: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                if (attempt < 3) Thread.sleep(300)
            }
        }
        return false
    }

    /**
     * Spustí read loop pro aktuální btSocket. Blokuje vlákno dokud je spojení aktivní.
     */
    private fun startSppReadLoop() {
        lastRssiUpdate = 0L
        lastPktMs = 0L
        pktCount = 0
        readingActive = true
        var validPktCount = 0   // počet platných paketů od připojení
        try {
            val input = btSocket!!.inputStream
            val btSb = StringBuilder()
            while (readingActive) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val line = btSb.toString().trim()
                    btSb.clear()
                    if (line.isNotEmpty()) Log.d(TAG, "BT RX: $line")
                    if (line.contains("CPS=")) {
                        val now = System.currentTimeMillis()
                        if (now - lastPktMs >= MIN_PKT_INTERVAL_MS) {
                            pktCount++
                            validPktCount++
                            lastPktMs = now
                            // Po 10 platných paketech — zařízení ověřeno, uložit do prefs
                            if (validPktCount == 10) {
                                val devName = btSocket?.remoteDevice?.name ?: ""
                                if (devName.isNotEmpty()) {
                                    getSharedPreferences("curie_prefs", Context.MODE_PRIVATE)
                                        .edit().putString(PREF_BT_NAME, devName).apply()
                                    Log.d(TAG, "SPP device verified and saved: $devName")
                                }
                            }
                            // VBAT posílat max 1× za 5.5s — odfiltrovat z řádku jinak
                            val sendLine = if (now - lastVbatUpdate >= VBAT_INTERVAL_MS) {
                                lastVbatUpdate = now
                                line
                            } else {
                                line.replace(Regex("""\s*VBAT=\d+mV"""), "")
                            }
                            broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, sendLine) }
                            sendRssiIfNeeded()
                        }
                    }
                } else {
                    btSb.append(b.toChar())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BT read error: ${e.message}")
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, e.message?.replace("'", "") ?: "Chyba BT") }
        } finally {
            readingActive = false
            // Reset autoConnectDone — umožní JS spustit reconnect přes ACTION_CONNECT_BT
            autoConnectDone = false
        }
        updateNotification("Odpojeno")
    }

    /**
     * Spustí BLE scan pro Raysid i RadPro najednou — jeden scanner, jeden callback.
     */
    @SuppressLint("MissingPermission")
    private fun startBleScanAll() {
        if (isScanning) return
        bleScanTarget = ScanTarget.ALL
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner ?: return
        isScanning = true
        bleScanActive = true
        updateNotification("Hledám BLE zařízení...")
        Log.d(TAG, "BLE scan ALL started")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, bleScanCallback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (bleScanActive) {
                stopBleScan()
                Log.d(TAG, "BLE scan ALL timeout")
            }
        }, BLE_SCAN_TIMEOUT_MS)
    }

    /**
     * Spustí BLE scan jen pro Raysid.
     */
    @SuppressLint("MissingPermission")
    private fun startBleScanRaysid() {
        if (isScanning || isRaysidMode) return
        bleScanTarget = ScanTarget.RAYSID
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT nepodporovan") }; return
        }
        if (!adapter.isEnabled) { broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT vypnuto") }; return }
        bleScanner = adapter.bluetoothLeScanner ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BLE scanner nedostupny") }; return
        }
        isScanning = true
        bleScanActive = true
        updateNotification("Hledám Raysid BLE...")
        Log.d(TAG, "BLE scan Raysid started")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, bleScanCallback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (bleScanActive) { stopBleScan(); Log.d(TAG, "BLE scan Raysid timeout") }
        }, BLE_SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanRadiaCode() {
        if (isScanning || isRadiaCodeMode) return
        bleScanTarget = ScanTarget.RADIACODE
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT nepodporovan") }; return
        }
        if (!adapter.isEnabled) { broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT vypnuto") }; return }
        bleScanner = adapter.bluetoothLeScanner ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BLE scanner nedostupny") }; return
        }
        isScanning = true
        bleScanActive = true
        updateNotification("Hledám RadiaCode BLE...")
        Log.d(TAG, "BLE scan RadiaCode started")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, bleScanCallback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (bleScanActive) { stopBleScan(); Log.d(TAG, "BLE scan RadiaCode timeout") }
        }, BLE_SCAN_TIMEOUT_MS)
    }

    private fun showWizard() {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val bonded = adapter?.bondedDevices?.map { it.name } ?: emptyList()
        broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "BT_SELECT:" + bonded.joinToString("|")) }
    }

    // ── Staré connectBT() ponecháno jako alias pro zpětnou kompatibilitu ─────
    private fun connectBT() {
        if (btConnecting) { Log.d(TAG, "connectBT() ignored — already connecting"); return }
        btConnecting = true
        try { autoConnect() } finally { btConnecting = false }
    }

    @SuppressLint("MissingPermission")
    private fun readBtRssi(): Int? {
        val socket = btSocket ?: return null
        return try {
            val method = socket.javaClass.getDeclaredMethod("getRssi")
            method.isAccessible = true
            (method.invoke(socket) as? Int)
        } catch (e1: Exception) {
            try {
                val field = socket.javaClass.getDeclaredField("mSocket")
                field.isAccessible = true
                val nativeSocket = field.get(socket)
                val method = nativeSocket?.javaClass?.getDeclaredMethod("getRssi")
                method?.isAccessible = true
                method?.invoke(nativeSocket) as? Int
            } catch (e2: Exception) { null }
        }
    }

    private fun sendRssiIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRssiUpdate < RSSI_INTERVAL_MS) return
        lastRssiUpdate = now
        val rssi = readBtRssi()
        if (rssi != null) {
            broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, "RSSI=$rssi") }
        }
    }

    // ── USB (klasický UsbSerial — pro jiná zařízení, ne RadPro) ──────────────

    private fun connectUSB() {
        val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "USB zarizeni nenalezeno") }
            return
        }
        val connection = usbManager.openDevice(drivers[0].device) ?: run {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "Nelze otevrit USB") }
            return
        }
        val port = drivers[0].ports[0]
        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, e.message?.replace("'", "") ?: "USB chyba") }
            return
        }
        usbPort = port
        sb.clear()
        val ioManager = SerialInputOutputManager(port, this)
        usbIoManager = ioManager
        ioManager.start()
        broadcast(BROADCAST_CONNECTED) { putExtra(EXTRA_TYPE, "USB") }
        updateNotification("USB připojeno")
        android.os.Handler(android.os.Looper.getMainLooper()).post { startGps() }
    }

    override fun onNewData(data: ByteArray) {
        if (isRadProMode) return  // RadProManager zpracovává data sám
        for (b in data) {
            val ch = b.toInt() and 0xFF
            if (ch == '\n'.code) {
                val line = sb.toString().trim()
                sb.clear()
                if (line.contains("CPS=")) {
                    val now = System.currentTimeMillis()
                    pktCount++
                    lastPktMs = now
                    broadcast(BROADCAST_DATA) { putExtra(EXTRA_DATA, line) }
                }
            } else if (ch >= 32) sb.append(ch.toChar())
        }
    }

    override fun onRunError(e: Exception) {
        broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "USB preruseno") }
        updateNotification("USB přerušeno")
    }

    private fun disconnect() {
        if (isRaysidMode) {
            stopBleScan()
            raysidBle?.disconnect()
            raysidBle = null
            isRaysidMode = false
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "Odpojeno") }
            updateNotification("Odpojeno")
            return
        }
        if (isRadiaCodeMode) {
            stopBleScan()
            radiaCodeManager?.disconnect()
            radiaCodeManager = null
            isRadiaCodeMode = false
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "Odpojeno") }
            updateNotification("Odpojeno")
            return
        }
        if (isRadProMode || radProUsbPending) {
            stopBleScan()
            radProManager?.disconnect()
            radProManager = null
            isRadProMode = false
            radProUsbPending = false
            broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "Odpojeno") }
            updateNotification("Odpojeno")
            return
        }
        readingActive = false
        try { usbIoManager?.stop() } catch (_: Exception) {}
        usbIoManager = null
        try { usbPort?.close() } catch (_: Exception) {}
        usbPort = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        broadcast(BROADCAST_ERROR) { putExtra(EXTRA_MSG, "Odpojeno") }
        updateNotification("Odpojeno")
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsRunning) return  // už běží
        val hasPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            broadcast(BROADCAST_GPS_ERROR) { putExtra(EXTRA_MSG, "GPS povoleni chybi") }
            return
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gpsListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                broadcast(BROADCAST_GPS) {
                    putExtra(EXTRA_LAT, loc.latitude)
                    putExtra(EXTRA_LON, loc.longitude)
                    putExtra(EXTRA_ACC, loc.accuracy.toDouble())
                    putExtra(EXTRA_ALT, loc.altitude)
                    // Bearing: -1 pokud není k dispozici, jinak skutečný úhel
                    putExtra(EXTRA_BEARING, if (loc.hasBearing()) loc.bearing.toDouble() else -1.0)
                    putExtra(EXTRA_HAS_BEARING, loc.hasBearing())
                    // Rychlost v m/s
                    putExtra("speed", if (loc.hasSpeed()) loc.speed.toDouble() else -1.0)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                broadcast(BROADCAST_GPS_ERROR) { putExtra(EXTRA_MSG, "GPS vypnuto") }
            }
        }
        try {
            val provider = if (locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            locationManager!!.requestLocationUpdates(provider, 1000L, 0f, gpsListener!!)
            gpsRunning = true
            Log.d(TAG, "GPS started")
        } catch (e: Exception) {
            Log.e(TAG, "GPS error: ${e.message}")
        }
    }

    private fun stopGps() {
        try { gpsListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
        gpsListener = null
        gpsRunning = false
        Log.d(TAG, "GPS stopped")
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CurieFinder",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "CurieFinder běží na pozadí" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, CurieService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, Class.forName("$packageName.MainActivity"))
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CurieFinder")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Odpojit", stopPending)
            .setOngoing(true)
            .build()
    }

    fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(status))
    }

    private fun broadcast(action: String, block: Intent.() -> Unit = {}) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action).apply { block() })
    }
}