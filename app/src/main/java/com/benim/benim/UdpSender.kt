package com.benim.benim.net

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process // Thread önceliği için eklendi
import android.util.Log
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.res.Configuration
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpService : Service(), SensorEventListener {

    companion object {
        private const val SERVER_PORT = 26760
        private const val PACKET_PING: Byte = 0x7F
        private const val PACKET_JOYSTICK: Byte = 0x01
        private const val PACKET_MOUSE_MOVE: Byte = 0x02
        private const val PACKET_MOUSE_BUTTON: Byte = 0x03
        private const val PACKET_MOUSE_WHEEL: Byte = 0x04
        private const val PACKET_GYRO: Byte = 0x0D // Yeni: Gyro paketi
        private const val TAG = "UdpService"
        private const val SERVER_TIMEOUT_MS = 5000L

        // Gönderim sıklığını limitlemek için (Örn: 20ms = 50 FPS)
        private var lastSendTime: Long = 0
        private const val SEND_INTERVAL_MS = 20L

        // Her seferinde allocate etmemek için tek bir buffer
        private val gyroBuffer = ByteBuffer.allocate(13).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }

        // Broadcast Action'ları
        const val ACTION_STATUS = "com.benim.ACTION_STATUS"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PING = "ping_ms"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_IS_ALIVE = "is_alive"

        // Status tipleri
        const val TYPE_DISCOVERY_START = "discovery_start"
        const val TYPE_DISCOVERY_SUCCESS = "discovery_success"
        const val TYPE_DISCOVERY_FAILED = "discovery_failed"
        const val TYPE_PING_LOOP_START = "ping_loop_start"
        const val TYPE_PING_UPDATE = "ping_update"
        const val TYPE_SERVER_TIMEOUT = "server_timeout"
        const val TYPE_CONNECTION_LOST = "connection_lost"

        // Fallback IP (Emülatör için)
        private const val FALLBACK_IP = "127.0.0.1"
    }

    // ===== Binder =====
    inner class LocalBinder : Binder() {
        fun getService(): UdpService = this@UdpService
    }

    private val binder = LocalBinder()
    private val serverLock = Any()

    // UI Thread Handler (Toast için)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var serverIpInternal: String? = null

    // OPTİMİZASYON 1: Adresi önbelleğe alıyoruz (Her pakette string çevrimi yapmamak için)
    private var serverAddressCache: InetAddress? = null

    @Volatile
    private var _isServerAlive = false
    val isServerAlive: Boolean get() = _isServerAlive

    @Volatile
    private var lastServerResponseTime: Long = 0

    @Volatile
    var lastPingMs: Long = -1

    private lateinit var socket: DatagramSocket
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>()

    @Volatile
    private var running = false

    // Ping istatistikleri
    private var pingsSent = 0
    private var pingsReceived = 0

    // Gyro için: Sensör manager ve sensör (private yapmadım, her yerden erişilebilir)
    var sensorManager: SensorManager? = null
    var gyroSensor: Sensor? = null

    // Son gyro verileri (her yerden okunabilir değişkenler, public)
    var gyroX: Float = 0f
    var gyroY: Float = 0f
    var gyroZ: Float = 0f

    // ===== Lifecycle =====
    override fun onCreate() {
        super.onCreate()

        showToast("🚀 UDP Servisi başlatılıyor...")

        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 3000
            // OPTİMİZASYON: Buffer boyutunu artır
            socket.receiveBufferSize = 65536
            socket.sendBufferSize = 65536
            Log.d(TAG, "Socket oluşturuldu")
        } catch (e: Exception) {
            showToast("❌ Socket hatası: ${e.message}")
            e.printStackTrace()
            return
        }

        // Gyro init
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroSensor != null) {
            sensorManager?.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.d(TAG, "Gyro sensörü yok")
        }

        running = true
        startListener()
        startSender()
        startPingLoop()
        startAliveMonitor()

        // Discovery başlat
        discoverServer()
    }

    override fun onDestroy() {
        running = false
        try {
            socket.close()
        } catch (_: Exception) {
        }
        sensorManager?.unregisterListener(this) // Gyro unregister
        showToast("🛑 UDP Servisi durduruldu")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ===== Toast Helper =====
    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ===== Broadcast Helper =====
    private fun sendStatusBroadcast(type: String, message: String = "") {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PING, lastPingMs)
            putExtra(EXTRA_SERVER_IP, getServerIp())
            putExtra(EXTRA_IS_ALIVE, _isServerAlive)
        }
        sendBroadcast(intent)
    }

    // ===== IP Yönetimi =====
    fun setServerIp(ip: String?) {
        synchronized(serverLock) {
            val oldIp = serverIpInternal
            serverIpInternal = ip

            // Cache güncelle
            if (ip != null) {
                try {
                    serverAddressCache = InetAddress.getByName(ip)
                } catch (e: Exception) {
                    serverAddressCache = null
                }
            } else {
                serverAddressCache = null
            }

            if (ip != null && ip != oldIp) {
                _isServerAlive = false
                lastPingMs = -1
                Log.d(TAG, "Server IP değişti: $oldIp -> $ip")
            }
        }
    }

    fun getServerIp(): String? {
        synchronized(serverLock) { return serverIpInternal }
    }

    // ===== Discovery =====
    fun discoverServer() {
        showToast("🔍 Sunucu aranıyor...")
        sendStatusBroadcast(TYPE_DISCOVERY_START, "Sunucu aranıyor...")

        thread(name = "udp-discover") {
            Log.d(TAG, "Discovery başlatılıyor...")

            try {
                val discoverSocket = DatagramSocket()
                discoverSocket.broadcast = true
                discoverSocket.soTimeout = 3000

                val msg = "DISCOVER_JOYSTICK_SERVER"
                val packet = DatagramPacket(
                    msg.toByteArray(),
                    msg.length,
                    InetAddress.getByName("255.255.255.255"),
                    SERVER_PORT
                )

                discoverSocket.send(packet)
                Log.d(TAG, "Broadcast gönderildi")

                val buf = ByteArray(256)
                val response = DatagramPacket(buf, buf.size)
                discoverSocket.receive(response)

                val responseStr = String(response.data, 0, response.length)
                if (responseStr.startsWith("I_AM_SERVER")) {
                    val ip = response.address.hostAddress ?: FALLBACK_IP
                    setServerIp(ip)

                    showToast("✅ Sunucu bulundu: $ip")
                    sendStatusBroadcast(TYPE_DISCOVERY_SUCCESS, ip)
                    Log.d(TAG, "Discovery BAŞARILI: $ip")
                } else {
                    handleDiscoveryFailed("Geçersiz cevap")
                }

                discoverSocket.close()

            } catch (e: Exception) {
                handleDiscoveryFailed(e.message ?: "Bilinmeyen hata")
            }
        }
    }

    private fun handleDiscoveryFailed(reason: String) {
        Log.d(TAG, "Discovery başarısız: $reason")

        // Fallback IP kullan
        setServerIp(FALLBACK_IP)

        showToast("⚠️ Otomatik bulunamadı, $FALLBACK_IP deneniyor...")
        sendStatusBroadcast(TYPE_DISCOVERY_FAILED, "Fallback: $FALLBACK_IP")
    }

    // ===== Paket Gönderimi =====
    fun sendJoystick(buttons: Byte, x: Byte, y: Byte, z: Byte) {
        sendRaw(byteArrayOf(PACKET_JOYSTICK, buttons, x, y, z))
    }

    fun sendMouseMove(dx: Byte, dy: Byte) {
        sendRaw(byteArrayOf(PACKET_MOUSE_MOVE, dx, dy, 0, 0))
    }

    fun sendMouseButton(button: Byte, pressed: Byte) {
        sendRaw(byteArrayOf(PACKET_MOUSE_BUTTON, button, pressed, 0, 0))
    }

    fun sendMouseWheel(delta: Byte) {
        sendRaw(byteArrayOf(PACKET_MOUSE_WHEEL, delta, 0, 0, 0))
    }

    fun sendRaw(data: ByteArray) {
        val addr = serverAddressCache ?: return // Cache'den al (Çok daha hızlı)
        try {
            val packet = DatagramPacket(data, data.size, addr, SERVER_PORT)
            sendQueue.offer(packet)
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw error: ${e.message}")
        }
    }

    // ===== Listener Thread =====
    private fun startListener() {
        thread(name = "udp-listener") {
            // OPTİMİZASYON 2: Thread önceliğini artır (Takılmaları önler)
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            Log.d(TAG, "Listener başlatıldı")
            val buffer = ByteArray(1500) // MTU size
            val packet = DatagramPacket(buffer, buffer.size)

            while (running) {
                try {
                    socket.receive(packet)
                    val len = packet.length
                    if (len <= 0) continue

                    val data = packet.data

                    // PING Cevabı (0x7F)
                    if (data[0] == PACKET_PING && len >= 9) {
                        handlePingResponse(data, packet.address.hostAddress ?: "?")
                    }
                    // Discovery Cevabı
                    else {
                        val msg = String(data, 0, len)
                        if (msg.startsWith("I_AM_SERVER")) {
                            val ip = packet.address.hostAddress ?: continue
                            if (getServerIp() != ip) {
                                setServerIp(ip)
                                showToast("📡 Server değişti: $ip")
                            }
                        }
                    }

                } catch (e: Exception) {
                    // Timeout normal, log kirliliği yapmasın
                    if (running && e.message?.contains("timed out") != true) {
                        Log.e(TAG, "Listener error: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "Listener durduruldu")
        }
    }

    private fun handlePingResponse(data: ByteArray, fromIp: String) {
        try {
            // Timestamp'ı çöz
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
                pingsReceived++
            }

            // İlk başarılı ping'de bildir
            if (!wasAlive) {
                showToast("🟢 Bağlantı kuruldu! Ping: ${rtt}ms")
            }

            // IP güncelle (gerekirse)
            if (getServerIp() != fromIp) {
                setServerIp(fromIp)
            }

            // UI güncelle
            sendStatusBroadcast(TYPE_PING_UPDATE)

            // Log.d(TAG, "PING: ${rtt}ms") // Çok fazla log basmasın diye kapattım

        } catch (e: Exception) {
            Log.e(TAG, "Ping parse error: ${e.message}")
        }
    }

    // ===== Sender Thread =====
    private fun startSender() {
        thread(name = "udp-sender") {
            // OPTİMİZASYON 2: Thread önceliğini artır
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            Log.d(TAG, "Sender başlatıldı")
            while (running) {
                try {
                    val packet = sendQueue.take()
                    socket.send(packet)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Send error: ${e.message}")
                }
            }
            Log.d(TAG, "Sender durduruldu")
        }
    }

    // ===== Ping Loop =====
    private fun startPingLoop() {
        thread(name = "udp-ping") {
            // Biraz bekle, socket hazır olsun
            Thread.sleep(500)

            showToast("📶 Ping döngüsü başladı")
            sendStatusBroadcast(TYPE_PING_LOOP_START)
            Log.d(TAG, "Ping loop başlatıldı")

            while (running) {
                try {
                    enqueuePing()
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "Ping loop durduruldu")
        }
    }

    private fun enqueuePing() {
        // Cache'den al (Daha hızlı)
        val addr = serverAddressCache ?: return

        val time = System.currentTimeMillis()
        val buf = ByteArray(9)
        buf[0] = PACKET_PING
        for (i in 0..7) {
            buf[i + 1] = (time shr (56 - i * 8)).toByte()
        }

        try {
            val packet = DatagramPacket(buf, buf.size, addr, SERVER_PORT)
            sendQueue.offer(packet)
            pingsSent++
        } catch (e: Exception) {
            Log.e(TAG, "Ping enqueue error: ${e.message}")
        }
    }

    // ===== Alive Monitor =====
    private fun startAliveMonitor() {
        thread(name = "udp-alive") {
            Log.d(TAG, "Alive monitor başlatıldı")

            while (running) {
                Thread.sleep(1700)

                synchronized(serverLock) {
                    val timeSinceResponse = System.currentTimeMillis() - lastServerResponseTime

                    if (_isServerAlive && timeSinceResponse > SERVER_TIMEOUT_MS) {
                        _isServerAlive = false
                        lastPingMs = -1

                        showToast("🔴 Bağlantı kesildi!")
                        sendStatusBroadcast(TYPE_SERVER_TIMEOUT)
                        Log.d(TAG, "Server TIMEOUT - ${timeSinceResponse}ms yanıt yok")
                    }
                }
            }
            Log.d(TAG, "Alive monitor durduruldu")
        }
    }

    // ===== İstatistikler =====
    fun getStats(): String {
        val lossPercent = if (pingsSent > 0) {
            ((pingsSent - pingsReceived) * 100 / pingsSent)
        } else 0

        return "Gönderilen: $pingsSent, Alınan: $pingsReceived, Kayıp: %$lossPercent"
    }

    // ===== Gyro Listener =====
    override fun onSensorChanged(event: SensorEvent?) {
        val sensor = event?.sensor ?: return

        if (sensor.type == Sensor.TYPE_GYROSCOPE) {
            val currentTime = System.currentTimeMillis()

            // 1. Hız Sınırlama: Çok sık paket gönderip ağı ve işlemciyi yorma
            if (currentTime - lastSendTime < SEND_INTERVAL_MS) return

            // 2. Ekran Modu Kontrolü
            if (!isLandscape()) return

            lastSendTime = currentTime

            // 3. Veriyi Hazırla (Eski ByteBuffer'ı temizleyip tekrar kullanıyoruz)
            synchronized(gyroBuffer) {
                gyroBuffer.clear()
                gyroBuffer.put(PACKET_GYRO)

                // Hassasiyet ayarı: rad/s -> mdeg/s
                // event.values[0] -> X, [1] -> Y, [2] -> Z
                gyroBuffer.putFloat(event.values[0] * 1000f)
                gyroBuffer.putFloat(event.values[1] * 1000f)
                gyroBuffer.putFloat(event.values[2] * 1000f)

                // sendRaw içinde DatagramPacket oluşturulduğu için
                // array'in o anki kopyasını göndermek en güvenlisidir
                sendRaw(gyroBuffer.array().copyOf())
            }

            // Log.d(TAG, "Gyro: X=${event.values[0]}") // Production'da bunu mutlaka kapat!
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Boş
    }

    // Yatay mod helper
    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}