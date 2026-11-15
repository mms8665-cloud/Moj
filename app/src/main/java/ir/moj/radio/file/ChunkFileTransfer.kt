package ir.moj.radio.file

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TransferState(
    val transferId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val isOutgoing: Boolean,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val isCancelled: Boolean = false,
    val localFilePath: String? = null
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

typealias TransferMap = Map<String, TransferState>

class ChunkFileTransfer(
    private val context: Context,
    private val onSendChunkPayload: (ByteArray) -> Unit
) {
    companion object {
        const val MAX_FILE_SIZE_BYTES: Long = 150L * 1024L * 1024L
        const val CHUNK_SIZE_BYTES: Int = 64 * 1024
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val incomingFiles = ConcurrentHashMap<String, IncomingSession>()

    private val _transfers = MutableStateFlow<TransferMap>(emptyMap())
    val transfers: StateFlow<TransferMap> = _transfers

    private data class IncomingSession(
        val file: File,
        val expectedSha256: String,
        val totalChunks: Int,
        val receivedChunks: MutableSet<Int> = mutableSetOf()
    )

    fun sendFile(scope: CoroutineScope, file: File): String? {
        if (!file.exists() || file.length() > MAX_FILE_SIZE_BYTES) return null
        val transferId = UUID.randomUUID().toString()
        val totalBytes = file.length()
        val totalChunks = ((totalBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt()
        val sha256 = computeSha256(file)

        updateState(TransferState(transferId, file.name, totalBytes, 0L, isOutgoing = true, localFilePath = file.absolutePath))

        val job = scope.launch(Dispatchers.IO) {
            try {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(CHUNK_SIZE_BYTES)
                    var chunkIndex = 0
                    var sentBytes = 0L
                    while (isActive) {
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        val payload = encodeChunk(
                            transferId = transferId,
                            fileName = file.name,
                            totalBytes = totalBytes,
                            totalChunks = totalChunks,
                            chunkIndex = chunkIndex,
                            sha256 = sha256,
                            data = buffer.copyOf(read)
                        )
                        onSendChunkPayload(payload)
                        sentBytes += read
                        chunkIndex++
                        updateState(
                            TransferState(
                                transferId = transferId,
                                fileName = file.name,
                                totalBytes = totalBytes,
                                transferredBytes = sentBytes,
                                isOutgoing = true,
                                isCompleted = chunkIndex == totalChunks,
                                localFilePath = file.absolutePath
                            )
                        )
                        delay(8)
                    }
                }
            } catch (_: CancellationException) {
                markCancelled(transferId)
            } catch (_: Exception) {
                markFailed(transferId)
            } finally {
                activeJobs.remove(transferId)
            }
        }
        activeJobs[transferId] = job
        return transferId
    }

    fun onChunkReceived(payload: ByteArray) {
        val dis = DataInputStream(ByteArrayInputStream(payload))
        val transferId = dis.readUTF()
        val fileName = dis.readUTF()
        val totalBytes = dis.readLong()
        val totalChunks = dis.readInt()
        val chunkIndex = dis.readInt()
        val expectedSha256 = dis.readUTF()
        val dataLen = dis.readInt()
        val data = ByteArray(dataLen)
        dis.readFully(data)

        if (totalBytes > MAX_FILE_SIZE_BYTES) return

        val dir = File(context.getExternalFilesDir(null), "MOJ_Received").apply { mkdirs() }
        val session = incomingFiles.getOrPut(transferId) {
            IncomingSession(File(dir, "${transferId.take(6)}_$fileName"), expectedSha256, totalChunks)
        }

        RandomAccessFile(session.file, "rw").use { raf ->
            raf.seek(chunkIndex.toLong() * CHUNK_SIZE_BYTES)
            raf.write(data)
        }
        session.receivedChunks.add(chunkIndex)

        val isDone = session.receivedChunks.size >= session.totalChunks
        var verified = false
        if (isDone) {
            verified = computeSha256(session.file) == session.expectedSha256
            incomingFiles.remove(transferId)
        }

        val receivedBytes = minOf(session.receivedChunks.size.toLong() * CHUNK_SIZE_BYTES, totalBytes)
        updateState(
            TransferState(
                transferId = transferId,
                fileName = fileName,
                totalBytes = totalBytes,
                transferredBytes = receivedBytes,
                isOutgoing = false,
                isCompleted = isDone && verified,
                isFailed = isDone && !verified,
                localFilePath = session.file.absolutePath
            )
        )
    }

    fun cancelTransfer(transferId: String) {
        activeJobs[transferId]?.cancel()
        activeJobs.remove(transferId)
        incomingFiles.remove(transferId)
        markCancelled(transferId)
    }

    private fun encodeChunk(
        transferId: String,
        fileName: String,
        totalBytes: Long,
        totalChunks: Int,
        chunkIndex: Int,
        sha256: String,
        data: ByteArray
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeUTF(transferId)
            dos.writeUTF(fileName)
            dos.writeLong(totalBytes)
            dos.writeInt(totalChunks)
            dos.writeInt(chunkIndex)
            dos.writeUTF(sha256)
            dos.writeInt(data.size)
            dos.write(data)
        }
        return bos.toByteArray()
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var read = fis.read(buf)
            while (read > 0) {
                digest.update(buf, 0, read)
                read = fis.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateState(state: TransferState) {
        val map = _transfers.value.toMutableMap()
        map[state.transferId] = state
        _transfers.value = map
    }

    private fun markCancelled(id: String) {
        _transfers.value[id]?.let { updateState(it.copy(isCancelled = true)) }
    }

    private fun markFailed(id: String) {
        _transfers.value[id]?.let { updateState(it.copy(isFailed = true)) }
    }
}
