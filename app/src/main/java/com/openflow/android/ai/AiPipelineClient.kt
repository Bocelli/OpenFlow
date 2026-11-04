package com.openflow.android.ai

import com.openflow.android.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiPipelineClient(private val prefs: PreferencesManager) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun processSpeechToPolishedText(
        audioFile: File,
        surroundingContext: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.currentApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Chiave API mancante. Configurala nell'app OpenFlow."))
        }

        val provider = prefs.activeProvider

        // 1. STADIO 1: Speech-To-Text con Whisper
        val rawTranscription = transcribeAudio(audioFile, apiKey, provider)
            .getOrElse { return@withContext Result.failure(it) }

        if (rawTranscription.isBlank()) {
            return@withContext Result.success("")
        }

        // 2. STADIO 2: Polishing e formattazione con LLM
        val polishedText = polishTextWithLlm(rawTranscription, surroundingContext, apiKey, provider)
            .getOrElse {
                // shortcut: Se l'LLM fallisce (es. quota o timeout), ritorna la trascrizione grezza per non perdere l'audio
                rawTranscription
            }

        Result.success(polishedText)
    }

    private fun transcribeAudio(
        audioFile: File,
        apiKey: String,
        provider: com.openflow.android.data.ApiProvider
    ): Result<String> {
        val mediaType = "audio/m4a".toMediaType()
        val fileBody = audioFile.asRequestBody(mediaType)

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, fileBody)
            .addFormDataPart("model", provider.asrModel)
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url("${provider.baseUrl}/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return Result.failure(IOException("Errore Whisper HTTP ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val text = json.optString("text", "").trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun polishTextWithLlm(
        rawText: String,
        contextText: String?,
        apiKey: String,
        provider: com.openflow.android.data.ApiProvider
    ): Result<String> {
        val preset = prefs.activePreset
        var systemInstruction = preset.prompt
        if (!contextText.isNullOrBlank()) {
            systemInstruction += "\nContesto del campo di testo attuale: \"$contextText\""
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", rawText)
            })
        }

        val requestPayload = JSONObject().apply {
            put("model", provider.llmModel)
            put("messages", messagesArray)
            put("temperature", 0.2)
        }

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestPayload.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return Result.failure(IOException("Errore LLM HTTP ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                val content = messageObj?.optString("content", "")?.trim() ?: rawText
                Result.success(content)
            } else {
                Result.success(rawText)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
