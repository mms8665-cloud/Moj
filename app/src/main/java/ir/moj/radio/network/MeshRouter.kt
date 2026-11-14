package ir.moj.radio.network

import ir.moj.radio.data.MeshPacket
import ir.moj.radio.data.PacketType
import ir.moj.radio.security.CryptoManager
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

class MeshRouter(
    private val cryptoManager: CryptoManager,
    private val memberManager: MemberManager,
    private val onPacketReceived: (MeshPacket, String) -> Unit
) {
    private val tcpPort = 45455
    private var serverSocket: ServerSocket? = null
    private var secretKey: SecretKey? = null
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val seenPacketIds = Collections.synchronizedSet(object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= 2000) remove(iterator().next())
            return super.add(element)
        }
    })

    private data class PeerConnection(
        val ip: String,
        val socket: Socket,
        val output: DataOutputStream
    )

    fun setNetworkKey(joinCode: String) {
        val salt = "MOJ_LOCAL_MESH_V1".toByteArray(Charsets.UTF_8)
        secretKey = cryptoManager.deriveNetworkKey(joinCode, salt)
    }

    fun startServer(scope: CoroutineScope, port: Int = tcpPort) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    val peerIp = client.inetAddress.hostAddress ?: continue
                    registerPeer(scope, peerIp, client)
                }
            }
        }
    }

    fun connectToPeer(scope: CoroutineScope, ip: String, port: Int = tcpPort, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5000)
                registerPeer(scope, ip, socket)
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private fun registerPeer(scope: CoroutineScope, peerIp: String, socket: Socket) {
        val outStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val inStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
        peers[peerIp] = PeerConnection(peerIp, socket, outStream)

        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !socket.isClosed) {
                    val length = inStream.readInt()
                    if (length <= 0 || length > 2 * 1024 * 1024) break
                    val encryptedBytes = ByteArray(length)
                    inStream.readFully(encryptedBytes)

                    val key = secretKey ?: continue
                    val rawBytes = runCatching { cryptoManager.decrypt(encryptedBytes, key) }.getOrNull() ?: continue
                    val packet = deserializePacket(rawBytes) ?: continue

                    if (!seenPacketIds.add(packet.packetId)) continue // جلوگیری از حلقه و تکرار

                    val isJoinFlow = packet.type == PacketType.JOIN_REQUEST ||
                            packet.type == PacketType.JOIN_APPROVED ||
                            packet.type == PacketType.JOIN_REJECTED

                    if (!isJoinFlow && !memberManager.isAuthorized(packet.senderId)) {
                        continue // مسدود کردن عضو تأییدنشده یا حذف‌شده
                    }

                    onPacketReceived(packet, peerIp)

                    // Relay به سایر گوشی‌های متصل در صورت باقی ماندن TTL
                    if (packet.ttl > 1) {
                        val relayed = packet.copy(ttl = packet.ttl - 1)
                        broadcastPacket(relayed, excludeIp = peerIp)
                    }
                }
            } catch (_: Exception) {
            } finally {
                peers.remove(peerIp)
                runCatching { socket.close() }
            }
        }
    }

    fun broadcastPacket(packet: MeshPacket, excludeIp: String? = null) {
        val key = secretKey ?: return
        seenPacketIds.add(packet.packetId)
        val raw = serializePacket(packet)
        val encrypted = cryptoManager.encrypt(raw, key)

        peers.values.forEach { peer ->
            if (peer.ip != excludeIp) {
                runCatching {
                    synchronized(peer.output) {
                        peer.output.writeInt(encrypted.size)
                        peer.output.write(encrypted)
                        peer.output.flush()
                    }
                }.onFailure {
                    peers.remove(peer.ip)
                    runCatching { peer.socket.close() }
                }
            }
        }
    }

    fun stopAll() {
        runCatching { serverSocket?.close() }
        peers.values.forEach { runCatching { it.socket.close() } }
        peers.clear()
        seenPacketIds.clear()
    }

    private fun serializePacket(packet: MeshPacket): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeUTF(packet.packetId)
            dos.writeByte(packet.type.code.toInt())
            dos.writeUTF(packet.senderId)
            dos.writeUTF(packet.senderNickname)
            dos.writeUTF(packet.targetId ?: "")
            dos.writeInt(packet.ttl)
            dos.writeLong(packet.timestamp)
            dos.writeInt(packet.payload.size)
            dos.write(packet.payload)
        }
        return bos.toByteArray()
    }

    private fun deserializePacket(bytes: ByteArray): MeshPacket? {
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { dis ->
                val id = dis.readUTF()
                val type = PacketType.fromCode(dis.readByte()) ?: return null
                val senderId = dis.readUTF()
                val senderNick = dis.readUTF()
                val target = dis.readUTF().ifEmpty { null }
                val ttl = dis.readInt()
                val ts = dis.readLong()
                val payloadSize = dis.readInt()
                val payload = ByteArray(payloadSize)
                dis.readFully(payload)
                MeshPacket(id, type, senderId, senderNick, target, ttl, ts, payload)
            }
        }.getOrNull()
    }
}
