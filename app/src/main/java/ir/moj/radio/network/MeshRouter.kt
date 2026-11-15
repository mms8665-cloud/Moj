package ir.moj.radio.network

import android.util.Log
import ir.moj.radio.data.MeshPacket
import ir.moj.radio.data.PacketType
import ir.moj.radio.security.CryptoManager
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MeshRouter(
    private val cryptoManager: CryptoManager,
    private val memberManager: MemberManager,
    private val onPacketReceived: (MeshPacket, String) -> Unit
) {
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var networkKey: String = ""

    fun setNetworkKey(key: String) {
        networkKey = key
    }

    fun startServer(scope: CoroutineScope, port: Int = 45455) {
        stopAll()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    handleClientSocket(socket)
                }
            } catch (_: Exception) {}
        }
    }

    fun connectToPeer(scope: CoroutineScope, hostIp: Int, port: Int = 45455, onResult: (Boolean) -> Unit) {
        connectToPeer(scope, hostIp.toString(), port, onResult)
    }

    fun connectToPeer(scope: CoroutineScope, hostIp: String, port: Int = 45455, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(hostIp, port)
                val peerIp = socket.inetAddress.hostAddress ?: hostIp
                activeConnections[peerIp] = socket
                handleClientSocket(socket, isServerSide = false)
                onResult(true)
            } catch (e: Exception) {
                Log.e("MeshRouter", "Connect error: ${e.message}")
                onResult(false)
            }
        }
    }

    private fun handleClientSocket(socket: Socket, isServerSide: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            val peerIp = socket.inetAddress.hostAddress ?: "unknown"
            if (isServerSide) {
                activeConnections[peerIp] = socket
            }
            try {
                val input = DataInputStream(socket.getInputStream())
                while (socket.isConnected && !socket.isClosed) {
                    val encryptedLen = input.readInt()
                    val encryptedBytes = ByteArray(encryptedLen)
                    input.readFully(encryptedBytes)

                    val decryptedJson = cryptoManager.decryptData(encryptedBytes, networkKey)
                    if (decryptedJson != null) {
                        val packet = MeshPacket.fromJson(decryptedJson)
                        if (packet != null) {
                            onPacketReceived(packet, peerIp)
                            // اگر پکت عمومی است یا برای دوربین/ویس است، به دیگران هم رله کن
                            if (packet.targetId == null || packet.type == PacketType.CAMERA_LIVE_FRAME) {
                                relayPacketToOthers(packet, excludeIp = peerIp)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                activeConnections.remove(peerIp)
                runCatching { socket.close() }
            }
        }
    }

    fun broadcastPacket(packet: MeshPacket) {
        val json = packet.toJson()
        val encrypted = cryptoManager.encryptData(json, networkKey) ?: return
        
        activeConnections.values.forEach { socket ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val output = DataOutputStream(socket.getOutputStream())
                    synchronized(output) {
                        output.writeInt(encrypted.size)
                        output.write(encrypted)
                        output.flush()
                    }
                }
            }
        }
    }

    private fun relayPacketToOthers(packet: MeshPacket, excludeIp: String) {
        val json = packet.toJson()
        val encrypted = cryptoManager.encryptData(json, networkKey) ?: return

        activeConnections.forEach { (ip, socket) ->
            if (ip != excludeIp) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val output = DataOutputStream(socket.getOutputStream())
                        synchronized(output) {
                            output.writeInt(encrypted.size)
                            output.write(encrypted)
                            output.flush()
                        }
                    }
                }
            }
        }
    }

    fun stopAll() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        activeConnections.values.forEach { runCatching { it.close() } }
        activeConnections.clear()
    }
}
