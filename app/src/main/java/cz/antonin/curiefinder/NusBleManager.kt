package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Obecný BLE NUS (Nordic UART Service) manager.
 * Připojí se k libovolnému BLE zařízení implementujícímu NUS
 * a přijímá textové řádky ve formátu CPS=... \n
 *
 * OPRAVA: parseData throttling — při batchi více řádků najednou
 * odešle pouze poslední CPS řádek. Starší hodnoty ze stejného batche
 * jsou zahozeny — při 250ms intervalu odesílání jsou stejné nebo
 * téměř stejné, ztráta dat je zanedbatelná ale UI přestane zamrzat.
 */
@SuppressLint("MissingPermission")
class NusBleManager(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onConnect: () -> Unit = {},
    private val onDisconnect: (reason: String) -> Unit = {}
) {
    companion object {
        const val TAG = "NusBle"
        val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // ESP→telefon
        val NUS_RX_UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // telefon→ESP
        val CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        const val RSSI_INTERVAL_MS     = 5000L
        // Minimální interval mezi dvěma onLine voláními — ochrana UI threadu
        // ESP posílá každých 250ms, takže 200ms je bezpečný práh
        const val LINE_THROTTLE_MS     = 200L
        const val RECONNECT_DELAY_MS   = 1500L   // pauza před reconnect pokusem
        const val RECONNECT_MAX_TRIES  = 8        // max pokusů před vzdáním
    }

    private var gatt: BluetoothGatt? = null
    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRssi = -99
    private var rxBuffer = StringBuilder()

    // Throttling — čas posledního onLine volání
    private var lastLineTs = 0L

    // Reconnect stav
    @Volatile private var reconnectEnabled  = false   // true po prvním úspěšném connect
    @Volatile private var reconnectTries    = 0
    private var lastDevice: BluetoothDevice? = null   // uložené zařízení pro reconnect

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect() → ${device.name} ${device.address}")
        lastDevice = device
        reconnectTries = 0
        reconnectEnabled = false  // bude true až po onConnect
        disconnect()
        try {
            Thread.sleep(300)
            // autoConnect=true — Android BLE stack se sám přepojí jakmile zařízení začne advertisovat
            gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connect error: ${e.message}")
            onDisconnect("NUS: chyba připojení")
        }
    }

    /** Interní reconnect — volá se z onConnectionStateChange po neočekávaném odpojení */
    private fun scheduleReconnect() {
        val device = lastDevice ?: return
        if (!reconnectEnabled) return
        if (reconnectTries >= RECONNECT_MAX_TRIES) {
            Log.w(TAG, "NUS reconnect: max pokusů dosaženo — vzdávám")
            onDisconnect("NUS: nelze se znovu připojit")
            return
        }
        reconnectTries++
        val delay = RECONNECT_DELAY_MS * reconnectTries.coerceAtMost(4)
        Log.d(TAG, "NUS reconnect pokus $reconnectTries za ${delay}ms")
        mainHandler.postDelayed({
            if (!connected) {
                try {
                    // Zavřít starý GATT před novým pokusem
                    try { gatt?.close() } catch (_: Exception) {}
                    gatt = null
                    // autoConnect=true — Android čeká na advertisement bez aktivního scanu
                    gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: Exception) {
                    Log.e(TAG, "NUS reconnect chyba: ${e.message}")
                    scheduleReconnect()
                }
            }
        }, delay)
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        reconnectEnabled = false  // záměrné odpojení — nereconnectovat
        reconnectTries = 0
        connected = false
        mainHandler.removeCallbacksAndMessages(null)
        rxBuffer.clear()
        lastLineTs = 0L
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close()     } catch (_: Exception) {}
        gatt = null
    }

    fun isConnected() = connected

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected (status=$status)")
                    reconnectTries = 0  // úspěšné připojení — reset čítače
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    mainHandler.postDelayed({ gatt.discoverServices() }, 600)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    mainHandler.removeCallbacksAndMessages(null)
                    Log.d(TAG, "GATT disconnected status=$status reconnectEnabled=$reconnectEnabled tries=$reconnectTries")
                    // Záměrné odpojení (status=0, reconnectEnabled=false) → informovat volající
                    // Neočekávané odpojení → zkusit reconnect sám bez JS round-tripu
                    if (!reconnectEnabled || status == 0) {
                        val reason = when (status) {
                            0    -> "Odpojeno"
                            8    -> "NUS: timeout"
                            19   -> "NUS: odpojeno zařízením"
                            133  -> "NUS: GATT error 133"
                            else -> "NUS: chyba $status"
                        }
                        try { gatt.close() } catch (_: Exception) {}
                        this@NusBleManager.gatt = null
                        onDisconnect(reason)
                    } else {
                        // Neočekávané odpojení — reconnect bez notifikace UI
                        // gatt.close() nevoláme — ponecháme stack aby čekal na advertisement
                        // při autoConnect=true stačí jen počkat
                        Log.d(TAG, "NUS neočekávané odpojení — spouštím reconnect")
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDisconnect("NUS: discovery chyba ($status)"); return
            }
            val txChar = gatt.getService(NUS_SERVICE_UUID)?.getCharacteristic(NUS_TX_UUID)
            if (txChar == null) {
                onDisconnect("NUS: TX charakteristika nenalezena — zařízení nepodporuje NUS"); return
            }
            gatt.setCharacteristicNotification(txChar, true)
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onDisconnect("NUS: CCCD nenalezen"); return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            Log.d(TAG, "NUS TX notify enabled")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDisconnect("NUS: CCCD selhal ($status)"); return
            }
            connected = true
            reconnectEnabled = true   // od teď povolíme auto-reconnect při výpadku
            reconnectTries = 0
            lastLineTs = 0L
            Log.d(TAG, "NUS connected — čekám na data")
            onConnect()
            scheduleRssi()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == NUS_TX_UUID) parseData(value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NUS_TX_UUID) parseData(characteristic.value ?: return)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) lastRssi = rssi
            if (connected) scheduleRssi()
        }
    }

    private fun parseData(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        rxBuffer.append(text)

        // Extrahovat všechny kompletní řádky
        val lines = mutableListOf<String>()
        while (true) {
            val nl = rxBuffer.indexOf('\n')
            if (nl < 0) break
            val line = rxBuffer.substring(0, nl).trim()
            rxBuffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            lines.add(line)
        }

        if (lines.isEmpty()) return

        val now = System.currentTimeMillis()

        if (lines.size == 1) {
            // Jeden řádek — standardní případ při nízkém CPS
            val line = lines[0]
            Log.v(TAG, "RX: $line")
            if (now - lastLineTs >= LINE_THROTTLE_MS) {
                lastLineTs = now
                onLine(addRssi(line))
            }
        } else {
            // Batch více řádků — přišly najednou z Android BLE stacku
            // Odeslat pouze poslední CPS řádek — nejnovější hodnota
            // Starší hodnoty jsou zahozeny (UI throttling)
            val lastCpsLine = lines.lastOrNull { it.contains("CPS=") } ?: lines.last()
            Log.v(TAG, "RX batch ${lines.size} řádků → odesílám poslední: $lastCpsLine")
            Log.v(TAG, "RX zahozeno ${lines.size - 1} starších řádků")
            lastLineTs = now
            onLine(addRssi(lastCpsLine))
        }
    }

    private fun addRssi(line: String): String {
        return if (line.contains("CPS=") && !line.contains("RSSI=")) {
            "$line RSSI=$lastRssi"
        } else {
            line
        }
    }

    private fun scheduleRssi() {
        mainHandler.postDelayed({
            if (connected) {
                try { gatt?.readRemoteRssi() } catch (e: Exception) { Log.w(TAG, "RSSI: ${e.message}") }
            }
        }, RSSI_INTERVAL_MS)
    }
}