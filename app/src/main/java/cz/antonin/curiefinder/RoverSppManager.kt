package cz.antonin.curiefinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Externí GNSS rover X20 (ESP32 + u-blox ZED-X20P) přes Bluetooth Classic SPP.
 *
 * - Automaticky se připojí ke spárovanému zařízení s názvem "ROVER-*".
 * - Po výpadku se připojuje znovu (pauza 2 → 5 → 10 s, dál po 10 s, bez limitu).
 * - Po každém připojení vybere profil 5 Hz:
 *     {"id":1,"cmd":"get","what":"profiles"}  → první profil s rate==5,
 *     přednostně dyn=="pedestrian"; když není aktivní →
 *     {"id":2,"cmd":"profile","i":<index>}
 * - Parsuje $PRVR,POS (každá epocha, i bez fixu), $PRVR,STA (1 Hz, poslední pole = Hz),
 *   fallback GGA + RMC, když $PRVR,POS nechodí.
 * - Kvalita 6 (jen dopočet) platí nejvýš ROVER_DR_MAX_MS v řadě, pak jako neplatná.
 * - Čas fixu: UTC z POS převedené na hodiny telefonu mediánem (příjem − UTC)
 *   z posledních OFFSET_WINDOW epoch. Zpožděné epochy (zaseknutí BT) se zařadí podle UTC.
 * - Volitelný NTRIP klient (v1): RTCM ze casteru se posílá surově do SPP roveru,
 *   casteru se posílá GGA z roveru každých NTRIP_GGA_INTERVAL_MS.
 * - Příkazy "rec" a "buf" se NEPOSÍLAJÍ (patří RoverGnss).
 *
 * SPP roveru obslouží jen jednoho klienta — současně s CurieFinderem nepojede RoverGnss.
 */
@SuppressLint("MissingPermission")
class RoverSppManager(
    private val context: Context,
    private val onFix: (RoverFix) -> Unit,
    private val onState: (JSONObject) -> Unit
) {
    companion object {
        const val TAG = "RoverSpp"
        const val NAME_PREFIX = "ROVER-"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val PREFS = "curie_prefs"
        const val PREF_ENABLED       = "rover_enabled"
        const val PREF_NTRIP_ENABLED = "ntrip_enabled"
        const val PREF_NTRIP_HOST    = "ntrip_host"
        const val PREF_NTRIP_PORT    = "ntrip_port"
        const val PREF_NTRIP_MOUNT   = "ntrip_mount"
        const val PREF_NTRIP_USER    = "ntrip_user"
        const val PREF_NTRIP_PASS    = "ntrip_pass"

        private val RECONNECT_DELAYS_MS = longArrayOf(2000L, 5000L, 10000L)
        private const val NOT_PAIRED_RETRY_MS     = 10000L
        private const val ROVER_DR_MAX_MS         = 5000L
        private const val OFFSET_WINDOW           = 50
        private const val POS_FALLBACK_MS         = 2000L
        private const val NTRIP_GGA_INTERVAL_MS   = 10000L
        private const val NTRIP_RETRY_MS          = 10000L
        private const val NTRIP_CONNECT_TIMEOUT   = 10000
        private const val NTRIP_READ_TIMEOUT      = 2000
        private const val NTRIP_NO_DATA_MS        = 30000L
        private const val STATE_MIN_INTERVAL_MS   = 900L
        private const val MAX_LINE_LEN            = 2048
    }

    /** Jeden fix roveru předávaný do JS. */
    data class RoverFix(
        val lat: Double,
        val lon: Double,
        val hAcc: Double,       // m, -1 = neznámé
        val quality: Int,       // 0–6 dle roveru
        val valid: Boolean,     // poloha použitelná (q 1–5, q6 nejvýš 5 s)
        val speed: Double,      // m/s, -1 = neznámé
        val course: Double,     // °, -1 = neznámé
        val moving: Int,        // příznak pohybu z IMU, -1 = neznámé
        val tMs: Long,          // čas epochy v hodinách telefonu (epoch ms)
        val utcMs: Long         // UTC z roveru (epoch ms), 0 = neznámé
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private val outLock = Object()
    private var workerThread: Thread? = null

    // Stav pro JS
    @Volatile private var connState = "OFF"     // OFF | NOT_PAIRED | CONNECTING | CONNECTED | DISCONNECTED
    @Volatile private var deviceName = ""
    @Volatile private var profileName = ""
    @Volatile private var profileIndex = -1
    @Volatile private var profileHz = 0
    @Volatile private var profile5HzMissing = false
    @Volatile private var lastStateSentMs = 0L

    // Parser
    private val offsets = ArrayDeque<Long>()
    @Volatile private var lastPosRxMs = 0L
    private var drSinceMs = 0L
    private var lastRmcSpeed = -1.0
    private var lastRmcCourse = -1.0
    @Volatile private var lastGga: String = ""
    private var cmdId = 10

    // NTRIP
    @Volatile private var ntripThread: Thread? = null
    @Volatile private var ntripSocket: Socket? = null
    @Volatile private var ntripState = "OFF"    // OFF | WAIT_ROVER | CONNECTING | OK | ERR
    @Volatile private var ntripMsg = ""
    @Volatile private var ntripBytes = 0L
    @Volatile private var ntripLastDataMs = 0L

    // ── Veřejné API ─────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        workerThread = Thread({ workerLoop() }, "RoverSpp").apply { isDaemon = true; start() }
        restartNtrip()
        Log.d(TAG, "start, enabled=${isEnabled()}")
    }

    fun stop() {
        running = false
        stopNtrip()
        closeSocket()
        workerThread?.interrupt()
        workerThread = null
        connState = "OFF"
        sendState(force = true)
        Log.d(TAG, "stop")
    }

    fun isEnabled(): Boolean = prefs.getBoolean(PREF_ENABLED, true)

    /** Přepínač „X20 / jen mobil“. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply()
        Log.d(TAG, "setEnabled($enabled)")
        if (!enabled) {
            closeSocket()
            connState = "OFF"
        }
        workerThread?.interrupt()   // probudit čekající smyčku
        restartNtrip()
        sendState(force = true)
    }

    /** Konfigurace NTRIP z JS (JSON: enabled, host, port, mount, user, pass). */
    fun setNtripConfig(json: String) {
        try {
            val o = JSONObject(json)
            prefs.edit()
                .putBoolean(PREF_NTRIP_ENABLED, o.optBoolean("enabled", false))
                .putString(PREF_NTRIP_HOST, o.optString("host", "").trim())
                .putInt(PREF_NTRIP_PORT, o.optInt("port", 2101))
                .putString(PREF_NTRIP_MOUNT, o.optString("mount", "").trim())
                .putString(PREF_NTRIP_USER, o.optString("user", ""))
                .putString(PREF_NTRIP_PASS, o.optString("pass", ""))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "setNtripConfig: ${e.message}")
        }
        restartNtrip()
        sendState(force = true)
    }

    /** Konfigurace pro dialog v JS (bez stavu). */
    fun getConfigJson(): String {
        val o = JSONObject()
        o.put("roverEnabled", isEnabled())
        o.put("ntripEnabled", prefs.getBoolean(PREF_NTRIP_ENABLED, false))
        o.put("host", prefs.getString(PREF_NTRIP_HOST, "") ?: "")
        o.put("port", prefs.getInt(PREF_NTRIP_PORT, 2101))
        o.put("mount", prefs.getString(PREF_NTRIP_MOUNT, "") ?: "")
        o.put("user", prefs.getString(PREF_NTRIP_USER, "") ?: "")
        o.put("pass", prefs.getString(PREF_NTRIP_PASS, "") ?: "")
        return o.toString()
    }

    fun stateJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", isEnabled())
        o.put("conn", connState)
        o.put("name", deviceName)
        o.put("prof", profileName)
        o.put("profIdx", profileIndex)
        o.put("hz", profileHz)
        o.put("no5hz", profile5HzMissing)
        o.put("ntrip", ntripState)
        o.put("ntripMsg", ntripMsg)
        o.put("ntripAgeS", if (ntripLastDataMs > 0) (System.currentTimeMillis() - ntripLastDataMs) / 1000 else -1)
        return o
    }

    // ── Připojení a reconnect ──────────────────────────────────────────────

    private fun workerLoop() {
        var attempt = 0
        while (running) {
            if (!isEnabled()) {
                connState = "OFF"
                sendState()
                sleepQuiet(1000L)
                continue
            }
            val device = findRoverDevice()
            if (device == null) {
                connState = "NOT_PAIRED"
                deviceName = ""
                sendState()
                sleepQuiet(NOT_PAIRED_RETRY_MS)
                continue
            }
            deviceName = device.name ?: ""
            connState = "CONNECTING"
            sendState(force = true)
            val ok = connect(device)
            if (ok) {
                attempt = 0
                connState = "CONNECTED"
                profileName = ""; profileIndex = -1; profileHz = 0; profile5HzMissing = false
                offsets.clear()
                drSinceMs = 0L
                sendState(force = true)
                requestProfiles()
                readLoop()            // blokuje do odpojení
                closeSocket()
                if (!running) break
                connState = "DISCONNECTED"
                sendState(force = true)
            } else {
                connState = "DISCONNECTED"
                sendState(force = true)
            }
            if (!running || !isEnabled()) continue
            val delay = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.size - 1)]
            attempt++
            Log.d(TAG, "reconnect za ${delay} ms (pokus $attempt)")
            sleepQuiet(delay)
        }
    }

    private fun findRoverDevice(): BluetoothDevice? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!adapter.isEnabled) return null
            adapter.bondedDevices?.firstOrNull { (it.name ?: "").startsWith(NAME_PREFIX, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "findRoverDevice: ${e.message}")
            null
        }
    }

    private fun connect(device: BluetoothDevice): Boolean {
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
        for (attempt in 1..2) {
            if (!running || !isEnabled()) return false
            var s: BluetoothSocket? = null
            try {
                s = if (attempt == 1) device.createRfcommSocketToServiceRecord(SPP_UUID)
                    else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                output = s.outputStream
                Log.d(TAG, "SPP připojeno: ${device.name} (pokus $attempt)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "SPP pokus $attempt selhal: ${e.message}")
                try { s?.close() } catch (_: Exception) {}
                sleepQuiet(300L)
            }
        }
        return false
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    // ── Čtení ───────────────────────────────────────────────────────────────

    private fun readLoop() {
        val input: InputStream = try { socket?.inputStream ?: return } catch (_: Exception) { return }
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        try {
            while (running && isEnabled()) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF)
                    if (c == '\n'.code) {
                        val line = sb.toString().trim()
                        sb.setLength(0)
                        if (line.isNotEmpty()) handleLine(line, System.currentTimeMillis())
                    } else if (c != '\r'.code) {
                        if (sb.length < MAX_LINE_LEN) sb.append(c.toChar()) else sb.setLength(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readLoop: ${e.message}")
        }
    }

    private fun handleLine(line: String, rxMs: Long) {
        try {
            when {
                line.startsWith("{") -> handleJson(line)
                line.startsWith("\$PRVR,POS") -> if (checksumOk(line)) handlePos(stripChecksum(line), rxMs)
                line.startsWith("\$PRVR,STA") -> if (checksumOk(line)) handleSta(stripChecksum(line))
                line.length > 6 && line[0] == '$' && line.substring(3, 6) == "GGA" -> {
                    if (checksumOk(line)) {
                        lastGga = line
                        if (rxMs - lastPosRxMs > POS_FALLBACK_MS) handleGga(stripChecksum(line), rxMs)
                    }
                }
                line.length > 6 && line[0] == '$' && line.substring(3, 6) == "RMC" -> {
                    if (checksumOk(line)) handleRmc(stripChecksum(line))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleLine '${line.take(60)}': ${e.message}")
        }
    }

    // ── JSON odpovědi a profil 5 Hz ─────────────────────────────────────────

    private fun requestProfiles() {
        sendCommand("""{"id":1,"cmd":"get","what":"profiles"}""")
    }

    private fun handleJson(line: String) {
        val o = JSONObject(line)
        when (o.optInt("id", -1)) {
            1 -> {
                if (!o.optBoolean("ok", false)) { Log.w(TAG, "get profiles: ${o.optString("err")}"); return }
                val data = o.optJSONObject("data") ?: return
                val active = data.optInt("active", -1)
                val arr = data.optJSONArray("profiles") ?: return
                var best = -1
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (p.optInt("rate", 0) != 5) continue
                    if (best < 0) best = i
                    if (p.optString("dyn", "") == "pedestrian") { best = i; break }
                }
                if (best < 0) {
                    profile5HzMissing = true
                    if (active in 0 until arr.length()) {
                        val p = arr.optJSONObject(active)
                        profileIndex = active
                        profileName = p?.optString("name", "") ?: ""
                        profileHz = p?.optInt("rate", 0) ?: 0
                    }
                    Log.w(TAG, "žádný profil 5 Hz")
                    sendState(force = true)
                    return
                }
                profile5HzMissing = false
                if (best == active) {
                    val p = arr.optJSONObject(best)
                    profileIndex = best
                    profileName = p?.optString("name", "") ?: ""
                    profileHz = 5
                    Log.d(TAG, "profil 5 Hz už aktivní: $best $profileName")
                    sendState(force = true)
                } else {
                    Log.d(TAG, "přepínám na profil $best")
                    sendCommand("""{"id":2,"cmd":"profile","i":$best}""")
                }
            }
            2 -> {
                if (o.optBoolean("ok", false)) {
                    profileIndex = o.optInt("i", profileIndex)
                    profileName = o.optString("name", profileName)
                    if (o.has("hz")) profileHz = o.optInt("hz", profileHz)
                    Log.d(TAG, "profil nastaven: $profileIndex $profileName ${profileHz} Hz")
                } else {
                    Log.w(TAG, "profile: ${o.optString("err")}")
                    profile5HzMissing = true
                }
                sendState(force = true)
            }
            3 -> {
                val prof = o.optJSONObject("prof") ?: return
                profileIndex = prof.optInt("i", profileIndex)
                profileName = prof.optString("name", profileName)
                profileHz = prof.optInt("hz", profileHz)
                sendState(force = true)
            }
        }
    }

    private fun sendCommand(json: String) {
        writeRaw((json + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun writeRaw(bytes: ByteArray): Boolean {
        synchronized(outLock) {
            val out = output ?: return false
            return try {
                out.write(bytes)
                out.flush()
                true
            } catch (e: Exception) {
                Log.w(TAG, "write: ${e.message}")
                false
            }
        }
    }

    // ── $PRVR,POS ───────────────────────────────────────────────────────────
    // Pole (k = index za "POS"): 1 UTC čas, 2 datum, 3 φ, 4 λ, 5 h_ell, 6 h_msl,
    // 7–9 σN/σE/σU, 10 hAcc, 11 vAcc, 12 kvalita, 13 carrSoln, 14 RTCM, 15 stáří korekcí,
    // 16 počet SV, 17 PDOP, 18 rychlost, 19 kurz, 20 pohyb, 21 hp

    private fun handlePos(body: String, rxMs: Long) {
        val p = body.split(',')
        fun f(k: Int): String = if (k + 1 < p.size) p[k + 1].trim() else ""
        lastPosRxMs = rxMs

        val q = f(12).toIntOrNull() ?: 0
        val lat = f(3).toDoubleOrNull()
        val lon = f(4).toDoubleOrNull()
        val hAcc = f(10).toDoubleOrNull() ?: -1.0
        val speed = f(18).toDoubleOrNull() ?: -1.0
        val course = f(19).toDoubleOrNull() ?: -1.0
        val moving = f(20).toIntOrNull() ?: -1
        val utcMs = parseUtc(f(1), f(2))

        emitFix(lat, lon, hAcc, q, speed, course, moving, utcMs, rxMs)
    }

    private fun emitFix(lat: Double?, lon: Double?, hAcc: Double, q: Int, speed: Double,
                        course: Double, moving: Int, utcMs: Long, rxMs: Long) {
        var valid = lat != null && lon != null && q in 1..6 &&
                Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0 && !(lat == 0.0 && lon == 0.0)
        if (valid && q == 6) {
            if (drSinceMs == 0L) drSinceMs = rxMs
            if (rxMs - drSinceMs > ROVER_DR_MAX_MS) valid = false
        } else if (q != 6) {
            drSinceMs = 0L
        }
        val tMs = toPhoneTime(utcMs, rxMs)
        onFix(RoverFix(lat ?: 0.0, lon ?: 0.0, hAcc, q, valid, speed, course, moving, tMs, utcMs))
    }

    /** UTC → hodiny telefonu přes medián (příjem − UTC). Zpožděné epochy medián neovlivní. */
    private fun toPhoneTime(utcMs: Long, rxMs: Long): Long {
        if (utcMs <= 0L) return rxMs
        val off = rxMs - utcMs
        offsets.addLast(off)
        while (offsets.size > OFFSET_WINDOW) offsets.removeFirst()
        val sorted = offsets.sorted()
        val med = sorted[sorted.size / 2]
        return utcMs + med
    }

    /** UTC čas a datum: "hhmmss(.sss)" + "ddmmyy", nebo "hh:mm:ss(.sss)" + "yyyy-mm-dd". */
    private fun parseUtc(time: String, date: String): Long {
        try {
            if (time.isEmpty() || date.isEmpty()) return 0L
            val h: Int; val mi: Int; val secD: Double
            if (time.contains(':')) {
                val t = time.split(':')
                h = t[0].toInt(); mi = t[1].toInt(); secD = t[2].toDouble()
            } else {
                h = time.substring(0, 2).toInt()
                mi = time.substring(2, 4).toInt()
                secD = time.substring(4).toDouble()
            }
            val y: Int; val mo: Int; val d: Int
            if (date.contains('-')) {
                val dd = date.split('-')
                y = dd[0].toInt(); mo = dd[1].toInt(); d = dd[2].toInt()
            } else {
                d = date.substring(0, 2).toInt()
                mo = date.substring(2, 4).toInt()
                val yy = date.substring(4).toInt()
                y = if (yy < 100) 2000 + yy else yy
            }
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, secD.toInt())
            return cal.timeInMillis + Math.round((secD - secD.toInt()) * 1000.0)
        } catch (_: Exception) {
            return 0L
        }
    }

    // ── $PRVR,STA ── pole 4 = index profilu, 6 = název, poslední = Hz ─────────

    private fun handleSta(body: String) {
        val p = body.split(',')
        fun f(k: Int): String = if (k + 1 < p.size) p[k + 1].trim() else ""
        val idx = f(4).toIntOrNull()
        val name = f(6)
        val hz = p.lastOrNull()?.trim()?.toIntOrNull()
        if (idx != null) profileIndex = idx
        if (name.isNotEmpty()) profileName = name
        if (hz != null && hz in 1..20) profileHz = hz
        sendState()
    }

    // ── Fallback GGA + RMC ─────────────────────────────────────────────────

    private fun handleGga(body: String, rxMs: Long) {
        val p = body.split(',')
        if (p.size < 10) return
        val q = p[6].toIntOrNull() ?: 0
        val lat = nmeaToDeg(p[2], p[3])
        val lon = nmeaToDeg(p[4], p[5])
        val utcMs = ggaUtc(p[1])
        emitFix(lat, lon, -1.0, q, lastRmcSpeed, lastRmcCourse, -1, utcMs, rxMs)
    }

    private fun handleRmc(body: String) {
        val p = body.split(',')
        if (p.size < 9) return
        val knots = p[7].toDoubleOrNull()
        lastRmcSpeed = if (knots != null) knots * 0.514444 else -1.0
        lastRmcCourse = p[8].toDoubleOrNull() ?: -1.0
    }

    /** GGA má jen čas — datum se doplní z hodin telefonu (UTC). */
    private fun ggaUtc(time: String): Long {
        if (time.length < 6) return 0L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val dd = String.format("%02d%02d%02d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR) % 100)
        val ms = parseUtc(time, dd)
        if (ms <= 0L) return 0L
        // přechod přes půlnoc: rozdíl víc než 12 h → posun o den
        val now = System.currentTimeMillis()
        return when {
            ms - now > 12 * 3600_000L -> ms - 86400_000L
            now - ms > 12 * 3600_000L -> ms + 86400_000L
            else -> ms
        }
    }

    private fun nmeaToDeg(v: String, hemi: String): Double? {
        val x = v.toDoubleOrNull() ?: return null
        val deg = Math.floor(x / 100.0)
        val res = deg + (x - deg * 100.0) / 60.0
        return if (hemi == "S" || hemi == "W") -res else res
    }

    // ── Checksum ────────────────────────────────────────────────────────────

    private fun checksumOk(line: String): Boolean {
        val star = line.lastIndexOf('*')
        if (star < 1 || star + 3 > line.length) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor line[i].code
        val given = line.substring(star + 1, star + 3).toIntOrNull(16) ?: return false
        return cs == given
    }

    private fun stripChecksum(line: String): String {
        val star = line.lastIndexOf('*')
        return if (star > 0) line.substring(0, star) else line
    }

    // ── Stav do JS ──────────────────────────────────────────────────────────

    private fun sendState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStateSentMs < STATE_MIN_INTERVAL_MS) return
        lastStateSentMs = now
        try { onState(stateJson()) } catch (e: Exception) { Log.w(TAG, "sendState: ${e.message}") }
    }

    // ── NTRIP klient (v1) ──────────────────────────────────────────────────

    private fun restartNtrip() {
        stopNtrip()
        if (!prefs.getBoolean(PREF_NTRIP_ENABLED, false) || !isEnabled()) {
            ntripState = "OFF"; ntripMsg = ""
            return
        }
        val t = Thread({ ntripLoop() }, "RoverNtrip")
        t.isDaemon = true
        ntripThread = t
        t.start()
    }

    private fun stopNtrip() {
        val t = ntripThread
        ntripThread = null
        try { ntripSocket?.close() } catch (_: Exception) {}
        ntripSocket = null
        t?.interrupt()
    }

    private fun ntripLoop() {
        val me = Thread.currentThread()
        while (running && ntripThread === me) {
            val host = prefs.getString(PREF_NTRIP_HOST, "") ?: ""
            val port = prefs.getInt(PREF_NTRIP_PORT, 2101)
            val mount = (prefs.getString(PREF_NTRIP_MOUNT, "") ?: "").trimStart('/')
            val user = prefs.getString(PREF_NTRIP_USER, "") ?: ""
            val pass = prefs.getString(PREF_NTRIP_PASS, "") ?: ""
            if (host.isEmpty() || mount.isEmpty()) {
                ntripState = "ERR"; ntripMsg = "CONFIG"
                sendState(force = true)
                return
            }
            if (connState != "CONNECTED") {
                ntripState = "WAIT_ROVER"; ntripMsg = ""
                sendState()
                sleepQuiet(2000L)
                continue
            }
            var s: Socket? = null
            try {
                ntripState = "CONNECTING"; ntripMsg = ""
                sendState(force = true)
                s = Socket()
                s.connect(InetSocketAddress(host, port), NTRIP_CONNECT_TIMEOUT)
                s.soTimeout = NTRIP_READ_TIMEOUT
                ntripSocket = s
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                val auth = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val req = StringBuilder()
                    .append("GET /").append(mount).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("User-Agent: NTRIP CurieFinder/1.0\r\n")
                    .append("Accept: */*\r\n")
                if (user.isNotEmpty()) req.append("Authorization: Basic ").append(auth).append("\r\n")
                req.append("\r\n")
                out.write(req.toString().toByteArray(Charsets.ISO_8859_1))
                out.flush()

                // Hlavička odpovědi
                val header = readHeader(inp)
                val first = header.lineSequence().firstOrNull()?.trim() ?: ""
                if (!(first.startsWith("ICY 200") || first.matches(Regex("HTTP/1\\.[01] 200.*")))) {
                    ntripState = "ERR"
                    ntripMsg = first.take(60)
                    sendState(force = true)
                    Log.w(TAG, "NTRIP odmítnuto: $first")
                    try { s.close() } catch (_: Exception) {}
                    sleepQuiet(NTRIP_RETRY_MS)
                    continue
                }
                ntripState = "OK"; ntripMsg = ""; ntripBytes = 0L
                sendState(force = true)
                Log.d(TAG, "NTRIP připojeno: $host:$port/$mount")

                var lastGgaSent = 0L
                val connectedAt = System.currentTimeMillis()
                val buf = ByteArray(2048)
                while (running && ntripThread === me && connState == "CONNECTED") {
                    val now = System.currentTimeMillis()
                    if (now - lastGgaSent >= NTRIP_GGA_INTERVAL_MS && lastGga.isNotEmpty()) {
                        out.write((lastGga + "\r\n").toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                        lastGgaSent = now
                    }
                    val n = try { inp.read(buf) } catch (_: java.net.SocketTimeoutException) { 0 }
                    if (n < 0) throw java.io.IOException("caster ukončil spojení")
                    if (n == 0 && System.currentTimeMillis() - maxOf(ntripLastDataMs, connectedAt) > NTRIP_NO_DATA_MS)
                        throw java.io.IOException("žádná data z casteru")
                    if (n > 0) {
                        writeRaw(buf.copyOf(n))
                        ntripBytes += n
                        ntripLastDataMs = System.currentTimeMillis()
                        sendState()
                    }
                }
            } catch (e: Exception) {
                if (ntripThread === me) {
                    ntripState = "ERR"
                    ntripMsg = (e.message ?: e.javaClass.simpleName).take(60)
                    sendState(force = true)
                    Log.w(TAG, "NTRIP: ${e.message}")
                }
            } finally {
                try { s?.close() } catch (_: Exception) {}
                if (ntripSocket === s) ntripSocket = null
            }
            if (running && ntripThread === me) sleepQuiet(NTRIP_RETRY_MS)
        }
    }

    /**
     * Hlavička odpovědi casteru. NTRIP v1 odpovídá "ICY 200 OK\r\n" a hned za tím
     * mohou následovat RTCM data — proto se u ICY čte jen první řádek.
     * HTTP odpověď se čte do prázdného řádku.
     */
    private fun readHeader(inp: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val c = inp.read()
            if (c < 0) break
            sb.append(c.toChar())
            if (c == '\n'.code) {
                val text = sb.toString()
                if (text.startsWith("ICY")) break
                if (text.endsWith("\r\n\r\n") || text.endsWith("\n\n")) break
            }
        }
        return sb.toString()
    }
}
