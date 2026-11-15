package ir.moj.radio.network

import android.util.Log
import ir.moj.radio.data.MeshPacket
import ir.moj.radio.data.PacketType
import ir.moj.radio.security.CryptoManager
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
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
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var secretKey: SecretKeySpec? = null
    private val secureRandom = SecureRandom()

    fun setNetworkKey(key: String) {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        secretKey = SecretKeySpec(hash, "AES")
    }

    fun startServer(scope: CoroutineScope, port: Int = 45455) {
        stopAll()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    handleClientSocket(socket, isServerSide = true)
                }
            } catch (_: Exception) {}
        }
    }

    fun connectToPeer(scope: CoroutineScope, hostIp: String, port: Int = 45455, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(hostIp, port)
                val peerIp = socket.inetAddress.hostAddress ?: hostIp
                activeConnections[peerIp] = socket
                handleClientSocket(socket, isServerSide = false)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                Log.e("MeshRouter", "Connect error: ${e.message}")
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    private fun handleClientSocket(socket: Socket, isServerSide: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val peerIp = socket.inetAddress.hostAddress ?: "unknown"
            if (isServerSide) {
                activeConnections[peerIp] = socket
            }
            try {
                val input = DataInputStream(socket.getInputStream())
                while (socket.isConnected && !socket.isClosed) {
                    val encryptedLen = input.readInt()
                    if (encryptedLen <= 0 || encryptedLen > 20 * 1024 * 1024) break
                    val encryptedBytes = ByteArray(encryptedLen)
                    input.readFully(encryptedBytes)

                    val plainBytes = decryptBytes(encryptedBytes) ?: continue
                    val packet = deserializePacket(plainBytes) ?: continue

                    onPacketReceived(packet, peerIp)
                    if (packet.targetId == null || packet.type == PacketType.CAMERA_LIVE_FRAME) {
                        relayPacketToOthers(packet, excludeIp = peerIp)
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
        val plainBytes = serializePacket(packet)
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

    private fun relayPacketToOthers(packet: MeshPacket, excludeIp: String) {
        val plainBytes = serializePacket(packet)
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

    private fun serializePacket(packet: MeshPacket): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeUTF(packet.type.name)
            dos.writeUTF(packet.senderId)
            dos.writeUTF(packet.senderNickname)
            dos.writeUTF(packet.targetId ?: "")
            dos.writeInt(packet.payload.size)
            dos.write(packet.payload)
        }
        return bos.toByteArray()
    }

    private fun deserializePacket(data: ByteArray): MeshPacket? {
        return runCatching {
            val dis = DataInputStream(ByteArrayInputStream(data))
            val type = PacketType.valueOf(dis.readUTF())
            val senderId = dis.readUTF()
            val senderNickname = dis.readUTF()
            val rawTarget = dis.readUTF()
            val targetId = rawTarget.ifEmpty { null }
            val payloadLen = dis.readInt()
            val payload = ByteArray(payloadLen)
            dis.readFully(payload)
            MeshPacket(
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
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        activeConnections.values.forEach { runCatching { it.close() } }
        activeConnections.clear()
    }
}
