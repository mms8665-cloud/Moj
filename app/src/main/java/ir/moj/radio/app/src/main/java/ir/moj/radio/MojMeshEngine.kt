package ir.moj.radio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.ConnectivityManager
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

class MojMeshEngine(private val context: Context) {

    companion object {
        const val DISCOVERY_UDP_PORT = 45454
        const val MESH_TCP_PORT = 45455
        const val MAX_FILE_SIZE_BYTES = 150L * 1024L * 1024L // 150 MB
        const val CHUNK_SIZE = 32 * 1024 // 32 KB per chunk for reliable mesh relay
        const val AUDIO_SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val myNodeId: String = getOrCreateNodeId()
    var myDeviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private val _role = MutableStateFlow(NodeRole.NONE)
    val role: StateFlow<NodeRole> = _role.asStateFlow()

    private val _joinCode = MutableStateFlow("")
    val joinCode: StateFlow<String> = _joinCode.asStateFlow()

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

    var isRogerBeepEnabled: Boolean = true
    var onNotificationEvent: ((title: String, body: String) -> Unit)? = null

    private var myAuthToken: String = ""
    private val networkSecret = UUID.randomUUID().toString()

    // کش ضد حلقه (Anti-Loop LRU Cache) برای نگهداری ۲۰۰۰ بسته آخر
    private val seenPacketIds = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(2048, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 2000
            }
        })
    )

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
        val prefs = context.getSharedPreferences("moj_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("node_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 12)
            prefs.edit().putString("node_id", id).apply()
        }
        return id
    }

    /**
     * بررسی واقعی پشتیبانی سخت‌افزار گوشی از هم‌زمانی Wi-Fi و Hotspot
     */
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
        _localIpAddress.value = "192.168.43.1 (پیش‌فرض هات‌اسپات)"
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

    /**
     * شروع شبکه به عنوان گوشی مرکزی (Host)
     */
    fun startHostNetwork() {
        stopNetwork()
        refreshLocalIp()
        val code = MojSecurityUtils.generateJoinCode(8)
        _joinCode.value = code
        _role.value = NodeRole.HOST
        _myApprovalStatus.value = MemberStatus.APPROVED
        myAuthToken = MojSecurityUtils.generateAuthToken(myNodeId, networkSecret)

        val selfNode = MemberNode(
            nodeId = myNodeId,
            deviceName = "$myDeviceName (مدیر)",
            ipAddress = _localIpAddress.value,
            status = MemberStatus.APPROVED,
            authToken = myAuthToken,
            hopCount = 0
        )
        memberMap[myNodeId] = selfNode
        updateMembersState()

        startTcpMeshServer()
        startUdpDiscoveryResponder()
        _statusBanner.value = "شبکه مرکزی موج فعال شد | کد اتصال: $code"
    }

    /**
     * پیوستن به شبکه با کد ۸ کاراکتری و (اختیاری) آدرس IP دستی
     */
    fun joinNetwork(inputCode: String, manualHostIp: String = "") {
        val cleanCode = inputCode.trim().uppercase()
        if (cleanCode.length !in 6..10) {
            _statusBanner.value = "خطا: کد اتصال باید بین ۶ تا ۱۰ کاراکتر باشد."
            return
        }
        stopNetwork()
        refreshLocalIp()
        _joinCode.value = cleanCode
        _role.value = NodeRole.CLIENT
        _myApprovalStatus.value = MemberStatus.PENDING
        _statusBanner.value = "در حال جستجوی شبکه موج با کد $cleanCode..."

        // رله سرور در گوشی عضو هم روشن می‌شود تا بتواند به عنوان گره واسطه برای گوشی سوم عمل کند
        startTcpMeshServer()
        startUdpDiscoveryResponder()

        scope.launch {
            var targetIp: String? = manualHostIp.trim().takeIf { it.isNotEmpty() }

            // ۱. تلاش برای کشف خودکار با UDP Broadcast
            if (targetIp == null) {
                targetIp = discoverHostViaUdp(cleanCode)
            }

            // ۲. اگر برودکست مسدود بود، بررسی Gateway هات‌اسپات و IPهای استاندارد اندروید
            val candidateIps = mutableListOf<String>()
            if (targetIp != null) candidateIps.add(targetIp)
            getGatewayIp()?.let { if (!candidateIps.contains(it)) candidateIps.add(it) }
            listOf("192.168.43.1", "192.168.49.1", "192.168.137.1").forEach {
                if (!candidateIps.contains(it)) candidateIps.add(it)
            }

            var connected = false
            for (ip in candidateIps) {
                if (connectToUpstreamNode(ip, cleanCode)) {
                    connected = true
                    break
                }
            }

            if (!connected) {
                _myApprovalStatus.value = MemberStatus.DISCONNECTED
                _statusBanner.value = "گوشی مرکزی پیدا نشد. از اتصال Wi-Fi به هات‌اسپات مطمئن شوید یا IP را دستی وارد کنید."
            }
        }
    }

    private suspend fun discoverHostViaUdp(code: String): String? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1800

            val reqJson = JSONObject().apply {
                put("cmd", "MOJ_DISCOVER")
                put("code", code)
                put("senderId", myNodeId)
            }.toString().toByteArray()

            val targets = listOf("255.255.255.255", "192.168.43.255", "192.168.49.255")
            for (bcast in targets) {
                try {
                    val pkt = DatagramPacket(reqJson, reqJson.size, InetAddress.getByName(bcast), DISCOVERY_UDP_PORT)
                    socket.send(pkt)
                } catch (_: Exception) {}
            }

            val buf = ByteArray(1024)
            val respPkt = DatagramPacket(buf, buf.size)
            socket.receive(respPkt)
            val respStr = String(respPkt.data, 0, respPkt.length)
            val respObj = JSONObject(respStr)
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
                    val msg = String(pkt.data, 0, pkt.length)
                    val json = JSONObject(msg)
                    if (json.optString("cmd") == "MOJ_DISCOVER") {
                        val reqCode = json.optString("code")
                        // هم گوشی مرکزی و هم گوشی‌های تأییدشده واسطه به درخواست کشف پاسخ می‌دهند
                        if (reqCode == _joinCode.value && _myApprovalStatus.value == MemberStatus.APPROVED) {
                            val reply = JSONObject().apply {
                                put("cmd", "MOJ_HERE")
                                put("code", _joinCode.value)
                                put("nodeId", myNodeId)
                            }.toString().toByteArray()
                            val respPkt = DatagramPacket(reply, reply.size, pkt.address, pkt.port)
                            socket.send(respPkt)
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
                    handleSocketConnection(clientSocket)
                }
            } catch (e: Exception) {
                if (_role.value != NodeRole.NONE) {
                    _statusBanner.value = "خطا در پورت شبکه محلی: ${e.localizedMessage}"
                }
            }
        }
    }

    private suspend fun connectToUpstreamNode(ip: String, code: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, MESH_TCP_PORT), 3000)
            val peer = handleSocketConnection(socket)

            val joinPayload = JSONObject().apply {
                put("joinCode", code)
                put("deviceName", myDeviceName)
                put("ipAddress", _localIpAddress.value)
            }
            val joinPacket = MeshPacket(
                type = "JOIN_REQ",
                senderId = myNodeId,
                senderName = myDeviceName,
                targetId = "HOST",
                payload = joinPayload
            )
            seenPacketIds.add(joinPacket.packetId)
            peer.sendLine(joinPacket.toJsonString())
            _statusBanner.value = "درخواست ورود به مدیر ارسال شد. منتظر تأیید مدیر بمانید..."
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun handleSocketConnection(socket: Socket): PeerConnection {
        val peer = PeerConnection(socket)
        val tempId = "peer_${UUID.randomUUID()}"
        peerConnections[tempId] = peer

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
                peerConnections.remove(tempId)
                peer.remoteNodeId?.let { nodeId ->
                    memberMap[nodeId]?.let { member ->
                        if (member.status == MemberStatus.APPROVED) {
                            member.status = MemberStatus.DISCONNECTED
                            updateMembersState()
                            onNotificationEvent?.invoke("قطع ارتباط عضو", "${member.deviceName} از شبکه قطع شد.")
                            if (_role.value == NodeRole.HOST) {
                                broadcastMemberList()
                            }
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

        // ۲. بررسی درخواست ورود در گوشی مدیر
        if (packet.type == "JOIN_REQ") {
            if (_role.value == NodeRole.HOST) {
                val reqCode = packet.payload.optString("joinCode")
                if (reqCode != _joinCode.value) {
                    val rejectPkt = MeshPacket(
                        type = "JOIN_RES",
                        senderId = myNodeId,
                        senderName = myDeviceName,
                        targetId = packet.senderId,
                        payload = JSONObject().apply {
                            put("status", MemberStatus.REJECTED.name)
                            put("reason", "کد اتصال نامعتبر است.")
                        }
                    )
                    sourcePeer.sendLine(rejectPkt.toJsonString())
                    return
                }
                val existing = memberMap[packet.senderId]
                val member = MemberNode(
                    nodeId = packet.senderId,
                    deviceName = packet.senderName,
                    ipAddress = packet.payload.optString("ipAddress", remoteIp),
                    status = existing?.status ?: MemberStatus.PENDING,
                    authToken = existing?.authToken ?: "",
                    hopCount = packet.hopCount
                )
                memberMap[packet.senderId] = member
                updateMembersState()
                onNotificationEvent?.invoke("درخواست ورود جدید", "${packet.senderName} درخواست پیوستن به شبکه موج را دارد.")
            } else {
                // اگر گوشی واسطه است، درخواست را به سمت مدیر رله کن
                relayPacket(packet, sourcePeer)
            }
            return
        }

        // ۳. بررسی پاسخ تأیید/رد/اخراج
        if (packet.type == "JOIN_RES") {
            if (packet.targetId == myNodeId) {
                val st = MemberStatus.valueOf(packet.payload.optString("status", "REJECTED"))
                _myApprovalStatus.value = st
                if (st == MemberStatus.APPROVED) {
                    myAuthToken = packet.payload.optString("authToken", "")
                    _statusBanner.value = "عضویت شما توسط مدیر تأیید شد. به شبکه موج متصل هستید."
                    onNotificationEvent?.invoke("تأیید عضویت", "ورود شما به شبکه موج تأیید شد.")
                } else if (st == MemberStatus.REJECTED) {
                    myAuthToken = ""
                    _statusBanner.value = "درخواست ورود شما توسط مدیر رد شد."
                } else if (st == MemberStatus.KICKED) {
                    myAuthToken = ""
                    _statusBanner.value = "شما توسط مدیر از شبکه حذف شدید."
                    stopNetwork()
                }
            } else {
                relayPacket(packet, sourcePeer)
            }
            return
        }

        // ۴. اعتبارسنجی امنیتی برای تمام بسته‌های دیگر
        if (_role.value == NodeRole.HOST) {
            val senderNode = memberMap[packet.senderId]
            if (senderNode == null || senderNode.status != MemberStatus.APPROVED || senderNode.authToken != packet.authToken) {
                return // مسدودسازی کامل اعضای غیرمجاز یا حذف‌شده
            }
            senderNode.lastSeen = System.currentTimeMillis()
        } else {
            if (_myApprovalStatus.value != MemberStatus.APPROVED) return
        }

        // ۵. رله بسته به سایر گره‌ها در معماری Mesh (اگر TTL باقی مانده باشد)
        if (packet.targetId == "ALL" || packet.targetId != myNodeId) {
            relayPacket(packet, sourcePeer)
        }

        // اگر بسته مخصوص گره دیگری بود، پردازش محلی نیازی نیست
        if (packet.targetId != "ALL" && packet.targetId != myNodeId && _role.value != NodeRole.HOST) {
            return
        }

        // ۶. پردازش محلی بر اساس نوع بسته
        when (packet.type) {
            "MEMBER_LIST" -> handleMemberListSync(packet.payload)
            "TEXT_MSG" -> handleIncomingText(packet)
            "PTT_FLOOR" -> handlePttFloorControl(packet)
            "PTT_AUDIO" -> handleIncomingAudioChunk(packet)
            "FILE_START" -> handleFileStart(packet)
            "FILE_CHUNK" -> handleFileChunk(packet)
            "FILE_END" -> handleFileEnd(packet)
            "FILE_CANCEL" -> handleFileCancel(packet)
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
        seenPacketIds.add(packet.packetId)
        val raw = packet.toJsonString()
        peerConnections.values.forEach { it.sendLine(raw) }
    }

    /**
     * تأیید عضو جدید توسط مدیر شبکه
     */
    fun approveMember(nodeId: String) {
        if (_role.value != NodeRole.HOST) return
        val member = memberMap[nodeId] ?: return
        val token = MojSecurityUtils.generateAuthToken(nodeId, networkSecret)
        member.status = MemberStatus.APPROVED
        member.authToken = token
        updateMembersState()

        val resPkt = MeshPacket(
            type = "JOIN_RES",
            senderId = myNodeId,
            senderName = myDeviceName,
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

    /**
     * رد درخواست یا حذف عضو توسط مدیر شبکه
     */
    fun rejectOrKickMember(nodeId: String, isKick: Boolean) {
        if (_role.value != NodeRole.HOST) return
        val member = memberMap[nodeId] ?: return
        val newStatus = if (isKick) MemberStatus.KICKED else MemberStatus.REJECTED
        member.status = newStatus
        member.authToken = ""
        updateMembersState()

        val resPkt = MeshPacket(
            type = "JOIN_RES",
            senderId = myNodeId,
            senderName = myDeviceName,
            targetId = nodeId,
            authToken = myAuthToken,
            payload = JSONObject().apply {
                put("status", newStatus.name)
            }
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
                put("deviceName", m.deviceName)
                put("ipAddress", m.ipAddress)
                put("status", m.status.name)
                put("hopCount", m.hopCount)
            })
        }
        val pkt = MeshPacket(
            type = "MEMBER_LIST",
            senderId = myNodeId,
            senderName = myDeviceName,
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
            list.add(
                MemberNode(
                    nodeId = obj.getString("nodeId"),
                    deviceName = obj.getString("deviceName"),
                    ipAddress = obj.optString("ipAddress", ""),
                    status = MemberStatus.valueOf(obj.optString("status", "APPROVED")),
                    hopCount = obj.optInt("hopCount", 1)
                )
            )
        }
        _members.value = list
    }

    private fun updateMembersState() {
        _members.value = memberMap.values.sortedBy { it.deviceName }
    }

    /**
     * ارسال پیام متنی در شبکه Mesh
     */
    fun sendTextMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _myApprovalStatus.value != MemberStatus.APPROVED) return
        val msg = ChatMessage(
            senderId = myNodeId,
            senderName = myDeviceName,
            text = clean,
            isIncoming = false
        )
        _messages.value = _messages.value + msg

        val pkt = MeshPacket(
            type = "TEXT_MSG",
            senderId = myNodeId,
            senderName = myDeviceName,
            authToken = myAuthToken,
            payload = JSONObject().apply {
                put("msgId", msg.id)
                put("text", clean)
                put("timestamp", msg.timestamp)
            }
        )
        scope.launch { broadcastPacket(pkt) }
    }

    private fun handleIncomingText(packet: MeshPacket) {
        val msg = ChatMessage(
            id = packet.payload.optString("msgId", UUID.randomUUID().toString()),
            senderId = packet.senderId,
            senderName = packet.senderName,
            text = packet.payload.optString("text", ""),
            timestamp = packet.payload.optLong("timestamp", System.currentTimeMillis()),
            isIncoming = true
        )
        _messages.value = _messages.value + msg
        onNotificationEvent?.invoke("پیام جدید از ${packet.senderName}", msg.text)
    }

    /**
     * بی‌سیم صوتی زنده (Push-To-Talk) با مدیریت نوبت صحبت
     */
    @SuppressLint("MissingPermission")
    fun startPushToTalk(): Boolean {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) {
            _statusBanner.value = "شما هنوز مجوز ارسال در شبکه را ندارید."
            return false
        }
        val currentSpeaker = _activeSpeaker.value
        if (currentSpeaker != null && currentSpeaker != myDeviceName) {
            _statusBanner.value = "خط مشغول است: $currentSpeaker در حال صحبت است."
            return false
        }

        isPttRecording = true
        _activeSpeaker.value = myDeviceName

        // اعلام گرفتن نوبت میکروفون به کل شبکه
        val floorTakePkt = MeshPacket(
            type = "PTT_FLOOR",
            senderId = myNodeId,
            senderName = myDeviceName,
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
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                recorder.startRecording()
                val frameBuf = ByteArray(1280) // 40ms PCM audio frame (کمترین تأخیر ممکن)
                while (isActive && isPttRecording) {
                    val read = recorder.read(frameBuf, 0, frameBuf.size)
                    if (read > 0) {
                        val b64 = Base64.encodeToString(frameBuf, 0, read, Base64.NO_WRAP)
                        val audioPkt = MeshPacket(
                            type = "PTT_AUDIO",
                            senderId = myNodeId,
                            senderName = myDeviceName,
                            authToken = myAuthToken,
                            payload = JSONObject().apply { put("pcm", b64) }
                        )
                        broadcastPacket(audioPkt)
                    }
                }
            } catch (e: Exception) {
                _statusBanner.value = "خطای دسترسی به میکروفون: ${e.localizedMessage}"
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
            senderName = myDeviceName,
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
            val minBuf = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            audioTrack?.play()
        } catch (_: Exception) {}
    }

    private fun handleIncomingAudioChunk(packet: MeshPacket) {
        val b64 = packet.payload.optString("pcm", "")
        if (b64.isEmpty()) return
        _activeSpeaker.value = packet.senderName
        try {
            val pcmBytes = Base64.decode(b64, Base64.NO_WRAP)
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (_: Exception) {}
    }

    private fun playRogerBeep() {
        if (!isRogerBeepEnabled) return
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
        } catch (_: Exception) {}
    }

    /**
     * ارسال بخش‌بندی‌شده (Chunked) عکس و فایل تا ۱۵۰ مگابایت با بررسی SHA-256 و قابلیت لغو
     */
    fun sendUriFile(uri: Uri, isImage: Boolean) {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) return
        scope.launch {
            try {
                val resolver = context.contentResolver
                var fileName = "moj_file_${System.currentTimeMillis()}"
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
                    _statusBanner.value = "خطا: حجم فایل بیش از حد مجاز (۱۵۰ مگابایت) است."
                    return@launch
                }

                // محاسبه SHA-256 برای تضمین صحت فایل در مقصد
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
                    senderName = myDeviceName,
                    sha256 = sha256Hex,
                    isImage = isImage,
                    isIncoming = false
                )
                _fileTransfers.value = _fileTransfers.value + transferItem

                // ۱. ارسال بسته شروع فایل
                val startPkt = MeshPacket(
                    type = "FILE_START",
                    senderId = myNodeId,
                    senderName = myDeviceName,
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

                // ۲. ارسال بخش‌ها (Chunks) با قابلیت Resume از آفست مشخص
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
                                senderName = myDeviceName,
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
                            senderName = myDeviceName,
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
                        delay(12) // کنترل ازدحام شبکه محلی (Congestion Control)
                    }
                }

                // ۳. اعلام پایان انتقال
                val endPkt = MeshPacket(
                    type = "FILE_END",
                    senderId = myNodeId,
                    senderName = myDeviceName,
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

        // بررسی صحت SHA-256 فایل دریافتی
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
                val notifTitle = if (item.isImage) "دریافت تصویر جدید" else "دریافت فایل جدید"
                onNotificationEvent?.invoke(notifTitle, "${item.fileName} از طرف ${item.senderName} دریافت و ذخیره شد.")
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
