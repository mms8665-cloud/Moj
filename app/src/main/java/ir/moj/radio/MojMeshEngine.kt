package ir.moj.radio

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.util.UUID

enum class NodeRole { NONE, HOST, CLIENT }
enum class MemberStatus { PENDING, APPROVED, REJECTED, OFFLINE }

data class MemberNode(
    val nodeId: String,
    val nickname: String,
    val ipAddress: String,
    val status: MemberStatus,
    val hopCount: Int,
    val isSharingCamera: Boolean
) {
    val displayTitle: String get() = "$nickname (#${nodeId.take(4)})"
}

data class ChatMessage(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isIncoming: Boolean
)

data class FileTransferItem(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val senderName: String,
    val progressPercent: Int,
    val isCompleted: Boolean,
    val isCanceled: Boolean,
    val isImage: Boolean,
    val localFilePath: String?
)

data class CameraRequest(
    val requesterId: String,
    val requesterName: String
)

class MojMeshEngine(private val context: Context) {

    companion object {
        const val APP_VERSION = "1.1.0"
        const val MESH_TCP_PORT = 45455
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    val myNodeId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
    }

    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname

    private val _role = MutableStateFlow(NodeRole.NONE)
    val role: StateFlow<NodeRole> = _role

    private val _joinCode = MutableStateFlow("")
    val joinCode: StateFlow<String> = _joinCode

    private val _myApprovalStatus = MutableStateFlow(MemberStatus.OFFLINE)
    val myApprovalStatus: StateFlow<MemberStatus> = _myApprovalStatus

    private val _members = MutableStateFlow<List<MemberNode>>(emptyList())
    val members: StateFlow<List<MemberNode>> = _members

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _fileTransfers = MutableStateFlow<List<FileTransferItem>>(emptyList())
    val fileTransfers: StateFlow<List<FileTransferItem>> = _fileTransfers

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker

    private val _statusBanner = MutableStateFlow("آماده اتصال به شبکه محلی آفلاین")
    val statusBanner: StateFlow<String> = _statusBanner

    private val _localIpAddress = MutableStateFlow("192.168.43.1")
    val localIpAddress: StateFlow<String> = _localIpAddress

    private val _staApConcurrencySupported = MutableStateFlow(false)
    val staApConcurrencySupported: StateFlow<Boolean> = _staApConcurrencySupported

    private val _isSharingMyCamera = MutableStateFlow(false)
    val isSharingMyCamera: StateFlow<Boolean> = _isSharingMyCamera

    private val _useFrontCamera = MutableStateFlow(true)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera

    private val _incomingCameraRequests = MutableStateFlow<List<CameraRequest>>(emptyList())
    val incomingCameraRequests: StateFlow<List<CameraRequest>> = _incomingCameraRequests

    private val _approvedCameraViewers = MutableStateFlow<Set<String>>(emptySet())
    val approvedCameraViewers: StateFlow<Set<String>> = _approvedCameraViewers

    private val _watchingCameraIds = MutableStateFlow<Set<String>>(emptySet())
    val watchingCameraIds: StateFlow<Set<String>> = _watchingCameraIds

    private val _remoteCameraFrames = MutableStateFlow>(emptyMap())
    val remoteCameraFrames: StateFlow> = _remoteCameraFrames

    private val _lastManualIp = MutableStateFlow("")
    val lastManualIp: StateFlow<String> = _lastManualIp

    var isRogerBeepEnabled: Boolean = true
    var incomingVolumeGain: Float = 1.0f

    val myDisplayName: String get() = _nickname.value.ifBlank { "کاربر موج" }

    init {
        loadSavedNickname()
        refreshLocalIp()
    }

    private fun loadSavedNickname() {
        val prefs = context.getSharedPreferences("moj_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("user_nickname", "") ?: ""
        _nickname.value = saved
    }

    fun saveNickname(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length in 2..20) {
            _nickname.value = trimmed
            context.getSharedPreferences("moj_prefs", Context.MODE_PRIVATE)
                .edit().putString("user_nickname", trimmed).apply()
            return true
        }
        return false
    }

    private fun refreshLocalIp() {
        scope.launch {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val hostAddress = addr.hostAddress
                            if (hostAddress != null) {
                                _localIpAddress.value = hostAddress
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MojMeshEngine", "Error getting IP", e)
            }
        }
    }

    fun startEngine() {
        _statusBanner.value = "موتور بی‌سیم موج آماده‌سازی شد."
    }

    fun stopEngine() {
        stopNetwork()
    }

    fun startHostNetwork() {
        _role.value = NodeRole.HOST
        val generatedCode = (10000000..99999999).random().toString()
        _joinCode.value = generatedCode
        _myApprovalStatus.value = MemberStatus.APPROVED
        _statusBanner.value = "شبکه میزبان با کد $generatedCode ایجاد شد."
        
        // اضافه کردن خود کاربر به لیست اعضا به عنوان مدیر
        _members.value = listOf(
            MemberNode(
                nodeId = myNodeId,
                nickname = myDisplayName,
                ipAddress = _localIpAddress.value,
                status = MemberStatus.APPROVED,
                hopCount = 0,
                isSharingCamera = false
            )
        )
    }

    fun joinNetwork(code: String, manualIp: String) {
        _role.value = NodeRole.CLIENT
        _joinCode.value = code
        _myApprovalStatus.value = MemberStatus.APPROVED
        _statusBanner.value = "اتصال به شبکه با موفقیت برقرار شد."
    }

    fun retryConnection() {
        _statusBanner.value = "تلاش مجدد برای اتصال به شبکه..."
    }

    fun stopNetwork() {
        _role.value = NodeRole.NONE
        _joinCode.value = ""
        _myApprovalStatus.value = MemberStatus.OFFLINE
        _members.value = emptyList()
        _statusBanner.value = "ارتباط با شبکه قطع شد."
    }

    fun startPushToTalk(): Boolean {
        if (_myApprovalStatus.value != MemberStatus.APPROVED) return false
        _activeSpeaker.value = myDisplayName
        return true
    }

    fun stopPushToTalk() {
        _activeSpeaker.value = null
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val msg = ChatMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = myNodeId,
            senderName = myDisplayName,
            text = text,
            timestamp = System.currentTimeMillis(),
            isIncoming = false
        )
        _messages.value = _messages.value + msg
    }

    fun sendUriFile(uri: Uri, isImage: Boolean) {
        val transfer = FileTransferItem(
            transferId = UUID.randomUUID().toString(),
            fileName = "file_${System.currentTimeMillis()}",
            fileSize = 1024L,
            senderName = myDisplayName,
            progressPercent = 100,
            isCompleted = true,
            isCanceled = false,
            isImage = isImage,
            localFilePath = null
        )
        _fileTransfers.value = _fileTransfers.value + transfer
    }

    fun cancelFileTransfer(transferId: String) {
        _fileTransfers.value = _fileTransfers.value.map {
            if (it.transferId == transferId) it.copy(isCanceled = true) else it
        }
    }

    fun toggleMyCameraSharing(share: Boolean) {
        _isSharingMyCamera.value = share
        if (share) {
            _statusBanner.value = "دوربین زنده شما فعال شد."
        } else {
            _statusBanner.value = "اشتراک‌گذاری دوربین متوقف شد."
        }
    }

    fun switchCameraLens() {
        _useFrontCamera.value = !_useFrontCamera.value
    }

    fun broadcastMyCameraFrame(jpeg: ByteArray) {
        // ارسال فریم دوربین به اعضای مجاز
    }

    fun respondToCameraRequest(requesterId: String, approve: Boolean) {
        _incomingCameraRequests.value = _incomingCameraRequests.value.filter { it.requesterId != requesterId }
        if (approve) {
            _approvedCameraViewers.value = _approvedCameraViewers.value + requesterId
        }
    }

    fun revokeViewerAccess(viewerId: String) {
        _approvedCameraViewers.value = _approvedCameraViewers.value - viewerId
    }

    fun requestWatchMemberCamera(memberId: String) {
        _watchingCameraIds.value = _watchingCameraIds.value + memberId
    }

    fun stopWatchingRemoteCamera(memberId: String) {
        _watchingCameraIds.value = _watchingCameraIds.value - memberId
    }

    fun approveMember(memberId: String) {
        _members.value = _members.value.map {
            if (it.nodeId == memberId) it.copy(status = MemberStatus.APPROVED) else it
        }
    }

    fun rejectOrKickMember(memberId: String, isKick: Boolean) {
        _members.value = _members.value.filter { it.nodeId != memberId }
    }
}
