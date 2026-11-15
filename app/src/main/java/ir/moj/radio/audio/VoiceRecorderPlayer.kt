package ir.moj.radio.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

class VoiceRecorderPlayer(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    var currentVoiceFile: File? = null

    fun startRecording(): File? {
        stopPlayback()
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.3gp")
        currentVoiceFile = file
        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            return file
        } catch (_: Exception) {
            stopRecording()
            return null
        }
    }

    fun stopRecording(): File? {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        return currentVoiceFile
    }

    fun playVoice(file: File, onCompletion: () -> Unit) {
        stopPlayback()
        if (!file.exists()) return
        try {
            mediaMediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    onCompletion()
                    release()
                    mediaPlayer = null
                }
            }
        } catch (_: Exception) {
            onCompletion()
        }
    }

    fun stopPlayback() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    private var mediaMediaPlayer: MediaPlayer?
        get() = mediaPlayer
        set(value) { mediaPlayer = value }
}
