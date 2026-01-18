// Güncellenmiş UdpService.kt
// Değişiklikler: Yeni paket tipleri eklendi (MOUSE_MOVE, MOUSE_BUTTON, MOUSE_WHEEL)
// sendJoystick genelleştirildi, sendRaw eklendi (her türlü paket için)

package com.benim.benim.net

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class UdpService : Service() {

    companion object {
        private const val SERVER_PORT = 26760
        private const val PACKET_PING: Byte = 0x7F
        private const val PACKET_JOYSTICK: Byte = 0x01
        private const val PACKET_MOUSE_MOVE: Byte = 0x02
        private const val PACKET_MOUSE_BUTTON: Byte = 0x03
        private const val PACKET_MOUSE_WHEEL: Byte = 0x04
        private const val TAG = "UdpService"
    }

    // ===== Binder =====
    inner class LocalBinder : Binder() {
        fun getService(): UdpService = this@UdpService
    }

    private val binder = LocalBinder()

    // ===== Network state (protected by serverLock)
    private val serverLock = Any()

    @Volatile
    private var serverIpInternal: String? = null

    @Volatile
    var lastPingMs: Long = -1

    @Volatile
    var lastStatusText: String = "Başlatılıyor"

    // single socket used for both send & receive
    private lateinit var socket: DatagramSocket

    // Send queue
    private val sendQueue = LinkedBlockingQueue<DatagramPacket>()

    @Volatile
    private var running = false

    // ===============================
    override fun onCreate() {
        super.onCreate()
        try {
            socket = DatagramSocket()
            socket.broadcast = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        running = true
        startListener()
        startSender()
        startPingLoop()
        discoverServer()
    }

    override fun onDestroy() {
        running = false
        try { socket.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // Public getters/setters (synchronized)
    fun setServerIp(ip: String?) {
        synchronized(serverLock) {
            serverIpInternal = ip
            lastStatusText = if (ip == null) "Server yok, localhost" else "Server set: $ip"
            Log.d(TAG, "setServerIp -> $serverIpInternal")
        }
    }

    fun getServerIp(): String? {
        synchronized(serverLock) { return serverIpInternal }
    }

    fun isUsingLocalhost(): Boolean {
        synchronized(serverLock) { return serverIpInternal == null }
    }

    private fun getTargetIpForPing(): String {
        val ip = getServerIp()
        return ip ?: "127.0.0.1"
    }

    // make discoverServer public so MainActivity can call it directly
    fun discoverServer() {
        thread(name = "udp-discover") {
            try {
                val s = DatagramSocket()
                s.broadcast = true
                s.soTimeout = 2000
                val msg = "DISCOVER_JOYSTICK_SERVER".toByteArray()
                val packet = DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), SERVER_PORT)
                s.send(packet)
                lastStatusText = "Broadcast gönderildi"
                // wait reply
                val buf = ByteArray(256)
                val response = DatagramPacket(buf, buf.size)
                s.receive(response)
                val ip = response.address.hostAddress
                setServerIp(ip)
                s.close()
                Log.d(TAG, "discoverServer -> found $ip")
            } catch (e: Exception) {
                setServerIp(null)
                Log.d(TAG, "discoverServer: no reply, fallback localhost (${e.localizedMessage})")
            }
        }
    }

    // Listener
    private fun startListener() {
        thread(name = "udp-listener") {
            while (running) {
                try {
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val length = packet.length
                    if (length <= 0) continue

                    val msg = String(packet.data, 0, length)
                    if (msg.startsWith("I_AM_SERVER")) {
                        // use setter
                        setServerIp(packet.address.hostAddress)
                        lastStatusText = "Server bulundu: ${getServerIp()}"
                        Log.d(TAG, "I_AM_SERVER from ${getServerIp()}")
                        continue
                    }

                    val b0 = packet.data[0]

                    if (b0 == PACKET_PING) {
                        try {
                            var sentTime: Long? = null
                            if (length >= 9) {
                                var tmp = 0L
                                for (i in 0..7) tmp = (tmp shl 8) or (packet.data[1 + i].toLong() and 0xFF)
                                sentTime = tmp
                            } else if (length >= 5) {
                                var tmp = 0L
                                for (i in 0..3) tmp = (tmp shl 8) or (packet.data[1 + i].toLong() and 0xFF)
                                sentTime = tmp
                            }
                            if (sentTime != null) {
                                val rtt = System.currentTimeMillis() - sentTime
                                lastPingMs = rtt
                                lastStatusText = "Ping: ${lastPingMs} ms"
                                Log.d(TAG, "PING echo received from ( {packet.address.hostAddress} rtt= ){rtt}ms")
                                // broadcast to UI
                                try {
                                    val i = Intent("com.benim.ACTION_PING")
                                    i.putExtra("ping_ms", lastPingMs)
                                    sendBroadcast(i)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            } else {
                                lastStatusText = "Ping (malformed)"
                                Log.d(TAG, "Ping malformed")
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        continue
                    }

                    if (b0 == PACKET_JOYSTICK) {
                        if (length >= 5) {
                            val buttons = packet.data[1].toInt() and 0xFF
                            val x = packet.data[2]
                            val y = packet.data[3]
                            val z = packet.data[4]
                            lastStatusText = "Joystick recv btn=0x${buttons.toString(16)}"
                            Log.d(TAG, "JOYSTICK from ( {packet.address.hostAddress} btn=0x ){buttons.toString(16)} x=$x y=$y")
                        }
                        continue
                    }

                    // fallback
                    lastStatusText = "Got ${length} bytes from ${packet.address.hostAddress}"
                    Log.d(TAG, lastStatusText)

                } catch (e: Exception) {
                    if (running) {
                        e.printStackTrace()
                        lastStatusText = "Listener error: ${e.localizedMessage}"
                        Log.d(TAG, "listener error: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    // Sender thread
    private fun startSender() {
        thread(name = "udp-sender") {
            while (running) {
                try {
                    val packet = sendQueue.take()
                    socket.send(packet)
                } catch (e: Exception) {
                    if (running) {
                        e.printStackTrace()
                        lastStatusText = "Send error: ${e.localizedMessage}"
                        Log.d(TAG, "send error: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    private fun startPingLoop() {
        thread(name = "udp-ping") {
            while (running) {
                try {
                    enqueuePing()
                    Thread.sleep(2000) //İlerde ping paketi yanına alive eklenecek server düşerse haber verilecek
                } catch (e: InterruptedException) {

                }
            }
        }
    }

    private fun enqueuePing() {
        val ip = getTargetIpForPing()
        val time = System.currentTimeMillis()
        val buf = ByteArray(9)
        buf[0] = PACKET_PING
        for (i in 0..7) {
            buf[i + 1] = (time shr (56 - i * 8)).toByte()
        }
        try {
            val packet = DatagramPacket(buf, buf.size, InetAddress.getByName(ip), SERVER_PORT)
            sendQueue.offer(packet)
            lastStatusText = "Ping gönderildi -> $ip"
            Log.d(TAG, "Ping sent to $ip ts=$time")
        } catch (e: Exception) {
            e.printStackTrace()
            lastStatusText = "Ping hata: ${e.localizedMessage}"
            Log.d(TAG, "Ping enqueue error: ${e.localizedMessage}")
        }
    }

    // Public API - Joystick (mevcut)
    fun sendJoystick(buttons: Byte, x: Byte, y: Byte, z: Byte) {
        val buf = byteArrayOf(PACKET_JOYSTICK, buttons, x, y, z)
        sendRaw(buf)
    }

    // Yeni: Genel raw gönderim (mouse için de kullanılacak)
    fun sendRaw(data: ByteArray) {
        val ip = getServerIp() ?: return
        try {
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(ip), SERVER_PORT)
            sendQueue.offer(packet)
        } catch (e: Exception) {
            e.printStackTrace()
            lastStatusText = "SendRaw hata: ${e.localizedMessage}"
            Log.d(TAG, "sendRaw error: ${e.localizedMessage}")
        }
    }
}