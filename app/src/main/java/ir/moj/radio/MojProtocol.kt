package ir.moj.radio

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class NodeRole {
    NONE, HOST, CLIENT
}

enum class MemberStatus {
    PENDING, APPROVED, REJECTED, KICKED, DISCONNECTED
}

data class MemberNode(
    val nodeId: String,
    val nickname: String,
    val ipAddress: String,
    var status: MemberStatus,
    var authToken: String = "",
    var hopCount: Int = 1,
    var isSharingCamera: Boolean = false,
    var lastSeen: Long = System.currentTimeMillis()
) {
    val displayTitle: String
        get() = "$nickname (#${nodeId.take(4)})"
}

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
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean
) {
    val progressPercent: Int
        get() = if (fileSize <= 0) 0 else ((bytesTransferred * 100) / fileSize).toInt().coerceIn(0, 100)
}

data class CameraViewRequest(
    val requesterId: String,
    val requesterName: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val type: String,
    val senderId: String,
    val senderName: String,
    val targetId: String = "ALL",
    val ttl: Int = 5,
    val hopCount: Int = 1,
    val authToken: String = "",
    val hmac: String = "",
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
        root.put("hmac", hmac)
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
                    hmac = root.optString("hmac", ""),
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
    private val PBKDF2_SALT = "MOJ_MESH_RADIO_SALT_V2".toByteArray(Charsets.UTF_8)

    fun generateJoinCode(length: Int = 8): String {
        val safeLen = length.coerceIn(6, 10)
        val sb = StringBuilder(safeLen)
        for (i in 0 until safeLen) {
            val idx = secureRandom.nextInt(SAFE_ALPHABET.length)
            sb.append(SAFE_ALPHABET[idx])
        }
        return sb.toString()
    }

    fun generateAuthToken(nodeId: String, networkSecret: String): String {
        val nonce = ByteArray(16)
        secureRandom.nextBytes(nonce)
        val raw = "$nodeId:$networkSecret:${Base64.encodeToString(nonce, Base64.NO_WRAP)}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun deriveAesKey(joinCode: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(joinCode.uppercase().toCharArray(), PBKDF2_SALT, 1000, 128)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    /**
     * رمزنگاری واقعی متقارن AES-GCM برای محافظت از پیام‌ها و اطلاعات حساس در شبکه محلی
     */
    fun encryptAesGcm(plainText: String, joinCode: String): String {
        return try {
            val key = deriveAesKey(joinCode)
            val iv = ByteArray(12)
            secureRandom.nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decryptAesGcm(encryptedB64: String, joinCode: String): String? {
        return try {
            val combined = Base64.decode(encryptedB64, Base64.NO_WRAP)
            if (combined.size <= 12) return null
            val iv = combined.copyOfRange(0, 12)
            val cipherBytes = combined.copyOfRange(12, combined.size)
            val key = deriveAesKey(joinCode)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * امضای دیجیتال بسته با HMAC-SHA256 برای تشخیص هرگونه دست‌کاری در مسیر
     */
    fun computeHmac(packetId: String, type: String, senderId: String, payloadStr: String, joinCode: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(joinCode.uppercase().toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val data = "$packetId|$type|$senderId|$payloadStr".toByteArray(Charsets.UTF_8)
            val rawHmac = mac.doFinal(data)
            rawHmac.joinToString("") { "%02x".format(it) }.take(24)
        } catch (e: Exception) {
            ""
        }
    }
}
