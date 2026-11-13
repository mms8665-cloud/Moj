package ir.moj.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

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
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val engine = mojService?.meshEngine
                        if (engine == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            MojMainApp(engine)
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
fun MojMainApp(engine: MojMeshEngine) {
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

    // درخواست مجوزهای ضروری زمان اجرا
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

    val tabs = listOf(
        "شبکه" to Icons.Default.WifiTethering,
        "مدیر" to Icons.Default.AdminPanelSettings,
        "اعضا" to Icons.Default.Group,
        "بی‌سیم" to Icons.Default.Mic,
        "پیام‌ها" to Icons.Default.Chat,
        "فایل و عکس" to Icons.Default.AttachFile,
        "تنظیمات" to Icons.Default.Settings
    )

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "بی‌سیم آفلاین موج (MOJ)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Badge(
                        containerColor = when (approvalStatus) {
                            MemberStatus.APPROVED -> Color(0xFF2E7D32)
                            MemberStatus.PENDING -> Color(0xFFF57F17)
                            else -> Color(0xFFC62828)
                        }
                    ) {
                        Text(
                            text = when (approvalStatus) {
                                MemberStatus.APPROVED -> "متصل (${role.name})"
                                MemberStatus.PENDING -> "منتظر تأیید مدیر"
                                else -> "آفلاین"
                            },
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusBanner,
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }
        },
        bottomBar = {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 4.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                tabs.forEachIndexed { index, pair ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(pair.first, fontSize = 12.sp) },
                        icon = { Icon(pair.second, contentDescription = pair.first) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            when (selectedTab) {
                0 -> HomeNetworkScreen(engine, role, joinCode, localIp, staApSupported) {
                    selectedTab = it
                }
                1 -> HostControlScreen(engine, role, joinCode, localIp, members)
                2 -> MembersListScreen(engine, role, members)
                3 -> WalkieTalkiePttScreen(engine, activeSpeaker, approvalStatus)
                4 -> ChatMessagesScreen(engine, messages, approvalStatus)
                5 -> FileAndPhotoScreen(context, engine, fileTransfers, approvalStatus)
                6 -> SettingsScreen(context, engine, staApSupported)
            }
        }
    }
}

/**
 * صفحه ۱: صفحه اصلی با گزینه ایجاد شبکه و پیوستن به شبکه
 */
@Composable
fun HomeNetworkScreen(
    engine: MojMeshEngine,
    role: NodeRole,
    joinCode: String,
    localIp: String,
    staApSupported: Boolean,
    onNavigateTab: (Int) -> Unit
) {
    var inputCode by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("وضعیت سخت‌افزار شبکه و هات‌اسپات", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("آدرس IP فعلی گوشی: $localIp", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (staApSupported)
                            "✅ این گوشی از هم‌زمانی Wi-Fi و Hotspot (رله سخت‌افزاری مستقیم) پشتیبانی می‌کند."
                        else
                            "⚠️ این گوشی از روشن بودن هم‌زمان Wi-Fi و Hotspot پشتیبانی نمی‌کند (محدودیت سخت‌افزاری اندروید). این گوشی می‌تواند هات‌اسپات اصلی (گوشی ۱) یا عضو متصل باشد.",
                        fontSize = 12.sp,
                        color = if (staApSupported) Color(0xFF69F0AE) else Color(0xFFFFD54F)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("۱. ایجاد شبکه مرکزی (گوشی شماره ۱)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ابتدا هات‌اسپات گوشی خود را روشن کنید، سپس دکمه زیر را بزنید تا کد ۸ کاراکتری تولید شود.",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            engine.startHostNetwork()
                            onNavigateTab(1)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.WifiTethering, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ایجاد شبکه جدید موج")
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("۲. پیوستن به شبکه (گوشی‌های عضو)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { if (it.length <= 10) inputCode = it.uppercase() },
                        label = { Text("کد اتصال ۸ کاراکتری (مثلاً K7M9P2X4)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("آدرس IP گوشی مرکزی (اختیاری - مثلاً 192.168.43.1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { engine.joinNetwork(inputCode, manualIp) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ارسال درخواست پیوستن به شبکه")
                    }
                }
            }
        }

        if (role != NodeRole.NONE) {
            item {
                OutlinedButton(
                    onClick = { engine.stopNetwork() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                ) {
                    Text("قطع کامل ارتباط شبکه (کد فعلی: $joinCode)")
                }
            }
        }
    }
}

/**
 * صفحه ۲: صفحه شبکه مرکزی با نمایش کد اتصال و درخواست‌های ورود
 */
@Composable
fun HostControlScreen(
    engine: MojMeshEngine,
    role: NodeRole,
    joinCode: String,
    localIp: String,
    members: List<MemberNode>
) {
    if (role != NodeRole.HOST) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("این بخش مخصوص مدیر شبکه (گوشی ایجادکننده شبکه) است.", textAlign = TextAlign.Center)
        }
        return
    }

    val pendingMembers = members.filter { it.status == MemberStatus.PENDING }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("کد اتصال شبکه موج (۸ کاراکتر)", fontSize = 13.sp, color = Color.LightGray)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = joinCode,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.secondary,
                    letterSpacing = 4.sp
                )
                Spacer(Modifier.height(6.dp))
                Text("آدرس IP مدیر: $localIp | پورت: ${MojMeshEngine.MESH_TCP_PORT}", fontSize = 12.sp)
            }
        }

        Text("درخواست‌های ورود منتظر تأیید (${pendingMembers.size})", fontWeight = FontWeight.Bold)

        if (pendingMembers.isEmpty()) {
            Text("در حال حاضر درخواست ورود جدیدی وجود ندارد.", fontSize = 13.sp, color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pendingMembers) { member ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(member.deviceName, fontWeight = FontWeight.Bold)
                                Text("IP: ${member.ipAddress} | گام: ${member.hopCount}", fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { engine.approveMember(member.nodeId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) {
                                    Text("تأیید")
                                }
                                OutlinedButton(
                                    onClick = { engine.rejectOrKickMember(member.nodeId, isKick = false) }
                                ) {
                                    Text("رد")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * صفحه ۳: صفحه اعضای شبکه و مدیریت آن‌ها
 */
@Composable
fun MembersListScreen(
    engine: MojMeshEngine,
    role: NodeRole,
    members: List<MemberNode>
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("فهرست کل اعضای شبکه (${members.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (members.isEmpty()) {
            Text("هنوز عضوی در شبکه ثبت نشده است.", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members) { m ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.deviceName, fontWeight = FontWeight.Bold)
                                Text(
                                    "وضعیت: ${m.status.name} | IP: ${m.ipAddress} | فاصله (Hop): ${m.hopCount}",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                            }
                            if (role == NodeRole.HOST && m.nodeId != engine.myNodeId && m.status == MemberStatus.APPROVED) {
                                Button(
                                    onClick = { engine.rejectOrKickMember(m.nodeId, isKick = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                                ) {
                                    Text("حذف عضو")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * صفحه ۴: صفحه بی‌سیم با دکمه بزرگ نگه‌داشتنی (Push-To-Talk)
 */
@Composable
fun WalkieTalkiePttScreen(
    engine: MojMeshEngine,
    activeSpeaker: String?,
    approvalStatus: MemberStatus
) {
    var isHolding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceAround
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("وضعیت کانال صوتی بی‌سیم", fontSize = 13.sp, color = Color.LightGray)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = activeSpeaker?.let { "🔊 در حال صحبت: $it" } ?: "کانال آزاد است (آماده مکالمه)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (activeSpeaker != null) Color(0xFFFFD54F) else Color(0xFF69F0AE)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(
                    when {
                        approvalStatus != MemberStatus.APPROVED -> Color.DarkGray
                        isHolding -> Color(0xFFD32F2F)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                .pointerInput(approvalStatus) {
                    detectTapGestures(
                        onPress = {
                            if (engine.startPushToTalk()) {
                                isHolding = true
                                tryAwaitRelease()
                                isHolding = false
                                engine.stopPushToTalk()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "PTT",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isHolding) "در حال ارسال صدا...\n(رها کنید تا قطع شود)" else "نگه دارید و صحبت کنید\n(Push To Talk)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        Text(
            text = "صدا به صورت زنده (16kHz PCM) از طریق گره‌های واسطه به تمام اعضای تأییدشده ارسال می‌شود.",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * صفحه ۵: صفحه پیام‌های متنی
 */
@Composable
fun ChatMessagesScreen(
    engine: MojMeshEngine,
    messages: List<ChatMessage>,
    approvalStatus: MemberStatus
) {
    var text by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isIncoming) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.isIncoming)
                                MaterialTheme.colorScheme.surface
                            else
                                Color(0xFF004D40)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                text = msg.senderName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(msg.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("پیام خود را بنویسید...") },
                modifier = Modifier.weight(1f),
                enabled = approvalStatus == MemberStatus.APPROVED
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    engine.sendTextMessage(text)
                    text = ""
                },
                enabled = approvalStatus == MemberStatus.APPROVED && text.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "ارسال")
            }
        }
    }
}

/**
 * صفحه ۶: صفحه ارسال و دریافت فایل و عکس (گالری، دوربین، حافظه تا ۱۵۰ مگابایت)
 */
@Composable
fun FileAndPhotoScreen(
    context: Context,
    engine: MojMeshEngine,
    transfers: List<FileTransferItem>,
    approvalStatus: MemberStatus
) {
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

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

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            tempCameraUri?.let { engine.sendUriFile(it, isImage = true) }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ارسال عکس و فایل (حداکثر ۱۵۰ مگابایت)", fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { galleryLauncher.launch("image/*") },
                enabled = approvalStatus == MemberStatus.APPROVED,
                modifier = Modifier.weight(1f)
            ) {
                Text("عکس گالری", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val photoFile = File(context.cacheDir, "moj_cam_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile
                    )
                    tempCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                enabled = approvalStatus == MemberStatus.APPROVED,
                modifier = Modifier.weight(1f)
            ) {
                Text("دوربین", fontSize = 12.sp)
            }
            Button(
                onClick = { fileLauncher.launch(arrayOf("*/*")) },
                enabled = approvalStatus == MemberStatus.APPROVED,
                modifier = Modifier.weight(1f)
            ) {
                Text("انتخاب فایل", fontSize = 12.sp)
            }
        }

        Divider(Modifier.padding(vertical = 4.dp))
        Text("وضعیت انتقال فایل‌ها (${transfers.size})", fontWeight = FontWeight.Bold)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transfers) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.fileName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${item.progressPercent}%", fontWeight = FontWeight.Bold)
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
                                TextButton(onClick = { engine.cancelFileTransfer(item.transferId) }) {
                                    Text("لغو", color = Color(0xFFFF5252))
                                }
                            }
                        }
                        if (item.isCompleted && item.localFilePath != null) {
                            Text(
                                text = "مسیر ذخیره: ${item.localFilePath}",
                                fontSize = 10.sp,
                                color = Color(0xFF69F0AE)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * صفحه ۷: صفحه تنظیمات
 */
@Composable
fun SettingsScreen(
    context: Context,
    engine: MojMeshEngine,
    staApSupported: Boolean
) {
    var deviceName by remember { mutableStateOf(engine.myDeviceName) }
    var rogerBeep by remember { mutableStateOf(engine.isRogerBeepEnabled) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات بی‌سیم موج", fontWeight = FontWeight.Bold, fontSize = 16.sp)

        OutlinedTextField(
            value = deviceName,
            onValueChange = {
                deviceName = it
                engine.myDeviceName = it
            },
            label = { Text("نام نمایشی دستگاه شما در شبکه") },
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("پخش صدای بوق پایان مکالمه (Roger Beep)")
                Switch(
                    checked = rogerBeep,
                    onCheckedChange = {
                        rogerBeep = it
                        engine.isRogerBeepEnabled = it
                    }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("پایداری پس‌زمینه در گوشی‌های سامسونگ و شیائومی", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "برای جلوگیری از قطع شدن بی‌سیم هنگام خاموش شدن صفحه، محدودیت باتری را برای برنامه موج غیرفعال کنید.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
                Spacer(Modifier.height(8.dp))
                Button(
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
                    Text("حذف محدودیت بهینه‌سازی باتری")
                }
            }
        }
    }
}
