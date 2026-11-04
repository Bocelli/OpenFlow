package com.openflow.android.ai

import android.util.Log
import com.openflow.android.data.PreferencesManager
import com.openflow.android.data.PromptPreset
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

data class PipelineOutput(
    val text: String,
    val isFallback: Boolean = false,
    val errorMessage: String? = null
)

class AiPipelineClient(private val prefs: PreferencesManager) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun processSpeechToPolishedText(
        audioFile: File,
        surroundingContext: String? = null
    ): Result<PipelineOutput> = withContext(Dispatchers.IO) {
        val apiKey = prefs.currentApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Chiave API mancante. Configurala nell'app OpenFlow."))
        }

        val provider = prefs.activeProvider

        // 1. STADIO 1: Speech-To-Text con Whisper
        val rawTranscription = transcribeAudio(audioFile, apiKey, provider)
            .getOrElse { return@withContext Result.failure(it) }

        if (rawTranscription.isBlank()) {
            return@withContext Result.success(PipelineOutput(text = ""))
        }

        Log.d(TAG, "RAW WHISPER: '$rawTranscription'")

        // Se l'utente ha selezionato "Trascrizione Pura (Zero IA)", salta l'LLM
        if (prefs.activePreset == PromptPreset.RAW) {
            val sanitized = sanitizeAndFormatText(rawTranscription)
            return@withContext Result.success(PipelineOutput(text = sanitized))
        }

        // 2. STADIO 2: Polishing e formattazione con LLM
        val llmResult = polishTextWithLlm(rawTranscription, surroundingContext, apiKey, provider)

        val finalOutput = if (llmResult.isSuccess) {
            val polished = llmResult.getOrThrow()
            Log.d(TAG, "POLISHED LLM: '$polished'")
            PipelineOutput(text = sanitizeAndFormatText(polished), isFallback = false)
        } else {
            val err = llmResult.exceptionOrNull()?.message ?: "Errore sconosciuto"
            Log.e(TAG, "LLM FALLITO: $err. Uso fallback Whisper grezzo.", llmResult.exceptionOrNull())
            // Fallback: pulisce comunque maiuscole e duplicazioni anche sul testo grezzo!
            PipelineOutput(
                text = sanitizeAndFormatText(rawTranscription),
                isFallback = true,
                errorMessage = err
            )
        }

        Result.success(finalOutput)
    }

    private fun transcribeAudio(
        audioFile: File,
        apiKey: String,
        provider: com.openflow.android.data.ApiProvider
    ): Result<String> {
        val mediaType = "audio/m4a".toMediaType()
        val fileBody = audioFile.asRequestBody(mediaType)

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, fileBody)
            .addFormDataPart("model", provider.asrModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0") // Determinismo puro

        val lang = prefs.language
        if (lang.isNotBlank() && lang != "auto") {
            multipartBuilder.addFormDataPart("language", lang)
        }

        val request = Request.Builder()
            .url("${provider.baseUrl}/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBuilder.build())
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
            systemInstruction += "\nContesto del testo già presente nel campo: \"$contextText\""
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
            put("temperature", 0.3) // 0.3: equilibrio ideale tra eleganza formattazione e fedeltà all'intento
            put("max_tokens", 2048)
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
                return Result.failure(IOException("HTTP ${response.code}: $responseBody"))
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

    companion object {
        private const val TAG = "AiPipelineClient"

        /**
         * Sanitizzazione deterministica e Garanzia di Qualità Tipografica (Wispr Engine):
         * 1. Elimina prefissi spuri (Broadcast, Message, Ecco il testo, ecc.)
         * 2. Rimuove blocchi markdown e virgolette di contorno superflue
         * 3. Corregge fonetica dello spelling ("a bici" -> "A B C")
         * 4. Deduplica parole e frasi ripetute (anche con virgole o pause, es. "io, io", "però, però")
         * 5. Pulisce spaziature prima della punteggiatura ed elimina spazi interni a virgolette
         * 6. Forza MAIUSCOLA dopo ogni punto (. ! ? \n\n) anche se preceduta da virgolette o parentesi
         * 7. Prima lettera dell'intero testo sempre maiuscola
         */
        fun sanitizeAndFormatText(output: String): String {
            var clean = output.trim()

            // 1. Rimuove prefissi spuri
            val prefixesToRemove = listOf(
                "Broadcast:", "Broadcast :", "Broadcast",
                "Message:", "Message :", "Messaggio:", "Messaggio :",
                "Testo:", "Testo :", "Trascrizione:", "Output:",
                "Ecco il testo:", "Ecco la trascrizione:", "Ecco il testo formattato:", "Ecco il racconto:"
            )
            for (prefix in prefixesToRemove) {
                if (clean.startsWith(prefix, ignoreCase = true)) {
                    clean = clean.substring(prefix.length).trim()
                }
            }

            // 2. Rimuove blocchi markdown ```text ... ```
            if (clean.startsWith("```") && clean.endsWith("```")) {
                clean = clean.substringAfter("\n").substringBeforeLast("```").trim()
            }

            // 3. Rimuove virgolette esterne superflue se racchiudono l'INTERO blocco
            if ((clean.startsWith("\"") && clean.endsWith("\"") && clean.count { it == '"' } == 2) ||
                (clean.startsWith("«") && clean.endsWith("»") && clean.count { it == '«' } == 1 && clean.count { it == '»' } == 1)) {
                clean = clean.substring(1, clean.length - 1).trim()
            }

            // 4. Correzione fonetica per lo spelling ("abici" / "a bici" -> "A B C")
            clean = clean.replace(Regex("""\b(abici|abbiccì|a\s+bici)\b""", RegexOption.IGNORE_CASE), "A B C")

            // 5. Deduplicazione automatica parole consecutive (elimina balbettii con pause, virgole o spazi)
            // Usa \p{L} per supportare qualsiasi carattere Unicode (inclusi accenti italiani: à è é ì ò ù)
            val duplicateSingleWordRegex = Regex("""\b(\p{L}+)[\s,.;:-]+\1\b""", RegexOption.IGNORE_CASE)
            while (duplicateSingleWordRegex.containsMatchIn(clean)) {
                clean = clean.replace(duplicateSingleWordRegex) { match -> match.groupValues[1] }
            }

            // Deduplicazione per frasi di due parole duplicate consecutive (es. "sto facendo, sto facendo" -> "sto facendo")
            val duplicateTwoWordsRegex = Regex("""\b(\p{L}+[\s,]+\p{L}+)[\s,.;:-]+\1\b""", RegexOption.IGNORE_CASE)
            while (duplicateTwoWordsRegex.containsMatchIn(clean)) {
                clean = clean.replace(duplicateTwoWordsRegex) { match -> match.groupValues[1] }
            }

            // 6. Normalizzazione spaziature tipografiche
            // Elimina spazi prima di virgole, punti, due punti, punti e virgola, interrogativi ed esclamativi
            clean = clean.replace(Regex("""\s+([,.:;!?])"""), "$1")

            // Rimuove spazi interni alle virgolette: « ciao » -> «ciao», " test " -> "test"
            clean = clean.replace(Regex("""([«"“])\s+"""), "$1")
            clean = clean.replace(Regex("""\s+([»"”])"""), "$1")

            // Normalizza spazi orizzontali multipli (preservando i ritorni a capo \n)
            clean = clean.replace(Regex("""[^\S\r\n]{2,}"""), " ")

            // 7. Forza MAIUSCOLA dopo ogni punto (. ! ?) anche in presenza di virgolette o parentesi
            clean = clean.replace(Regex("""([.!?]\s*["'«“‘(\[]?\s*)([\p{Ll}])""")) { match ->
                match.groupValues[1] + match.groupValues[2].uppercase()
            }

            // 8. Forza MAIUSCOLA dopo ritorni a capo (\n\n o \n) anche in presenza di virgolette o parentesi
            clean = clean.replace(Regex("""(\n+\s*["'«“‘(\[]?\s*)([\p{Ll}])""")) { match ->
                match.groupValues[1] + match.groupValues[2].uppercase()
            }

            // 9. Prima lettera dell'intero testo sempre maiuscola
            if (clean.isNotEmpty()) {
                clean = clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

            return clean.trim()
        }
    }
}
