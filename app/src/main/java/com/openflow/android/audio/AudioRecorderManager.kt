package com.openflow.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    var isRecording: Boolean = false
        private set

    fun startRecording(): Result<File> {
        if (isRecording) {
            stopRecording()
        }

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
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000) // 16kHz ottimale per Whisper
                setAudioEncodingBitRate(32000) // compatto e velocissimo da inviare via rete
                setAudioChannels(1) // Mono
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            Result.success(audioFile)
        } catch (e: IOException) {
            Log.e(TAG, "Errore durante avvio registrazione: ${e.message}", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Restituisce l'ampiezza istantanea (0..32767) per animare la waveform in tempo reale
     */
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
        currentOutputFile?.delete()
        currentOutputFile = null
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
    }
}
