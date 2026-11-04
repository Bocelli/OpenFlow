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

    private var peakAmplitudeInSession: Int = 0
    private var recordingStartTimeMs: Long = 0L

    fun startRecording(): Result<File> {
        if (isRecording) {
            stopRecording()
        }

        peakAmplitudeInSession = 0
        recordingStartTimeMs = System.currentTimeMillis()

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
            val amp = if (isRecording) mediaRecorder?.maxAmplitude ?: 0 else 0
            if (amp > peakAmplitudeInSession) {
                peakAmplitudeInSession = amp
            }
            amp
        } catch (_: Exception) {
            0
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        return try {
            val finalAmp = try { mediaRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
            if (finalAmp > peakAmplitudeInSession) {
                peakAmplitudeInSession = finalAmp
            }
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

    /**
     * Valuta se durante la sessione è stata rilevata una reale attività vocale.
     * Esclude registrazioni vuote (silenzio, colpi accidentali o solo ronzio di fondo).
     * Sotto i 900 di ampiezza con MediaRecorder Voice Recognition si ha solo rumore termico/ambiente.
     */
    fun hasDetectedVoiceActivity(): Boolean {
        val durationMs = if (recordingStartTimeMs > 0) System.currentTimeMillis() - recordingStartTimeMs else 0L
        if (durationMs < 350L && peakAmplitudeInSession < 1500) {
            return false
        }
        return peakAmplitudeInSession >= MIN_VOICE_AMPLITUDE_THRESHOLD
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
        peakAmplitudeInSession = 0
        recordingStartTimeMs = 0L
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
        private const val MIN_VOICE_AMPLITUDE_THRESHOLD = 900
    }
}
