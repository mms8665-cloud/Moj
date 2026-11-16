package ir.moj.radio

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ir.moj.radio.audio.PttAudioEngine
import ir.moj.radio.audio.VoiceRecorderPlayer
import ir.moj.radio.camera.LocalCameraEngine
import ir.moj.radio.data.*
import ir.moj.radio.file.ChunkFileTransfer
import ir.moj.radio.network.LocalHotspotCredentials
import ir.moj.radio.network.MemberManager
import ir.moj.radio.network.MeshRouter
import ir.moj.radio.network.UdpDiscovery
import ir.moj.radio.network.WifiHotspotManager
import ir.moj.radio.security.CryptoManager
import ir.moj.radio.security.LicenseManager
import ir.moj.radio.service.MojForegroundService
import ir.moj.radio.ui.screens.*
import ir.moj.radio.ui.theme.MojTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    private lateinit var userPrefs: UserPrefs
    private lateinit var licenseManager: LicenseManager
    private val cryptoManager = CryptoManager()
    private val memberManager = MemberManager()
    private lateinit var udpDiscovery: UdpDiscovery
    private lateinit var wifiHotspotManager: WifiHotspotManager
    private lateinit var meshRouter: MeshRouter
    private lateinit var pttEngine: PttAudioEngine
    private lateinit var voiceRecorderPlayer: VoiceRecorderPlayer
    private lateinit var fileTransfer: ChunkFileTransfer
    private lateinit var cameraEngine: LocalCameraEngine

    // لیست اعضای تأییدشده برای بازگشت خودکار بدون نیاز به مجوز مجدد
    private val approvedUserIds = ConcurrentHashMap.newKeySet<String>()
    private var deputyFailoverJob: Job? = null

    private var currentSettings by mutableStateOf(
        UserSettings("", "", true, 1f, CameraQuality.MEDIUM, true)
    )
    private var isConnected by mutableStateOf(false)
    private var isHost by mutableStateOf(false)
    private var isRelay by mutableStateOf(false)
    private var isDeputy by mutableStateOf(false)
    private var deputyUserId by mutableStateOf<String?>(null)
    private var isReconnecting by mutableStateOf(false)

    private var isFullAccessAllowed by mutableStateOf(true)
    private var isActivated by mutableStateOf(false)
    private var remainingTrialHours by mutableStateOf(48L)
    private var activeNetworkName by mutableStateOf("شبکه محلی موج")
    private var activeJoinCode by mutableStateOf<String?>(null)
    private var hotspotInfo by mutableStateOf<String?>(null)
    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private val chatMessages = mutableStateListOf<ChatItem>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val parts = contents.split("#")
        when {
            parts.size == 6 && parts[0] == "MOJ_WIFI" -> {
                val hostIp = parts[1]
                val port = parts[2].toIntOrNull() ?: 45455
                val code = parts[3]
                val ssid = parts[4]
                val pass = parts[5]
                Toast.makeText(this, "در حال اتصال خودکار به وای‌فای $ssid ...", Toast.LENGTH_SHORT).show()
                wifiHotspotManager.connectToWifi(
                    ssid = ssid,
                    password = pass,
                    onConnected = {
                        lifecycleScope.launch {
                            delay(800)
                            joinLocalNetwork(hostIp, port, code)
                        }
                    },
                    onFailed = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                )
            }
            parts.size == 4 && parts[0] == "MOJ" -> {
                val hostIp = parts[1]
                val port = parts[2].toIntOrNull() ?: 45455
                val code = parts[3]
                joinLocalNetwork(hostIp, port, code)
            }
            else -> {
                Toast.makeText(this, "QR کد نامعتبر است!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { copyUriAndSend(it, "photo_${System.currentTimeMillis()}.jpg") } }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { copyUriAndSend(it, "file_${System.currentTimeMillis()}.bin") } }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            fileTransfer.sendFile(lifecycleScope, file)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        userPrefs = UserPrefs(this)
        licenseManager = LicenseManager(this)
        refreshLicenseState()

        udpDiscovery = UdpDiscovery(this)
        wifiHotspotManager = WifiHotspotManager(this)
        voiceRecorderPlayer = VoiceRecorderPlayer(this)

        requestRequiredPermissions()
        initEngines()

        lifecycleScope.launch {
            userPrefs.ensureUserId()
            userPrefs.settingsFlow.collect { settings ->
                currentSettings = settings
                pttEngine.rogerBeepEnabled = settings.rogerBeepEnabled
                pttEngine.outputVolume = settings.outputVolume
                cameraEngine.currentQuality = settings.cameraQuality
            }
        }

        udpDiscovery.startListening(lifecycleScope)

        setContent {
            MojTheme {
                var currentScreen by remember { mutableStateOf("main") }
                val discovered by udpDiscovery.discoveredNetworks.collectAsState()
                val members by memberManager.members.collectAsState()
                val pendingJoins by memberManager.pendingRequests.collectAsState()
                val activeSpeaker by pttEngine.activeSpeakerName.collectAsState()
                val isTransmitting by pttEngine.isTransmitting.collectAsState()
                val transfersMap by fileTransfer.transfers.collectAsState()
                val isSharingCam by cameraEngine.isSharingMyCamera.collectAsState()
                val localCamFrame by cameraEngine.localPreviewFrame.collectAsState()
                val remoteCams by cameraEngine.remoteStreams.collectAsState()

                if (currentSettings.nickname.isBlank()) {
                    WelcomeScreen(
                        onSaveNickname = { nick ->
                            lifecycleScope.launch { userPrefs.saveNickname(nick) }
                        }
                    )
                } else {
                    when (currentScreen) {
                        "settings" -> SettingsScreen(
                            settings = currentSettings,
                            isConnected = isConnected,
                            staApSupported = udpDiscovery.isStaApConcurrencySupported(),
                            onSaveNickname = { nick -> lifecycleScope.launch { userPrefs.saveNickname(nick) } },
                            onVolumeChange = { v -> lifecycleScope.launch { userPrefs.setOutputVolume(v) } },
                            onRogerBeepToggle = { e -> lifecycleScope.launch { userPrefs.setRogerBeep(e) } },
                            onCameraQualityChange = { q -> lifecycleScope.launch { userPrefs.setCameraQuality(q) } },
                            onNotificationsToggle = { e -> lifecycleScope.launch { userPrefs.setNotificationsEnabled(e) } },
                            onOpenGuide = { currentScreen = "guide" },
                            onLeaveNetwork = {
                                disconnectNetwork()
                                currentScreen = "main"
                            },
                            onBack = { currentScreen = "main" }
                        )

                        "guide" -> GuideScreen(onBack = { currentScreen = "main" })

                        else -> MainRadioScreen(
                            myNickname = currentSettings.nickname,
                            networkName = activeNetworkName,
                            joinCode = activeJoinCode,
                            hotspotInfo = hotspotInfo,
                            qrBitmap = qrBitmap,
                            localCameraFrame = localCamFrame,
                            isHost = isHost,
                            isRelay = isRelay,
                            isDeputy = isDeputy,
                            deputyUserId = deputyUserId,
                            isReconnecting = isReconnecting,
                            isConnected = isConnected,
                            staApSupported = udpDiscovery.isStaApConcurrencySupported(),
                            isFullAccessAllowed = isFullAccessAllowed,
                            isActivated = isActivated,
                            remainingTrialHours = remainingTrialHours,
                            deviceCode = licenseManager.getDeviceCode(),
                            activeSpeaker = activeSpeaker,
                            isTransmitting = isTransmitting,
                            isSharingMyCamera = isSharingCam,
                            messages = chatMessages,
                            transfers = transfersMap.values.toList(),
                            members = members,
                            pendingJoinRequests = pendingJoins,
                            remoteCameras = remoteCams.values.toList(),
                            discoveredNetworks = discovered,
                            onActivateApp = { code ->
                                val ok = licenseManager.activate(code)
                                if (ok) refreshLicenseState()
                                ok
                            },
                            onCreateNetwork = { createLocalNetwork() },
                            onJoinNetwork = { ip, port, code -> joinLocalNetwork(ip, port, code) },
                            onScanQrCode = { launchQrScanner() },
                            onOpenWifiPanel = { wifiHotspotManager.openWifiPanel() },
                            onToggleRelayMode = { toggleRelayMode() },
                            onAssignDeputy = { userId -> assignDeputy(userId) },
                            onStartVoiceRecord = { voiceRecorderPlayer.startRecording() },
                            onStopVoiceRecord = {
                                val voiceFile = voiceRecorderPlayer.stopRecording()
                                if (voiceFile != null && voiceFile.exists()) {
                                    fileTransfer.sendFile(lifecycleScope, voiceFile)
                                }
                            },
                            onPlayAudio = { file -> voiceRecorderPlayer.playVoice(file) {} },
                            onStopAudio = { voiceRecorderPlayer.stopPlayback() },
                            onApproveMember = { id -> approveMember(id) },
                            onRejectMember = { id -> rejectMember(id) },
                            onKickMember = { id -> kickMember(id) },
                            onStartPtt = { pttEngine.startTransmitting(lifecycleScope, currentSettings.nickname) },
                            onStopPtt = { pttEngine.stopTransmitting() },
                            onSendText = { text -> sendTextMessage(text) },
                            onPickGalleryImage = { galleryLauncher.launch("image/*") },
                            onTakeCameraPhoto = { takePictureLauncher.launch(null) },
                            onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
                            onCancelTransfer = { id -> fileTransfer.cancelTransfer(id) },
                            onToggleMyCamera = {
                                if (isSharingCam) cameraEngine.stopSharing()
                                else cameraEngine.startSharing(this@MainActivity, useFrontCamera = false)
                            },
                            onSwitchMyCamera = { cameraEngine.switchCamera(this@MainActivity) },
                            onStopWatchingCamera = { ownerId -> cameraEngine.stopWatchingRemote(ownerId) },
                            onOpenSettings = { currentScreen = "settings" },
                            onOpenGuide = { currentScreen = "guide" }
                        )
                    }
                }
            }
        }
    }

    private fun refreshLicenseState() {
        isActivated = licenseManager.isActivated()
        isFullAccessAllowed = licenseManager.isFullAccessAllowed()
        remainingTrialHours = licenseManager.remainingTrialHours()
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("دوربین را روی QR کد گوشی مرکزی یا واسطه بگیرید")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    private fun generateQrBitmap(content: String): Bitmap? {
        return runCatching {
            BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 600, 600)
        }.getOrNull()
    }

    private fun initEngines() {
        meshRouter = MeshRouter(cryptoManager, memberManager) { packet, peerIp ->
            handleIncomingPacket(packet, peerIp)
        }

        // مدیریت قطع موقت و اتصال مجدد خودکار + انتقال مدیریت به جانشین
        meshRouter.onUpstreamDisconnected = {
            if (!isHost && isConnected) {
                isReconnecting = true
                Toast.makeText(this, "ارتباط قطع شد؛ تلاش برای اتصال مجدد خودکار...", Toast.LENGTH_SHORT).show()
                if (isDeputy) {
                    deputyFailoverJob?.cancel()
                    deputyFailoverJob = lifecycleScope.launch {
                        delay(6000L)
                        if (isReconnecting && isDeputy && !isHost) {
                            promoteSelfToHost()
                        }
                    }
                }
            }
        }

        meshRouter.onUpstreamReconnected = {
            deputyFailoverJob?.cancel()
            isReconnecting = false
            sendPacket(PacketType.JOIN_REQUEST, currentSettings.nickname.toByteArray())
            Toast.makeText(this, "اتصال مجدد به شبکه برقرار شد!", Toast.LENGTH_SHORT).show()
        }

        pttEngine = PttAudioEngine(
            onAudioFrameCaptured = { pcm ->
                sendPacket(PacketType.VOICE_PTT_FRAME, pcm)
            },
            onTransmitEnded = {
                sendPacket(PacketType.VOICE_PTT_END, ByteArray(0))
            }
        )

        fileTransfer = ChunkFileTransfer(this) { chunkPayload ->
            sendPacket(PacketType.FILE_CHUNK, chunkPayload)
        }

        cameraEngine = LocalCameraEngine(
            context = this,
            onEncodedFrameReady = { jpeg ->
                sendPacket(PacketType.CAMERA_LIVE_FRAME, jpeg, targetId = null)
            },
            onSharingStateChanged = { sharing ->
                memberManager.updateCameraSharing(currentSettings.userId, sharing)
                val type = if (sharing) PacketType.CAMERA_VIEW_RESPONSE else PacketType.CAMERA_STOPPED
                sendPacket(type, if (sharing) "SHARING".toByteArray() else ByteArray(0))
            }
        )
    }

    private fun getLocalIpAddress(preferHotspot: Boolean = true): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val candidates = interfaces.filter { it.isUp && !it.isLoopback }.flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { iface.name.lowercase() to (it.hostAddress ?: "") }
            }
            if (preferHotspot) {
                candidates.firstOrNull { (name, ip) ->
                    (name.contains("ap") || name.contains("swlan")) && ip.startsWith("192.168.")
                }?.second
                    ?: candidates.firstOrNull { (name, ip) -> name.contains("wlan") && ip.startsWith("192.168.") }?.second
                    ?: candidates.firstOrNull { (_, ip) -> ip.startsWith("192.168.") }?.second
                    ?: "192.168.43.1"
            } else {
                candidates.firstOrNull { (_, ip) -> ip.startsWith("192.168.") }?.second
                    ?: candidates.firstOrNull { (_, ip) -> ip.startsWith("10.") || ip.startsWith("172.") }?.second
                    ?: "192.168.43.1"
            }
        } catch (_: Exception) {
            "192.168.43.1"
        }
    }

    private fun createLocalNetwork() {
        Toast.makeText(this, "در حال روشن کردن هات‌اسپات محلی...", Toast.LENGTH_SHORT).show()
        wifiHotspotManager.startLocalHotspot(
            onStarted = { creds ->
                lifecycleScope.launch {
                    delay(1200)
                    startHostServer(creds)
                }
            },
            onFailed = {
                startHostServer(null)
            }
        )
    }

    private fun startHostServer(creds: LocalHotspotCredentials?) {
        val code = cryptoManager.generateJoinCode()
        val myIp = getLocalIpAddress(preferHotspot = creds != null)
        activeJoinCode = code
        activeNetworkName = "موج ($myIp)"
        isHost = true
        isRelay = false
        isDeputy = false
        isConnected = true
        isReconnecting = false
        approvedUserIds.clear()
        approvedUserIds.add(currentSettings.userId)

        if (creds != null) {
            hotspotInfo = "هات‌اسپات محلی روشن است: ${creds.ssid}"
            qrBitmap = generateQrBitmap("MOJ_WIFI#$myIp#45455#$code#${creds.ssid}#${creds.password}")
        } else {
            hotspotInfo = "متصل به هات‌اسپات/وای‌فای فعلی گوشی"
            qrBitmap = generateQrBitmap("MOJ#$myIp#45455#$code")
        }

        meshRouter.setNetworkKey(code)
        meshRouter.startServer(lifecycleScope)
        memberManager.registerHost(currentSettings.userId, currentSettings.nickname, myIp)
        udpDiscovery.startBroadcasting(lifecycleScope, activeNetworkName)
        pttEngine.initializePlayback()
        runCatching { MojForegroundService.start(this, "مدیر شبکه: $myIp (کد: $code)") }
        Toast.makeText(this, "شبکه آماده است! آیکن QR بالا را بزنید", Toast.LENGTH_LONG).show()
    }

    // قابلیت ۲: روشن/خاموش کردن حالت گوشی واسطه (Repeater)
    private fun toggleRelayMode() {
        val code = activeJoinCode ?: return
        if (isRelay) {
            wifiHotspotManager.stopLocalHotspot()
            isRelay = false
            qrBitmap = null
            hotspotInfo = null
            Toast.makeText(this, "حالت واسطه خاموش شد", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "در حال روشن کردن هات‌اسپات واسطه...", Toast.LENGTH_SHORT).show()
        wifiHotspotManager.startLocalHotspot(
            onStarted = { creds ->
                lifecycleScope.launch {
                    delay(1200)
                    val myApIp = getLocalIpAddress(preferHotspot = true)
                    meshRouter.startRelayServer(lifecycleScope)
                    isRelay = true
                    hotspotInfo = "واسطه فعال: ${creds.ssid}"
                    qrBitmap = generateQrBitmap("MOJ_WIFI#$myApIp#45455#$code#${creds.ssid}#${creds.password}")
                    Toast.makeText(this@MainActivity, "حالت واسطه فعال شد! بقیه می‌توانند QR شما را اسکن کنند", Toast.LENGTH_LONG).show()
                }
            },
            onFailed = { msg ->
                Toast.makeText(this, "امکان فعال‌سازی واسطه روی این گوشی نیست: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    // قابلیت ۳: انتخاب جانشین توسط مدیر شبکه
    private fun assignDeputy(userId: String) {
        deputyUserId = userId
        sendPacket(PacketType.MEMBER_LIST_SYNC, "DEPUTY_SET#$userId".toByteArray(Charsets.UTF_8))
        syncApprovedList()
        Toast.makeText(this, "جانشین شبکه انتخاب شد", Toast.LENGTH_SHORT).show()
    }

    private fun syncApprovedList() {
        val joined = approvedUserIds.joinToString(",")
        sendPacket(PacketType.MEMBER_LIST_SYNC, "APPROVED_SYNC#$joined".toByteArray(Charsets.UTF_8))
    }

    // ارتقای خودکار جانشین به مدیر جدید در صورت خروج یا قطع شدن مدیر اصلی
    private fun promoteSelfToHost() {
        val code = activeJoinCode ?: return
        val myIp = getLocalIpAddress(preferHotspot = isRelay)
        isHost = true
        isDeputy = false
        isReconnecting = false
        activeNetworkName = "موج - جانشین ($myIp)"

        meshRouter.startRelayServer(lifecycleScope)
        memberManager.registerHost(currentSettings.userId, currentSettings.nickname, myIp)
        udpDiscovery.startBroadcasting(lifecycleScope, activeNetworkName)
        if (qrBitmap == null) {
            qrBitmap = generateQrBitmap("MOJ#$myIp#45455#$code")
        }
        sendPacket(PacketType.MEMBER_LIST_SYNC, "HOST_TAKEOVER#$myIp#45455".toByteArray(Charsets.UTF_8))
        runCatching { MojForegroundService.start(this, "مدیر جدید (جانشین): $myIp") }
        Toast.makeText(this, "مدیر اصلی قطع شد؛ شما اکنون مدیر شبکه هستید!", Toast.LENGTH_LONG).show()
    }

    private fun joinLocalNetwork(hostIp: String, port: Int, code: String) {
        activeJoinCode = code
        isHost = false
        meshRouter.setNetworkKey(code)
        Toast.makeText(this, "در حال اتصال به $hostIp ...", Toast.LENGTH_SHORT).show()
        meshRouter.connectToPeer(lifecycleScope, hostIp, port) { ok ->
            if (ok) {
                isReconnecting = false
                sendPacket(PacketType.JOIN_REQUEST, currentSettings.nickname.toByteArray())
                Toast.makeText(this, "درخواست ارسال شد...", Toast.LENGTH_SHORT).show()
                runCatching { MojForegroundService.start(this, "متصل به شبکه موج") }
            } else {
                Toast.makeText(this, "اتصال برقرار نشد! اتصال وای‌فای را بررسی کنید", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun approveMember(userId: String) {
        approvedUserIds.add(userId)
        memberManager.approveMember(userId)
        sendPacket(PacketType.JOIN_APPROVED, userId.toByteArray(), targetId = userId)
        syncApprovedList()
    }

    private fun rejectMember(userId: String) {
        approvedUserIds.remove(userId)
        memberManager.rejectMember(userId)
        sendPacket(PacketType.JOIN_REJECTED, userId.toByteArray(), targetId = userId)
    }

    private fun kickMember(userId: String) {
        approvedUserIds.remove(userId)
        memberManager.kickMember(userId)
        sendPacket(PacketType.MEMBER_KICKED, userId.toByteArray(), targetId = userId)
        syncApprovedList()
    }

    private fun sendTextMessage(text: String) {
        sendPacket(PacketType.TEXT_MESSAGE, text.toByteArray(Charsets.UTF_8))
        addChatItem(currentSettings.nickname, text, isMine = true)
    }

    private fun sendPacket(type: PacketType, payload: ByteArray, targetId: String? = null) {
        val packet = MeshPacket(
            type = type,
            senderId = currentSettings.userId,
            senderNickname = currentSettings.nickname,
            targetId = targetId,
            payload = payload
        )
        lifecycleScope.launch(Dispatchers.IO) {
            meshRouter.broadcastPacket(packet)
        }
    }

    private fun handleIncomingPacket(packet: MeshPacket, peerIp: String) {
        if (packet.targetId != null && packet.targetId != currentSettings.userId) return

        when (packet.type) {
            PacketType.JOIN_REQUEST -> {
                if (isHost) {
                    // قابلیت ۱: اگر قبلاً تأیید شده، بدون نیاز به مجوز مجدد وصل شود
                    if (approvedUserIds.contains(packet.senderId)) {
                        memberManager.addJoinRequest(packet.senderId, packet.senderNickname, peerIp)
                        approveMember(packet.senderId)
                    } else {
                        memberManager.addJoinRequest(packet.senderId, packet.senderNickname, peerIp)
                        notifyIfEnabled("درخواست ورود جدید", "${packet.senderNickname} می‌خواهد به شبکه بپیوندد")
                    }
                }
            }
            PacketType.JOIN_APPROVED -> {
                runOnUiThread {
                    isConnected = true
                    isReconnecting = false
                    pttEngine.initializePlayback()
                    memberManager.registerHost(packet.senderId, packet.senderNickname, peerIp)
                    memberManager.addJoinRequest(currentSettings.userId, currentSettings.nickname, getLocalIpAddress(false))
                    memberManager.approveMember(currentSettings.userId)
                    Toast.makeText(this, "اتصال به شبکه برقرار شد!", Toast.LENGTH_SHORT).show()
                }
            }
            PacketType.JOIN_REJECTED, PacketType.MEMBER_KICKED -> {
                runOnUiThread { disconnectNetwork() }
            }
            PacketType.TEXT_MESSAGE -> {
                val text = String(packet.payload, Charsets.UTF_8)
                runOnUiThread { addChatItem(packet.senderNickname, text, isMine = false) }
                notifyIfEnabled("پیام از ${packet.senderNickname}", text)
            }
            PacketType.VOICE_PTT_FRAME -> pttEngine.onRemoteAudioReceived(packet.senderNickname, packet.payload)
            PacketType.VOICE_PTT_END -> pttEngine.onRemoteTransmitEnded()
            PacketType.FILE_CHUNK -> fileTransfer.onChunkReceived(packet.payload)
            PacketType.CAMERA_VIEW_RESPONSE -> memberManager.updateCameraSharing(packet.senderId, true)
            PacketType.CAMERA_LIVE_FRAME -> {
                if (packet.senderId != currentSettings.userId) {
                    cameraEngine.onRemoteFrameReceived(packet.senderId, packet.senderNickname, packet.payload)
                }
            }
            PacketType.CAMERA_STOPPED -> {
                memberManager.updateCameraSharing(packet.senderId, false)
                cameraEngine.stopWatchingRemote(packet.senderId)
            }
            PacketType.MEMBER_LIST_SYNC -> {
                val msg = String(packet.payload, Charsets.UTF_8)
                val parts = msg.split("#")
                when (parts.firstOrNull()) {
                    "DEPUTY_SET" -> {
                        val depId = parts.getOrNull(1)
                        runOnUiThread {
                            deputyUserId = depId
                            isDeputy = (depId == currentSettings.userId)
                            if (isDeputy) {
                                Toast.makeText(this, "شما به عنوان جانشین شبکه انتخاب شدید", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    "APPROVED_SYNC" -> {
                        val ids = parts.getOrNull(1)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                        approvedUserIds.addAll(ids)
                    }
                    "HOST_TAKEOVER" -> {
                        val newIp = parts.getOrNull(1) ?: return
                        val newPort = parts.getOrNull(2)?.toIntOrNull() ?: 45455
                        val code = activeJoinCode ?: return
                        if (!isHost) {
                            runOnUiThread {
                                Toast.makeText(this, "جانشین مدیریت شبکه را بر عهده گرفت؛ اتصال به $newIp", Toast.LENGTH_SHORT).show()
                                joinLocalNetwork(newIp, newPort, code)
                            }
                        }
                    }
                }
            }
            PacketType.CAMERA_VIEW_REQUEST -> Unit
        }
    }

    private fun getFileNameFromUri(uri: Uri, fallback: String): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIdx)
                if (!name.isNullOrBlank()) return name
            }
        }
        return fallback
    }

    private fun copyUriAndSend(uri: Uri, fallbackName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val realName = getFileNameFromUri(uri, fallbackName)
            val outFile = File(cacheDir, realName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            fileTransfer.sendFile(lifecycleScope, outFile)
        }
    }

    private fun addChatItem(sender: String, text: String, isMine: Boolean) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        chatMessages.add(ChatItem(UUID.randomUUID().toString(), sender, text, isMine, time))
    }

    private fun notifyIfEnabled(title: String, msg: String) {
        if (currentSettings.notificationsEnabled) {
            MojForegroundService.notifyEvent(this, title, msg)
        }
    }

    private fun disconnectNetwork() {
        deputyFailoverJob?.cancel()
        isConnected = false
        isHost = false
        isRelay = false
        isDeputy = false
        deputyUserId = null
        isReconnecting = false
        activeJoinCode = null
        hotspotInfo = null
        qrBitmap = null
        approvedUserIds.clear()
        udpDiscovery.stopBroadcasting()
        meshRouter.stopAll()
        cameraEngine.stopSharing()
        cameraEngine.stopWatchingAll()
        wifiHotspotManager.release()
        MojForegroundService.stop(this)
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        deputyFailoverJob?.cancel()
        pttEngine.release()
        voiceRecorderPlayer.stopPlayback()
        cameraEngine.release()
        udpDiscovery.stopBroadcasting()
        udpDiscovery.stopListening()
        meshRouter.stopAll()
        wifiHotspotManager.release()
    }
}
