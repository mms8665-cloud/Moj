package ir.moj.radio.camera

import android.content.Context
import android.graphics.*
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ir.moj.radio.data.CameraQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class CameraViewRequest(
    val requesterId: String,
    val requesterNickname: String
)

data class RemoteCameraStream(
    val ownerId: String,
    val ownerNickname: String,
    val latestFrame: Bitmap
)

class LocalCameraEngine(
    private val context: Context,
    private val onEncodedFrameReady: (targetUserIds: Set<String>, jpegBytes: ByteArray) -> Unit,
    private val onSharingStateChanged: (isSharing: Boolean) -> Unit
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var lastFrameSentTimeMs: Long = 0L

    var currentQuality: CameraQuality = CameraQuality.MEDIUM

    private val approvedViewers = ConcurrentHashMap.newKeySet<String>()

    private val _isSharingMyCamera = MutableStateFlow(false)
    val isSharingMyCamera: StateFlow<Boolean> = _isSharingMyCamera

    private val _pendingViewRequests = MutableStateFlow<List<CameraViewRequest>>(emptyList())
    val pendingViewRequests: StateFlow<List<CameraViewRequest>> = _pendingViewRequests

    private val _remoteStreams = MutableStateFlow>(emptyMap())
    typealias RemoteStreamMap = Map<String, RemoteCameraStream>
    val remoteStreams: StateFlow> = _remoteStreams

    fun onIncomingViewRequest(requesterId: String, requesterNickname: String) {
        val current = _pendingViewRequests.value
        if (current.none { it.requesterId == requesterId }) {
            _pendingViewRequests.value = current + CameraViewRequest(requesterId, requesterNickname)
        }
    }

    fun approveViewer(requesterId: String) {
        approvedViewers.add(requesterId)
        _pendingViewRequests.value = _pendingViewRequests.value.filterNot { it.requesterId == requesterId }
    }

    fun rejectViewer(requesterId: String) {
        approvedViewers.remove(requesterId)
        _pendingViewRequests.value = _pendingViewRequests.value.filterNot { it.requesterId == requesterId }
    }

    fun startSharing(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean = false) {
        lensFacing = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            bindAnalysisUseCase(lifecycleOwner, provider)
            _isSharingMyCamera.value = true
            onSharingStateChanged(true)
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner) {
        if (!_isSharingMyCamera.value) return
        val useFront = lensFacing == CameraSelector.LENS_FACING_BACK
        startSharing(lifecycleOwner, useFront)
    }

    fun stopSharing() {
        cameraProvider?.unbindAll()
        approvedViewers.clear()
        _isSharingMyCamera.value = false
        onSharingStateChanged(false)
    }

    fun onRemoteFrameReceived(ownerId: String, ownerNickname: String, jpegBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
        val updated = _remoteStreams.value.toMutableMap()
        updated[ownerId] = RemoteCameraStream(ownerId, ownerNickname, bitmap)
        _remoteStreams.value = updated
    }

    fun stopWatchingRemote(ownerId: String) {
        val updated = _remoteStreams.value.toMutableMap()
        updated.remove(ownerId)
        _remoteStreams.value = updated
    }

    fun stopWatchingAll() {
        _remoteStreams.value = emptyMap()
    }

    private fun bindAnalysisUseCase(lifecycleOwner: LifecycleOwner, provider: ProcessCameraProvider) {
        provider.unbindAll()
        val quality = currentQuality
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(quality.width, quality.height))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val now = System.currentTimeMillis()
                val minIntervalMs = 1000L / quality.targetFps
                if (now - lastFrameSentTimeMs >= minIntervalMs && approvedViewers.isNotEmpty()) {
                    lastFileSentTimeMs(now)
                    lastFrameSentTimeMs = now
                    val jpeg = imageProxyToJpeg(imageProxy, quality.jpegQuality)
                    if (jpeg != null) {
                        onEncodedFrameReady(approvedViewers.toSet(), jpeg)
                    }
                }
            } finally {
                imageProxy.close()
            }
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.bindToLifecycle(lifecycleOwner, selector, analysis)
    }

    private fun lastFileSentTimeMs(time: Long) {
        lastFrameSentTimeMs = time
    }

    private fun imageProxyToJpeg(image: ImageProxy, quality: Int): ByteArray? {
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
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    fun release() {
        stopSharing()
        stopWatchingAll()
        cameraExecutor.shutdown()
    }
}
