package com.openflow.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    var isRecording: Boolean = false
        private set

    fun startRecording(): Result<File> {
        if (isRecording) {
            stopRecording()
        }

        // Acquisisce un WakeLock parziale per garantire che la CPU non venga sospesa
        // durante la cattura del flusso vocale (particolarmente critico su Samsung, OnePlus, Vivo).
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenFlow:RecordingWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(90_000L) // Timeout precauzionale di 90s
            }
        } catch (_: Exception) {}

        val cacheDir = context.cacheDir
        val audioFile = File(cacheDir, "openflow_voice_${System.currentTimeMillis()}.m4a")
        currentOutputFile = audioFile

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            recorder.apply {
                // VOICE_RECOGNITION attiva il DSP hardware di cancellazione rumore ed eco
                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                } catch (e: Exception) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000) // 16kHz nativo per i modelli Whisper
                setAudioEncodingBitRate(64000) // 64kbps per preservare la chiarezza fonetica (lettere e consonanti)
                setAudioChannels(1) // Mono
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            Result.success(audioFile)
        } catch (e: Exception) {
            // Non solo IOException: start() lancia IllegalStateException se il microfono è occupato (es. chiamata)
            Log.e(TAG, "Errore durante avvio registrazione: ${e.message}", e)
            try { recorder.release() } catch (_: Exception) {} // mediaRecorder non è ancora assegnato: cleanup() non lo vede
            cleanup()
            Result.failure(e)
        }
    }

    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            releaseWakeLock()
            currentOutputFile
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante stop registrazione: ${e.message}", e)
            cleanup()
            null
        }
    }

    fun cancelRecording() {
        try {
            if (isRecording) {
                mediaRecorder?.stop()
            }
        } catch (_: Exception) {
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
        releaseWakeLock()
        currentOutputFile?.delete()
        currentOutputFile = null
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
    }
}
