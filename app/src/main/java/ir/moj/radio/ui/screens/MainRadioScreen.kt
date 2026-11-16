package ir.moj.radio.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ir.moj.radio.camera.RemoteCameraStream
import ir.moj.radio.data.NetworkMember
import ir.moj.radio.file.TransferState
import ir.moj.radio.network.DiscoveredNetwork
import ir.moj.radio.ui.theme.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class ChatItem(
    val id: String,
    val senderNickname: String,
    val text: String,
    val isMine: Boolean,
    val timestampText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainRadioScreen(
    myNickname: String,
    networkName: String,
    joinCode: String?,
    hotspotInfo: String?,
    qrBitmap: Bitmap?,
    localCameraFrame: Bitmap?,
    isHost: Boolean,
    isRelay: Boolean,
    isDeputy: Boolean,
    deputyUserId: String?,
    isReconnecting: Boolean,
    isConnected: Boolean,
    staApSupported: Boolean,
    isFullAccessAllowed: Boolean,
    isActivated: Boolean,
    remainingTrialHours: Long,
    deviceCode: String,
    activeSpeaker: String?,
    isTransmitting: Boolean,
    isSharingMyCamera: Boolean,
    messages: List<ChatItem>,
    transfers: List<TransferState>,
    members: List<NetworkMember>,
    pendingJoinRequests: List<NetworkMember>,
    remoteCameras: List<RemoteCameraStream>,
    discoveredNetworks: List<DiscoveredNetwork>,
    onActivateApp: (String) -> Boolean,
    onCreateNetwork: () -> Unit,
    onJoinNetwork: (hostIp: String, port: Int, code: String) -> Unit,
    onScanQrCode: () -> Unit,
    onOpenWifiPanel: () -> Unit,
    onToggleRelayMode: () -> Unit,
    onAssignDeputy: (String) -> Unit,
    onStartVoiceRecord: () -> Unit,
    onStopVoiceRecord: () -> Unit,
    onPlayAudio: (File) -> Unit,
    onStopAudio: () -> Unit,
    onApproveMember: (String) -> Unit,
    onRejectMember: (String) -> Unit,
    onKickMember: (String) -> Unit,
    onStartPtt: () -> Unit,
    onStopPtt: () -> Unit,
    onSendText: (String) -> Unit,
    onPickGalleryImage: () -> Unit,
    onTakeCameraPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onToggleMyCamera: () -> Unit,
    onSwitchMyCamera: () -> Unit,
    onStopWatchingCamera: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var messageText by remember { mutableStateOf("") }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showMembersSheet by remember { mutableStateOf(false) }
    var showActivationDialog by remember { mutableStateOf(false) }
    var fullScreenImagePath by remember { mutableStateOf<String?>(null) }
    var fullScreenVideoPath by remember { mutableStateOf<String?>(null) }
    var isRecordingVoice by remember { mutableStateOf(false) }

    fun requireFullAccess(action: () -> Unit) {
        if (isFullAccessAllowed) action() else showActivationDialog = true
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MojNavyDark),
        containerColor = MojNavyDark,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MojNavySurface),
                title = {
                    Column {
                        Text(
                            text = if (isConnected) networkName else "موج (متصل نیست)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                isReconnecting -> "در حال اتصال مجدد خودکار..."
                                isConnected -> "کد: ${joinCode ?: "-"} | اعضا: ${members.size}"
                                else -> "شبکه بسازید یا QR کد را اسکن کنید"
                            },
                            fontSize = 11.sp,
                            color = when {
                                isReconnecting -> Color(0xFFFFB703)
                                isConnected -> MojGreenOnline
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                actions = {
                    if (!isActivated) {
                        IconButton(onClick = { showActivationDialog = true }) {
                            Icon(
                                imageVector = if (isFullAccessAllowed) Icons.Default.Key else Icons.Default.Lock,
                                contentDescription = "فعال‌سازی",
                                tint = if (isFullAccessAllowed) MojCyan else MojRedAlert
                            )
                        }
                    }
                    IconButton(onClick = onOpenWifiPanel) {
                        Icon(Icons.Default.Wifi, contentDescription = "پنل وای‌فای", tint = MojCyan)
                    }
                    if ((isHost || isRelay) && qrBitmap != null) {
                        IconButton(onClick = { showQrDialog = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = "نمایش QR کد", tint = MojCyan)
                        }
                    }
                    IconButton(onClick = { showMembersSheet = true }) {
                        BadgedBox(
                            badge = {
                                if (pendingJoinRequests.isNotEmpty()) {
                                    Badge { Text("${pendingJoinRequests.size}") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Group, contentDescription = "اعضا")
                        }
                    }
                    IconButton(onClick = onOpenGuide) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "راهنما")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "تنظیمات")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (!isActivated) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isFullAccessAllowed) Color(0xFF10304B) else Color(0xFF4A141C))
                        .clickable { showActivationDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFullAccessAllowed) {
                            "نسخه تست رایگان (باقی‌مانده: $remainingTrialHours ساعت) - لمس برای فعال‌سازی"
                        } else {
                            "مهلت تست تمام شد (فقط واکی‌تاکی فعال است) - لمس برای فعال‌سازی"
                        },
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MojNavyCard)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isReconnecting -> Color(0xFFFFB703)
                                    isConnected -> MojGreenOnline
                                    else -> Color.Gray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val roleTag = when {
                        isHost -> "(مدیر)"
                        isDeputy && isRelay -> "(جانشین/واسطه)"
                        isDeputy -> "(جانشین)"
                        isRelay -> "(واسطه)"
                        else -> ""
                    }
                    Text(
                        text = "$myNickname $roleTag",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (!isConnected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = onCreateNetwork,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("ایجاد شبکه", fontSize = 11.sp)
                        }
                        FilledTonalButton(
                            onClick = onScanQrCode,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("اسکن QR", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { showConnectDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("دستی", fontSize = 11.sp)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isHost && staApSupported) {
                            FilledTonalButton(
                                onClick = onToggleRelayMode,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isRelay) MojGreenOnline.copy(alpha = 0.25f) else MojNavySurface
                                )
                            ) {
                                Text(if (isRelay) "واسطه فعال" else "حالت واسطه", fontSize = 11.sp)
                            }
                        }
                        if (isSharingMyCamera) {
                            IconButton(onClick = onSwitchMyCamera, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Cameraswitch, contentDescription = "تغییر دوربین", tint = MojCyan)
                            }
                        }
                    }
                }
            }

            if (localCameraFrame != null || remoteCameras.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (localCameraFrame != null) {
                        item(key = "my_local_camera") {
                            Box(
                                modifier = Modifier
                                    .width(200.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                            ) {
                                Image(
                                    bitmap = localCameraFrame.asImageBitmap(),
                                    contentDescription = "دوربین شما",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = "دوربین شما (زنده)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                IconButton(
                                    onClick = onToggleMyCamera,
                                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "توقف", tint = Color.White)
                                }
                            }
                        }
                    }

                    items(remoteCameras, key = { it.ownerId }) { stream ->
                        Box(
                            modifier = Modifier
                                .width(200.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                        ) {
                            Image(
                                bitmap = stream.latestFrame.asImageBitmap(),
                                contentDescription = "دوربین ${stream.ownerNickname}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Text(
                                text = "زنده: ${stream.ownerNickname}",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            IconButton(
                                onClick = { onStopWatchingCamera(stream.ownerId) },
                                modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "بستن", tint = Color.White)
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(transfers, key = { it.transferId }) { transfer ->
                    val lower = remember(transfer.fileName) { transfer.fileName.lowercase() }
                    val isImageFile = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
                    val isVideoFile = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov")
                    val isAudioFile = lower.endsWith(".3gp") || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg")

                    val previewBitmap = remember(transfer.localFilePath, transfer.isCompleted) {
                        if (isImageFile && transfer.localFilePath != null && (transfer.isCompleted || transfer.isOutgoing)) {
                            runCatching { BitmapFactory.decodeFile(transfer.localFilePath) }.getOrNull()
                        } else null
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MojNavyCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${if (transfer.isOutgoing) "ارسال" else "دریافت"}: ${transfer.fileName}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (transfer.isCompleted && transfer.localFilePath != null) {
                                    IconButton(
                                        onClick = {
                                            val srcFile = File(transfer.localFilePath)
                                            if (srcFile.exists()) {
                                                runCatching {
                                                    val savedName = "MOJ_${System.currentTimeMillis()}_${transfer.fileName}"
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                        val values = ContentValues().apply {
                                                            put(MediaStore.MediaColumns.DISPLAY_NAME, savedName)
                                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MojRadio")
                                                        }
                                                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                                        if (uri != null) {
                                                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                                                FileInputStream(srcFile).use { it.copyTo(out) }
                                                            }
                                                        }
                                                    } else {
                                                        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                                        val dest = File(dir, savedName)
                                                        FileInputStream(srcFile).use { input ->
                                                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                                                        }
                                                    }
                                                    Toast.makeText(context, "در پوشه Download ذخیره شد", Toast.LENGTH_SHORT).show()
                                                }.onFailure {
                                                    Toast.makeText(context, "خطا در ذخیره فایل", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = "دانلود", tint = MojCyan)
                                    }
                                }
                                if (!transfer.isCompleted && !transfer.isCancelled && !transfer.isFailed) {
                                    TextButton(onClick = { onCancelTransfer(transfer.transferId) }) {
                                        Text("لغو", color = MojRedAlert, fontSize = 12.sp)
                                    }
                                }
                            }

                            if (previewBitmap != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = transfer.fileName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { fullScreenImagePath = transfer.localFilePath }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            } else if (isVideoFile && transfer.localFilePath != null && (transfer.isCompleted || transfer.isOutgoing)) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = { fullScreenVideoPath = transfer.localFilePath },
                                    colors = ButtonDefaults.buttonColors(containerColor = MojElectricBlue)
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("پخش ویدئو داخل برنامه")
                                }
                            } else if (isAudioFile && transfer.localFilePath != null && (transfer.isCompleted || transfer.isOutgoing)) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onPlayAudio(File(transfer.localFilePath)) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MojElectricBlue)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("پخش صوت")
                                    }
                                    OutlinedButton(onClick = onStopAudio) {
                                        Icon(Icons.Default.Stop, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("توقف")
                                    }
                                }
                            }

                            LinearProgressIndicator(
                                progress = { transfer.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            val statusLabel = when {
                                transfer.isCompleted -> "تکمیل (نمایش در اپ - برای ذخیره دکمه دانلود را بزنید)"
                                transfer.isCancelled -> "لغو شد"
                                transfer.isFailed -> "خطا در انتقال فایل"
                                else -> "${transfer.progressPercent}٪"
                            }
                            Text(statusLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isMine) Arrangement.Start else Arrangement.End
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.isMine) Color(0xFF0E3A5D) else MojNavyCard
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = msg.senderNickname,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MojCyan
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = msg.text, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = msg.timestampText,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MojNavySurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (activeSpeaker != null) {
                    Text(
                        text = "در حال صحبت: $activeSpeaker",
                        color = MojCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isTransmitting) MojRedAlert else MojElectricBlue)
                        .pointerInput(isConnected) {
                            detectTapGestures(
                                onPress = {
                                    if (isConnected) {
                                        onStartPtt()
                                        tryAwaitRelease()
                                        onStopPtt()
                                    }
                                }
                            )
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Mic, contentDescription = "بی‌سیم", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTransmitting) "در حال ارسال صدا... (رها کنید)" else "نگه دارید و صحبت کنید (PTT)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { requireFullAccess(onPickGalleryImage) }, enabled = isConnected) {
                        Icon(Icons.Default.Photo, contentDescription = "گالری", tint = if (isFullAccessAllowed) MojCyan else Color.Gray)
                    }
                    IconButton(onClick = { requireFullAccess(onTakeCameraPhoto) }, enabled = isConnected) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "عکس با دوربین", tint = if (isFullAccessAllowed) MojCyan else Color.Gray)
                    }
                    IconButton(onClick = { requireFullAccess(onPickFile) }, enabled = isConnected) {
                        Icon(Icons.Default.AttachFile, contentDescription = "ارسال فایل", tint = if (isFullAccessAllowed) MojCyan else Color.Gray)
                    }
                    IconButton(onClick = { requireFullAccess(onToggleMyCamera) }, enabled = isConnected) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = "دوربین زنده",
                            tint = when {
                                !isFullAccessAllowed -> Color.Gray
                                isSharingMyCamera -> MojRedAlert
                                else -> MojCyan
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !isFullAccessAllowed -> Color.Gray
                                    isRecordingVoice -> MojRedAlert
                                    else -> MojCyan
                                }
                            )
                            .pointerInput(isConnected, isFullAccessAllowed) {
                                detectTapGestures(
                                    onPress = {
                                        if (isConnected) {
                                            if (!isFullAccessAllowed) {
                                                showActivationDialog = true
                                            } else {
                                                isRecordingVoice = true
                                                onStartVoiceRecord()
                                                tryAwaitRelease()
                                                isRecordingVoice = false
                                                onStopVoiceRecord()
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.KeyboardVoice, contentDescription = "ویس", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        enabled = isFullAccessAllowed,
                        placeholder = {
                            Text(
                                text = if (isFullAccessAllowed) "پیام متنی..." else "برای پیام متنی اپ را فعال کنید",
                                fontSize = 13.sp
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            requireFullAccess {
                                if (messageText.isNotBlank()) {
                                    onSendText(messageText.trim())
                                    messageText = ""
                                }
                            }
                        },
                        enabled = isConnected && (!isFullAccessAllowed || messageText.isNotBlank())
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال", tint = if (isFullAccessAllowed) MojCyan else Color.Gray)
                    }
                }
            }
        }
    }

    if (fullScreenVideoPath != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.fromFile(File(fullScreenVideoPath!!)))
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = { fullScreenVideoPath = null },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "بستن ویدئو", tint = Color.White)
            }
        }
    }

    if (fullScreenImagePath != null) {
        var zoomScale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        val fullBmp = remember(fullScreenImagePath) {
            runCatching { BitmapFactory.decodeFile(fullScreenImagePath) }.getOrNull()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                        if (zoomScale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (fullBmp != null) {
                Image(
                    bitmap = fullBmp.asImageBitmap(),
                    contentDescription = "تصویر تمام صفحه",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        val srcFile = File(fullScreenImagePath!!)
                        if (srcFile.exists()) {
                            runCatching {
                                val savedName = "MOJ_${System.currentTimeMillis()}.jpg"
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, savedName)
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MojRadio")
                                    }
                                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    if (uri != null) {
                                        context.contentResolver.openOutputStream(uri)?.use { out ->
                                            FileInputStream(srcFile).use { it.copyTo(out) }
                                        }
                                    }
                                } else {
                                    val dir = File(context.getExternalFilesDir(null), "Pictures").apply { mkdirs() }
                                    val dest = File(dir, savedName)
                                    FileInputStream(srcFile).use { input ->
                                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                                    }
                                }
                                Toast.makeText(context, "در گالری ذخیره شد", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, "خطا در ذخیره تصویر", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Download, contentDescription = "ذخیره در گالری", tint = Color.White)
                }

                IconButton(
                    onClick = {
                        fullScreenImagePath = null
                        zoomScale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "بستن", tint = Color.White)
                }
            }
        }
    }

    if (showActivationDialog) {
        var activationInput by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showActivationDialog = false },
            title = { Text("فعال‌سازی دائمی موج") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "کد دستگاه زیر را برای پشتیبانی بفرستید و کد فعال‌سازی اختصاصی همین گوشی را وارد کنید:",
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MojNavyCard)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = deviceCode,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MojCyan
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(deviceCode))
                            Toast.makeText(context, "کد دستگاه کپی شد", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("کپی")
                        }
                    }
                    OutlinedTextField(
                        value = activationInput,
                        onValueChange = {
                            activationInput = it.uppercase()
                            errorMsg = null
                        },
                        label = { Text("کد فعال‌سازی (۱۰ کاراکتر)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg != null) {
                        Text(errorMsg!!, color = MojRedAlert, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (onActivateApp(activationInput)) {
                        Toast.makeText(context, "برنامه با موفقیت برای این گوشی فعال شد!", Toast.LENGTH_LONG).show()
                        showActivationDialog = false
                    } else {
                        errorMsg = "کد فعال‌سازی برای این گوشی نامعتبر است!"
                    }
                }) {
                    Text("فعال‌سازی")
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivationDialog = false }) { Text("بستن") }
            }
        )
    }

    if (showQrDialog && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(if (isRelay) "QR کد گوشی واسطه (ادامه شبکه)" else "QR کد اتصال خودکار به شبکه") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(230.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("شبکه: $networkName", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("کد اتصال: ${joinCode ?: "-"}", fontSize = 13.sp, color = MojCyan)
                    if (!hotspotInfo.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(hotspotInfo, fontSize = 11.sp, color = MojGreenOnline)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showQrDialog = false }) { Text("بستن") }
            }
        )
    }

    if (showConnectDialog) {
        var codeInput by remember { mutableStateOf("") }
        var ipInput by remember { mutableStateOf(discoveredNetworks.firstOrNull()?.hostIp ?: "192.168.43.1") }
        var portInput by remember { mutableStateOf("45455") }

        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text("اتصال به شبکه موج با کد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ابتدا به وای‌فای/هات‌اسپات گوشی مرکزی وصل شوید، سپس کد اتصال را وارد کنید:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (discoveredNetworks.isNotEmpty()) {
                        discoveredNetworks.forEach { net ->
                            TextButton(onClick = {
                                ipInput = net.hostIp
                            }) {
                                Text("یافت شد: ${net.networkName} (${net.hostIp})")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { if (it.length <= 10) codeInput = it.uppercase() },
                        label = { Text("کد اتصال (۸ کاراکتر)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("آدرس IP گوشی مرکزی (پیش‌فرض 192.168.43.1)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (codeInput.isNotBlank()) {
                        onJoinNetwork(ipInput.trim().ifBlank { "192.168.43.1" }, portInput.toIntOrNull() ?: 45455, codeInput.trim())
                        showConnectDialog = false
                    } else {
                        Toast.makeText(context, "لطفاً کد اتصال را وارد کنید", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("اتصال") }
            },
            dismissButton = {
                TextButton(onClick = { showConnectDialog = false }) { Text("انصراف") }
            }
        )
    }

    if (showMembersSheet) {
        ModalBottomSheet(onDismissRequest = { showMembersSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("اعضای شبکه (${members.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = if (staApSupported) "پشتیبانی هم‌زمان Wi-Fi + Hotspot (واسطه): فعال" else "این گوشی هم‌زمانی Wi-Fi و Hotspot را پشتیبانی نمی‌کند",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isHost && pendingJoinRequests.isNotEmpty()) {
                    Text("درخواست‌های در انتظار تأیید:", color = MojCyan, fontSize = 13.sp)
                    pendingJoinRequests.forEach { req ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(req.nickname)
                            Row {
                                TextButton(onClick = { onApproveMember(req.userId) }) { Text("تأیید", color = MojGreenOnline) }
                                TextButton(onClick = { onRejectMember(req.userId) }) { Text("رد", color = MojRedAlert) }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                members.forEach { member ->
                    val isThisDeputy = deputyUserId == member.userId
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            val badge = when {
                                member.isHost -> "(مدیر)"
                                isThisDeputy -> "(جانشین)"
                                else -> ""
                            }
                            Text("${member.nickname} $badge", fontWeight = FontWeight.Medium)
                            Text(if (member.isOnline) "آنلاین" else "آفلاین", fontSize = 11.sp, color = MojGreenOnline)
                        }
                        if (isHost && !member.isHost) {
                            Row {
                                if (!isThisDeputy) {
                                    TextButton(onClick = { onAssignDeputy(member.userId) }) {
                                        Text("انتخاب جانشین", color = MojCyan, fontSize = 12.sp)
                                    }
                                }
                                TextButton(onClick = { onKickMember(member.userId) }) {
                                    Text("حذف", color = MojRedAlert, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
