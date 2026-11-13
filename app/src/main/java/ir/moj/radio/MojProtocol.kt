package ir.moj.radio

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

enum class NodeRole {
    NONE, HOST, CLIENT
}

enum class MemberStatus {
    PENDING, APPROVED, REJECTED, KICKED, DISCONNECTED
}

data class MemberNode(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    var status: MemberStatus,
    var authToken: String = "",
    var hopCount: Int = 1,
    var lastSeen: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean
)

data class FileTransferItem(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val senderId: String,
    val senderName: String,
    val sha256: String,
    val isImage: Boolean,
    var bytesTransferred: Long = 0L,
    var isCompleted: Boolean = false,
    var isCanceled: Boolean = false,
    var localFilePath: String? = null,
    val isIncoming: Boolean
) {
    val progressPercent: Int
        get() = if (fileSize <= 0) 0 else ((bytesTransferred * 100) / fileSize).toInt().coerceIn(0, 100)
}

/**
 * بسته استاندارد شبکه Mesh موج
 * شامل مکانیزم TTL و Packet ID برای جلوگیری از حلقه و تکرار
 */
data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val type: String,
    val senderId: String,
    val senderName: String,
    val targetId: String = "ALL", // "ALL" یا nodeId خاص
    val ttl: Int = 5,
    val hopCount: Int = 1,
    val authToken: String = "",
    val payload: JSONObject = JSONObject()
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("packetId", packetId)
        root.put("type", type)
        root.put("senderId", senderId)
        root.put("senderName", senderName)
        root.put("targetId", targetId)
        root.put("ttl", ttl)
        root.put("hopCount", hopCount)
        root.put("authToken", authToken)
        root.put("payload", payload)
        return root.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): MeshPacket? {
            return try {
                val root = JSONObject(jsonStr)
                MeshPacket(
                    packetId = root.getString("packetId"),
                    type = root.getString("type"),
                    senderId = root.getString("senderId"),
                    senderName = root.optString("senderName", "کاربر"),
                    targetId = root.optString("targetId", "ALL"),
                    ttl = root.optInt("ttl", 5),
                    hopCount = root.optInt("hopCount", 1),
                    authToken = root.optString("authToken", ""),
                    payload = root.optJSONObject("payload") ?: JSONObject()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object MojSecurityUtils {
    // حروف بزرگ انگلیسی و اعداد بدون کاراکترهای اشتباه‌گیر (بدون O, 0, I, 1)
    private const val SAFE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    /**
     * تولید کد اتصال تصادفی دقیقاً ۸ کاراکتری
     */
    fun generateJoinCode(length: Int = 8): String {
        val safeLen = length.coerceIn(6, 10)
        val sb = StringBuilder(safeLen)
        for (i in 0 until safeLen) {
            val idx = secureRandom.nextInt(SAFE_ALPHABET.length)
            sb.append(SAFE_ALPHABET[idx])
        }
        return sb.toString()
    }

    /**
     * تولید توکن احراز هویت برای اعضای تأییدشده توسط مدیر
     */
    fun generateAuthToken(nodeId: String, networkSecret: String): String {
        val raw = "$nodeId:$networkSecret:${System.currentTimeMillis() / 60000}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun sha256Bytes(data: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(data)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
