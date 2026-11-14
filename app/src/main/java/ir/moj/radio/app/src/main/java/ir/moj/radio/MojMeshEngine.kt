package ir.moj.radio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class MojMeshEngine(private val context: Context) {

    companion object {
        const val DISCOVERY_UDP_PORT = 45454
        const val MESH_TCP_PORT = 45455
        const val MAX_FILE_SIZE_BYTES = 150L * 1024L * 1024L // 150 MB
        const val CHUNK_SIZE = 32 * 1024 // 32 KB
        const val AUDIO_SAMPLE_RATE = 16000
        const val MAX_SIMULTANEOUS_CAMERAS = 2
        const val APP_VERSION = "1.1.0"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("moj_prefs", Context.MODE_PRIVATE)

    val myNodeId: String = getOrCreateNodeId()

    private val _nickname = MutableStateFlow(prefs.getString("nickname", "") ?: "")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _role = MutableStateFlow(NodeRole.NONE)
    val role: StateFlow<NodeRole> = _role.asStateFlow()

    private val _joinCode = MutableStateFlow("")
    val joinCode: StateFlow<String> = _joinCode.asStateFlow()

    private val _lastManualIp = MutableStateFlow(prefs.getString("last_manual_ip", "") ?: "")
    val lastManualIp: StateFlow<String> = _lastManualIp.asStateFlow()

    private val _myApprovalStatus = MutableStateFlow(MemberStatus.DISCONNECTED)
    val myApprovalStatus: StateFlow<MemberStatus> = _myApprovalStatus.asStateFlow()

    private val _members = MutableStateFlow<List<MemberNode>>(emptyList())
    val members: StateFlow<List<MemberNode>> = _members.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _fileTransfers = MutableStateFlow<List<FileTransferItem>>(emptyList())
    val fileTransfers: StateFlow<List<FileTransferItem>> = _fileTransfers.asStateFlow()

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()

    private val _statusBanner = MutableStateFlow("آماده ایجاد یا پیوستن به شبکه موج")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _localIpAddress = MutableStateFlow("نامشخص")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _staApConcurrencySupported = MutableStateFlow(false)
    val staApConcurrencySupported: StateFlow<Boolean> = _staApConcurrencySupported.asStateFlow()

    // وضعیت‌های اشتراک‌گذاری دوربین زنده (Live Camera Sharing)
    private val _isSharingMyCamera = MutableStateFlow(false)
    val isSharingMyCamera: StateFlow<Boolean> = _isSharingMyCamera.asStateFlow()

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    private val _incomingCameraRequests = MutableStateFlow<List<CameraViewRequest>>(emptyList())
    val incomingCameraRequests: StateFlow<List<CameraViewRequest>> = _incomingCameraRequests.asStateFlow()

    private val _approvedCameraViewers = MutableStateFlow<Set<String>>(emptySet())
    val approvedCameraViewers: StateFlow<Set<String>> = _approvedCameraViewers.asStateFlow()

    private val _watchingCameraIds = MutableStateFlow<Set<String>>(emptySet())
    val watchingCameraIds: StateFlow<Set<String>> = _watchingCameraIds.asStateFlow()

    private val _remoteCameraFrames = MutableStateFlow>(emptyMap())
    val remoteCameraFrames: StateFlow> = _remoteCameraFrames.asStateFlow()

    var isRogerBeepEnabled: Boolean
        get() = prefs.getBoolean("roger_beep", true)
        set(value) = prefs.edit().putBoolean("roger_beep", value).apply()

    var incomingVolumeGain: Float
        get() = prefs.getFloat("volume_gain", 1.0f)
        set(value) {
            prefs.edit().putFloat("volume_gain", value.coerceIn(0.2f, 2.0f)).apply()
            audioTrack?.setVolume(value.coerceIn(0.0f, 1.0f))
        }

    var onNotificationEvent: ((title: String, body: String) -> Unit)? = null

    private var myAuthToken: String = ""
    private val networkSecret = UUID.randomUUID().toString()
    private var lastFrameSentTime = 0L

    private val seenPacketIds = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(2500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 2200
            }
        })
    )

    private val kickedNodeIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val revokedTokens = Collections.synchronizedSet(mutableSetOf<String>())

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val memberMap = ConcurrentHashMap<String, MemberNode>()
    private val incomingFileStreams = ConcurrentHashMap<String, RandomAccessFile>()

    private var tcpServerSocket: ServerSocket? = null
    private var udpDiscoverySocket: DatagramSocket? = null
    private var isPttRecording = false
    private var pttJob: Job? = null
    private var audioTrack: AudioTrack? = null

    init {
        checkHardwareConcurrency()
        refreshLocalIp()
        initAudioPlayer()
    }

    private fun getOrCreateNodeId(): String {
        var id = prefs.getString("node_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 8).uppercase()
            prefs.edit().putString("node_id", id).apply()
        }
        return id
    }

    fun saveNickname(newName: String): Boolean {
        val clean = newName.trim()
        if (clean.length !in 2..20) return false
        prefs.edit().putString("nickname", clean).apply()
        _nickname.value = clean
        memberMap[myNodeId]?.let {
            memberMap[myNodeId] = it.copy(nickname = clean)
            updateMembersState()
            if (_role.value == NodeRole.HOST) broadcastMemberList()
        }
        return true
    }

    val myDisplayName: String
        get() = _nickname.value.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}".take(16) }

    fun checkHardwareConcurrency() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager?.isStaApConcurrencySupported == true
        } else {
            false
        }
        _staApConcurrencySupported.value = supported
    }

    fun refreshLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            _localIpAddress.value = ip
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        _localIpAddress.value = "192.168.43.1"
        return _localIpAddress.value
    }

    private fun getGatewayIp(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo ?: return null
            val ipInt = dhcp.gateway
            if (ipInt == 0) null else String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            null
        }
    }

    fun startHostNetwork() {
        stopNetwork()
        kickedNodeIds.clear()
        revokedTokens.clear()
        refreshLocalIp()
        val code = MojSecurityUtils.generateJoinCode(8)
        _joinCode.value = code
        _role.value = NodeRole.HOST
        _myApprovalStatus.value = MemberStatus.APPROVED
        myAuthToken = MojSecurityUtils.generateAuthToken(myNodeId, networkSecret)

        val selfNode = MemberNode(
            nodeId = myNodeId,
            nickname = "$myDisplayName (مدیر)",
            ipAddress = _localIpAddress.value,
            status = MemberStatus.APPROVED,
            authToken = myAuthToken,
            hopCount = 0
        )
        memberMap[myNodeId] = selfNode
        updateMembersState()

        startTcpMeshServer()
        startUdpDiscoveryResponder()
        _statusBanner.value = "شبکه مرکزی فعال شد | کد: $code | IP: ${_localIpAddress.value}"
    }

    fun joinNetwork(inputCode: String, manualHostIp: String = "") {
        val cleanCode = inputCode.trim().uppercase()
        if (cleanCode.length !in 6..10) {
            _statusBanner.value = "خطا: کد اتصال باید بین ۶ تا ۱۰ کاراکتر باشد."
            return
        }
        if (manualHostIp.isNotBlank()) {
            prefs.edit().putString("last_manual_ip", manualHostIp.trim()).apply()
            _lastManualIp.value = manualHostIp.trim()
        }
        stopNetwork()
        refreshLocalIp()
        _joinCode.value = cleanCode
        _role.value = NodeRole.CLIENT
        _myApprovalStatus.value = MemberStatus.PENDING
        _statusBanner.value = "در حال جستجو و اتصال به شبکه موج ($cleanCode)..."

        startTcpMeshServer()
        startUdpDiscoveryResponder()

        scope.launch {
            val cleanManual = manualHostIp.trim().substringBefore(":")
            val manualPort = manualHostIp.trim().substringAfter(":", "").toIntOrNull() ?: MESH_TCP_PORT

            val candidateIps = LinkedHashSet<String>()
            if (cleanManual.isNotEmpty()) candidateIps.add(cleanManual)

            // ۱. کشف سریع از طریق UDP Broadcast در تمام زیرشبکه‌ها
            discoverHostViaUdp(cleanCode)?.let { candidateIps.add(it) }

            // ۲. بررسی آدرس Gateway هات‌اسپات و زیرشبکه فعلی گوشی
            getGatewayIp()?.let { candidateIps.add(it) }
            val myIp = _localIpAddress.value
            if (myIp.count { it == '.' } == 3) {
                val prefix = myIp.substringBeforeLast(".")
                candidateIps.add("$prefix.1")
            }
            // ۳. آدرس‌های استاندارد هات‌اسپات در سامسونگ، شیائومی و اندروید خام
            listOf("192.168.43.1", "192.168.49.1", "192.168.137.1", "192.168.1.1").forEach {
                candidateIps.add(it)
            }

            var connected = false
            for (ip in candidateIps) {
                _statusBanner.value = "در حال امتحان اتصال به آدرس $ip:$manualPort..."
                if (connectToUpstreamNode(ip, manualPort, cleanCode)) {
                    connected = true
                    break
                }
            }

            if (!connected) {
                _myApprovalStatus.value = MemberStatus.DISCONNECTED
                _statusBanner.value = "اتصال برقرار نشد. از اتصال Wi-Fi به هات‌اسپات مطمئن شوید یا IP مدیر را وارد کرده و تلاش مجدد بزنید."
            }
        }
    }

    fun retryConnection() {
        if (_joinCode.value.isNotEmpty() && _role.value == NodeRole.CLIENT) {
            joinNetwork(_joinCode.value, _lastManualIp.value)
        }
    }

    private suspend fun discoverHostViaUdp(code: String): String? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1600

            val reqJson = JSONObject().apply {
                put("cmd", "MOJ_DISCOVER")
                put("code", code)
                put("senderId", myNodeId)
            }.toString().toByteArray()

            val bcastList = mutableSetOf("255.255.255.255", "192.168.43.255", "192.168.49.255")
            val myIp = _localIpAddress.value
            if (myIp.count { it == '.' } == 3) {
                bcastList.add("${myIp.substringBeforeLast(".")}.255")
            }

            for (bcast in bcastList) {
                try {
                    val pkt = DatagramPacket(reqJson, reqJson.size, InetAddress.getByName(bcast), DISCOVERY_UDP_PORT)
                    socket.send(pkt)
                } catch (_: Exception) {}
            }

            val buf = ByteArray(1024)
            val respPkt = DatagramPacket(buf, buf.size)
            socket.receive(respPkt)
            val respObj = JSONObject(String(respPkt.data, 0, respPkt.length))
            if (respObj.optString("cmd") == "MOJ_HERE" && respObj.optString("code") == code) {
                return@withContext respPkt.address.hostAddress
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        null
    }

    private fun startUdpDiscoveryResponder() {
        scope.launch {
            try {
                udpDiscoverySocket?.close()
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                udpDiscoverySocket = socket

                val buf = ByteArray(1024)
                while (isActive && !socket.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val json = JSONObject(String(pkt.data, 0, pkt.length))
                    if (json.optString("cmd") == "MOJ_DISCOVER") {
                        val reqCode = json.optString("code")
                        if (reqCode == _joinCode.value && _myApprovalStatus.value == MemberStatus.APPROVED) {
                            val reply = JSONObject().apply {
                                put("cmd", "MOJ_HERE")
                                put("code", _joinCode.value)
                                put("nodeId", myNodeId)
                            }.toString().toByteArray()
                            socket.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startTcpMeshServer() {
        scope.launch {
            try {
                tcpServerSocket?.close()
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(MESH_TCP_PORT))
                tcpServerSocket = server

                while (isActive && !server.isClosed) {
                    val clientSocket = server.accept()
                    clientSocket.tcpNoDelay = true
                    clientSocket.keepAlive = true
                    handleSocketConnection(clientSocket)
                }
            } catch (e: Exception) {
                if (_role.value != NodeRole.NONE) {
                    _statusBanner.value = "خطا در سوکت شبکه: ${e.localizedMessage}"
                }
            }
        }
    }

    private suspend fun connectToUpstreamNode(ip: String, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(ip, port), 2500)
            val peer = handleSocketConnection(socket)

            val joinPayload = JSONObject().apply {
                put("joinCode", code)
                put("nickname", myDisplayName)
                put("ipAddress", _localIpAddress.value)
            }
            val joinPacket = signPacket(
                MeshPacket(
                    type = "JOIN_REQ",
                    senderId = myNodeId,
                    senderName = myDisplayName,
                    targetId = "HOST",
                    payload = joinPayload
                )
            )
            seenPacketIds.add(joinPacket.packetId)
            peer.sendLine(joinPacket.toJsonString())
            _statusBanner.value = "متصل به $ip | درخواست ورود ارسال شد. منتظر تأیید مدیر بمانید..."
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun signPacket(packet: MeshPacket): MeshPacket {
        val code = _joinCode.value
        val hmac = MojSecurityUtils.computeHmac(
            packet.packetId,
            packet.type,
            packet.senderId,
            packet.payload.toString(),
            code
        )
        return packet.copy(hmac = hmac)
    }

    private fun verifyPacketHmac(packet: MeshPacket): Boolean {
        val code = _joinCode.value
        if (code.isEmpty()) return false
        val expected = MojSecurityUtils.computeHmac(
            packet.packetId,
            packet.type,
            packet.senderId,
            packet.payload.toString(),
            code
        )
        return expected.isNotEmpty() && expected == packet.hmac
    }

    private fun handleSocketConnection(socket: Socket): PeerConnection {
        val peer = PeerConnection(socket)
        val connId = "peer_${UUID.randomUUID()}"
        peerConnections[connId] = peer

        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val packet = MeshPacket.fromJsonString(line) ?: continue
                    peer.remoteNodeId = packet.senderId
                    processIncomingPacket(packet, peer, socket.inetAddress.hostAddress ?: "")
                }
            } catch (_: Exception) {
            } finally {
                peerConnections.remove(connId)
                peer.remoteNodeId?.let { nodeId ->
                    _remoteCameraFrames.value = _remoteCameraFrames.value - nodeId
                    _watchingCameraIds.value = _watchingCameraIds.value - nodeId
                    memberMap[nodeId]?.let { member ->
                        if (member.status == MemberStatus.APPROVED) {
                            member.status = MemberStatus.DISCONNECTED
                            member.isSharingCamera = false
                            updateMembersState()
                            onNotificationEvent?.invoke("قطع ارتباط عضو", "${member.displayTitle} از شبکه خارج شد.")
                            if (_role.value == NodeRole.HOST) broadcastMemberList()
                        }
                    }
                }
                try { socket.close() } catch (_: Exception) {}
            }
        }
        return peer
    }

    private fun processIncomingPacket(packet: MeshPacket, sourcePeer: PeerConnection, remoteIp: String) {
        // ۱. جلوگیری از حلقه و بسته‌های تکراری
        if (!seenPacketIds.add(packet.packetId)) return

        // ۲. بررسی امضای HMAC برای جلوگیری از دست‌کاری
        if (!verifyPacketHmac(packet)) return

        // ۳. بررسی مسدود نبودن فرستنده
        if (kickedNodeIds.contains(packet.senderId) || (packet.authToken.isNotEmpty() && revokedTokens.contains(packet.authToken))) {
            return
        }

        // ۴. مدیریت درخواست ورود در گوشی مدیر
        if (packet.type == "JOIN_REQ") {
            if (_role.value == NodeRole.HOST) {
                val reqCode = packet.payload.optString("joinCode")
                if (reqCode != _joinCode.value) {
                    val rejectPkt = signPacket(
                        MeshPacket(
                            type = "JOIN_RES",
                            senderId = myNodeId,
                            senderName = myDisplayName,
                            targetId = packet.senderId,
                            payload = JSONObject().apply {
                                put("status", MemberStatus.REJECTED.name)
                                put("reason", "کد اتصال اشتباه است.")
                            }
                        )
                    )
                    sourcePeer.sendLine(rejectPkt.toJsonString())
                    return
                }
                val existing = memberMap[packet.senderId]
                val member = MemberNode(
                    nodeId = packet.senderId,
                    nickname = packet.payload.optString("nickname", packet.senderName),
                    ipAddress = packet.payload.optString("ipAddress", remoteIp),
                    status = if (existing?.status == MemberStatus.APPROVED) MemberStatus.APPROVED else MemberStatus.PENDING,
                    authToken = existing?.authToken ?: "",
                    hopCount = packet.hopCount
                )
                memberMap[packet.senderId] = member
                updateMembersState()
                onNotificationEvent?.invoke("درخواست ورود جدید", "${member.displayTitle} درخواست ورود به شبکه موج را دارد.")
            } else {
                relayPacket(packet, sourcePeer)
            }
            return
        }

        // ۵. پاسخ ورود یا اخراج عضو
        if (packet.type == "JOIN_RES") {
            if (packet.targetId == myNodeId) {
                val st = MemberStatus.valueOf(packet.payload.optString("status", "REJECTED"))
                _myApprovalStatus.value = st
                when (st) {
                    MemberStatus.APPROVED -> {
                        myAuthToken = packet.payload.optString("authToken", "")
                        _statusBanner.value = "عضویت تأیید شد | متصل به شبکه موج (${_joinCode.value})"
                        onNotificationEvent?.invoke("تأیید ورود", "ورود شما به شبکه موج تأیید شد.")
                    }
                    MemberStatus.REJECTED -> {
                        myAuthToken = ""
                        _statusBanner.value = "درخواست ورود شما توسط مدیر رد شد."
                    }
                    MemberStatus.KICKED -> {
                        myAuthToken = ""
                        _statusBanner.value = "شما توسط مدیر از شبکه حذف شدید."
                        stopNetwork()
                    }
                    else -> {}
                }
            } else {
                relayPacket(packet, sourcePeer)
            }
            return
        }

        // ۶. احراز هویت اعضا در گوشی مرکزی و اعضا
        if (_role.value == NodeRole.HOST) {
            val senderNode = memberMap[packet.senderId]
            if (senderNode == null || senderNode.status != MemberStatus.APPROVED || senderNode.authToken != packet.authToken) {
                return
            }
            senderNode.lastSeen = System.currentTimeMillis()
        } else {
            if (_myApprovalStatus.value != MemberStatus.APPROVED) return
        }

        // ۷. رله بسته به سایر گره‌ها (دریافت صدا و دیتا توسط مدیر و انتقال به بقیه)
        if (packet.targetId == "ALL" || packet.targetId != myNodeId) {
            relayPacket(packet, sourcePeer)
        }

        if (packet.targetId != "ALL" && packet.targetId != myNodeId) {
            return
        }

        // ۸. پردازش محلی بسته
        when (packet.type) {
            "MEMBER_LIST" -> handleMemberListSync(packet.payload)
            "TEXT_MSG" -> handleIncomingText(packet)
            "PTT_FLOOR" -> handlePttFloorControl(packet)
            "PTT_AUDIO" -> handleIncomingAudioChunk(packet)
            "FILE_START" -> handleFileStart(packet)
            "FILE_CHUNK" -> handleFileChunk(packet)
            "FILE_END" -> handleFileEnd(packet)
            "FILE_CANCEL" -> handleFileCancel(packet)
            "CAM_STATE" -> handleCameraStateChange(packet)
            "CAM_VIEW_REQ" -> handleCameraViewRequest(packet)
            "CAM_VIEW_RES" -> handleCameraViewResponse(packet)
            "CAM_STOP_VIEW" -> handleCameraStopView(packet)
            "CAM_FRAME" -> handleIncomingCameraFrame(packet)
        }
    }

    private fun relayPacket(packet: MeshPacket, excludePeer: PeerConnection?) {
        if (packet.ttl <= 1) return
        val relayed = packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
        val raw = relayed.toJsonString()
        peerConnections.values.forEach { peer ->
            if (peer !== excludePeer) {
                peer.sendLine(raw)
            }
        }
    }

    private fun broadcastPacket(packet: MeshPacket) {
        val signed = signPacket(packet)
        seenPacketIds.add(signed.packetId)
        val raw = signed.toJsonString()
        peerConnections.values.forEach { it.sendLine(raw) }
    }

    fun approveMember(nodeId: String) {
        if (_role.value != NodeRole.HOST) return
        val member = memberMap[nodeId] ?: return
        kickedNodeIds.remove(nodeId)
        val token = MojSecurityUtils.generateAuthToken(nodeId, networkSecret)
        member.status = MemberStatus.APPROVED
        member.authToken = token
        updateMembersState()

        val resPkt = MeshPacket(
            type = "JOIN_RES",
            senderId = myNodeId,
            senderName = myDisplayName,
            targetId = nodeId,
            authToken = myAuthToken,
            payload = JSONObject().apply {
                put("status", MemberStatus.APPROVED.name)
                put("authToken", token)
            }
        )
        broadcastPacket(resPkt)
        broadcastMemberList()
    }

    fun rejectOrKickMember(nodeId: String, isKick: Boolean) {
        if (_role.value != NodeRole.HOST) return
        val member = memberMap[nodeId] ?: return
        if (isKick) {
            kickedNodeIds.add(nodeId)
            if (member.authToken.isNotEmpty()) revokedTokens.add(member.authToken)
        }
        val newStatus = if (isKick) MemberStatus.KICKED else MemberStatus.REJECTED
        member.status = newStatus
        member.authToken = ""
        member.isSharingCamera = false
        _approvedCameraViewers.value = _approvedCameraViewers.value - nodeId
        _watchingCameraIds.value = _watchingCameraIds.value - nodeId
        _remoteCameraFrames.value = _remoteCameraFrames.value - nodeId
        updateMembersState()

        val resPkt = MeshPacket(
            type = "JOIN_RES",
            senderId = myNodeId,
            senderName = myDisplayName,
            targetId = nodeId,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("status", newStatus.name) }
        )
        broadcastPacket(resPkt)
        broadcastMemberList()
    }

    private fun broadcastMemberList() {
        if (_role.value != NodeRole.HOST) return
        val arr = JSONArray()
        memberMap.values.forEach { m ->
            arr.put(JSONObject().apply {
                put("nodeId", m.nodeId)
                put("nickname", m.nickname)
                put("ipAddress", m.ipAddress)
                put("status", m.status.name)
                put("hopCount", m.hopCount)
                put("isSharingCamera", m.isSharingCamera)
            })
        }
        val pkt = MeshPacket(
            type = "MEMBER_LIST",
            senderId = myNodeId,
            senderName = myDisplayName,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("members", arr) }
        )
        broadcastPacket(pkt)
    }

    private fun handleMemberListSync(payload: JSONObject) {
        if (_role.value == NodeRole.HOST) return
        val arr = payload.optJSONArray("members") ?: return
        val list = mutableListOf<MemberNode>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val m = MemberNode(
                nodeId = obj.getString("nodeId"),
                nickname = obj.optString("nickname", "کاربر"),
                ipAddress = obj.optString("ipAddress", ""),
                status = MemberStatus.valueOf(obj.optString("status", "APPROVED")),
                hopCount = obj.optInt("hopCount", 1),
                isSharingCamera = obj.optBoolean("isSharingCamera", false)
            )
            memberMap[m.nodeId] = m
            list.add(m)
        }
        _members.value = list.sortedBy { it.nickname }
    }

    private fun updateMembersState() {
        _members.value = memberMap.values.sortedBy { it.nickname }
    }

    /**
     * ارسال پیام متنی رمزنگاری‌شده با AES-GCM
     */
    fun sendTextMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _myApprovalStatus.value != MemberStatus.APPROVED) return
        val msg = ChatMessage(
            senderId = myNodeId,
            senderName = myDisplayName,
            text = clean,
            isIncoming = false
        )
        _messages.value = _messages.value + msg

        val cipherText = MojSecurityUtils.encryptAesGcm(clean, _joinCode.value)
        val pkt = MeshPacket(
            type = "TEXT_MSG",
            senderId = myNodeId,
            senderName = myDisplayName,
            authToken = myAuthToken,
            payload = JSONObject().apply {
                put("msgId", msg.id)
                put("cipher", cipherText)
                put("timestamp", msg.timestamp)
            }
        )
        scope.launch { broadcastPacket(pkt) }
    }

    private fun handleIncomingText(packet: MeshPacket) {
        val cipher = packet.payload.optString("cipher", "")
        val plain = if (cipher.isNotEmpty()) {
            MojSecurityUtils.decryptAesGcm(cipher, _joinCode.value) ?: return
        } else {
            packet.payload.optString("text", "")
        }
        val msg = ChatMessage(
            id = packet.payload.optString("msgId", UUID.randomUUID().toString()),
            senderId = packet.senderId,
            senderName = packet.senderName,
            text = plain,
            timestamp = packet.payload.optLong("timestamp", System.currentTimeMillis()),
            isIncoming = true
        )
        _messages.value = _messages.value + msg
        onNotificationEvent?.invoke("پیام جدید از ${packet.senderName}", msg.text)
    }

    /**
     * بی‌سیم صوتی دوطرفه (Push-To-Talk) فعال در پیش‌زمینه و پس‌زمینه
     */
    @SuppressLint("MissingPermission")
    fun startPushToTalk(): Boolean {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) {
            _statusBanner.value = "شما هنوز تأیید نشده‌اید."
            return false
        }
        val currentSpeaker = _activeSpeaker.value
        if (currentSpeaker != null && currentSpeaker != myDisplayName) {
            _statusBanner.value = "خط مشغول است: $currentSpeaker در حال صحبت است."
            return false
        }

        isPttRecording = true
        _activeSpeaker.value = myDisplayName

        val floorTakePkt = MeshPacket(
            type = "PTT_FLOOR",
            senderId = myNodeId,
            senderName = myDisplayName,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("action", "TAKE") }
        )
        scope.launch { broadcastPacket(floorTakePkt) }

        pttJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                recorder.startRecording()
                val frameBuf = ByteArray(1280)
                while (isActive && isPttRecording) {
                    val read = recorder.read(frameBuf, 0, frameBuf.size)
                    if (read > 0) {
                        val b64 = Base64.encodeToString(frameBuf, 0, read, Base64.NO_WRAP)
                        val audioPkt = MeshPacket(
                            type = "PTT_AUDIO",
                            senderId = myNodeId,
                            senderName = myDisplayName,
                            authToken = myAuthToken,
                            payload = JSONObject().apply { put("pcm", b64) }
                        )
                        broadcastPacket(audioPkt)
                    }
                }
            } catch (e: Exception) {
                _statusBanner.value = "خطای میکروفون: ${e.localizedMessage}"
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
            }
        }
        return true
    }

    fun stopPushToTalk() {
        if (!isPttRecording) return
        isPttRecording = false
        pttJob?.cancel()
        _activeSpeaker.value = null

        val floorReleasePkt = MeshPacket(
            type = "PTT_FLOOR",
            senderId = myNodeId,
            senderName = myDisplayName,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("action", "RELEASE") }
        )
        scope.launch { broadcastPacket(floorReleasePkt) }
        playRogerBeep()
    }

    private fun handlePttFloorControl(packet: MeshPacket) {
        val action = packet.payload.optString("action")
        if (action == "TAKE") {
            _activeSpeaker.value = packet.senderName
        } else if (action == "RELEASE") {
            if (_activeSpeaker.value == packet.senderName) {
                _activeSpeaker.value = null
            }
            playRogerBeep()
        }
    }

    private fun initAudioPlayer() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isSpeakerphoneOn = true

            val minBuf = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.setVolume(incomingVolumeGain.coerceIn(0.0f, 1.0f))
            audioTrack?.play()
        } catch (_: Exception) {}
    }

    private fun handleIncomingAudioChunk(packet: MeshPacket) {
        val b64 = packet.payload.optString("pcm", "")
        if (b64.isEmpty()) return
        _activeSpeaker.value = packet.senderName
        try {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
            val pcmBytes = Base64.decode(b64, Base64.NO_WRAP)
            // اعمال تقویت صدا در صورت تنظیم بالای ۱۰۰٪
            val gain = incomingVolumeGain
            if (gain != 1.0f && pcmBytes.size >= 2) {
                var i = 0
                while (i < pcmBytes.size - 1) {
                    val sample = ((pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)).toShort()
                    val boosted = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    pcmBytes[i] = (boosted.toInt() and 0xFF).toByte()
                    pcmBytes[i + 1] = ((boosted.toInt() shr 8) and 0xFF).toByte()
                    i += 2
                }
            }
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (_: Exception) {}
    }

    private fun playRogerBeep() {
        if (!isRogerBeepEnabled) return
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {}
    }

    // ==================== قابلیت اشتراک‌گذاری دوطرفه دوربین زنده ====================

    fun toggleMyCameraSharing(enable: Boolean) {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) return
        _isSharingMyCamera.value = enable
        if (!enable) {
            _approvedCameraViewers.value = emptySet()
            _incomingCameraRequests.value = emptyList()
        }
        memberMap[myNodeId]?.isSharingCamera = enable
        updateMembersState()

        val pkt = MeshPacket(
            type = "CAM_STATE",
            senderId = myNodeId,
            senderName = myDisplayName,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("sharing", enable) }
        )
        scope.launch {
            broadcastPacket(pkt)
            if (_role.value == NodeRole.HOST) broadcastMemberList()
        }
    }

    fun switchCameraLens() {
        _useFrontCamera.value = !_useFrontCamera.value
    }

    fun requestWatchMemberCamera(targetNodeId: String) {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) return
        if (_watchingCameraIds.value.size >= MAX_SIMULTANEOUS_CAMERAS && !_watchingCameraIds.value.contains(targetNodeId)) {
            _statusBanner.value = "محدودیت پهنای باند شبکه محلی: حداکثر $MAX_SIMULTANEOUS_CAMERAS دوربین هم‌زمان قابل مشاهده است."
            return
        }
        val pkt = MeshPacket(
            type = "CAM_VIEW_REQ",
            senderId = myNodeId,
            senderName = myDisplayName,
            targetId = targetNodeId,
            authToken = myAuthToken
        )
        scope.launch { broadcastPacket(pkt) }
        _statusBanner.value = "درخواست مشاهده دوربین ارسال شد. منتظر اجازه صاحب گوشی بمانید..."
    }

    fun respondToCameraRequest(requesterId: String, approved: Boolean) {
        _incomingCameraRequests.value = _incomingCameraRequests.value.filterNot { it.requesterId == requesterId }
        if (approved) {
            _approvedCameraViewers.value = _approvedCameraViewers.value + requesterId
        } else {
            _approvedCameraViewers.value = _approvedCameraViewers.value - requesterId
        }
        val pkt = MeshPacket(
            type = "CAM_VIEW_RES",
            senderId = myNodeId,
            senderName = myDisplayName,
            targetId = requesterId,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("approved", approved) }
        )
        scope.launch { broadcastPacket(pkt) }
    }

    fun revokeViewerAccess(viewerId: String) {
        _approvedCameraViewers.value = _approvedCameraViewers.value - viewerId
        val pkt = MeshPacket(
            type = "CAM_VIEW_RES",
            senderId = myNodeId,
            senderName = myDisplayName,
            targetId = viewerId,
            authToken = myAuthToken,
            payload = JSONObject().apply { put("approved", false) }
        )
        scope.launch { broadcastPacket(pkt) }
    }

    fun stopWatchingRemoteCamera(ownerNodeId: String) {
        _watchingCameraIds.value = _watchingCameraIds.value - ownerNodeId
        _remoteCameraFrames.value = _remoteCameraFrames.value - ownerNodeId
        val pkt = MeshPacket(
            type = "CAM_STOP_VIEW",
            senderId = myNodeId,
            senderName = myDisplayName,
            targetId = ownerNodeId,
            authToken = myAuthToken
        )
        scope.launch { broadcastPacket(pkt) }
    }

    fun broadcastMyCameraFrame(jpegBytes: ByteArray) {
        if (!_isSharingMyCamera.value || _approvedCameraViewers.value.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameSentTime < 180) return // ~5.5 FPS برای پایداری شبکه Mesh
        lastFrameSentTime = now

        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val viewers = _approvedCameraViewers.value.toList()
        scope.launch {
            for (viewerId in viewers) {
                val framePkt = MeshPacket(
                    type = "CAM_FRAME",
                    senderId = myNodeId,
                    senderName = myDisplayName,
                    targetId = viewerId,
                    authToken = myAuthToken,
                    payload = JSONObject().apply { put("jpeg", b64) }
                )
                broadcastPacket(framePkt)
            }
        }
    }

    private fun handleCameraStateChange(packet: MeshPacket) {
        val sharing = packet.payload.optBoolean("sharing", false)
        memberMap[packet.senderId]?.isSharingCamera = sharing
        updateMembersState()
        if (!sharing) {
            _watchingCameraIds.value = _watchingCameraIds.value - packet.senderId
            _remoteCameraFrames.value = _remoteCameraFrames.value - packet.senderId
        }
        if (_role.value == NodeRole.HOST) broadcastMemberList()
    }

    private fun handleCameraViewRequest(packet: MeshPacket) {
        if (!_isSharingMyCamera.value) return
        if (_incomingCameraRequests.value.none { it.requesterId == packet.senderId }) {
            _incomingCameraRequests.value = _incomingCameraRequests.value + CameraViewRequest(
                requesterId = packet.senderId,
                requesterName = packet.senderName
            )
            onNotificationEvent?.invoke(
                "درخواست مشاهده دوربین",
                "${packet.senderName} درخواست مشاهده دوربین زنده شما را دارد."
            )
        }
    }

    private fun handleCameraViewResponse(packet: MeshPacket) {
        val approved = packet.payload.optBoolean("approved", false)
        if (approved) {
            if (_watchingCameraIds.value.size < MAX_SIMULTANEOUS_CAMERAS) {
                _watchingCameraIds.value = _watchingCameraIds.value + packet.senderId
                _statusBanner.value = "دسترسی به دوربین زنده ${packet.senderName} تأیید شد."
            }
        } else {
            _watchingCameraIds.value = _watchingCameraIds.value - packet.senderId
            _remoteCameraFrames.value = _remoteCameraFrames.value - packet.senderId
            _statusBanner.value = "مشاهده دوربین ${packet.senderName} متوقف یا رد شد."
        }
    }

    private fun handleCameraStopView(packet: MeshPacket) {
        _approvedCameraViewers.value = _approvedCameraViewers.value - packet.senderId
    }

    private fun handleIncomingCameraFrame(packet: MeshPacket) {
        if (!_watchingCameraIds.value.contains(packet.senderId)) return
        val b64 = packet.payload.optString("jpeg", "")
        if (b64.isEmpty()) return
        try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            _remoteCameraFrames.value = _remoteCameraFrames.value + (packet.senderId to bmp)
        } catch (_: Exception) {}
    }

    // ==================== ارسال بخش‌بندی‌شده فایل و عکس ====================

    fun sendUriFile(uri: Uri, isImage: Boolean) {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) return
        scope.launch {
            try {
                val resolver = context.contentResolver
                var fileName = if (isImage) "moj_img_${System.currentTimeMillis()}.jpg" else "moj_file_${System.currentTimeMillis()}"
                var fileSize = 0L
                val mimeType = resolver.getType(uri) ?: if (isImage) "image/jpeg" else "application/octet-stream"

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    _statusBanner.value = "خطا: حجم فایل بیش از ۱۵۰ مگابایت است."
                    return@launch
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var actualSize = 0L
                resolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(CHUNK_SIZE)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        digest.update(buf, 0, r)
                        actualSize += r
                    }
                }
                if (fileSize <= 0L) fileSize = actualSize
                val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                val transferId = UUID.randomUUID().toString()

                val transferItem = FileTransferItem(
                    transferId = transferId,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    senderId = myNodeId,
                    senderName = myDisplayName,
                    sha256 = sha256Hex,
                    isImage = isImage,
                    isIncoming = false
                )
                _fileTransfers.value = _fileTransfers.value + transferItem

                val startPkt = MeshPacket(
                    type = "FILE_START",
                    senderId = myNodeId,
                    senderName = myDisplayName,
                    authToken = myAuthToken,
                    payload = JSONObject().apply {
                        put("transferId", transferId)
                        put("fileName", fileName)
                        put("fileSize", fileSize)
                        put("mimeType", mimeType)
                        put("sha256", sha256Hex)
                        put("isImage", isImage)
                    }
                )
                broadcastPacket(startPkt)

                resolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(CHUNK_SIZE)
                    var offset = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        val currentState = _fileTransfers.value.find { it.transferId == transferId }
                        if (currentState?.isCanceled == true) {
                            val cancelPkt = MeshPacket(
                                type = "FILE_CANCEL",
                                senderId = myNodeId,
                                senderName = myDisplayName,
                                authToken = myAuthToken,
                                payload = JSONObject().apply { put("transferId", transferId) }
                            )
                            broadcastPacket(cancelPkt)
                            return@launch
                        }

                        val b64Chunk = Base64.encodeToString(buf, 0, read, Base64.NO_WRAP)
                        val chunkPkt = MeshPacket(
                            type = "FILE_CHUNK",
                            senderId = myNodeId,
                            senderName = myDisplayName,
                            authToken = myAuthToken,
                            payload = JSONObject().apply {
                                put("transferId", transferId)
                                put("offset", offset)
                                put("data", b64Chunk)
                            }
                        )
                        broadcastPacket(chunkPkt)
                        offset += read
                        updateFileProgress(transferId, offset, isCompleted = false)
                        delay(10)
                    }
                }

                val endPkt = MeshPacket(
                    type = "FILE_END",
                    senderId = myNodeId,
                    senderName = myDisplayName,
                    authToken = myAuthToken,
                    payload = JSONObject().apply {
                        put("transferId", transferId)
                        put("sha256", sha256Hex)
                    }
                )
                broadcastPacket(endPkt)
                updateFileProgress(transferId, fileSize, isCompleted = true)
            } catch (e: Exception) {
                _statusBanner.value = "خطا در ارسال فایل: ${e.localizedMessage}"
            }
        }
    }

    fun cancelFileTransfer(transferId: String) {
        _fileTransfers.value = _fileTransfers.value.map {
            if (it.transferId == transferId) it.copy(isCanceled = true) else it
        }
        incomingFileStreams.remove(transferId)?.close()
    }

    private fun handleFileStart(packet: MeshPacket) {
        val p = packet.payload
        val transferId = p.optString("transferId")
        val fileName = p.optString("fileName", "received_file")
        val fileSize = p.optLong("fileSize", 0L)
        if (fileSize > MAX_FILE_SIZE_BYTES) return

        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "MOJ_Downloads")
        if (!outDir.exists()) outDir.mkdirs()
        val destFile = File(outDir, "${transferId.take(6)}_$fileName")

        try {
            val raf = RandomAccessFile(destFile, "rw")
            incomingFileStreams[transferId] = raf
            val item = FileTransferItem(
                transferId = transferId,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = p.optString("mimeType", "application/octet-stream"),
                senderId = packet.senderId,
                senderName = packet.senderName,
                sha256 = p.optString("sha256", ""),
                isImage = p.optBoolean("isImage", false),
                bytesTransferred = destFile.length(),
                localFilePath = destFile.absolutePath,
                isIncoming = true
            )
            _fileTransfers.value = _fileTransfers.value + item
        } catch (_: Exception) {}
    }

    private fun handleFileChunk(packet: MeshPacket) {
        val p = packet.payload
        val transferId = p.optString("transferId")
        val offset = p.optLong("offset", 0L)
        val dataB64 = p.optString("data", "")
        val raf = incomingFileStreams[transferId] ?: return

        try {
            val bytes = Base64.decode(dataB64, Base64.NO_WRAP)
            synchronized(raf) {
                raf.seek(offset)
                raf.write(bytes)
            }
            updateFileProgress(transferId, offset + bytes.size, isCompleted = false)
        } catch (_: Exception) {}
    }

    private fun handleFileEnd(packet: MeshPacket) {
        val transferId = packet.payload.optString("transferId")
        val expectedSha = packet.payload.optString("sha256")
        val raf = incomingFileStreams.remove(transferId)
        try { raf?.close() } catch (_: Exception) {}

        val item = _fileTransfers.value.find { it.transferId == transferId } ?: return
        val path = item.localFilePath ?: return
        val file = File(path)

        scope.launch {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(CHUNK_SIZE)
                var r: Int
                while (fis.read(buf).also { r = it } != -1) {
                    digest.update(buf, 0, r)
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha.isEmpty() || actualSha.equals(expectedSha, ignoreCase = true)) {
                updateFileProgress(transferId, item.fileSize, isCompleted = true)
                val notifTitle = if (item.isImage) "دریافت عکس جدید" else "دریافت فایل جدید"
                onNotificationEvent?.invoke(notifTitle, "${item.fileName} از طرف ${item.senderName} دریافت شد.")
            } else {
                _statusBanner.value = "خطا در بررسی صحت فایل دریافتی (${item.fileName})"
            }
        }
    }

    private fun handleFileCancel(packet: MeshPacket) {
        val transferId = packet.payload.optString("transferId")
        cancelFileTransfer(transferId)
    }

    private fun updateFileProgress(transferId: String, transferred: Long, isCompleted: Boolean) {
        _fileTransfers.value = _fileTransfers.value.map {
            if (it.transferId == transferId) {
                it.copy(bytesTransferred = transferred, isCompleted = isCompleted)
            } else it
        }
    }

    fun stopNetwork() {
        isPttRecording = false
        pttJob?.cancel()
        _isSharingMyCamera.value = false
        _approvedCameraViewers.value = emptySet()
        _watchingCameraIds.value = emptySet()
        _remoteCameraFrames.value = emptyMap()
        _incomingCameraRequests.value = emptyList()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        memberMap.clear()
        try { tcpServerSocket?.close() } catch (_: Exception) {}
        try { udpDiscoverySocket?.close() } catch (_: Exception) {}
        _role.value = NodeRole.NONE
        _myApprovalStatus.value = MemberStatus.DISCONNECTED
        _activeSpeaker.value = null
        _members.value = emptyList()
        _statusBanner.value = "ارتباط شبکه قطع شد."
    }

    private inner class PeerConnection(private val socket: Socket) {
        var remoteNodeId: String? = null
        private val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)), true)

        @Synchronized
        fun sendLine(line: String) {
            try {
                if (!socket.isClosed) {
                    writer.println(line)
                }
            } catch (_: Exception) {}
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
