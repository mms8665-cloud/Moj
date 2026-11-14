package ir.moj.radio.data

import java.util.UUID

enum class PacketType(val code: Byte) {
    JOIN_REQUEST(1),
    JOIN_APPROVED(2),
    JOIN_REJECTED(3),
    MEMBER_KICKED(4),
    TEXT_MESSAGE(5),
    VOICE_PTT_FRAME(6),
    VOICE_PTT_END(7),
    FILE_CHUNK(8),
    CAMERA_VIEW_REQUEST(9),
    CAMERA_VIEW_RESPONSE(10),
    CAMERA_LIVE_FRAME(11),
    CAMERA_STOPPED(12),
    MEMBER_LIST_SYNC(13);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.find { it.code == code }
    }
}

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val type: PacketType,
    val senderId: String,
    val senderNickname: String,
    val targetId: String? = null, // null = broadcast to all approved members
    val ttl: Int = 5,             // حداکثر پرش Relay برای جلوگیری از حلقه
    val timestamp: Long = System.currentTimeMillis(),
    val payload: ByteArray
)

data class NetworkMember(
    val userId: String,
    val nickname: String,
    val ipAddress: String,
    val isHost: Boolean = false,
    val isApproved: Boolean = false,
    val isOnline: Boolean = true,
    val isSharingCamera: Boolean = false
)
