package ir.moj.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var mojService by mutableStateOf<MojForegroundService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MojForegroundService.LocalBinder
            mojService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mojService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // مدیریت استاندارد فضای امن نمایشگر و دکمه‌های ناوبری اندروید
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val serviceIntent = Intent(this, MojForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFF00BFA5),
                        secondary = Color(0xFF64FFDA),
                        background = Color(0xFF101418),
                        surface = Color(0xFF1C2228)
                    )
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .imePadding(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val engine = mojService?.meshEngine
                        if (engine == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            MojRootContainer(engine)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun MojRootContainer(engine: MojMeshEngine) {
    val nickname by engine.nickname.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // ۱. دریافت اجباری نام مستعار در اولین ورود
    if (nickname.length !in 2..20) {
        MandatoryNicknameScreen(engine)
    } else {
        MojMainNavigator(engine)
    }
}

@Composable
fun MandatoryNicknameScreen(engine: MojMeshEngine) {
    var inputName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.PersonPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "خوش آمدید به بی‌سیم آفلاین موج",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "لطفاً پیش از ورود، یک نام مستعار (بین ۲ تا ۲۰ کاراکتر) انتخاب کنید. این نام در پیام‌ها و بی‌سیم به سایر اعضا نمایش داده می‌شود.",
                    fontSize = 13.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = inputName,
                    onValueChange = {
                        if (it.length <= 20) inputName = it
                        errorText = null
                    },
                    label = { Text("نام مستعار شما (مثلاً مهدی - واحد ۱)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(errorText!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (!engine.saveNickname(inputName)) {
                            errorText = "نام مستعار باید حداقل ۲ و حداکثر ۲۰ کاراکتر باشد."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ثبت نام مستعار و ورود به برنامه")
                }
            }
        }
    }
}

@Composable
fun MojMainNavigator(engine: MojMeshEngine) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val role by engine.role.collectAsState()
    val joinCode by engine.joinCode.collectAsState()
    val approvalStatus by engine.myApprovalStatus.collectAsState()
    val members by engine.members.collectAsState()
    val messages by engine.messages.collectAsState()
    val fileTransfers by engine.fileTransfers.collectAsState()
    val activeSpeaker by engine.activeSpeaker.collectAsState()
    val statusBanner by engine.statusBanner.collectAsState()
    val localIp by engine.localIpAddress.collectAsState()
    val staApSupported by engine.staApConcurrencySupported.collectAsState()
    val isSharingCam by engine.isSharingMyCamera.collectAsState()
    val incomingCamReqs by engine.incomingCameraRequests.collectAsState()

    val pendingMembersCount = members.count { it.status == MemberStatus.PENDING }

    val tabs = listOf(
        "بی‌سیم و ارتباط" to Icons.Default.RecordVoiceOver,
        "شبکه و اعضا" to Icons.Default.Groups,
        "راهنمای استفاده" to Icons.Default.HelpOutline,
        "تنظیمات" to Icons.Default.Settings
    )

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "موج (MOJ)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${engine.myDisplayName} (#${engine.myNodeId.take(4)})",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isSharingCam) {
                            Badge(containerColor = Color(0xFFD32F2F)) {
                                Text("🔴 دوربین زنده فعال", color = Color.White, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                        Badge(
                            containerColor = when (approvalStatus) {
                                MemberStatus.APPROVED -> Color(0xFF2E7D32)
                                MemberStatus.PENDING -> Color(0xFFF57F17)
                                else -> Color(0xFFC62828)
                            }
                        ) {
                            Text(
                                text = when (approvalStatus) {
                                    MemberStatus.APPROVED -> if (role == NodeRole.HOST) "مدیر شبکه ($joinCode)" else "متصل ($joinCode)"
                                    MemberStatus.PENDING -> "منتظر تأیید"
                                    else -> "آفلاین"
                                },
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(text = statusBanner, fontSize = 11.sp, color = Color(0xFFB0BEC5))
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                tabs.forEachIndexed { index, pair ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (index == 1 && pendingMembersCount > 0 && role == NodeRole.HOST) {
                                        Badge { Text("$pendingMembersCount") }
                                    } else if (index == 0 && incomingCamReqs.isNotEmpty()) {
                                        Badge { Text("${incomingCamReqs.size}") }
                                    }
                                }
                            ) {
                                Icon(pair.second, contentDescription = pair.first)
                            }
                        },
                        label = { Text(pair.first, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            when (selectedTab) {
                0 -> UnifiedCommunicationScreen(
                    context = context,
                    engine = engine,
                    role = role,
                    approvalStatus = approvalStatus,
                    members = members,
                    messages = messages,
                    transfers = fileTransfers,
                    activeSpeaker = activeSpeaker,
                    onGoToNetworkTab = { selectedTab = 1 },
                    onGoToGuideTab = { selectedTab = 2 }
                )
                1 -> NetworkAndMembersScreen(
                    engine = engine,
                    role = role,
                    joinCode = joinCode,
                    localIp = localIp,
                    staApSupported = staApSupported,
                    members = members
                )
                2 -> UserGuideScreen()
                3 -> SettingsScreen(
                    context = context,
                    engine = engine,
                    onOpenGuide = { selectedTab = 2 }
                )
            }
        }
    }
}

/**
 * صفحه اصلی یکپارچه ارتباطی:
 * شامل بی‌سیم PTT، پیام متنی، ارسال عکس گالری، دوربین، فایل و دوربین زنده دوطرفه در یک صفحه
 */
@Composable
fun UnifiedCommunicationScreen(
    context: Context,
    engine: MojMeshEngine,
    role: NodeRole,
    approvalStatus: MemberStatus,
    members: List<MemberNode>,
    messages: List<ChatMessage>,
    transfers: List<FileTransferItem>,
    activeSpeaker: String?,
    onGoToNetworkTab: () -> Unit,
    onGoToGuideTab: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    var isHoldingPtt by remember { mutableStateOf(false) }
    var showLiveCameraPanel by remember { mutableStateOf(false) }
    var tempCameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val isSharingMyCam by engine.isSharingMyCamera.collectAsState()
    val useFrontCam by engine.useFrontCamera.collectAsState()
    val incomingCamReqs by engine.incomingCameraRequests.collectAsState()
    val approvedViewers by engine.approvedCameraViewers.collectAsState()
    val watchingIds by engine.watchingCameraIds.collectAsState()
    val remoteFrames by engine.remoteCameraFrames.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { engine.sendUriFile(it, isImage = true) }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { engine.sendUriFile(it, isImage = false) }
    }

    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            tempCameraPhotoUri?.let { engine.sendUriFile(it, isImage = true) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // نوار سریع اتصال یا دسترسی به دوربین زنده و راهنما
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (approvalStatus != MemberStatus.APPROVED) {
                Button(
                    onClick = onGoToNetworkTab,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) {
                    Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ایجاد یا اتصال به شبکه", fontSize = 12.sp)
                }
                if (role == NodeRole.CLIENT) {
                    OutlinedButton(onClick = { engine.retryConnection() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تلاش مجدد", fontSize = 12.sp)
                    }
                }
            } else {
                Button(
                    onClick = { showLiveCameraPanel = !showLiveCameraPanel },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showLiveCameraPanel || isSharingMyCam || watchingIds.isNotEmpty())
                            Color(0xFFD32F2F)
                        else
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showLiveCameraPanel) "بستن پنل دوربین زنده" else "دوربین زنده اعضا (${members.count { it.isSharingCamera }})",
                        fontSize = 12.sp
                    )
                }
            }

            OutlinedButton(onClick = onGoToGuideTab) {
                Icon(Icons.Default.HelpOutline, contentDescription = "راهنما", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("راهنما", fontSize = 12.sp)
            }
        }

        // نمایش درخواست‌های دریافتی مشاهده دوربین (فقط صاحب گوشی اجازه تأیید دارد)
        incomingCamReqs.forEach { req ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📹 ${req.requesterName} اجازه مشاهده دوربین شما را می‌خواهد",
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { engine.respondToCameraRequest(req.requesterId, true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("تأیید", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = { engine.respondToCameraRequest(req.requesterId, false) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("رد", fontSize = 11.sp) }
                    }
                }
            }
        }

        // پنل اشتراک‌گذاری و مشاهده دوربین زنده
        if (showLiveCameraPanel && approvalStatus == MemberStatus.APPROVED) {
            LiveCameraSharingSection(
                engine = engine,
                members = members,
                isSharingMyCam = isSharingMyCam,
                useFrontCam = useFrontCam,
                approvedViewers = approvedViewers,
                watchingIds = watchingIds,
                remoteFrames = remoteFrames
            )
        }

        // بخش میانی: لیست یکپارچه پیام‌های متنی، عکس‌ها و فایل‌ها
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14191F))
        ) {
            if (messages.isEmpty() && transfers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "هنوز پیامی یا فایلی ردوبدل نشده است.\nاز کادر پایین برای پیام، عکس، فایل یا بی‌سیم استفاده کنید.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transfers) { item ->
                        FileTransferBubble(item = item, onCancel = { engine.cancelFileTransfer(item.transferId) })
                    }
                    items(messages) { msg ->
                        ChatMessageBubble(msg = msg)
                    }
                }
            }
        }

        // نوار یکپارچه ارسال پیام، عکس گالری، دوربین و فایل
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { galleryLauncher.launch("image/*") },
                enabled = approvalStatus == MemberStatus.APPROVED
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "گالری عکس", tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(
                onClick = {
                    val photoFile = File(context.cacheDir, "moj_cam_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile
                    )
                    tempCameraPhotoUri = uri
                    takePhotoLauncher.launch(uri)
                },
                enabled = approvalStatus == MemberStatus.APPROVED
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "عکس با دوربین", tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(
                onClick = { fileLauncher.launch(arrayOf("*/*")) },
                enabled = approvalStatus == MemberStatus.APPROVED
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = "ارسال فایل", tint = MaterialTheme.colorScheme.secondary)
            }

            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("پیام متنی...", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = approvalStatus == MemberStatus.APPROVED
            )

            Button(
                onClick = {
                    engine.sendTextMessage(messageText)
                    messageText = ""
                },
                enabled = approvalStatus == MemberStatus.APPROVED && messageText.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "ارسال")
            }
        }

        // دکمه بزرگ و قابل دسترس بی‌سیم (Push-To-Talk) در پایین صفحه اصلی
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        approvalStatus != MemberStatus.APPROVED -> Color(0xFF37474F)
                        isHoldingPtt -> Color(0xFFD32F2F)
                        activeSpeaker != null -> Color(0xFFF57F17)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                .pointerInput(approvalStatus) {
                    detectTapGestures(
                        onPress = {
                            if (engine.startPushToTalk()) {
                                isHoldingPtt = true
                                tryAwaitRelease()
                                isHoldingPtt = false
                                engine.stopPushToTalk()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "PTT",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        approvalStatus != MemberStatus.APPROVED -> "برای استفاده از بی‌سیم، ابتدا به شبکه متصل شوید"
                        isHoldingPtt -> "🔴 در حال ارسال صدای شما... (برای توقف رها کنید)"
                        activeSpeaker != null -> "🔊 در حال دریافت صدا از: $activeSpeaker"
                        else -> "دکمه بی‌سیم (Push To Talk) - نگه دارید و صحبت کنید"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun LiveCameraSharingSection(
    engine: MojMeshEngine,
    members: List<MemberNode>,
    isSharingMyCam: Boolean,
    useFrontCam: Boolean,
    approvedViewers: Set<String>,
    watchingIds: Set<String>,
    remoteFrames: Map<String, Bitmap>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("اشتراک‌گذاری دوطرفه دوربین زنده", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isSharingMyCam) {
                        OutlinedButton(
                            onClick = { engine.switchCameraLens() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(if (useFrontCam) "دوربین عقب" else "دوربین جلو", fontSize = 11.sp)
                        }
                    }
                    Button(
                        onClick = { engine.toggleMyCameraSharing(!isSharingMyCam) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSharingMyCam) Color(0xFFC62828) else Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(if (isSharingMyCam) "توقف اشتراک دوربین من" else "اشتراک‌گذاری دوربین من", fontSize = 11.sp)
                    }
                }
            }

            if (isSharingMyCam) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(110.dp, 85.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.Red, RoundedCornerShape(8.dp))
                    ) {
                        LocalCameraPreviewAndAnalyzer(
                            useFrontCamera = useFrontCam,
                            onFrameCaptured = { jpeg -> engine.broadcastMyCameraFrame(jpeg) }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("دوربین شما فعال است (${approvedViewers.size} بیننده مجاز)", fontSize = 12.sp, color = Color(0xFF69F0AE))
                        approvedViewers.forEach { viewerId ->
                            val viewerName = members.find { it.nodeId == viewerId }?.displayTitle ?: viewerId
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("• $viewerName", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { engine.revokeViewerAccess(viewerId) }) {
                                    Text("قطع دسترسی", fontSize = 10.sp, color = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                }
            }

            // فهرست اعضای در حال اشتراک‌گذاری دوربین
            val remoteSharers = members.filter { it.nodeId != engine.myNodeId && it.status == MemberStatus.APPROVED && it.isSharingCamera }
            if (remoteSharers.isNotEmpty()) {
                Text("اعضای دارای دوربین آماده مشاهده:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(remoteSharers) { m ->
                        val isWatching = watchingIds.contains(m.nodeId)
                        Button(
                            onClick = {
                                if (isWatching) engine.stopWatchingRemoteCamera(m.nodeId)
                                else engine.requestWatchMemberCamera(m.nodeId)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isWatching) Color(0xFFC62828) else Color(0xFF0277BD)
                            )
                        ) {
                            Text(if (isWatching) "توقف مشاهده ${m.nickname}" else "درخواست مشاهده ${m.nickname}", fontSize = 11.sp)
                        }
                    }
                }
            }

            // نمایش تصویر زنده دوربین‌های تأییدشده (پشتیبانی از مشاهده هم‌زمان)
            if (watchingIds.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(watchingIds.toList()) { ownerId ->
                        val ownerName = members.find { it.nodeId == ownerId }?.displayTitle ?: ownerId
                        val bmp = remoteFrames[ownerId]
                        Card(
                            modifier = Modifier.size(160.dp, 125.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Black)
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = ownerName,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("در انتظار دریافت تصویر...", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0x99000000))
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(ownerName, fontSize = 10.sp, color = Color.White)
                                    IconButton(
                                        onClick = { engine.stopWatchingRemoteCamera(ownerId) },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalCameraPreviewAndAnalyzer(
    useFrontCamera: Boolean,
    onFrameCaptured: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(useFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            val jpeg = imageProxyToCompressedJpeg(imageProxy, useFrontCamera)
                            if (jpeg != null) {
                                onFrameCaptured(jpeg)
                            }
                            imageProxy.close()
                        }
                    }

                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun imageProxyToCompressedJpeg(image: ImageProxy, isFrontCamera: Boolean): ByteArray? {
    return try {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val rawOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 50, rawOut)
        val rawBytes = rawOut.toByteArray()

        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) postScale(-1f, 1f)
        }
        val scaled = Bitmap.createScaledBitmap(bmp, 320, 240, true)
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        val finalOut = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 45, finalOut)
        finalOut.toByteArray()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ChatMessageBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isIncoming) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (msg.isIncoming) MaterialTheme.colorScheme.surface else Color(0xFF004D40)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = msg.senderName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(msg.text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun FileTransferBubble(item: FileTransferItem, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${if (item.isImage) "🖼️" else "📁"} ${item.fileName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text("${item.progressPercent}%", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { item.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "فرستنده: ${item.senderName} | " +
                            when {
                                item.isCanceled -> "لغو شده"
                                item.isCompleted -> "تکمیل و تأیید شده (SHA-256)"
                                else -> "در حال انتقال..."
                            },
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
                if (!item.isCompleted && !item.isCanceled) {
                    TextButton(onClick = onCancel) {
                        Text("لغو", color = Color(0xFFFF5252), fontSize = 11.sp)
                    }
                }
            }
            if (item.isCompleted && item.isImage && item.localFilePath != null) {
                val bmp = remember(item.localFilePath) {
                    BitmapFactory.decodeFile(item.localFilePath)
                }
                if (bmp != null) {
                    Spacer(Modifier.height(6.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.fileName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/**
 * صفحه ۲: مدیریت شبکه، ورود با کد دستی و مدیریت اعضا
 */
@Composable
fun NetworkAndMembersScreen(
    engine: MojMeshEngine,
    role: NodeRole,
    joinCode: String,
    localIp: String,
    staApSupported: Boolean,
    members: List<MemberNode>
) {
    val savedManualIp by engine.lastManualIp.collectAsState()
    var inputCode by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf(savedManualIp) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("وضعیت شبکه محلی و هات‌اسپات", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("آدرس IP فعلی گوشی شما: $localIp | پورت: ${MojMeshEngine.MESH_TCP_PORT}", fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (staApSupported)
                            "✅ این گوشی از اتصال هم‌زمان Wi-Fi و ایجاد Hotspot (رله سخت‌افزاری مستقیم) پشتیبانی می‌کند."
                        else
                            "⚠️ این گوشی از روشن بودن هم‌زمان Wi-Fi و Hotspot پشتیبانی نمی‌کند. روش جایگزین: یکی از گوشی‌ها هات‌اسپات را روشن کند و سایر گوشی‌ها به آن متصل شوند؛ برنامه موج پیام‌ها، صدا و تصویر را از طریق گوشی‌های متصل در شبکه رله می‌کند.",
                        fontSize = 11.sp,
                        color = if (staApSupported) Color(0xFF69F0AE) else Color(0xFFFFD54F)
                    )
                }
            }
        }

        if (role == NodeRole.HOST) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF004D40))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("کد اتصال ۸ کاراکتری شبکه شما:", fontSize = 12.sp, color = Color.LightGray)
                        Text(
                            text = joinCode,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.secondary,
                            letterSpacing = 4.sp
                        )
                        Text("آدرس IP مدیر برای ورود دستی: $localIp", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ایجاد یا پیوستن به شبکه بی‌سیم", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Button(
                        onClick = { engine.startHostNetwork() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.WifiTethering, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ایجاد شبکه جدید (به عنوان گوشی مرکزی)")
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { if (it.length <= 10) inputCode = it.uppercase() },
                        label = { Text("کد اتصال (۸ کاراکتر)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("آدرس IP گوشی مرکزی (در صورت مسدود بودن Broadcast - مثلاً 192.168.43.1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { engine.joinNetwork(inputCode, manualIp) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("پیوستن با کد دستی")
                        }
                        if (role == NodeRole.CLIENT) {
                            OutlinedButton(onClick = { engine.retryConnection() }) {
                                Text("تلاش مجدد")
                            }
                        }
                    }

                    if (role != NodeRole.NONE) {
                        OutlinedButton(
                            onClick = { engine.stopNetwork() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                        ) {
                            Text("خروج کامل از شبکه")
                        }
                    }
                }
            }
        }

        item {
            Text("مدیریت اعضای شبکه (${members.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        items(members) { m ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${m.displayTitle} ${if (m.isSharingCamera) "📹" else ""}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "وضعیت: ${m.status.name} | IP: ${m.ipAddress} | گام: ${m.hopCount}",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                    if (role == NodeRole.HOST && m.nodeId != engine.myNodeId) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (m.status == MemberStatus.PENDING) {
                                Button(
                                    onClick = { engine.approveMember(m.nodeId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("تأیید", fontSize = 11.sp) }
                                OutlinedButton(
                                    onClick = { engine.rejectOrKickMember(m.nodeId, isKick = false) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("رد", fontSize = 11.sp) }
                            } else if (m.status == MemberStatus.APPROVED) {
                                Button(
                                    onClick = { engine.rejectOrKickMember(m.nodeId, isKick = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("حذف عضو", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * صفحه ۳: صفحه مستقل «راهنمای استفاده از موج» شامل هر ۱۴ بخش درخواستی
 */
@Composable
fun UserGuideScreen() {
    val guideSections = listOf(
        "۱. معرفی برنامه و قابلیت آفلاین آن" to
                "اپلیکیشن موج (MOJ) یک بی‌سیم گروهی، پیام‌رسان و سامانه اشتراک‌گذاری فایل و دوربین زنده کاملاً آفلاین است. تمام ارتباطات از طریق Wi-Fi و هات‌اسپات گوشی‌ها انجام شده و به اینترنت یا سرور خارجی هیچ نیازی ندارد.",
        "۲. انتخاب و تغییر نام مستعار" to
                "در اولین ورود به برنامه، انتخاب نام مستعار (بین ۲ تا ۲۰ کاراکتر) اجباری است. این نام در حافظه گوشی ذخیره می‌شود و در تمام پیام‌ها و فهرست اعضا به همراه ۴ کاراکتر شناسه یکتا نمایش داده می‌شود. از بخش تنظیمات می‌توانید هر زمان آن را تغییر دهید.",
        "۳. ایجاد شبکه توسط گوشی مرکزی" to
                "در گوشی اول، ابتدا هات‌اسپات (Hotspot) اندروید را روشن کنید. سپس وارد تب «شبکه و اعضا» شوید و دکمه «ایجاد شبکه جدید» را بزنید. یک کد ۸ کاراکتری و آدرس IP مدیر به شما نمایش داده می‌شود.",
        "۴. اتصال گوشی‌های دیگر با کد دستی" to
                "در گوشی‌های دیگر، ابتدا از طریق Wi-Fi به هات‌اسپات گوشی اول متصل شوید. سپس در تب «شبکه و اعضا»، کد ۸ کاراکتری را وارد کرده و دکمه «پیوستن با کد دستی» را بزنید.",
        "۵. تأیید و رد درخواست ورود توسط مدیر" to
                "صرفِ داشتن کد برای ورود کافی نیست. پس از ارسال درخواست، اعلانی برای گوشی مرکزی (مدیر) ارسال می‌شود. مدیر در تب «شبکه و اعضا» می‌تواند درخواست عضو جدید را تأیید یا رد کند و هر زمان بخواهد عضوی را حذف نماید.",
        "۶. نحوه استفاده از دکمه بی‌سیم (Push To Talk)" to
                "در پایین صفحه اصلی ارتباطی، دکمه بزرگ بی‌سیم قرار دارد. انگشت خود را روی آن نگه دارید و صحبت کنید. با رها کردن دکمه، ارسال صدا متوقف شده و بوق پایان مکالمه (Roger Beep) برای اعضا پخش می‌شود.",
        "۷. ارسال و دریافت پیام متنی" to
                "در صفحه اصلی ارتباطی، پیام خود را در کادر پایین بنویسید و دکمه ارسال را بزنید. پیام‌ها با الگوریتم AES-GCM رمزنگاری شده و به تمام اعضای مجاز شبکه می‌رسند.",
        "۸. ارسال عکس از گالری" to
                "روی آیکون گالری (کنار کادر پیام) بزنید و تصویر موردنظر را انتخاب کنید. عکس به‌صورت تکه‌تکه با نمایش درصد پیشرفت ارسال شده و در صفحه چت نمایش داده می‌شود.",
        "۹. گرفتن عکس با دوربین و ارسال آن" to
                "روی آیکون دوربین عکاسی کنار کادر پیام بزنید، عکس بگیرید و تأیید کنید تا بلافاصله برای اعضای شبکه ارسال شود.",
        "۱۰. ارسال و دریافت فایل (تا ۱۵۰ مگابایت)" to
                "روی آیکون سنجاق (Attach) کنار کادر پیام بزنید و هر فایلی تا حجم ۱۵۰ مگابایت را انتخاب کنید. صحت فایل در مقصد با هش SHA-256 بررسی شده و در پوشه MOJ_Downloads ذخیره می‌شود.",
        "۱۱. مشاهده اعضای متصل و دوربین زنده" to
                "در صفحه اصلی با زدن دکمه «دوربین زنده اعضا» می‌توانید دوربین خود را به اشتراک بگذارید یا درخواست مشاهده دوربین سایر اعضا را بفرستید. دوربین هیچ فردی بدون تأیید مستقیم صاحب همان گوشی فعال نمی‌شود.",
        "۱۲. خروج از شبکه" to
                "از طریق تب «شبکه و اعضا» یا صفحه «تنظیمات» می‌توانید روی دکمه «خروج کامل از شبکه» بزنید تا تمام ارتباطات و اشتراک دوربین متوقف شود.",
        "۱۳. محدودیت‌های Wi-Fi و هات‌اسپات در اندروید" to
                "بسیاری از گوشی‌ها به دلیل محدودیت تراشه Wi-Fi نمی‌توانند هم‌زمان به Wi-Fi وصل باشند و Hotspot هم بسازند. برنامه موج این قابلیت را بررسی و در بالای صفحه شبکه اعلام می‌کند.",
        "۱۴. رفع مشکلات رایج اتصال" to
                "اگر با وارد کردن کد ۸ کاراکتری، گوشی مرکزی به دلیل مسدود بودن UDP Broadcast پیدا نشد، کافی است آدرس IP نمایش‌داده‌شده در گوشی مدیر (معمولاً 192.168.43.1) را در کادر IP وارد کرده و دکمه پیوستن یا تلاش مجدد را بزنید."
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "راهنمای جامع استفاده از بی‌سیم موج",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        items(guideSections) { (title, body) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(4.dp))
                    Text(body, fontSize = 12.sp, color = Color.LightGray)
                }
            }
        }
    }
}

/**
 * صفحه ۴: تنظیمات کامل برنامه
 */
@Composable
fun SettingsScreen(
    context: Context,
    engine: MojMeshEngine,
    onOpenGuide: () -> Unit
) {
    val currentNickname by engine.nickname.collectAsState()
    val role by engine.role.collectAsState()
    val statusBanner by engine.statusBanner.collectAsState()

    var nameInput by remember { mutableStateOf(currentNickname) }
    var rogerBeep by remember { mutableStateOf(engine.isRogerBeepEnabled) }
    var volumeGain by remember { mutableFloatStateOf(engine.incomingVolumeGain) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("تنظیمات برنامه موج", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تغییر نام مستعار", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { if (it.length <= 20) nameInput = it },
                        label = { Text("نام مستعار (۲ تا ۲۰ کاراکتر)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            saveFeedback = if (engine.saveNickname(nameInput)) {
                                "نام مستعار با موفقیت ذخیره شد."
                            } else {
                                "خطا: نام باید بین ۲ تا ۲۰ کاراکتر باشد."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره نام مستعار")
                    }
                    if (saveFeedback != null) {
                        Text(saveFeedback!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("بوق پایان مکالمه (Roger Beep)", fontSize = 13.sp)
                        Switch(
                            checked = rogerBeep,
                            onCheckedChange = {
                                rogerBeep = it
                                engine.isRogerBeepEnabled = it
                            }
                        )
                    }

                    HorizontalDivider()

                    Text("تنظیم بلندی صدای دریافتی بی‌سیم: ${(volumeGain * 100).toInt()}%", fontSize = 13.sp)
                    Slider(
                        value = volumeGain,
                        onValueChange = {
                            volumeGain = it
                            engine.incomingVolumeGain = it
                        },
                        valueRange = 0.2f..2.0f
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("وضعیت شبکه و نسخه برنامه", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("نسخه برنامه: ${MojMeshEngine.APP_VERSION} (شناسه دستگاه: #${engine.myNodeId})", fontSize = 12.sp)
                    Text("وضعیت فعلی: $statusBanner", fontSize = 12.sp, color = Color.LightGray)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenGuide, modifier = Modifier.weight(1f)) {
                            Text("مشاهده راهنمای استفاده")
                        }
                        if (role != NodeRole.NONE) {
                            OutlinedButton(
                                onClick = { engine.stopNetwork() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                            ) {
                                Text("خروج از شبکه")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("غیرفعال کردن محدودیت باتری (مخصوص سامسونگ و پس‌زمینه)")
                    }
                }
            }
        }
    }
}
