package ir.moj.radio.audio

import android.annotation.SuppressLint
import android.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

class PttAudioEngine(
    private val onAudioFrameCaptured: (ByteArray) -> Unit,
    private val onTransmitEnded: () -> Unit
) {
    private val sampleRate = 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private var recordJob: Job? = null
    private var audioTrack: AudioTrack? = null

    @Volatile var rogerBeepEnabled: Boolean = true
    @Volatile var outputVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            audioTrack?.setVolume(field)
        }

    private val _activeSpeakerName = MutableStateFlow<String?>(null)
    val activeSpeakerName: StateFlow<String?> = _activeSpeakerName

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting

    fun initializePlayback() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .apply {
                setVolume(outputVolume)
                play()
            }
    }

    @SuppressLint("MissingPermission")
    fun startTransmitting(scope: CoroutineScope, myNickname: String): Boolean {
        if (_activeSpeakerName.value != null && _activeSpeakerName.value != myNickname) {
            return false // کانال مشغول است (مدیریت نوبت صحبت)
        }
        if (_isTransmitting.value) return true

        _isTransmitting.value = true
        _activeSpeakerName.value = myNickname

        recordJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
            val frameSize = 1280 // 40ms PCM 16-bit @ 16kHz
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelIn,
                encoding,
                maxOf(minBuf, frameSize * 2)
            )
            val buffer = ByteArray(frameSize)
            try {
                recorder.startRecording()
                while (isActive && _isTransmitting.value) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onAudioFrameCaptured(buffer.copyOf(read))
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
        return true
    }

    fun stopTransmitting() {
        if (!_isTransmitting.value) return
        _isTransmitting.value = false
        recordJob?.cancel()
        recordJob = null
        _activeSpeakerName.value = null
        onTransmitEnded()
        if (rogerBeepEnabled) playRogerBeep()
    }

    fun onRemoteAudioReceived(senderNickname: String, pcmData: ByteArray) {
        initializePlayback()
        _activeSpeakerName.value = senderNickname
        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    fun onRemoteTransmitEnded() {
        _activeSpeakerName.value = null
        if (rogerBeepEnabled) playRogerBeep()
    }

    fun playRogerBeep() {
        initializePlayback()
        val durationMs = 110
        val numSamples = (sampleRate * durationMs) / 1000
        val beepPcm = ByteArray(numSamples * 2)
        val freq = 1350.0
        for (i in 0 until numSamples) {
            val sample = (sin(2.0 * PI * i * freq / sampleRate) * 14000).toInt().toShort()
            beepPcm[2 * i] = (sample.toInt() and 0xFF).toByte()
            beepPcm[2 * i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        audioTrack?.write(beepPcm, 0, beepPcm.size)
    }

    fun release() {
        stopTransmitting()
        runCatching { audioTrack?.stop() }
        audioTrack?.release()
        audioTrack = null
    }
}
