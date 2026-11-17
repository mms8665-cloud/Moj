package ir.moj.radio

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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

    private val approvedUserIds = ConcurrentHashMap.newKeySet<String>()
    private var deputyFailoverJob: Job? = null

    private var currentSettings by mutableStateOf(
        UserSettings("", "", true, 1f, CameraQuality.MEDIUM, true)
    )
    private var isConnected by mutableStateOf(false)
    private var isHost by mutableStateOf(false)
    private var isRelay by mutableStateOf(false)
    private var isLocalHotspotOn by mutableStateOf(false)
    private var isSharedWifiMode by mutableStateOf(false)
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
        val contents = result?.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult

        val parts = contents.split("#")
        when {
            parts.size == 6 && parts[0] == "MOJ_WIFI" -> {
                val qrHostIp = parts[1]
                val port = parts[2].toIntOrNull() ?: 45455
                val code = parts[3]
                val ssid = parts[4]
                val pass = parts[5]
                Toast.makeText(this, "در حال اتصال به وای‌فای $ssid ...", Toast.LENGTH_SHORT).show()
                wifiHotspotManager.connectToWifi(
                    ssid = ssid,
                    password = pass,
                    onConnected = { detectedGatewayIp ->
                        val primaryIp = detectedGatewayIp ?: qrHostIp
                        joinLocalNetworkWithFallback(primaryIp, qrHostIp, port, code)
                    },
                    onFailed = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        val gwIp = wifiHotspotManager.getWifiGatewayIp()
                        joinLocalNetworkWithFallback(qrHostIp, gwIp, port, code)
                    }
                )
            }
            parts.size == 4 && parts[0] == "MOJ" -> {
                val qrHostIp = parts[1]
                val port = parts[2].toIntOrNull() ?: 45455
                val code = parts[3]
                val gwIp = wifiHotspotManager.getWifiGatewayIp()
                joinLocalNetworkWithFallback(qrHostIp, gwIp, port, code)
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
                            staApSupported = wifiHotspotManager.isStaApConcurrencySupported(),
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
                            isLocalHotspotOn = isLocalHotspotOn,
                            isSharedWifiMode = isSharedWifiMode,
                            isDeputy = isDeputy,
                            deputyUserId = deputyUserId,
                            isReconnecting = isReconnecting,
                            isConnected = isConnected,
                            staApSupported = wifiHotspotManager.isStaApConcurrencySupported(),
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
                            onToggleLocalHotspot = { toggleHostLocalHotspot() },
                            onJoinNetwork = { ip, port, code ->
                                val gwIp = wifiHotspotManager.getWifiGatewayIp()
                                joinLocalNetworkWithFallback(ip, gwIp, port, code)
                            },
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
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) {
            requestRequiredPermissions()
            Toast.makeText(this, "ابتدا مجوز دوربین را تأیید کنید", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("دوربین را روی QR کد گوشی مرکزی یا واسطه بگیرید")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }.onFailure {
            Toast.makeText(this, "خطا در باز کردن اسکنر QR", Toast.LENGTH_SHORT).show()
        }
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

    private fun findRealLocalIp(preferHotspot: Boolean): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val candidates = interfaces.filter { it.isUp && !it.isLoopback }.flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { iface.name.lowercase() to (it.hostAddress ?: "") }
            }
            if (preferHotspot) {
                candidates.firstOrNull { (name, ip) ->
                    (name.contains("ap") || name.contains("swlan") || name.contains("wlan1") || name.contains("wlan2")) && ip.startsWith("192.168.")
                }?.second
                    ?: candidates.firstOrNull { (name, ip) -> name.contains("wlan") && ip.startsWith("192.168.") }?.second
                    ?: candidates.firstOrNull { (_, ip) -> ip.startsWith("192.168.") }?.second
            } else {
                candidates.firstOrNull { (_, ip) -> ip.startsWith("192.168.") }?.second
                    ?: candidates.firstOrNull { (_, ip) -> ip.startsWith("10.") || ip.startsWith("172.") }?.second
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun waitForHotspotIp(): String {
        repeat(8) {
            val ip = findRealLocalIp(preferHotspot = true)
            if (!ip.isNullOrBlank()) return ip
            delay(500)
        }
        return findRealLocalIp(preferHotspot = false) ?: "192.168.43.1"
    }

    // ۱. ایجاد شبکه موج بدون روشن کردن خودکار هات‌اسپات (کارکرد روی وای‌فای مشترک)
    private fun createLocalNetwork() {
        val wifiIp = findRealLocalIp(preferHotspot = false)
        if (wifiIp.isNullOrBlank()) {
            Toast.makeText(
                this,
                "به وای‌فای متصل نیستید! ابتدا به مودم وصل شوید یا پس از ایجاد شبکه، هات‌اسپات محلی را روشن کنید.",
                Toast.LENGTH_LONG
            ).show()
        }
        val effectiveIp = wifiIp ?: "192.168.43.1"
        isSharedWifiMode = wifiHotspotManager.isConnectedToWifi()
        isLocalHotspotOn = wifiHotspotManager.isHotspotActive
        startHostServer(creds = null, myIp = effectiveIp)
    }

    // ۲. دکمه مستقل روشن/خاموش کردن هات‌اسپات محلی بدون قطع سرور موج
    private fun toggleHostLocalHotspot() {
        val code = activeJoinCode
        if (!isHost || code == null) {
            Toast.makeText(this, "ابتدا شبکه موج را ایجاد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        if (isLocalHotspotOn) {
            wifiHotspotManager.stopLocalHotspot()
            isLocalHotspotOn = false
            val wifiIp = findRealLocalIp(preferHotspot = false) ?: "192.168.43.1"
            hotspotInfo = "هات‌اسپات محلی خاموش است (وای‌فای مشترک: $wifiIp)"
            qrBitmap = generateQrBitmap("MOJ#$wifiIp#45455#$code")
            Toast.makeText(this, "هات‌اسپات محلی خاموش شد؛ سرور موج همچنان فعال است", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "در حال روشن کردن هات‌اسپات محلی...", Toast.LENGTH_SHORT).show()
        wifiHotspotManager.startLocalHotspot(
            onStarted = { creds ->
                lifecycleScope.launch {
                    val apIp = waitForHotspotIp()
                    isLocalHotspotOn = true
                    hotspotInfo = "هات‌اسپات روشن: ${creds.ssid} \vert{} رمز: ${creds.password}"
                    qrBitmap = generateQrBitmap("MOJ_WIFI#$apIp#45455#$code#${creds.ssid}#${creds.password}")
                    Toast.makeText(this@MainActivity, "هات‌اسپات محلی روشن شد (بدون قطع اعضای فعلی)", Toast.LENGTH_LONG).show()
                }
            },
            onFailed = { msg ->
                isLocalHotspotOn = false
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun startHostServer(creds: LocalHotspotCredentials?, myIp: String) {
        val code = cryptoManager.generateJoinCode()
        activeJoinCode = code
        activeNetworkName = "موج ($myIp)"
        isHost = true
        isRelay = false
        isDeputy = false
        isConnected = true
        isReconnecting = false
        approvedUserIds.clear()
        approvedUserIds.add(currentSettings.userId)
        memberManager.clearAll()

        if (creds != null) {
            isLocalHotspotOn = true
            hotspotInfo = "هات‌اسپات روشن: ${creds.ssid} \vert{} رمز: ${creds.password}"
            qrBitmap = generateQrBitmap("MOJ_WIFI#$myIp#45455#$code#${creds.ssid}#${creds.password}")
        } else {
            isLocalHotspotOn = false
            hotspotInfo = if (isSharedWifiMode) "متصل به وای‌فای مشترک ($myIp) - هات‌اسپات خاموش" else "سرور موج فعال روی $myIp"
            qrBitmap = generateQrBitmap("MOJ#$myIp#45455#$code")
        }

        meshRouter.setNetworkKey(code)
        meshRouter.startServer(lifecycleScope)
        memberManager.registerHost(currentSettings.userId, currentSettings.nickname, myIp)
        udpDiscovery.startBroadcasting(lifecycleScope, activeNetworkName)
        pttEngine.initializePlayback()
        runCatching { MojForegroundService.start(this, "مدیر شبکه: $myIp (کد: $code)") }
        Toast.makeText(this, "شبکه موج ایجاد شد! آیکن QR بالا را بزنید", Toast.LENGTH_LONG).show()
    }

    // ۳. حالت واسطه واقعی (Relay) با بررسی STA + AP
    private fun toggleRelayMode() {
        val code = activeJoinCode ?: return
        if (isRelay) {
            wifiHotspotManager.stopLocalHotspot()
            isRelay = false
            isLocalHotspotOn = false
            qrBitmap = null
            hotspotInfo = null
            Toast.makeText(this, "حالت واسطه خاموش شد", Toast.LENGTH_SHORT).show()
            return
        }

        if (!wifiHotspotManager.isStaApConcurrencySupported()) {
            Toast.makeText(
                this,
                "سخت‌افزار یا نسخه اندروید این گوشی از وای‌فای و هات‌اسپات هم‌زمان (STA+AP) پشتیبانی نمی‌کند.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(this, "در حال فعال‌سازی گره واسطه (Mesh Relay)...", Toast.LENGTH_SHORT).show()
        wifiHotspotManager.unbindProcessForRelay()
        wifiHotspotManager.startLocalHotspot(
            onStarted = { creds ->
                lifecycleScope.launch {
                    val myApIp = waitForHotspotIp()
                    meshRouter.startRelayServer(lifecycleScope)
                    udpDiscovery.startBroadcasting(lifecycleScope, "موج-واسطه (${currentSettings.nickname})")
                    isRelay = true
                    isLocalHotspotOn = true
                    hotspotInfo = "واسطه فعال: ${creds.ssid} \vert{} رمز: ${creds.password}"
                    qrBitmap = generateQrBitmap("MOJ_WIFI#$myApIp#45455#$code#${creds.ssid}#${creds.password}")
                    Toast.makeText(this@MainActivity, "حالت واسطه فعال شد! گوشی بعدی می‌تواند QR شما را اسکن کند", Toast.LENGTH_LONG).show()
                }
            },
            onFailed = { msg ->
                isRelay = false
                isLocalHotspotOn = false
                Toast.makeText(this, "امکان فعال‌سازی واسطه نیست: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

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

    private fun promoteSelfToHost() {
        val code = activeJoinCode ?: return
        val myIp = findRealLocalIp(preferHotspot = isRelay) ?: "192.168.43.1"
        isHost = true
