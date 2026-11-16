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
                val hostIp = parts[1]
                val port = parts[2].toIntOrNull() ?: 45455
                val code = parts[3]
                val ssid = parts[4]
                val pass = parts[5]
                Toast.makeText(this, "در حال اتصال به وای‌فای $ssid ...", Toast.LENGTH_SHORT).show()
                wifiHotspotManager.connectToWifi(
                    ssid = ssid,
                    password = pass,
                    onConnected = { detectedGatewayIp ->
                        val targetIp = detectedGatewayIp ?: hostIp
                        joinLocalNetworkWithFallback(targetIp, hostIp, port, code)
                    },
                    onFailed = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        joinLocalNetwork(hostIp, port, code)
                    }
                )
            }
            parts.size == 4 && parts[0] == "MOJ" -> {
                val hostIp = parts[1]
                val port = parts[2].toIntOrNull() ?: 45455
                val code = parts[3]
                val gwIp = wifiHotspotManager.getWifiGatewayIp()
                joinLocalNetworkWithFallback(hostIp, gwIp, port, code)
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
                            onStopPtt = { pttEngine.stopTransmitting
