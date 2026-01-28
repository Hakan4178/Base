package com.benim.benim.net

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class UdpService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "UdpService"
        private const val SERVER_PORT = 26760
        private const val SERVER_TIMEOUT_MS = 5000L
        private const val FALLBACK_IP = "127.0.0.1"

        // ═══════════════════════════════════════════════════════════
        // PAKET TİPLERİ
        // ═══════════════════════════════════════════════════════════
        const val PACKET_GAMEPAD: Byte = 0x01
        const val PACKET_MOUSE_MOVE: Byte = 0x02
        const val PACKET_MOUSE_BUTTON: Byte = 0x03
        const val PACKET_MOUSE_WHEEL: Byte = 0x04
        const val PACKET_GYRO: Byte = 0x0D
        const val PACKET_PING: Byte = 0x7F

        // ═══════════════════════════════════════════════════════════
        // BUTON BİT MASKELERİ (32-bit)
        // ═══════════════════════════════════════════════════════════
        const val BTN_A: Int           = 1 shl 0
        const val BTN_B: Int           = 1 shl 1
        const val BTN_X: Int           = 1 shl 2
        const val BTN_Y: Int           = 1 shl 3
        const val BTN_L1: Int          = 1 shl 4
        const val BTN_R1: Int          = 1 shl 5
        const val BTN_L2: Int          = 1 shl 6
        const val BTN_R2: Int          = 1 shl 7
        const val BTN_SELECT: Int      = 1 shl 8
        const val BTN_START: Int       = 1 shl 9
        const val BTN_HOME: Int        = 1 shl 10
        const val BTN_L3: Int          = 1 shl 11
        const val BTN_R3: Int          = 1 shl 12
        const val BTN_DPAD_UP: Int     = 1 shl 13
        const val BTN_DPAD_DOWN: Int   = 1 shl 14
        const val BTN_DPAD_LEFT: Int   = 1 shl 15
        const val BTN_DPAD_RIGHT: Int  = 1 shl 16
        const val BTN_TOUCHPAD: Int    = 1 shl 17
        const val BTN_CAPTURE: Int     = 1 shl 18
        const val BTN_MIC: Int         = 1 shl 19
        const val BTN_PADDLE_1: Int    = 1 shl 20
        const val BTN_PADDLE_2: Int    = 1 shl 21
        const val BTN_PADDLE_3: Int    = 1 shl 22
        const val BTN_PADDLE_4: Int    = 1 shl 23

        // ═══════════════════════════════════════════════════════════
        // BROADCAST
        // ═══════════════════════════════════════════════════════════
        const val ACTION_STATUS = "com.benim.ACTION_STATUS"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PING = "ping_ms"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_IS_ALIVE = "is_alive"

        const val TYPE_DISCOVERY_START = "discovery_start"
        const val TYPE_DISCOVERY_SUCCESS = "discovery_success"
        const val TYPE_DISCOVERY_FAILED = "discovery_failed"
        const val TYPE_PING_UPDATE = "ping_update"
        const val TYPE_SERVER_TIMEOUT = "server_timeout"
    }

    // ═══════════════════════════════════════════════════════════════
    // BINDER
    // ═══════════════════════════════════════════════════════════════
    inner class LocalBinder : Binder() {
        fun getService(): UdpService = this@UdpService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ═══════════════════════════════════════════════════════════════
    // NETWORK
    // ═══════════════════════════════════════════════════════════════
    private lateinit var socket: DatagramSocket
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    private val serverLock = Any()

    @Volatile private var serverIpInternal: String? = null
    @Volatile private var serverAddressCache: InetAddress? = null
    @Volatile private var running = false
    @Volatile private var _isServerAlive = false
    @Volatile private var lastServerResponseTime: Long = 0
    @Volatile var lastPingMs: Long = -1

    val isServerAlive: Boolean get() = _isServerAlive

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD STATE
    // ═══════════════════════════════════════════════════════════════
    private val gamepadLock = Any()

    @Volatile private var buttons: Int = 0
    @Volatile private var leftStickX: Int = 0
    @Volatile private var leftStickY: Int = 0
    @Volatile private var rightStickX: Int = 0
    @Volatile private var rightStickY: Int = 0
    @Volatile private var triggerL2: Int = 0
    @Volatile private var triggerR2: Int = 0

    @Volatile private var lastSendTime: Long = 0
    private val SEND_INTERVAL_MS = 8L

    // 12-byte paket (XOR dahil)
    private val gamepadPacket = ByteArray(12)

    // ═══════════════════════════════════════════════════════════════
    // GYRO
    // ═══════════════════════════════════════════════════════════════
    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    @Volatile var gyroEnabled = false
        private set

    // Gyro ayarları
    @Volatile var gyroLandscapeOnly = false  // false = her yönde çalışır
    @Volatile var gyroSensitivity = 1.0f     // Hassasiyet çarpanı

    private var lastGyroSendTime: Long = 0
    private val GYRO_SEND_INTERVAL_MS = 16L  // ~60 FPS

    // Gyro debug
    @Volatile var gyroPacketsSent: Long = 0
        private set

    // 7-byte gyro paketi (daha basit: int16 format)
    private val gyroPacket = ByteArray(7)

    // ═══════════════════════════════════════════════════════════════
    // UI HANDLER
    // ═══════════════════════════════════════════════════════════════
    private val mainHandler = Handler(Looper.getMainLooper())

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 3000
                receiveBufferSize = 65536
                sendBufferSize = 65536
            }
        } catch (e: Exception) {
            showToast("❌ Socket hatası: ${e.message}")
            return
        }

        // Gyro sensör init
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroSensor != null) {
            Log.d(TAG, "✅ Gyro sensör bulundu: ${gyroSensor?.name}")
        } else {
            Log.w(TAG, "⚠️ Gyro sensör bulunamadı!")
        }

        running = true
        startSender()
        startListener()
        startPingLoop()
        startAliveMonitor()

        discoverServer()
        showToast("🚀 UDP Servisi başlatıldı")
    }

    override fun onDestroy() {
        running = false
        disableGyro()
        try { socket.close() } catch (_: Exception) {}
        showToast("🛑 UDP Servisi durduruldu")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD API - BUTONLAR
    // ═══════════════════════════════════════════════════════════════

    fun setButton(button: Int, pressed: Boolean) {
        synchronized(gamepadLock) {
            buttons = if (pressed) buttons or button else buttons and button.inv()
        }
        sendGamepadPacket()
    }

    fun setButtons(mask: Int, pressed: Boolean) {
        synchronized(gamepadLock) {
            buttons = if (pressed) buttons or mask else buttons and mask.inv()
        }
        sendGamepadPacket()
    }

    fun setButtonState(newState: Int) {
        synchronized(gamepadLock) { buttons = newState }
        sendGamepadPacket()
    }

    fun clearButtons() {
        synchronized(gamepadLock) { buttons = 0 }
        sendGamepadPacket()
    }

    fun isButtonPressed(button: Int): Boolean = (buttons and button) != 0

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD API - JOYSTICK
    // ═══════════════════════════════════════════════════════════════

    fun setLeftStick(x: Float, y: Float) {
        synchronized(gamepadLock) {
            leftStickX = (x.coerceIn(-1f, 1f) * 127).toInt()
            leftStickY = (y.coerceIn(-1f, 1f) * 127).toInt()
        }
        sendGamepadPacket()
    }

    fun setLeftStickRaw(x: Int, y: Int) {
        synchronized(gamepadLock) {
            leftStickX = x.coerceIn(-127, 127)
            leftStickY = y.coerceIn(-127, 127)
        }
        sendGamepadPacket()
    }

    fun setRightStick(x: Float, y: Float) {
        synchronized(gamepadLock) {
            rightStickX = (x.coerceIn(-1f, 1f) * 127).toInt()
            rightStickY = (y.coerceIn(-1f, 1f) * 127).toInt()
        }
        sendGamepadPacket()
    }

    fun setRightStickRaw(x: Int, y: Int) {
        synchronized(gamepadLock) {
            rightStickX = x.coerceIn(-127, 127)
            rightStickY = y.coerceIn(-127, 127)
        }
        sendGamepadPacket()
    }

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD API - TETİKLER
    // ═══════════════════════════════════════════════════════════════

    fun setL2(value: Float) {
        synchronized(gamepadLock) {
            triggerL2 = (value.coerceIn(0f, 1f) * 255).toInt()
            buttons = if (triggerL2 > 20) buttons or BTN_L2 else buttons and BTN_L2.inv()
        }
        sendGamepadPacket()
    }

    fun setR2(value: Float) {
        synchronized(gamepadLock) {
            triggerR2 = (value.coerceIn(0f, 1f) * 255).toInt()
            buttons = if (triggerR2 > 20) buttons or BTN_R2 else buttons and BTN_R2.inv()
        }
        sendGamepadPacket()
    }

    fun setTriggers(l2: Float, r2: Float) {
        synchronized(gamepadLock) {
            triggerL2 = (l2.coerceIn(0f, 1f) * 255).toInt()
            triggerR2 = (r2.coerceIn(0f, 1f) * 255).toInt()
            buttons = if (triggerL2 > 20) buttons or BTN_L2 else buttons and BTN_L2.inv()
            buttons = if (triggerR2 > 20) buttons or BTN_R2 else buttons and BTN_R2.inv()
        }
        sendGamepadPacket()
    }

    fun setTriggersRaw(l2: Int, r2: Int) {
        synchronized(gamepadLock) {
            triggerL2 = l2.coerceIn(0, 255)
            triggerR2 = r2.coerceIn(0, 255)
            buttons = if (triggerL2 > 20) buttons or BTN_L2 else buttons and BTN_L2.inv()
            buttons = if (triggerR2 > 20) buttons or BTN_R2 else buttons and BTN_R2.inv()
        }
        sendGamepadPacket()
    }

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD API - BATCH UPDATE
    // ═══════════════════════════════════════════════════════════════

    fun update(
        btns: Int? = null,
        lx: Float? = null, ly: Float? = null,
        rx: Float? = null, ry: Float? = null,
        l2: Float? = null, r2: Float? = null
    ) {
        synchronized(gamepadLock) {
            btns?.let { buttons = it }
            lx?.let { leftStickX = (it.coerceIn(-1f, 1f) * 127).toInt() }
            ly?.let { leftStickY = (it.coerceIn(-1f, 1f) * 127).toInt() }
            rx?.let { rightStickX = (it.coerceIn(-1f, 1f) * 127).toInt() }
            ry?.let { rightStickY = (it.coerceIn(-1f, 1f) * 127).toInt() }
            l2?.let {
                triggerL2 = (it.coerceIn(0f, 1f) * 255).toInt()
                buttons = if (triggerL2 > 20) buttons or BTN_L2 else buttons and BTN_L2.inv()
            }
            r2?.let {
                triggerR2 = (it.coerceIn(0f, 1f) * 255).toInt()
                buttons = if (triggerR2 > 20) buttons or BTN_R2 else buttons and BTN_R2.inv()
            }
        }
        sendGamepadPacket()
    }

    fun updateRaw(
        btns: Int = buttons,
        lx: Int = leftStickX, ly: Int = leftStickY,
        rx: Int = rightStickX, ry: Int = rightStickY,
        l2: Int = triggerL2, r2: Int = triggerR2
    ) {
        synchronized(gamepadLock) {
            buttons = btns
            leftStickX = lx.coerceIn(-127, 127)
            leftStickY = ly.coerceIn(-127, 127)
            rightStickX = rx.coerceIn(-127, 127)
            rightStickY = ry.coerceIn(-127, 127)
            triggerL2 = l2.coerceIn(0, 255)
            triggerR2 = r2.coerceIn(0, 255)
        }
        sendGamepadPacket()
    }

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD PAKET GÖNDERİMİ (12 BYTE + XOR)
    // ═══════════════════════════════════════════════════════════════

    private fun sendGamepadPacket() {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < SEND_INTERVAL_MS) return
        lastSendTime = now

        if (serverAddressCache == null) return

        synchronized(gamepadLock) {
            // Byte 0: Header
            gamepadPacket[0] = PACKET_GAMEPAD

            // Byte 1-4: Buttons (Little-Endian)
            gamepadPacket[1] = (buttons and 0xFF).toByte()
            gamepadPacket[2] = ((buttons shr 8) and 0xFF).toByte()
            gamepadPacket[3] = ((buttons shr 16) and 0xFF).toByte()
            gamepadPacket[4] = ((buttons shr 24) and 0xFF).toByte()

            // Byte 5-8: Joystick
            gamepadPacket[5] = leftStickX.toByte()
            gamepadPacket[6] = leftStickY.toByte()
            gamepadPacket[7] = rightStickX.toByte()
            gamepadPacket[8] = rightStickY.toByte()

            // Byte 9-10: Tetikler
            gamepadPacket[9] = triggerL2.toByte()
            gamepadPacket[10] = triggerR2.toByte()

            // Byte 11: XOR Checksum
            var xor = 0
            for (i in 0..10) {
                xor = xor xor (gamepadPacket[i].toInt() and 0xFF)
            }
            gamepadPacket[11] = xor.toByte()
        }

        sendQueue.offer(gamepadPacket.copyOf())
    }

    fun forceUpdate() {
        lastSendTime = 0
        sendGamepadPacket()
    }

    // ═══════════════════════════════════════════════════════════════
    // MOUSE API
    // ═══════════════════════════════════════════════════════════════

    fun sendMouseMove(dx: Int, dy: Int) {
        sendRaw(byteArrayOf(
            PACKET_MOUSE_MOVE,
            dx.coerceIn(-128, 127).toByte(),
            dy.coerceIn(-128, 127).toByte()
        ))
    }

    fun sendMouseButton(button: Byte, pressed: Boolean) {
        sendRaw(byteArrayOf(PACKET_MOUSE_BUTTON, button, if (pressed) 1 else 0))
    }

    fun sendMouseWheel(delta: Int) {
        sendRaw(byteArrayOf(PACKET_MOUSE_WHEEL, delta.coerceIn(-128, 127).toByte()))
    }

    // ═══════════════════════════════════════════════════════════════
    // GYRO API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gyro sensörünü etkinleştir
     * @return true = başarılı, false = sensör yok
     */
    fun enableGyro(): Boolean {
        if (gyroSensor == null) {
            Log.w(TAG, "Gyro sensör yok!")
            return false
        }

        if (!gyroEnabled) {
            sensorManager?.registerListener(
                this,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            gyroEnabled = true
            gyroPacketsSent = 0
            Log.d(TAG, "✅ Gyro etkinleştirildi")
            showToast("🌀 Gyro açık")
        }
        return true
    }

    /**
     * Gyro sensörünü devre dışı bırak
     */
    fun disableGyro() {
        if (gyroEnabled) {
            sensorManager?.unregisterListener(this)
            gyroEnabled = false
            Log.d(TAG, "⏹️ Gyro devre dışı")
        }
    }

    /**
     * Gyro toggle
     */
    fun toggleGyro(): Boolean {
        return if (gyroEnabled) {
            disableGyro()
            false
        } else {
            enableGyro()
        }
    }

    /**
     * Gyro sensör olayı
     *
     * PAKET FORMATI (7 byte - Ham Int16):
     * [0]   = 0x0D (Header)
     * [1-2] = gX (int16, Little-Endian, ±32767)
     * [3-4] = gY (int16)
     * [5-6] = gZ (int16)
     *
     * Değer aralığı: ±500 deg/s = ±32767
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return
        if (!gyroEnabled) return

        // Opsiyonel: Sadece landscape modda çalış
        if (gyroLandscapeOnly && !isLandscape()) return

        // Rate limit
        val now = System.currentTimeMillis()
        if (now - lastGyroSendTime < GYRO_SEND_INTERVAL_MS) return
        lastGyroSendTime = now

        // Server kontrolü
        if (serverAddressCache == null) return

        // Ham değerler (rad/s)
        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]

        // rad/s → deg/s: değer * (180 / π) ≈ değer * 57.2958
        val degX = rawX * 57.2958f * gyroSensitivity
        val degY = rawY * 57.2958f * gyroSensitivity
        val degZ = rawZ * 57.2958f * gyroSensitivity

        // ±500 deg/s = ±32767 olacak şekilde ölçekle
        val scale = 32767f / 500f  // ≈ 65.534

        val gx = (degX * scale).toInt().coerceIn(-32767, 32767)
        val gy = (degY * scale).toInt().coerceIn(-32767, 32767)
        val gz = (degZ * scale).toInt().coerceIn(-32767, 32767)

        // Paket oluştur (7 byte)
        gyroPacket[0] = PACKET_GYRO

        // gX (Little-Endian int16)
        gyroPacket[1] = (gx and 0xFF).toByte()
        gyroPacket[2] = ((gx shr 8) and 0xFF).toByte()

        // gY
        gyroPacket[3] = (gy and 0xFF).toByte()
        gyroPacket[4] = ((gy shr 8) and 0xFF).toByte()

        // gZ
        gyroPacket[5] = (gz and 0xFF).toByte()
        gyroPacket[6] = ((gz shr 8) and 0xFF).toByte()

        sendQueue.offer(gyroPacket.copyOf())
        gyroPacketsSent++

        // Debug log (her 60 pakette bir)
        if (gyroPacketsSent % 60 == 0L) {
            Log.d(TAG, "Gyro #$gyroPacketsSent: X=$gx Y=$gy Z=$gz (raw: ${rawX.format(2)}, ${rawY.format(2)}, ${rawZ.format(2)})")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Gyro durumunu kontrol et
     */
    fun hasGyro(): Boolean = gyroSensor != null

    /**
     * Gyro debug bilgisi
     */
    fun getGyroDebug(): String {
        return if (gyroSensor != null) {
            "Gyro: ${if (gyroEnabled) "AÇIK" else "KAPALI"} | " +
                    "Paket: $gyroPacketsSent | " +
                    "Sensör: ${gyroSensor?.name}"
        } else {
            "Gyro: YOK"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NETWORK
    // ═══════════════════════════════════════════════════════════════

    fun discoverServer() {
        sendStatusBroadcast(TYPE_DISCOVERY_START)

        thread(name = "udp-discover") {
            try {
                val discoverSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3000
                }

                val msg = "DISCOVER_JOYSTICK_SERVER"
                val packet = DatagramPacket(
                    msg.toByteArray(), msg.length,
                    InetAddress.getByName("255.255.255.255"), SERVER_PORT
                )
                discoverSocket.send(packet)

                val buf = ByteArray(256)
                val response = DatagramPacket(buf, buf.size)
                discoverSocket.receive(response)

                val responseStr = String(response.data, 0, response.length)
                if (responseStr.startsWith("I_AM_SERVER")) {
                    val ip = response.address.hostAddress ?: FALLBACK_IP
                    setServerIp(ip)
                    showToast("✅ Sunucu: $ip")
                    sendStatusBroadcast(TYPE_DISCOVERY_SUCCESS, ip)
                }
                discoverSocket.close()

            } catch (e: Exception) {
                setServerIp(FALLBACK_IP)
                showToast("⚠️ Fallback: $FALLBACK_IP")
                sendStatusBroadcast(TYPE_DISCOVERY_FAILED, FALLBACK_IP)
            }
        }
    }

    fun setServerIp(ip: String?) {
        synchronized(serverLock) {
            serverIpInternal = ip
            serverAddressCache = ip?.let {
                try { InetAddress.getByName(it) } catch (_: Exception) { null }
            }
            _isServerAlive = false
            lastPingMs = -1
        }
    }

    fun getServerIp(): String? = synchronized(serverLock) { serverIpInternal }

    fun sendRaw(data: ByteArray) {
        sendQueue.offer(data)
    }

    private fun startSender() {
        thread(name = "udp-sender") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            while (running) {
                try {
                    val data = sendQueue.take()
                    val addr = serverAddressCache ?: continue
                    socket.send(DatagramPacket(data, data.size, addr, SERVER_PORT))
                } catch (_: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, "Send: ${e.message}") }
            }
        }
    }

    private fun startListener() {
        thread(name = "udp-listener") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)

            while (running) {
                try {
                    socket.receive(packet)
                    if (packet.length <= 0) continue

                    when (buffer[0]) {
                        PACKET_PING -> handlePingResponse(buffer, packet.length)
                        else -> {
                            val msg = String(buffer, 0, packet.length)
                            if (msg.startsWith("I_AM_SERVER")) {
                                packet.address.hostAddress?.let { setServerIp(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running && e.message?.contains("timed out") != true) {
                        Log.e(TAG, "Listen: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handlePingResponse(data: ByteArray, len: Int) {
        if (len < 9) return

        var sentTime: Long = 0
        for (i in 0..7) {
            sentTime = (sentTime shl 8) or (data[1 + i].toLong() and 0xFF)
        }

        val rtt = System.currentTimeMillis() - sentTime
        val wasAlive = _isServerAlive

        synchronized(serverLock) {
            lastPingMs = rtt
            _isServerAlive = true
            lastServerResponseTime = System.currentTimeMillis()
        }

        if (!wasAlive) showToast("🟢 Bağlantı: ${rtt}ms")
        sendStatusBroadcast(TYPE_PING_UPDATE)
    }

    private fun startPingLoop() {
        thread(name = "udp-ping") {
            Thread.sleep(500)
            while (running) {
                serverAddressCache?.let {
                    val time = System.currentTimeMillis()
                    val buf = ByteArray(9)
                    buf[0] = PACKET_PING
                    for (i in 0..7) buf[i + 1] = (time shr (56 - i * 8)).toByte()
                    sendQueue.offer(buf)
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun startAliveMonitor() {
        thread(name = "udp-alive") {
            while (running) {
                Thread.sleep(1700)
                synchronized(serverLock) {
                    val elapsed = System.currentTimeMillis() - lastServerResponseTime
                    if (_isServerAlive && elapsed > SERVER_TIMEOUT_MS) {
                        _isServerAlive = false
                        lastPingMs = -1
                        showToast("🔴 Bağlantı kesildi!")
                        sendStatusBroadcast(TYPE_SERVER_TIMEOUT)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun sendStatusBroadcast(type: String, message: String = "") {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PING, lastPingMs)
            putExtra(EXTRA_SERVER_IP, getServerIp())
            putExtra(EXTRA_IS_ALIVE, _isServerAlive)
        })
    }

    fun getDebugState(): String = synchronized(gamepadLock) {
        "BTN:${buttons.toString(16)} L:($leftStickX,$leftStickY) R:($rightStickX,$rightStickY) T:$triggerL2/$triggerR2 | ${getGyroDebug()}"
    }
}