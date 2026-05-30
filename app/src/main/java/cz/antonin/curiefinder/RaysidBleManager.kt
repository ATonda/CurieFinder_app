package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class RaysidBleManager(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onConnect: () -> Unit = {},
    private val onDisconnect: (String) -> Unit = {}
) {
    companion object {
        const val TAG = "RaysidBleManager"

        val TX_UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
        val RX_UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
        val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        const val CONNECTION_TIMEOUT_MS = 30000L
        const val PING_INTERVAL_MS = 5000L
        const val MAX_QUEUE_SIZE = 50
        const val JUMP_THRESHOLD = 0.15  // 15% změny = skok

        val HELLO_PACKET = byteArrayOf(
            0xFF.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0x17.toByte(), 0x64.toByte(),
            0x8F.toByte(), 0x32.toByte(), 0x12.toByte(), 0x00.toByte(), 0x64.toByte(),
            0x17.toByte(), 0x20.toByte(), 0x8F.toByte(), 0x0E.toByte()
        )
    }

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private var pingRunnable: Runnable? = null
    private var isReady = false

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private val dataQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isProcessing = false

    private var lastCps = 0.0
    private var lastDoseRate = 0.0
    private var lastBattery = 0
    private var lastDataTime = 0L
    private var lastRssi = -99

    private val rssiToken = Object()

    private val cpsHistory = mutableListOf<Double>()
    private val MAX_HISTORY = 2  // Pouze 2 hodnoty pro rychlejší reakci
    private var lastRawCps = 0.0

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Log.e(TAG, "=== Connecting to Raysid: ${device.name} ===")

        // Spustit čerstvý processing thread pro každé připojení
        processingThread?.quitSafely()
        val thread = HandlerThread("RaysidProcessing").apply { start() }
        processingThread = thread
        processingHandler = Handler(thread.looper)

        connectionTimeoutRunnable = Runnable {
            Log.e(TAG, "!!! Connection timeout !!!")
            disconnect()
            onDisconnect("Raysid: připojení timeout")
        }
        mainHandler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        startProcessing()
    }

    fun disconnect() {
        Log.e(TAG, "disconnect() called")
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pingRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
        pingRunnable = null
        isReady = false
        isProcessing = false
        dataQueue.clear()
        cpsHistory.clear()

        processingThread?.quitSafely()
        processingThread = null
        processingHandler = null
        mainHandler.removeCallbacksAndMessages(rssiToken)

        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
    }

    private fun startProcessing() {
        isProcessing = true
        processingHandler?.post(object : Runnable {
            override fun run() {
                if (!isProcessing) return

                if (dataQueue.size > MAX_QUEUE_SIZE) {
                    Log.e(TAG, "Queue overflow! Clearing ${dataQueue.size} packets")
                    dataQueue.clear()
                }

                val data = dataQueue.poll()
                if (data != null) {
                    parseRaysidPacket(data)
                }
                processingHandler?.postDelayed(this, 10)
            }
        })
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.e(TAG, "onConnectionStateChange: status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.e(TAG, "✓ Connected to Raysid")
                    connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.e(TAG, "✗ Disconnected from Raysid")
                    isReady = false
                    isProcessing = false
                    mainHandler.post { onDisconnect("Raysid odpojen") }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.e(TAG, "onServicesDiscovered: status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDisconnect("Service discovery failed")
                return
            }

            var targetService = gatt.getService(TX_UUID)
            if (targetService == null) {
                targetService = gatt.getService(UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"))
            }

            if (targetService == null) {
                Log.e(TAG, "Raysid service not found!")
                onDisconnect("Raysid service not found")
                return
            }

            txCharacteristic = targetService.getCharacteristic(TX_UUID)
            rxCharacteristic = targetService.getCharacteristic(RX_UUID)

            if (txCharacteristic == null || rxCharacteristic == null) {
                Log.e(TAG, "Characteristics not found!")
                onDisconnect("Characteristics not found")
                return
            }

            Log.e(TAG, "Found characteristics, enabling notifications")

            gatt.setCharacteristicNotification(rxCharacteristic!!, true)
            val descriptor = rxCharacteristic!!.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                onReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.e(TAG, "onDescriptorWrite: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onReady()
            } else {
                onDisconnect("Failed to enable notifications")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            lastDataTime = System.currentTimeMillis()
            dataQueue.add(value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            lastDataTime = System.currentTimeMillis()
            dataQueue.add(value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRssi = rssi
            }
            if (isReady) scheduleRssi()
        }
    }

    private fun onReady() {
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        isReady = true
        Log.e(TAG, "=== Raysid ready, sending HELLO packets ===")

        sendHelloPacket()

        mainHandler.postDelayed({
            if (isReady) {
                sendHelloPacket()
                Log.e(TAG, "Second HELLO packet sent")
            }
        }, 200)

        startPingTimer()
        scheduleRssi()

        onConnect()
    }

    private fun scheduleRssi() {
        mainHandler.removeCallbacksAndMessages(rssiToken)
        mainHandler.postDelayed({
            if (isReady && gatt != null) {
                try { gatt?.readRemoteRssi() } catch (_: Exception) {}
            }
        }, rssiToken, 5000L)
    }

    private fun startPingTimer() {
        pingRunnable = Runnable {
            if (isReady && txCharacteristic != null) {
                sendPing(0)
            }
            if (isReady) {
                mainHandler.postDelayed(pingRunnable!!, PING_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(pingRunnable!!, 2000)
    }

    private fun sendHelloPacket() {
        if (txCharacteristic == null) return

        try {
            txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txCharacteristic?.value = HELLO_PACKET
            gatt?.writeCharacteristic(txCharacteristic)
        } catch (e: Exception) {
            Log.e(TAG, "sendHelloPacket error: ${e.message}")
        }
    }

    private fun sendPing(tab: Int) {
        if (txCharacteristic == null || !isReady) return

        try {
            val unix = System.currentTimeMillis() / 1000
            val payload = byteArrayOf(
                0x12, tab.toByte(),
                ((unix shr 24) and 0xFF).toByte(),
                ((unix shr 16) and 0xFF).toByte(),
                ((unix shr 8) and 0xFF).toByte(),
                (unix and 0xFF).toByte()
            )

            val packet = wrapCommand(payload)
            txCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txCharacteristic?.value = packet
            gatt?.writeCharacteristic(txCharacteristic)
        } catch (e: Exception) {
            Log.e(TAG, "sendPing error: ${e.message}")
        }
    }

    private fun wrapCommand(payload: ByteArray): ByteArray {
        val crc1 = crc1(payload)
        val inner = ByteArray(1 + 4 + payload.size)
        inner[0] = 0xEE.toByte()
        inner[1] = ((crc1 shr 24) and 0xFF).toByte()
        inner[2] = ((crc1 shr 16) and 0xFF).toByte()
        inner[3] = ((crc1 shr 8) and 0xFF).toByte()
        inner[4] = (crc1 and 0xFF).toByte()
        System.arraycopy(payload, 0, inner, 5, payload.size)

        val crc2 = crc2(inner)
        val packet = ByteArray(1 + 1 + inner.size + 1)
        packet[0] = 0xFF.toByte()
        packet[1] = crc2
        System.arraycopy(inner, 0, packet, 2, inner.size)
        packet[packet.size - 1] = (inner.size + 3).toByte()

        return packet
    }

    private fun crc1(data: ByteArray): Long {
        var crc = 0L
        var i = 0
        while (i < data.size) {
            when {
                i + 3 < data.size -> {
                    crc += ((data[i + 3].toLong() and 0xFF) shl 24) or
                            ((data[i + 2].toLong() and 0xFF) shl 16) or
                            ((data[i + 1].toLong() and 0xFF) shl 8) or
                            (data[i].toLong() and 0xFF)
                }
                i + 2 < data.size -> {
                    crc += ((data[i + 2].toLong() and 0xFF) shl 16) or
                            ((data[i + 1].toLong() and 0xFF) shl 8) or
                            (data[i].toLong() and 0xFF)
                }
                i + 1 < data.size -> {
                    crc += ((data[i + 1].toLong() and 0xFF) shl 8) or
                            (data[i].toLong() and 0xFF)
                }
                else -> {
                    crc += (data[i].toLong() and 0xFF)
                }
            }
            i += 4
        }
        return crc and 0xFFFFFFFFL
    }

    private fun crc2(data: ByteArray): Byte {
        var crc: Byte = 0
        for (b in data) {
            crc = (crc.toInt() xor b.toInt()).toByte()
        }
        return crc
    }

    private fun parseRaysidPacket(bytes: ByteArray) {
        if (bytes.size < 2) return

        val packetType = bytes[1].toInt() and 0xFF

        when (packetType) {
            0x17 -> parseCpsDosePacket(bytes)
            0x02 -> parseBatteryPacket(bytes)
        }
    }

    private fun parseCpsDosePacket(bytes: ByteArray) {
        try {
            var overload = 0
            if (bytes[0].toInt() == 18 && bytes.size > 14) {
                overload = bytes[14].toInt() and 0xFF
            }
            if (overload > 1) return

            var newCps = -1.0
            val sets = if (bytes.size <= 20) 2 else 12

            for (k in 0 until sets) {
                val dataTypePos = k * 3 + 2
                val valueLowPos = k * 3 + 3
                val valueHighPos = k * 3 + 4

                if (valueHighPos >= bytes.size) break

                val dataType = bytes[dataTypePos].toInt() and 0xFF
                if (dataType != 0) continue

                val rawValue = ((bytes[valueHighPos].toInt() and 0xFF) shl 8) or (bytes[valueLowPos].toInt() and 0xFF)
                val unpacked = unpackValue(rawValue.toLong())
                newCps = unpacked / 600.0
                break
            }

            if (newCps < 0 || newCps > 2000) return

            // Detekce skoku pro rychlejší reakci
            if (lastRawCps > 0 && Math.abs(newCps - lastRawCps) / lastRawCps > JUMP_THRESHOLD) {
                // Skok - okamžitá reakce bez průměrování
                lastCps = newCps
                cpsHistory.clear()
                cpsHistory.add(newCps)
                Log.d(TAG, "Jump detected: ${lastRawCps} → $newCps")
            } else {
                // Normální průměrování
                cpsHistory.add(newCps)
                while (cpsHistory.size > MAX_HISTORY) cpsHistory.removeAt(0)
                lastCps = cpsHistory.average()
            }
            lastRawCps = newCps
            lastDoseRate = lastCps / 316.0

            sendDataToUI()
        } catch (e: Exception) {
            Log.e(TAG, "Parse CPS error: ${e.message}")
        }
    }

    private fun parseBatteryPacket(bytes: ByteArray) {
        try {
            if (bytes.size < 5) return
            lastBattery = bytes[4].toInt() and 0xFF
            sendDataToUI()
        } catch (e: Exception) {
            Log.e(TAG, "Parse battery error: ${e.message}")
        }
    }

    private fun unpackValue(v: Long): Long {
        val mult10 = (v / 6000).toInt()
        var result = v % 6000
        for (k in 0 until mult10) {
            result *= 10
        }
        return result
    }

    private fun sendDataToUI() {
        val voltage = if (lastBattery > 0) {
            (3000 + (lastBattery * 12)).coerceIn(3000, 4200)
        } else {
            3700
        }

        val line = String.format(
            java.util.Locale.US,
            "CPS=%.1f RATE=%.4f VBAT=%dmV RSSI=%d",
            lastCps, lastDoseRate, voltage, lastRssi
        )
        onLine(line)
    }
}