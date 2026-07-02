package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class RadiaCodeManager(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onConnect: () -> Unit = {},
    private val onDisconnect: (String) -> Unit = {}
) {
    companion object {
        const val TAG = "RadiaCode"

        val SERVICE_UUID = UUID.fromString("e63215e5-7003-49d8-96b0-b024798fb901")
        val TX_UUID      = UUID.fromString("e63215e6-7003-49d8-96b0-b024798fb901")
        val RX_UUID      = UUID.fromString("e63215e7-7003-49d8-96b0-b024798fb901")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        const val CONNECTION_TIMEOUT_MS = 30_000L
        // Polling 100ms — zařízení samo aktualizuje DATA_BUF ~1s, ale rychlejší polling
        // zajistí příjem dat okamžitě po aktualizaci bez zbytečného čekání
        const val DATA_INTERVAL_MS = 100L
        const val RSSI_INTERVAL_MS = 2_000L
        const val CHUNK_SIZE = 18

        const val CMD_SET_EXCHANGE   = 0x0007
        const val CMD_SET_TIME       = 0x0A04
        const val CMD_WR_VIRT_SFR    = 0x0825
        const val VSFR_CPS_FILTER    = 0x8005
        const val CMD_RD_VIRT_STRING = 0x0826
        const val VS_DATA_BUF        = 0x0100

        val SET_EXCHANGE_DATA = byteArrayOf(0x01, 0xFF.toByte(), 0x12, 0xFF.toByte())

        val EID0_PAYLOAD_SIZES = mapOf(
            0 to 15, 1 to 8, 2 to 16, 3 to 14,
            4 to 16, 5 to 16, 6 to 6, 7 to 4, 8 to 6, 9 to 6
        )
    }

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isReady = AtomicBoolean(false)
    private var cmdSeq = 0

    private var pendingChunks = mutableListOf<ByteArray>()
    private var chunkIndex = 0
    private val isWriting = AtomicBoolean(false)

    private val initQueue = ArrayDeque<Pair<String, ByteArray>>()
    private var initInProgress = false

    private var connectedAddress: String = ""
    private var dataRunnable: Runnable? = null
    private var rssiRunnable: Runnable? = null
    private var connTimeoutRunnable: Runnable? = null

    private val dataQueue = ConcurrentLinkedQueue<ByteArray>()
    private var rxBuffer = ByteArray(0)

    // Čistá data - žádné filtry, žádné průměrování
    private var lastCps = 0.0
    private var lastDoseRate = 0.0
    private var lastBattery = 0
    private var lastTemperature = 0.0
    private var lastRssi = -99

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect → ${device.name} ${device.address}")
        disconnect()
        connectedAddress = device.address
        connTimeoutRunnable = Runnable {
            Log.e(TAG, "connection timeout")
            disconnect()
            onDisconnect("RadiaCode: timeout připojení")
        }
        mainHandler.postDelayed(connTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        startPacketProcessing()
    }

    fun disconnect() {
        connTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        dataRunnable?.let { mainHandler.removeCallbacks(it) }
        rssiRunnable?.let { mainHandler.removeCallbacks(it) }
        connTimeoutRunnable = null
        dataRunnable = null
        rssiRunnable = null

        isReady.set(false)
        isWriting.set(false)
        initInProgress = false
        pendingChunks.clear()
        chunkIndex = 0
        initQueue.clear()
        dataQueue.clear()
        rxBuffer = ByteArray(0)

        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txChar = null
        rxChar = null
        connectedAddress = ""
    }

    fun isConnected() = isReady.get()

    private fun startPacketProcessing() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isReady.get() && gatt == null) return
                dataQueue.poll()?.let { parsePacket(it) }
                mainHandler.postDelayed(this, 5)
            }
        })
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected")
                    connTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.postDelayed({ gatt.discoverServices() }, 100)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected, status=$status")
                    isReady.set(false)
                    try { gatt.close() } catch (_: Exception) {}
                    this@RadiaCodeManager.gatt = null
                    onDisconnect("RadiaCode odpojen (status $status)")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onDisconnect("RadiaCode: service discovery chyba ($status)")
                return
            }
            // Požádat o HIGH priority — zkrátí connection interval z ~50ms na ~7.5ms
            // Výsledek: nižší latence přenosu dat
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            val svc = gatt.getService(SERVICE_UUID) ?: run {
                onDisconnect("RadiaCode: service nenalezen")
                return
            }
            txChar = svc.getCharacteristic(TX_UUID)
            rxChar = svc.getCharacteristic(RX_UUID)
            if (txChar == null || rxChar == null) {
                onDisconnect("RadiaCode: charakteristiky nenalezeny")
                return
            }
            gatt.setCharacteristicNotification(rxChar!!, true)
            val cccd = rxChar!!.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } else {
                onReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) onReady()
            else onDisconnect("RadiaCode: CCCD zápis selhal ($status)")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "write failed: $status")
                isWriting.set(false)
                pendingChunks.clear()
                chunkIndex = 0
                if (initInProgress) sendNextInitCommand()
                return
            }
            if (chunkIndex < pendingChunks.size) {
                writeNextChunk()
            } else {
                isWriting.set(false)
                pendingChunks.clear()
                chunkIndex = 0
                if (initInProgress) sendNextInitCommand()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt.device.address != connectedAddress) return
            if (characteristic.uuid == RX_UUID) dataQueue.add(value.copyOf())
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt.device.address != connectedAddress) return
            if (characteristic.uuid == RX_UUID)
                dataQueue.add(characteristic.value?.copyOf() ?: return)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) lastRssi = rssi
        }
    }

    private fun onReady() {
        Log.d(TAG, "onReady — sestavuji init frontu")

        val cal = Calendar.getInstance()
        val timeData = byteArrayOf(
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            (cal.get(Calendar.YEAR) - 2000).toByte(),
            0,
            cal.get(Calendar.SECOND).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            0
        )
        val deviceTimePayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()

        initQueue.clear()
        initQueue.addLast("SET_EXCHANGE" to buildCommand(CMD_SET_EXCHANGE, SET_EXCHANGE_DATA))
        initQueue.addLast("SET_TIME" to buildCommand(CMD_SET_TIME, timeData))
        initQueue.addLast("DEVICE_TIME" to buildCommand(CMD_WR_VIRT_SFR, deviceTimePayload))

        // Vypnutí interního CPS filtru
        val noFilter = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(VSFR_CPS_FILTER).putInt(0).array()
        initQueue.addLast("CPS_FILTER=0" to buildCommand(CMD_WR_VIRT_SFR, noFilter))

        initInProgress = true
        sendNextInitCommand()
    }

    private fun sendNextInitCommand() {
        val (name, data) = initQueue.removeFirstOrNull() ?: run {
            initInProgress = false
            isReady.set(true)
            Log.d(TAG, "init complete — spouštím timery")
            onConnect()
            startDataTimer()
            startRssiTimer()
            return
        }
        Log.d(TAG, "init: $name")
        enqueueWrite(data)
    }

    private fun enqueueWrite(data: ByteArray) {
        if (isWriting.get()) {
            Log.w(TAG, "enqueueWrite: předchozí zápis ještě běží")
            return
        }
        val chunks = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < data.size) {
            val end = minOf(pos + CHUNK_SIZE, data.size)
            chunks.add(data.sliceArray(pos until end))
            pos = end
        }
        pendingChunks = chunks
        chunkIndex = 0
        isWriting.set(true)
        writeNextChunk()
    }

    @Suppress("DEPRECATION")
    private fun writeNextChunk() {
        val chunk = pendingChunks[chunkIndex++]
        val g = gatt ?: return
        val ch = txChar ?: return

        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = chunk
            g.writeCharacteristic(ch)
        }
    }

    private fun buildCommand(cmdId: Int, args: ByteArray): ByteArray {
        val seqNo = (0x80 + cmdSeq).toByte()
        cmdSeq = (cmdSeq + 1) % 32

        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(cmdId.toShort())
            .put(0)
            .put(seqNo)
            .array()

        val body = header + args
        return ByteBuffer.allocate(4 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(body.size)
            .put(body)
            .array()
    }

    private fun requestDataBuf() {
        if (!isReady.get() || initInProgress) return
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(VS_DATA_BUF).array()
        enqueueWrite(buildCommand(CMD_RD_VIRT_STRING, payload))
    }

    private fun startDataTimer() {
        dataRunnable = Runnable {
            requestDataBuf()
            if (isReady.get()) mainHandler.postDelayed(dataRunnable!!, DATA_INTERVAL_MS)
        }
        mainHandler.postDelayed(dataRunnable!!, DATA_INTERVAL_MS)
    }

    private fun startRssiTimer() {
        rssiRunnable = Runnable {
            if (isReady.get()) try { gatt?.readRemoteRssi() } catch (_: Exception) {}
            if (isReady.get()) mainHandler.postDelayed(rssiRunnable!!, RSSI_INTERVAL_MS)
        }
        mainHandler.postDelayed(rssiRunnable!!, RSSI_INTERVAL_MS)
    }

    private fun parsePacket(chunk: ByteArray) {
        try {
            rxBuffer += chunk
            while (rxBuffer.size >= 4) {
                val pktLen = ByteBuffer.wrap(rxBuffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val total = 4 + pktLen
                if (rxBuffer.size < total) break

                val pkt = rxBuffer.sliceArray(0 until total)
                rxBuffer = rxBuffer.sliceArray(total until rxBuffer.size)

                val body = pkt.sliceArray(4 until total)
                if (body.size < 4) continue

                val cmdId = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
                if (cmdId == CMD_RD_VIRT_STRING) {
                    parseDataBufResponse(body.sliceArray(4 until body.size))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePacket chyba: ${e.message}")
            rxBuffer = ByteArray(0)
        }
    }

    private fun parseDataBufResponse(payload: ByteArray) {
        if (payload.size < 8) return
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val retcode = bb.int
        val dataLen = bb.int
        if (retcode != 1) return
        if (payload.size < 8 + dataLen) return
        parseDataBufData(payload.sliceArray(8 until 8 + dataLen))
    }

    private fun parseDataBufData(data: ByteArray) {
        var pos = 0
        while (pos + 7 <= data.size) {
            val eid = data[pos + 1].toInt() and 0xFF
            val gid = data[pos + 2].toInt() and 0xFF
            pos += 7  // přeskočit 7B hlavičku záznamu

            if (eid == 0) {
                // Pevná délka payloadu podle gid
                val payloadLen = EID0_PAYLOAD_SIZES[gid]
                if (payloadLen == null) {
                    // Neznámý gid — nedokážeme určit délku, data jsou od tohoto místa neplatná
                    break
                }
                if (pos + payloadLen > data.size) break

                when (gid) {
                    // gid=0 - RealTimeData
                    0 -> {
                        val bb = ByteBuffer.wrap(data, pos, 15).order(ByteOrder.LITTLE_ENDIAN)
                        val cpsRaw      = bb.float.toDouble()
                        val doseRateRaw = bb.float.toDouble()

                        if (cpsRaw >= 0.0 && cpsRaw < 1_000_000.0) {
                            lastCps = cpsRaw
                        }

                        // doseRate z gid=0:
                        // - platné: >= 0.01 µSv/h → použít přímo
                        // - neplatné (0 nebo šum < 0.01): přepočítat z aktuálního CPS
                        //   Koeficient ~30 CPS/µSv/h platí pro Cs-137 / RC-103 trubici
                        //   gid=9 (RawDoseRate) přepíše tuto hodnotu pokud přijde ve stejném paketu
                        if (doseRateRaw >= 0.01 && doseRateRaw < 10_000.0) {
                            lastDoseRate = doseRateRaw
                        } else if (lastCps > 0.0) {
                            lastDoseRate = lastCps / 30.0
                        }

                        sendDataToUI()
                    }

                    // gid=3 - RareData (baterie, teplota)
                    3 -> {
                        val bb = ByteBuffer.wrap(data, pos, 14).order(ByteOrder.LITTLE_ENDIAN)
                        bb.int          // duration
                        bb.float        // accumulated dose — ignorujeme
                        val tempRaw   = bb.short.toInt() and 0xFFFF
                        val chargeRaw = bb.short.toInt() and 0xFFFF

                        lastTemperature = (tempRaw - 2000) / 100.0
                        lastBattery     = (chargeRaw / 100).coerceIn(0, 100)

                        sendDataToUI()
                    }

                    // gid=8 - RawCountRate: float CPS + uint16 — alternativní CPS zdroj
                    8 -> {
                        val bb = ByteBuffer.wrap(data, pos, 6).order(ByteOrder.LITTLE_ENDIAN)
                        val rawCps = bb.float.toDouble()
                        // Použít jen pokud gid=0 nedodal platnou hodnotu (záloha)
                        if (rawCps >= 0.0 && rawCps < 1_000_000.0 && lastCps <= 0.0) {
                            lastCps = rawCps
                        }
                    }

                    // gid=9 - RawDoseRate: float doseRate (µSv/h) + uint16
                    // Toto je autoritativní zdroj dose rate při vysokém záření
                    // kdy gid=0 může posílat doseRate=0
                    9 -> {
                        val bb = ByteBuffer.wrap(data, pos, 6).order(ByteOrder.LITTLE_ENDIAN)
                        val rawDose = bb.float.toDouble()
                        if (rawDose >= 0.01 && rawDose < 10_000.0) {
                            lastDoseRate = rawDose
                        }
                    }
                    // ostatní gid — jen posun pos, data nepoužíváme
                }
                pos += payloadLen

            } else if (eid == 1) {
                // Sample bloky — variabilní délka: [samplesNum(2B), smpl_time_ms(4B), data(N*bytesPerSample)]
                if (pos + 6 > data.size) break
                val samplesNum = ByteBuffer.wrap(data, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                pos += 6  // přeskočit samplesNum(2) + smpl_time_ms(4)

                val bytesPerSample = when (gid) {
                    1 -> 8
                    2 -> 16
                    3 -> 14
                    else -> {
                        // Neznámý gid v eid=1 — nedokážeme přeskočit, data jsou od tohoto místa neplatná
                        // NESMÍ být break uvnitř when — to by vyskočilo jen z when, ne z while
                        pos = data.size  // signalizuj konec dat
                        0
                    }
                }
                if (bytesPerSample == 0) break  // neznámý gid — ukončit while

                val blockSize = samplesNum * bytesPerSample
                if (pos + blockSize > data.size) break
                pos += blockSize

            } else {
                // Neznámý eid — konec platných dat
                break
            }
        }
    }

    private fun sendDataToUI() {
        val voltage = if (lastBattery > 0) (3000 + lastBattery * 12).coerceIn(3000, 4200) else 0

        // CPS formát: do 20 CPS → 2 desetinná místa, nad 20 → celé číslo
        // Locale.US povinné — CZ locale produkuje čárku místo tečky → parseLine selže
        val locale = java.util.Locale.US
        val cpsStr = if (lastCps < 20.0) String.format(locale, "%.2f", lastCps)
        else                String.format(locale, "%.0f", lastCps)

        // µSv/h - vždy Locale.US
        val doseStr = when {
            lastDoseRate < 0.01  -> String.format(locale, "%.4f", lastDoseRate)
            lastDoseRate < 0.1   -> String.format(locale, "%.3f", lastDoseRate)
            lastDoseRate <= 20.0 -> String.format(locale, "%.2f", lastDoseRate)
            else                 -> String.format(locale, "%.1f", lastDoseRate)
        }

        // RATE_USV= prefix jednoznačně označuje µSv/h z RadiaCode
        // (na rozdíl od RATE= které CurieFinder HW používá pro CPM)
        val line = String.format(
            java.util.Locale.US,
            "CPS=%s RATE_USV=%s VBAT=%dmV RSSI=%d TEMP=%.1f",
            cpsStr, doseStr, voltage, lastRssi, lastTemperature
        )
        Log.d(TAG, ">>> $line")
        onLine(line)
    }
}