package ir.moj.radio.network

import android.util.Log
import ir.moj.radio.data.MeshPacket
import ir.moj.radio.security.CryptoManager
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MeshRouter(
    private val cryptoManager: CryptoManager,
    private val memberManager: MemberManager,
    private val onPacketReceived: (MeshPacket, String) -> Unit
) {
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    private val seenPacketIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var reconnectJob: Job? = null
    private var secretKey: SecretKeySpec? = null
    private val secureRandom = SecureRandom()

    @Volatile private var manualDisconnect: Boolean = false
    @Volatile private var upstreamHostIp: String? = null
    @Volatile private var upstreamPort: Int = 45455

    var onUpstreamDisconnected: (() -> Unit)? = null
    var onUpstreamReconnected: (() -> Unit)? = null

    fun setNetworkKey(key: String) {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        secretKey = SecretKeySpec(hash, "AES")
    }

    fun startServer(scope: CoroutineScope, port: Int = 45455) {
        stopAll()
        manualDisconnect = false
        startRelayServer(scope, port)
    }

    // روشن کردن سرور روی گوشی واسطه یا جانشین بدون قطع کردن اتصال‌های فعلی
    fun startRelayServer(scope: CoroutineScope, port: Int = 45455) {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    handleClientSocket(socket, isUpstream = false)
                }
            } catch (_: Exception) {}
        }
    }

    fun connectToPeer(scope: CoroutineScope, hostIp: String, port: Int = 45455, onResult: (Boolean) -> Unit) {
        manualDisconnect = false
        upstreamHostIp = hostIp
        upstreamPort = port
        reconnectJob?.cancel()

        scope.launch(Dispatchers.IO) {
            val ok = openUpstreamSocket(scope, hostIp, port)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private fun openUpstreamSocket(scope: CoroutineScope, hostIp: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(hostIp, port), 4000)
            val peerIp = socket.inetAddress.hostAddress ?: hostIp
            activeConnections[peerIp] = socket
            handleClientSocket(socket, isUpstream = true, scope = scope)
            true
        } catch (e: Exception) {
            Log.e("MeshRouter", "Connect error: ${e.message}")
            false
        }
    }

    private fun scheduleAutoReconnect(scope: CoroutineScope) {
        val targetIp = upstreamHostIp ?: return
        if (manualDisconnect || reconnectJob?.isActive == true) return

        reconnectJob = scope.launch(Dispatchers.IO) {
            while (isActive && !manualDisconnect) {
                delay(3000L)
                val ipToTry = upstreamHostIp ?: break
                val connected = openUpstreamSocket(scope, ipToTry, upstreamPort)
                if (connected) {
                    withContext(Dispatchers.Main) { onUpstreamReconnected?.invoke() }
                    break
                }
            }
        }
    }

    private fun handleClientSocket(socket: Socket, isUpstream: Boolean, scope: CoroutineScope? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val peerIp = socket.inetAddress.hostAddress ?: "unknown"
            activeConnections[peerIp] = socket
            try {
                val input = DataInputStream(socket.getInputStream())
                while (socket.isConnected && !socket.isClosed) {
                    val encryptedLen = input.readInt()
                    if (encryptedLen <= 0 || encryptedLen > 20 * 1024 * 1024) break
                    val encryptedBytes = ByteArray(encryptedLen)
                    input.readFully(encryptedBytes)

                    val plainBytes = decryptBytes(encryptedBytes) ?: continue
                    val (packetId, packet) = deserializePacket(plainBytes) ?: continue

                    if (!seenPacketIds.add(packetId)) continue
                    if (seenPacketIds.size > 1500) seenPacketIds.clear()

                    onPacketReceived(packet, peerIp)
                    relayPacketToOthers(packetId, packet, excludeIp = peerIp)
                }
            } catch (_: Exception) {
            } finally {
                activeConnections.remove(peerIp)
                runCatching { socket.close() }
                if (isUpstream && !manualDisconnect && scope != null) {
                    withContext(Dispatchers.Main) { onUpstreamDisconnected?.invoke() }
                    scheduleAutoReconnect(scope)
                }
            }
        }
    }

    fun broadcastPacket(packet: MeshPacket) {
        val packetId = UUID.randomUUID().toString()
        seenPacketIds.add(packetId)
        val plainBytes = serializePacket(packetId, packet)
        val encrypted = encryptBytes(plainBytes) ?: return

        activeConnections.values.forEach { socket ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    synchronized(socket) {
                        val output = DataOutputStream(socket.getOutputStream())
                        output.writeInt(encrypted.size)
                        output.write(encrypted)
                        output.flush()
                    }
                }
            }
        }
    }

    private fun relayPacketToOthers(packetId: String, packet: MeshPacket, excludeIp: String) {
        val plainBytes = serializePacket(packetId, packet)
        val encrypted = encryptBytes(plainBytes) ?: return

        activeConnections.forEach { (ip, socket) ->
            if (ip != excludeIp) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        synchronized(socket) {
                            val output = DataOutputStream(socket.getOutputStream())
                            output.writeInt(encrypted.size)
                            output.write(encrypted)
                            output.flush()
                        }
                    }
                }
            }
        }
    }

    private fun serializePacket(packetId: String, packet: MeshPacket): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeUTF(packetId)
            dos.writeUTF(packet.type.name)
            dos.writeUTF(packet.senderId)
            dos.writeUTF(packet.senderNickname)
            dos.writeUTF(packet.targetId ?: "")
            dos.writeInt(packet.payload.size)
            dos.write(packet.payload)
        }
        return bos.toByteArray()
    }

    private fun deserializePacket(data: ByteArray): Pair<String, MeshPacket>? {
        return runCatching {
            val dis = DataInputStream(ByteArrayInputStream(data))
            val packetId = dis.readUTF()
            val type = ir.moj.radio.data.PacketType.valueOf(dis.readUTF())
            val senderId = dis.readUTF()
            val senderNickname = dis.readUTF()
            val rawTarget = dis.readUTF()
            val targetId = rawTarget.ifEmpty { null }
            val payloadLen = dis.readInt()
            val payload = ByteArray(payloadLen)
            dis.readFully(payload)
            packetId to MeshPacket(
                type = type,
                senderId = senderId,
                senderNickname = senderNickname,
                targetId = targetId,
                payload = payload
            )
        }.getOrNull()
    }

    private fun encryptBytes(plain: ByteArray): ByteArray? {
        val key = secretKey ?: return plain
        return runCatching {
            val iv = ByteArray(12)
            secureRandom.nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain)
            iv + encrypted
        }.getOrNull()
    }

    private fun decryptBytes(data: ByteArray): ByteArray? {
        val key = secretKey ?: return data
        if (data.size <= 12) return null
        return runCatching {
            val iv = data.copyOfRange(0, 12)
            val cipherText = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(cipherText)
        }.getOrNull()
    }

    fun stopAll() {
        manualDisconnect = true
        upstreamHostIp = null
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        activeConnections.values.forEach { runCatching { it.close() } }
        activeConnections.clear()
        seenPacketIds.clear()
    }
}
