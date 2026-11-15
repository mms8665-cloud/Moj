package ir.moj.radio.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredNetwork(
    val networkName: String,
    val hostIp: String,
    val tcpPort: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

class UdpDiscovery(private val context: Context) {
    private val udpPort = 45454
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null

    private val _discoveredNetworks = MutableStateFlow<List<DiscoveredNetwork>>(emptyList())
    val discoveredNetworks: StateFlow<List<DiscoveredNetwork>> = _discoveredNetworks

    fun isStaApConcurrencySupported(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager.isStaApConcurrencySupported
        } else {
            false
        }
    }

    fun startBroadcasting(scope: CoroutineScope, networkName: String, tcpPort: Int = 45455) {
        stopBroadcasting()
        broadcastJob = scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket().apply { broadcast = true }
            try {
                val message = "MOJ_HOST#$networkName\vert{}$tcpPort".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    runCatching {
                        val packet = DatagramPacket(message, message.size, broadcastAddr, udpPort)
                        socket.send(packet)
                    }
                    delay(2000)
                }
            } finally {
                socket.close()
            }
        }
    }

    fun startListening(scope: CoroutineScope) {
        stopListening()
        listenJob = scope.launch(Dispatchers.IO) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifiManager.createMulticastLock("moj_udp_lock").apply {
                setReferenceCounted(true)
                acquire()
            }
            val socket = DatagramSocket(udpPort).apply {
                broadcast = true
                soTimeout = 3000
            }
            val buffer = ByteArray(1024)
            try {
                while (isActive) {
                    runCatching {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val parts = text.split("#")
                        if (parts.size == 3 && parts[0] == "MOJ_HOST") {
                            val hostIp = packet.address.hostAddress ?: return@runCatching
                            val entry = DiscoveredNetwork(
                                networkName = parts[1],
                                hostIp = hostIp,
                                tcpPort = parts[2].toIntOrNull() ?: 45455
                            )
                            val updated = _discoveredNetworks.value
                                .filterNot { it.hostIp == hostIp }
                                .filter { System.currentTimeMillis() - it.lastSeen < 10_000 } + entry
                            _discoveredNetworks.value = updated
                        }
                    }
                }
            } finally {
                socket.close()
                if (multicastLock.isHeld) multicastLock.release()
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }
}
