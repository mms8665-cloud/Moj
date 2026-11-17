package ir.moj.radio.network

import ir.moj.radio.data.NetworkMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class MemberManager {
    private val membersMap = ConcurrentHashMap<String, NetworkMember>()
    private val revokedUserIds = ConcurrentHashMap.newKeySet<String>()

    private val _members = MutableStateFlow<List<NetworkMember>>(emptyList())
    val members: StateFlow<List<NetworkMember>> = _members

    private val _pendingRequests = MutableStateFlow<List<NetworkMember>>(emptyList())
    val pendingRequests: StateFlow<List<NetworkMember>> = _pendingRequests

    fun registerHost(userId: String, nickname: String, ip: String) {
        val host = NetworkMember(
            userId = userId,
            nickname = nickname,
            ipAddress = ip,
            isHost = true,
            isApproved = true,
            isOnline = true,
            hopCount = 0
        )
        membersMap[userId] = host
        publishState()
    }

    fun addJoinRequest(userId: String, nickname: String, ip: String, hopCount: Int = 0): Boolean {
        if (revokedUserIds.contains(userId)) return false
        val existing = membersMap[userId]
        val candidate = NetworkMember(
            userId = userId,
            nickname = nickname,
            ipAddress = ip,
            isHost = existing?.isHost ?: false,
            isApproved = existing?.isApproved ?: false,
            isOnline = true,
            isSharingCamera = existing?.isSharingCamera ?: false,
            hopCount = hopCount
        )
        membersMap[userId] = candidate
        publishState()
        return true
    }

    fun approveMember(userId: String): NetworkMember? {
        val existing = membersMap[userId] ?: return null
        val approved = existing.copy(isApproved = true, isOnline = true)
        membersMap[userId] = approved
        publishState()
        return approved
    }

    fun rejectMember(userId: String) {
        membersMap.remove(userId)
        publishState()
    }

    fun kickMember(userId: String) {
        revokedUserIds.add(userId)
        membersMap.remove(userId)
        publishState()
    }

    fun isAuthorized(userId: String): Boolean {
        if (revokedUserIds.contains(userId)) return false
        return membersMap[userId]?.isApproved == true
    }

    fun updateCameraSharing(userId: String, isSharing: Boolean) {
        val existing = membersMap[userId] ?: return
        membersMap[userId] = existing.copy(isSharingCamera = isSharing)
        publishState()
    }

    fun markOffline(userId: String) {
        val existing = membersMap[userId] ?: return
        membersMap[userId] = existing.copy(isOnline = false, isSharingCamera = false)
        publishState()
    }

    fun syncMembersFromHost(list: List<NetworkMember>) {
        membersMap.clear()
        list.forEach { membersMap[it.userId] = it }
        publishState()
    }

    fun clearAll() {
        membersMap.clear()
        revokedUserIds.clear()
        publishState()
    }

    private fun publishState() {
        val all = membersMap.values.toList()
        _members.value = all.filter { it.isApproved }
        _pendingRequests.value = all.filter { !it.isApproved }
    }
}
