package ir.moj.radio.network

import android.util.Log
import ir.moj.radio.data.MeshPacket
import ir.moj.radio.data.PacketType
import ir.moj.radio.security.CryptoManager
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
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
    private data class PeerConnection(
        val id: String,
        val ip: String,
        val socket: Socket,
        val isUpstream: Boolean
    )

    private val activeConnections = ConcurrentHashMap<String, PeerConnection>()

    // کش LRU ضدتکرار و ضدحلقه
    private val seenLock = Any()
    private val seenPacketIds = object : LinkedHashMap<String, Boolean>(3000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 3000
        }
    }

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

    fun getDirectPeerCount(): Int = activeConnections.size

    fun startServer(scope: CoroutineScope, port: Int = 45455) {
        stopAll()
        manualDisconnect = false
        startRelayServer(scope, port)
    }

    // Bind روی 0.0.0.0 برای پذیرش هم‌زمان کلاینت‌های وای‌فای مشترک و هات‌اسپات محلی
    fun startRelayServer(scope: CoroutineScope, port: Int = 45455) {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
                }
                serverSocket = ss
                while (isActive) {
                    val socket = ss.accept()
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    handleClientSocket(socket, isUpstream = false)
                }
            } catch (e: Exception) {
                Log.e("MeshRouter", "Server error: ${e.message}")
            }
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
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(hostIp, port), 4500)
            handleClientSocket(socket, isUpstream = true, scope = scope)
            true
        } catch (e: Exception) {
            Log.e("MeshRouter", "Connect error to $hostIp:$port -> ${e.message}")
            false
        }
    }

    private fun scheduleAutoReconnect(scope: CoroutineScope) {
        if (upstreamHostIp == null || manualDisconnect || reconnectJob?.isActive == true) return

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

    private fun markPacketSeen(packetId: String): Boolean {
        synchronized(seenLock) {
            if (seenPacketIds.containsKey(packetId)) return false
            seenPacketIds[packetId] = true
            return true
        }
    }

    private fun handleClientSocket(socket: Socket, isUpstream: Boolean, scope: CoroutineScope? = null) {
        val peerIp = socket.inetAddress?.hostAddress ?: "unknown"
        val connId = "${peerIp}:${socket.port}:${UUID.randomUUID().toString().take(6)}"
        val conn = PeerConnection(connId, peerIp, socket, isUpstream)
        activeConnections[connId] = conn

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (socket.isConnected && !socket.isClosed) {
                    val encryptedLen = input.readInt()
                    if (encryptedLen <= 0 || encryptedLen > 20 * 1024 * 1024) break
                    val encryptedBytes = ByteArray(encryptedLen)
                    input.readFully(encryptedBytes)

                    val plainBytes = decryptBytes(encryptedBytes) ?: continue
                    val packet = deserializePacket(plainBytes) ?: continue

                    if (!markPacketSeen(packet.packetId)) continue

                    onPacketReceived(packet, peerIp)

                    // بازپخش به سایر گره‌ها در صورت باقی ماندن TTL
                    if (packet.ttl > 1) {
                        val forwarded = packet.copy(
                            ttl = packet.ttl - 1,
                            hopCount = packet.hopCount + 1
                        )
                        relayPacketToOthers(forwarded, excludeConnId = connId)
                    }
                }
            } catch (_: Exception) {
            } finally {
                activeConnections.remove(connId)
                runCatching { socket.close() }
                if (isUpstream && !manualDisconnect && scope != null) {
                    withContext(Dispatchers.Main) { onUpstreamDisconnected?.invoke() }
                    scheduleAutoReconnect(scope)
                }
            }
        }
    }

    fun broadcastPacket(packet: MeshPacket) {
        markPacketSeen(packet.packetId)
        val plainBytes = serializePacket(packet)
        val encrypted = encryptBytes(plainBytes) ?: return

        activeConnections.values.forEach { conn ->
            sendEncryptedToConnection(conn, encrypted)
        }
    }

    private fun relayPacketToOthers(packet: MeshPacket, excludeConnId: String) {
        val plainBytes = serializePacket(packet)
        val encrypted = encryptBytes(plainBytes) ?: return

        activeConnections.values.forEach { conn ->
            if (conn.id != excludeConnId) {
                sendEncryptedToConnection(conn, encrypted)
            }
        }
    }

    private fun sendEncryptedToConnection(conn: PeerConnection, encrypted: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                synchronized(conn.socket) {
                    val output = DataOutputStream(conn.socket.getOutputStream())
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                    output.flush()
                }
            }.onFailure {
                activeConnections.remove(conn.id)
                runCatching { conn.socket.close() }
            }
        }
    }

    private fun serializePacket(packet: MeshPacket): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeUTF(packet.packetId)
            dos.writeUTF(packet.type.name)
            dos.writeUTF(packet.senderId)
            dos.writeUTF(packet.senderNickname)
            dos.writeUTF(packet.targetId ?: "")
            dos.writeInt(packet.ttl)
            dos.writeInt(packet.hopCount)
            dos.writeInt(packet.payload.size)
            dos.write(packet.payload)
        }
        return bos.toByteArray()
    }

    private fun deserializePacket(data: ByteArray): MeshPacket? {
        return runCatching {
            val dis = DataInputStream(ByteArrayInputStream(data))
            val packetId = dis.readUTF()
            val type = PacketType.valueOf(dis.readUTF())
            val senderId = dis.readUTF()
            val senderNickname = dis.readUTF()
            val rawTarget = dis.readUTF()
            val targetId = rawTarget.ifEmpty { null }
            val ttl = dis.readInt()
            val hopCount = dis.readInt()
            val payloadLen = dis.readInt()
            val payload = ByteArray(payloadLen)
            dis.readFully(payload)
            MeshPacket(
                packetId = packetId,
                type = type,
                senderId = senderId,
                senderNickname = senderNickname,
                targetId = targetId,
                ttl = ttl,
                hopCount = hopCount,
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
        activeConnections.values.forEach { runCatching { it.socket.close() } }
        activeConnections.clear()
        synchronized(seenLock) { seenPacketIds.clear() }
    }
}
