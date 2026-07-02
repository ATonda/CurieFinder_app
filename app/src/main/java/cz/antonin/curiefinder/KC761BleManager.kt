package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE manager pro KC761x (KC761A/B/C/CN).
 *
 * Protokol: BLE 5.0, GATT, NUS-like UUID (stejné jako Raysid/NusBleManager).
 * Data jsou binární — KC761 odesílá binární pakety, ne textové řádky.
 *
 * Po subscribe na TX charakteristiku KC761 automaticky spustí auto-upload
 * a každou sekundu posílá STATUS_DATA paket (flag 0xA3, délka 81 bytů).
 *
 * Parsovaná pole ze STATUS_DATA:
 *   offset 33, int32 LE  → RAD0_RAW_CPS (cps)
 *   offset 39, FP16 LE   → RAD0_RAW_DOSE_EQ_RATE (mSv/h → ×1000 = µSv/h)
 *   offset  8, uint8     → BAT_PERCENT (%)
 *
 * Výstup onLine: "CPS=X.XX RATE=X.XX" kde RATE je µSv/h.
 * Chybějící BG= je záměrné — KC761 background neposílá.
 */
@SuppressLint("MissingPermission")
class KC761BleManager(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onConnect: () -> Unit = {},
    private val onDisconnect: (reason: String) -> Unit = {}
) {
    companion object {
        const val TAG = "KC761Ble"

        // NUS UUID — stejné jako u Raysid a NusBleManager
        val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Binární protokol — KC761 Programming Manual V1.8
        const val FLAG_STATUS_AUTO = 0xA3  // STATUS_DATA auto-upload
        const val FLAG_MC_AUTO     = 0xA1  // MC_DATA auto-upload (spektrum) — ignorujeme
        const val FLAG_DEVICE_INFO = 0xA5  // DEVICE_INFO — ignorujeme
        const val STATUS_DATA_LEN  = 81    // délka STATUS_DATA paketu

        // Offsety v STATUS_DATA paketu (ověřeno z logu 2026-06-29)
        const val OFF_FLAG         = 1     // uint8  — packet flag
        const val OFF_BAG_LEN      = 2     // uint16 LE — délka paketu
        const val OFF_BAT_PERCENT  = 8     // uint8  — % baterie
        const val OFF_RAW_CPS      = 33    // int32  LE — RAD0_RAW_CPS (cps)
        const val OFF_RAW_DOSE_EQ  = 39    // FP16   LE — RAD0_RAW_DOSE_EQ_RATE (mSv/h)

        const val RSSI_INTERVAL_MS    = 5000L
        const val RECONNECT_DELAY_MS  = 1500L
        const val RECONNECT_MAX_TRIES = 8
    }

    private var gatt: BluetoothGatt? = null
    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRssi = -99

    // Akumulační buffer pro případ fragmentovaných BLE paketů
    private val rxBuffer = mutableListOf<Byte>()

    @Volatile private var reconnectEnabled = false
    @Volatile private var reconnectTries = 0
    private var lastDevice: BluetoothDevice? = null

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect() → ${device.name} ${device.address}")
        lastDevice = device
        reconnectTries = 0
        reconnectEnabled = false
        disconnect()
        try {
            Thread.sleep(300)
            gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connect error: ${e.message}")
            onDisconnect("KC761: chyba připojení")
        }
    }

    private fun scheduleReconnect() {
        val device = lastDevice ?: return
        if (!reconnectEnabled) return
        if (reconnectTries >= RECONNECT_MAX_TRIES) {
            Log.w(TAG, "KC761 reconnect: max pokusů dosaženo")
            onDisconnect("KC761: nelze se znovu připojit")
            return
        }
        reconnectTries++
        val delay = RECONNECT_DELAY_MS * reconnectTries.coerceAtMost(4)
        Log.d(TAG, "KC761 reconnect pokus $reconnectTries za ${delay}ms")
        mainHandler.postDelayed({
            if (!connected) {
                try {
                    try { gatt?.close() } catch (_: Exception) {}
                    gatt = null
                    gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: Exception) {
                    Log.e(TAG, "KC761 reconnect chyba: ${e.message}")
                    scheduleReconnect()
                }
            }
        }, delay)
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        reconnectEnabled = false
        reconnectTries = 0
        connected = false
        mainHandler.removeCallbacksAndMessages(null)
        rxBuffer.clear()
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
                    reconnectTries = 0
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // KC761 potřebuje velké MTU pro 504B pakety (spektrum)
                    gatt.requestMtu(512)
                    mainHandler.postDelayed({ gatt.discoverServices() }, 800)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    mainHandler.removeCallbacksAndMessages(null)
                    Log.d(TAG, "GATT disconnected status=$status reconnectEnabled=$reconnectEnabled tries=$reconnectTries")
                    if (!reconnectEnabled || status == 0) {
                        val reason = when (status) {
                            0    -> "Odpojeno"
                            8    -> "KC761: timeout"
                            19   -> "KC761: odpojeno zařízením"
                            133  -> "KC761: GATT error 133"
                            else -> "KC761: chyba $status"
                        }
                        try { gatt.close() } catch (_: Exception) {}
                        this@KC761BleManager.gatt = null
                        onDisconnect(reason)
                    } else {
                        Log.d(TAG, "KC761 neočekávané odpojení — spouštím reconnect")
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: $mtu status=$status")
            // MTU ≥ 507 → KC761 použije 504B pakety pro spektrum
            // Pro STATUS_DATA (81B) je MTU irelevantní
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDisconnect("KC761: discovery chyba ($status)"); return
            }
            // KC761 nemusí exponovat standardní NUS service UUID 6E400001
            // TX a RX charakteristiky hledáme napříč všemi službami
            var txChar: BluetoothGattCharacteristic? = null
            for (service in gatt.services) {
                txChar = service.getCharacteristic(NUS_TX_UUID)
                if (txChar != null) {
                    Log.d(TAG, "KC761 TX found in service ${service.uuid}")
                    break
                }
            }
            if (txChar == null) {
                onDisconnect("KC761: TX charakteristika nenalezena"); return
            }
            gatt.setCharacteristicNotification(txChar, true)
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onDisconnect("KC761: CCCD nenalezen"); return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            Log.d(TAG, "KC761 TX notify enabled")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDisconnect("KC761: CCCD selhal ($status)"); return
            }
            connected = true
            reconnectEnabled = true
            reconnectTries = 0
            rxBuffer.clear()
            Log.d(TAG, "KC761 connected — čekám na STATUS_DATA auto-upload")
            onConnect()
            scheduleRssi()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX_UUID) parsePacket(value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == NUS_TX_UUID) parsePacket(characteristic.value ?: return)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) lastRssi = rssi
            if (connected) scheduleRssi()
        }
    }

    /**
     * Zpracuje příchozí BLE notifikaci.
     *
     * KC761 může poslat STATUS_DATA (81B) v jednom paketu, nebo fragmentovaně
     * přes více BLE notifikací (závisí na MTU). Buffer akumuluje byty
     * a zpracovává pakety jakmile je k dispozici celý.
     *
     * Identifikace paketu: byte[1] = FLAG (0xA3 = STATUS_DATA auto-upload).
     * Délka paketu: byte[2..3] = BAG_LEN (uint16 LE).
     */
    private fun parsePacket(data: ByteArray) {
        rxBuffer.addAll(data.toList())

        // Zpracovávat dokud buffer obsahuje alespoň hlavičku (4 byty: SYNC, FLAG, BAG_LEN)
        while (rxBuffer.size >= 4) {
            val flag   = rxBuffer[1].toInt() and 0xFF
            val bagLen = ((rxBuffer[2].toInt() and 0xFF) or ((rxBuffer[3].toInt() and 0xFF) shl 8))

            // Bezpečnostní kontrola — BAG_LEN mimo rozumný rozsah → zahazovat po bytu
            if (bagLen < 4 || bagLen > 1100) {
                Log.w(TAG, "KC761 neplatný BAG_LEN=$bagLen flag=0x${flag.toString(16)} — zahazuji byte")
                rxBuffer.removeAt(0)
                continue
            }

            // Nemáme ještě celý paket
            if (rxBuffer.size < bagLen) break

            // Extrahovat celý paket
            val pkt = ByteArray(bagLen) { rxBuffer[it] }
            repeat(bagLen) { rxBuffer.removeAt(0) }

            when (flag) {
                FLAG_STATUS_AUTO -> processStatusData(pkt)
                FLAG_MC_AUTO     -> { /* spektrum — ignorujeme, nezajímá nás pro základní měření */ }
                FLAG_DEVICE_INFO -> { /* info o zařízení — ignorujeme */ }
                else             -> Log.v(TAG, "KC761 neznámý flag=0x${flag.toString(16)} len=$bagLen")
            }
        }
    }

    /**
     * Zpracuje STATUS_DATA paket (flag 0xA3, délka 81 bytů).
     *
     * Ověřené offsety z KC761 Programming Manual V1.8 a logu 2026-06-29:
     *   [8]    BAT_PERCENT  uint8
     *   [33]   RAD0_RAW_CPS int32 LE
     *   [39]   RAD0_RAW_DOSE_EQ_RATE FP16 LE (mSv/h)
     */
    private fun processStatusData(pkt: ByteArray) {
        if (pkt.size < STATUS_DATA_LEN) {
            Log.w(TAG, "KC761 STATUS_DATA příliš krátký: ${pkt.size}B")
            return
        }

        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)

        val batPercent = pkt[OFF_BAT_PERCENT].toInt() and 0xFF
        val rawCps     = buf.getInt(OFF_RAW_CPS)
        val fp16Val    = buf.getShort(OFF_RAW_DOSE_EQ).toInt() and 0xFFFF
        val doseEqMsv  = decodeFp16(fp16Val)  // mSv/h

        // Validace — disabled sensor vrací -1
        if (rawCps < 0) {
            Log.v(TAG, "KC761 RAD0 disabled (CPS=$rawCps)")
            return
        }

        val doseEqUsv = doseEqMsv * 1000.0  // mSv/h → µSv/h

        // Sestavit textový řetězec kompatibilní s parseLine v index.html
        // RATE= je zde µSv/h — KC761BleManager vždy posílá µSv/h
        // BG= záměrně chybí — KC761 background neposílá
        val line = buildString {
            append("CPS=")
            append(String.format("%.2f", rawCps.toDouble()))
            if (doseEqUsv >= 0.0) {
                append(" RATE=")
                append(String.format("%.4f", doseEqUsv))
            }
            append(" BATP=")
            append(batPercent)
            append(" RSSI=")
            append(lastRssi)
        }

        Log.v(TAG, "KC761 → $line")
        onLine(line)
    }

    /**
     * Dekóduje IEEE 754 half-precision (FP16) na Double.
     * Little-endian — val je již 16bitová hodnota (LSB | MSB<<8).
     *
     * Ověřeno z logu: 0xBC00 = -1.0 (disabled sensor), 0x0487 ≈ 0.000069 mSv/h.
     */
    private fun decodeFp16(v: Int): Double {
        val sign = (v shr 15) and 1
        val exp  = (v shr 10) and 0x1F
        val mant = v and 0x3FF
        return when (exp) {
            0    -> (if (sign == 0) 1.0 else -1.0) * Math.pow(2.0, -14.0) * (mant / 1024.0)
            31   -> if (mant == 0) (if (sign == 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
            else Double.NaN
            else -> (if (sign == 0) 1.0 else -1.0) * Math.pow(2.0, (exp - 15).toDouble()) * (1.0 + mant / 1024.0)
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