package ir.moj.radio

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import ir.moj.radio.audio.PttAudioEngine
import ir.moj.radio.camera.LocalCameraEngine
import ir.moj.radio.data.*
import ir.moj.radio.file.ChunkFileTransfer
import ir.moj.radio.network.MemberManager
import ir.moj.radio.network.MeshRouter
import ir.moj.radio.network.UdpDiscovery
import ir.moj.radio.security.CryptoManager
import ir.moj.radio.service.MojForegroundService
import ir.moj.radio.ui.screens.*
import ir.moj.radio.ui.theme.MojTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var userPrefs: UserPrefs
    private val cryptoManager = CryptoManager()
    private val memberManager = MemberManager()
    private lateinit var udpDiscovery: UdpDiscovery
    private lateinit var meshRouter: MeshRouter
    private lateinit var pttEngine: PttAudioEngine
    private lateinit var fileTransfer: ChunkFileTransfer
    private lateinit var cameraEngine: LocalCameraEngine

    private var currentSettings by mutableStateOf(
        UserSettings("", "", true, 1f, CameraQuality.MEDIUM, true)
    )
    private var isConnected by mutableStateOf(false)
    private var isHost by mutableStateOf(false)
    private var activeNetworkName by mutableStateOf("شبکه محلی موج")
    private var activeJoinCode by mutableStateOf<String?>(null)
    private val chatMessages = mutableStateListOf<ChatItem>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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
            FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            fileTransfer.sendFile(lifecycleScope, file)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        userPrefs = UserPrefs(this)
        udpDiscovery = UdpDiscovery(this)

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
                val camRequests by cameraEngine.pendingViewRequests.collectAsState()
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
                            isHost = isHost,
                            isConnected = isConnected,
                            staApSupported = udpDiscovery.isStaApConcurrencySupported(),
                            activeSpeaker = activeSpeaker,
                            isTransmitting = isTransmitting,
                            isSharingMyCamera = isSharingCam,
                            messages = chatMessages,
                            transfers = transfersMap.values.toList(),
                            members = members,
                            pendingJoinRequests = pendingJoins,
                            pendingCameraRequests = camRequests,
                            remoteCameras = remoteCams.values.toList(),
                            discoveredNetworks = discovered,
                            onCreateNetwork = { createLocalNetwork() },
                            onJoinNetwork = { ip, port, code -> joinLocalNetwork(ip, port, code) },
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
                            onRequestWatchCamera = { ownerId -> requestWatchCamera(ownerId) },
                            onApproveCameraViewer = { reqId -> cameraEngine.approveViewer(reqId) },
                            onRejectCameraViewer = { reqId -> cameraEngine.rejectViewer(reqId) },
                            onStopWatchingCamera = { ownerId -> cameraEngine.stopWatchingRemote(ownerId) },
                            onOpenSettings = { currentScreen = "settings" },
                            onOpenGuide = { currentScreen = "guide" }
                        )
                    }
                }
            }
        }
    }

    private fun initEngines() {
        meshRouter = MeshRouter(cryptoManager, memberManager) { packet, peerIp ->
            handleIncomingPacket(packet, peerIp)
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
            onEncodedFrameReady = { targetIds, jpeg ->
                targetIds.forEach { targetId ->
                    sendPacket(PacketType.CAMERA_LIVE_FRAME, jpeg, targetId = targetId)
                }
            },
            onSharingStateChanged = { sharing ->
                memberManager.updateCameraSharing(currentSettings.userId, sharing)
                val type = if (sharing) PacketType.CAMERA_VIEW_RESPONSE else PacketType.CAMERA_STOPPED
                sendPacket(type, if (sharing) "SHARING".toByteArray() else ByteArray(0))
            }
        )
    }

    private fun createLocalNetwork() {
        val code = cryptoManager.generateJoinCode()
        activeJoinCode = code
        activeNetworkName = "موج - ${currentSettings.nickname}"
        isHost = true
        isConnected = true

        meshRouter.setNetworkKey(code)
        meshRouter.startServer(lifecycleScope)
        memberManager.registerHost(currentSettings.userId, currentSettings.nickname, "127.0.0.1")
        udpDiscovery.startBroadcasting(lifecycleScope, activeNetworkName)
        pttEngine.initializePlayback()
        MojForegroundService.start(this, "مدیر شبکه: $activeNetworkName (کد: $code)")
    }

    private fun joinLocalNetwork(hostIp: String, port: Int, code: String) {
        activeJoinCode = code
        isHost = false
        meshRouter.setNetworkKey(code)
        meshRouter.connectToPeer(lifecycleScope, hostIp, port) { ok ->
            if (ok) {
                sendPacket(PacketType.JOIN_REQUEST, currentSettings.nickname.toByteArray())
                MojForegroundService.start(this, "در انتظار تأیید مدیر شبکه...")
            }
        }
    }

    private fun approveMember(userId: String) {
        memberManager.approveMember(userId)
        sendPacket(PacketType.JOIN_APPROVED, userId.toByteArray(), targetId = userId)
    }

    private fun rejectMember(userId: String) {
        memberManager.rejectMember(userId)
        sendPacket(PacketType.JOIN_REJECTED, userId.toByteArray(), targetId = userId)
    }

    private fun kickMember(userId: String) {
        memberManager.kickMember(userId)
        sendPacket(PacketType.MEMBER_KICKED, userId.toByteArray(), targetId = userId)
    }

    private fun sendTextMessage(text: String) {
        sendPacket(PacketType.TEXT_MESSAGE, text.toByteArray(Charsets.UTF_8))
        addChatItem(currentSettings.nickname, text, isMine = true)
    }

    private fun requestWatchCamera(ownerId: String) {
        sendPacket(PacketType.CAMERA_VIEW_REQUEST, currentSettings.nickname.toByteArray(), targetId = ownerId)
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
                    memberManager.addJoinRequest(packet.senderId, packet.senderNickname, peerIp)
                    notifyIfEnabled("درخواست ورود جدید", "${packet.senderNickname} می‌خواهد به شبکه بپیوندد")
                }
            }
            PacketType.JOIN_APPROVED -> {
                runOnUiThread {
                    isConnected = true
                    pttEngine.initializePlayback()
                    memberManager.approveMember(currentSettings.userId)
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
            PacketType.CAMERA_VIEW_REQUEST -> {
                cameraEngine.onIncomingViewRequest(packet.senderId, packet.senderNickname)
                notifyIfEnabled("درخواست دوربین زنده", "${packet.senderNickname} درخواست مشاهده دوربین شما را دارد")
            }
            PacketType.CAMERA_VIEW_RESPONSE -> memberManager.updateCameraSharing(packet.senderId, true)
            PacketType.CAMERA_LIVE_FRAME -> cameraEngine.onRemoteFrameReceived(packet.senderId, packet.senderNickname, packet.payload)
            PacketType.CAMERA_STOPPED -> {
                memberManager.updateCameraSharing(packet.senderId, false)
                cameraEngine.stopWatchingRemote(packet.senderId)
            }
            PacketType.MEMBER_LIST_SYNC -> Unit
        }
    }

    private fun copyUriAndSend(uri: Uri, fallbackName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val outFile = File(cacheDir, fallbackName)
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
        isConnected = false
        isHost = false
        activeJoinCode = null
        udpDiscovery.stopBroadcasting()
        meshRouter.stopAll()
        cameraEngine.stopSharing()
        cameraEngine.stopWatchingAll()
        MojForegroundService.stop(this)
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    override onDestroy() {
        super.onDestroy()
        pttEngine.release()
        cameraEngine.release()
        udpDiscovery.stopBroadcasting()
        udpDiscovery.stopListening()
        meshRouter.stopAll()
    }
}
