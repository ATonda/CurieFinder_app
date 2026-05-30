package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.TimeZone
import java.util.UUID

/**
 * RadProManager – univerzální komunikace s FNIRSI GC-01 s RadPro firmware
 *
 * Podporuje dva transporty:
 *   1. USB CDC  — Android USB Host, kabel USB-C → USB-C
 *   2. BLE UART — HM-10 nebo ESP32-S3-Zero jako BLE bridge
 *
 * Výstupní formát:
 *   USB: CPS=X.X VBAT=XmV RATE=X.XXX DEVICE=<deviceId>
 *   BLE: CPS=X.X VBAT=XmV RSSI=-65 RATE=X.XXX DEVICE=<deviceId>
 */
@SuppressLint("MissingPermission")
class RadProManager(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onConnect: (transport: String) -> Unit = {},
    private val onDisconnect: (reason: String) -> Unit = {}
) {
    companion object {
        const val TAG = "RadProManager"

        // BLE UUIDs — HM-10
        val HM10_SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val HM10_CHAR_UUID    = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

        // BLE UUIDs — Nordic UART Service
        val NUS_SERVICE_UUID  = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID       = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_UUID       = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID         = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // USB
        const val ACTION_USB_PERMISSION  = "cz.antonin.curiefinder.USB_PERMISSION"
        const val USB_BAUD_RATE          = 115200
        const val USB_READ_TIMEOUT_MS    = 100
        const val USB_WRITE_TIMEOUT_MS   = 200
        val RADPRO_USB_VIDS              = setOf(0x0483, 0x4348, 0x1A86, 0x10C4)  // STM32, WCH, CH340, CP210x

        // Intervaly
        const val POLL_INTERVAL_MS        = 1000L
        const val BATTERY_INTERVAL_MS     = 60_000L
        const val RSSI_INTERVAL_MS        = 5_000L
        const val TIME_SYNC_INTERVAL_MS      = 30 * 60 * 1000L  // 30 minut
        const val SENSITIVITY_INTERVAL_MS   = 3 * 60 * 1000L   // 3 minuty
        const val CMD_TIMEOUT_MS            = 2000L
        const val UINT32_MAX              = 4294967295L
    }

    // ── Stav ─────────────────────────────────────────────────────────────────

    enum class Transport { NONE, USB, BLE }

    @Volatile var transport = Transport.NONE
    @Volatile var connected = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Měření
    private var lastPulseCount   = -1L
    private var lastPulseTime    = -1L
    private var lastCps          = 0.0
    private var lastRate         = 0.0   // µSv/h = tubeRate / tubeSensitivity
    private var lastRawRate      = 0.0   // CPM z GET tubeRate
    private var tubeSensitivity  = 153.8 // výchozí, přepsáno při init a každé 3 minuty
    private var lastSensitivityTs = 0L
    private var lastVbat         = 0.0
    private var lastRssi         = -99
    private var deviceId         = ""    // z GET deviceId, pro CSV hlavičku

    // Fronta příkazů
    private data class PendingCommand(val cmd: String, val onResponse: (String) -> Unit, val critical: Boolean = true)
    private val cmdQueue     = ArrayDeque<PendingCommand>()
    private var cmdInFlight: PendingCommand? = null
    private val timeoutToken = Object()
    private var consecutiveTimeouts = 0
    private val MAX_CONSECUTIVE_TIMEOUTS = 3

    // RX buffer
    private val rxBuffer = StringBuilder()

    // ── USB ───────────────────────────────────────────────────────────────────

    private var usbManager:     UsbManager?          = null
    private var usbDevice:      UsbDevice?           = null
    private var usbConnection:  UsbDeviceConnection? = null
    private var usbInterface:   UsbInterface?        = null
    private var usbEndpointIn:  UsbEndpoint?         = null
    private var usbEndpointOut: UsbEndpoint?         = null
    private var usbReadThread:  Thread?              = null
    @Volatile private var usbRunning = false

    @Volatile private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device  = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    Log.d(TAG, "USB permission granted: ${device.productName}")
                    openUsbDevice(device)
                } else {
                    onDisconnect("RadPro USB: oprávnění zamítnuto")
                }
            }
        }
    }

    // ── BLE ───────────────────────────────────────────────────────────────────

    private var gatt:      BluetoothGatt?               = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isNus = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun connectUsb() {
        if (!tryConnectUsb()) onDisconnect("RadPro: FNIRSI přes USB nenalezeno")
    }

    fun connectBle(device: BluetoothDevice) {
        Log.d(TAG, "connectBle: ${device.name} ${device.address}")
        transport = Transport.BLE
        disconnectBle()
        mainHandler.postDelayed({
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, 300)
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        connected = false
        transport = Transport.NONE
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(rssiToken)
        cmdQueue.clear()
        cmdInFlight    = null
        lastPulseCount = -1L
        lastPulseTime  = -1L
        rxBuffer.clear()
        disconnectUsb()
        disconnectBle()
    }

    /** Vrátí deviceId string pro CSV hlavičku */
    fun getDeviceId(): String = deviceId

    // ── USB připojení ─────────────────────────────────────────────────────────

    private fun tryConnectUsb(): Boolean {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager = mgr
        val device = mgr.deviceList.values.firstOrNull { isRadProDevice(it) }
        if (device == null) { Log.d(TAG, "No RadPro USB device found"); return false }
        Log.d(TAG, "RadPro USB device found: ${device.productName} VID=${device.vendorId} PID=${device.productId}")
        if (!mgr.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission")
            context.registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            usbReceiverRegistered = true
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mgr.requestPermission(device, pi)
            return true
        }
        openUsbDevice(device)
        return true
    }

    private fun isRadProDevice(device: UsbDevice): Boolean {
        if (device.vendorId !in RADPRO_USB_VIDS) return false
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                iface.interfaceClass == UsbConstants.USB_CLASS_COMM) return true
        }
        return false
    }

    private fun openUsbDevice(device: UsbDevice) {
        val mgr = usbManager ?: return
        Log.d(TAG, "Opening USB device: ${device.productName}")
        var cdcInterface: UsbInterface? = null
        var epIn:  UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN)  epIn  = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    }
                }
                if (epIn != null && epOut != null) { cdcInterface = iface; break }
            }
        }
        if (cdcInterface == null || epIn == null || epOut == null) {
            transport = Transport.NONE
            onDisconnect("RadPro USB: CDC interface nenalezena"); return
        }
        val conn = mgr.openDevice(device) ?: run {
            transport = Transport.NONE
            onDisconnect("RadPro USB: nelze otevřít zařízení"); return
        }
        if (!conn.claimInterface(cdcInterface, true)) {
            transport = Transport.NONE
            conn.close(); onDisconnect("RadPro USB: nelze claim interface"); return
        }
        setUsbLineCoding(conn, USB_BAUD_RATE)
        usbDevice = device; usbConnection = conn; usbInterface = cdcInterface
        usbEndpointIn = epIn; usbEndpointOut = epOut; transport = Transport.USB
        usbRunning = true
        usbReadThread = Thread { usbReadLoop() }.also { it.start() }
        Log.d(TAG, "USB connected, starting init")
        onInitReady()
    }

    private fun setUsbLineCoding(conn: UsbDeviceConnection, baud: Int) {
        val lc = ByteArray(7)
        lc[0] = (baud and 0xFF).toByte()
        lc[1] = ((baud shr 8)  and 0xFF).toByte()
        lc[2] = ((baud shr 16) and 0xFF).toByte()
        lc[3] = ((baud shr 24) and 0xFF).toByte()
        lc[4] = 0; lc[5] = 0; lc[6] = 8
        conn.controlTransfer(0x21, 0x20, 0, 0, lc, lc.size, USB_WRITE_TIMEOUT_MS)
        conn.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, USB_WRITE_TIMEOUT_MS)
        Log.d(TAG, "USB line coding set: $baud 8N1")
    }

    private fun usbReadLoop() {
        val buf = ByteArray(64)
        Log.d(TAG, "USB read thread started")
        while (usbRunning) {
            val conn = usbConnection ?: break
            val ep   = usbEndpointIn  ?: break
            val len  = conn.bulkTransfer(ep, buf, buf.size, USB_READ_TIMEOUT_MS)
            if (len > 0) { val s = String(buf, 0, len); mainHandler.post { onRxData(s) } }
        }
        Log.d(TAG, "USB read thread stopped")
    }

    private fun disconnectUsb() {
        usbRunning = false
        try { usbReadThread?.interrupt() } catch (_: Exception) {}
        usbReadThread = null
        try { usbConnection?.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        usbConnection = null; usbInterface = null
        usbEndpointIn = null; usbEndpointOut = null; usbDevice = null
        if (usbReceiverRegistered) {
            try { context.unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }
    }

    // ── BLE připojení ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "BLE onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    mainHandler.postDelayed({ gatt.discoverServices() }, 600)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    mainHandler.removeCallbacksAndMessages(null)
                    cmdQueue.clear(); cmdInFlight = null
                    val reason = when (status) {
                        0    -> "Odpojeno"
                        8    -> "RadPro BLE: timeout"
                        19   -> "RadPro BLE: odpojeno zařízením"
                        133  -> "RadPro BLE: GATT error 133"
                        else -> "RadPro BLE: chyba $status"
                    }
                    try { gatt.close() } catch (_: Exception) {}
                    this@RadProManager.gatt = null
                    onDisconnect(reason)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { onDisconnect("RadPro BLE: discovery chyba ($status)"); return }
            val hm10Char  = gatt.getService(HM10_SERVICE_UUID)?.getCharacteristic(HM10_CHAR_UUID)
            val nusTxChar = gatt.getService(NUS_SERVICE_UUID)?.getCharacteristic(NUS_TX_UUID)
            val nusRxChar = gatt.getService(NUS_SERVICE_UUID)?.getCharacteristic(NUS_RX_UUID)
            when {
                hm10Char != null -> { isNus = false; writeChar = hm10Char; enableBleNotify(gatt, hm10Char) }
                nusTxChar != null && nusRxChar != null -> { isNus = true; writeChar = nusRxChar; enableBleNotify(gatt, nusTxChar) }
                else -> onDisconnect("RadPro BLE: UART service nenalezena")
            }
        }

        private fun enableBleNotify(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } else onInitReady()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { onDisconnect("RadPro BLE: CCCD selhal ($status)"); return }
            onInitReady()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onRxData(String(value))
        }

        @Suppress("DEPRECATION") @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onRxData(String(characteristic.value ?: return))
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) { lastRssi = rssi; emitLine() }
            if (connected && transport == Transport.BLE) scheduleRssi()
        }
    }

    private fun disconnectBle() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close()     } catch (_: Exception) {}
        gatt = null; writeChar = null
    }

    // ── Inicializace ─────────────────────────────────────────────────────────

    private fun onInitReady() {
        Log.d(TAG, "Init ready → GET deviceId [transport=$transport]")
        sendCommand("GET deviceId") { response ->
            if (!response.contains("Rad Pro", ignoreCase = true)) {
                Log.w(TAG, "Not RadPro firmware: $response")
                onDisconnect("RadPro: firmware nerozpoznán")
                return@sendCommand
            }
            deviceId = response.split(';')[0].trim().let { s ->
                // Vzít jen část před závorkou: "FNIRSI GC-01 (CH32...)" → "FNIRSI GC-01"
                val parenIdx = s.indexOf('(')
                if (parenIdx > 0) s.substring(0, parenIdx).trim() else s
            }
            Log.d(TAG, "RadPro confirmed: $deviceId")

            // Synchronizace času a timezone hned při startu
            syncTime {
                // Dead time compensation — jednorázově, jen pro log
                sendCommand("GET tubeDeadTimeCompensation") { dtc ->
                    Log.d(TAG, "tubeDeadTimeCompensation: $dtc")

                    // Načíst tubeSensitivity při startu
                    sendCommand("GET tubeSensitivity", critical = false) { sens ->
                        sens.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                            tubeSensitivity = it
                            Log.d(TAG, "tubeSensitivity: $it")
                        }
                        lastSensitivityTs = System.currentTimeMillis()

                        connected = true
                        val transportName = if (transport == Transport.USB) "USB" else "BLE"
                        onConnect(transportName)
                        // Poslat deviceId jednorázově
                        if (deviceId.isNotEmpty()) onLine("DEVICE=${deviceId.replace(' ', '_')}")

                        // Battery hned při startu
                        sendCommand("GET deviceBatteryVoltage") { v ->
                            v.toDoubleOrNull()?.takeIf { it > 0 }?.let { lastVbat = it }
                        }

                        schedulePoll()
                        scheduleBattery()
                        scheduleTimeSync()
                        scheduleSensitivity()
                        if (transport == Transport.BLE) scheduleRssi()
                    }
                }
            }
        }
    }

    private fun syncTime(onDone: () -> Unit = {}) {
        val unixTime   = System.currentTimeMillis() / 1000
        val tzOffsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        Log.d(TAG, "syncTime: unix=$unixTime tz=$tzOffsetMin min")
        sendCommand("SET deviceTime $unixTime") { _ ->
            sendCommand("SET deviceTimeZone $tzOffsetMin") { _ ->
                Log.d(TAG, "Time sync done")
                onDone()
            }
        }
    }

    // ── RX / příkazy ─────────────────────────────────────────────────────────

    private fun onRxData(data: String) {
        rxBuffer.append(data)
        while (true) {
            val idx = rxBuffer.indexOf('\n')
            if (idx < 0) break
            val line = rxBuffer.substring(0, idx).trimEnd('\r', '\n').trim()
            rxBuffer.delete(0, idx + 1)
            if (line.isEmpty()) continue
            Log.d(TAG, "RX: $line")
            handleResponse(line)
        }
    }

    private fun handleResponse(line: String) {
        val cmd = cmdInFlight ?: return
        cmdInFlight = null
        mainHandler.removeCallbacksAndMessages(timeoutToken)
        consecutiveTimeouts = 0  // úspěšná odpověď — reset čítače
        val value = when {
            line.startsWith("OK")    -> line.removePrefix("OK").trim()
            line.startsWith("ERROR") -> ""
            else                     -> line
        }
        cmd.onResponse(value)
        processQueue()
    }

    private fun sendCommand(cmd: String, critical: Boolean = true, onResponse: (String) -> Unit) {
        cmdQueue.addLast(PendingCommand(cmd, onResponse, critical))
        if (cmdInFlight == null) processQueue()
    }

    private fun processQueue() {
        if (cmdInFlight != null || cmdQueue.isEmpty()) return
        val pending = cmdQueue.removeFirst()
        cmdInFlight = pending
        val data = (pending.cmd + "\r\n").toByteArray()
        when (transport) {
            Transport.USB -> {
                val conn = usbConnection; val ep = usbEndpointOut
                if (conn != null && ep != null) {
                    Thread {
                        conn.bulkTransfer(ep, data, data.size, USB_WRITE_TIMEOUT_MS)
                        Log.d(TAG, "USB TX: ${pending.cmd}")
                    }.start()
                } else { cmdInFlight = null; pending.onResponse(""); return }
            }
            Transport.BLE -> {
                val wc = writeChar; val g = gatt
                if (wc != null && g != null) {
                    wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    wc.value = data; g.writeCharacteristic(wc)
                    Log.d(TAG, "BLE TX: ${pending.cmd}")
                } else { cmdInFlight = null; pending.onResponse(""); return }
            }
            else -> { cmdInFlight = null; pending.onResponse(""); return }
        }
        mainHandler.postDelayed({
            if (cmdInFlight?.cmd == pending.cmd) {
                Log.w(TAG, "Timeout: ${pending.cmd}")
                cmdInFlight = null
                if (pending.critical) consecutiveTimeouts++
                if (pending.critical && consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    Log.w(TAG, "Too many timeouts ($consecutiveTimeouts) — disconnecting")
                    consecutiveTimeouts = 0
                    disconnect()
                    onDisconnect("Timeout — USB odpojeno")
                    return@postDelayed
                }
                pending.onResponse(""); processQueue()
            }
        }, timeoutToken, CMD_TIMEOUT_MS)
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun schedulePoll() {
        mainHandler.postDelayed({
            if (!connected) return@postDelayed
            doPoll()
            schedulePoll()
        }, POLL_INTERVAL_MS)
    }

    private fun doPoll() {
        sendCommand("GET tubePulseCount") { pulseStr ->
            val pulseCount = pulseStr.toLongOrNull() ?: return@sendCommand
            sendCommand("GET deviceTime") { timeStr ->
                val deviceTime = timeStr.toLongOrNull() ?: return@sendCommand
                lastPulseCount = pulseCount
                lastPulseTime  = deviceTime
                sendCommand("GET tubeRate") { rateStr ->
                    rateStr.toDoubleOrNull()?.let { rawRate ->
                        lastRawRate = rawRate
                        lastCps     = rawRate / 60.0                          // CPM → CPS s desetinami
                        lastRate    = rawRate / tubeSensitivity               // µSv/h dle citlivosti trubice
                    }
                    emitLine()
                }
            }
        }
    }

    private fun scheduleSensitivity() {
        mainHandler.postDelayed({
            if (!connected) return@postDelayed
            sendCommand("GET tubeSensitivity", critical = false) { sens ->
                sens.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                    tubeSensitivity = it
                    Log.d(TAG, "tubeSensitivity refresh: $it")
                }
            }
            scheduleSensitivity()
        }, SENSITIVITY_INTERVAL_MS)
    }

    private fun scheduleBattery() {
        mainHandler.postDelayed({
            if (!connected) return@postDelayed
            sendCommand("GET deviceBatteryVoltage") { v ->
                v.toDoubleOrNull()?.takeIf { it > 0 }?.let { lastVbat = it; emitLine() }
            }
            scheduleBattery()
        }, BATTERY_INTERVAL_MS)
    }

    private val rssiToken = Object()

    private fun scheduleRssi() {
        mainHandler.removeCallbacksAndMessages(rssiToken)
        mainHandler.postDelayed({
            if (connected && transport == Transport.BLE) {
                try { gatt?.readRemoteRssi() } catch (_: Exception) {}
            }
        }, rssiToken, RSSI_INTERVAL_MS)
    }

    private fun scheduleTimeSync() {
        mainHandler.postDelayed({
            if (!connected) return@postDelayed
            syncTime()
            scheduleTimeSync()
        }, TIME_SYNC_INTERVAL_MS)
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    private fun emitLine() {
        if (!connected) return
        val vbatMv = if (lastVbat > 0) (lastVbat * 1000).toInt() else 3700
        val cpsStr = String.format(java.util.Locale.US, "%.3f", lastCps)
        val sb = StringBuilder()
        sb.append("CPS=$cpsStr")
        sb.append(" VBAT=${vbatMv}mV")
        if (transport == Transport.BLE) sb.append(" RSSI=$lastRssi")
        sb.append(" RATE=${String.format(java.util.Locale.US, "%.4f", lastRate)}")
        if (deviceId.isNotEmpty()) sb.append(" DEVICE=${deviceId.replace(' ', '_')}")
        val line = sb.toString()
        Log.v(TAG, "emit: $line")
        onLine(line)
    }
}