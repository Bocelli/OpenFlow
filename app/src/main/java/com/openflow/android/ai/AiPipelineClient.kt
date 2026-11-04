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

        // CASO SPECIALE: GOOGLE GEMINI (Multimodale diretto Audio -> Testo formattato)
        if (provider == com.openflow.android.data.ApiProvider.GEMINI) {
            Log.d(TAG, "Elaborazione audio multimodale nativa con Google Gemini...")
            val geminiResult = processGeminiMultimodalAudio(audioFile, apiKey)
            return@withContext if (geminiResult.isSuccess) {
                val polished = geminiResult.getOrThrow()
                Log.d(TAG, "GEMINI SUCCESS: '$polished'")
                Result.success(PipelineOutput(text = sanitizeAndFormatText(polished), isFallback = false))
            } else {
                val err = geminiResult.exceptionOrNull()?.message ?: "Errore sconosciuto Gemini"
                Log.e(TAG, "Gemini fallito: $err")
                Result.success(PipelineOutput(
                    text = "",
                    isFallback = true,
                    errorMessage = err
                ))
            }
        }

        // PIPELINE STANDARD A 2 STADI (Whisper STT + LLM Polisher) per Groq e OpenAI
        // 1. STADIO 1: Speech-To-Text con Whisper
        val rawTranscription = transcribeAudio(audioFile, apiKey, provider)
            .getOrElse { return@withContext Result.failure(it) }

        if (rawTranscription.isBlank()) {
            return@withContext Result.success(PipelineOutput(text = ""))
        }

        Log.d(TAG, "RAW WHISPER: '$rawTranscription'")

        // 2. STADIO 2: Polishing e formattazione con LLM
        val llmResult = polishTextWithLlm(rawTranscription, apiKey, provider)

        val finalOutput = if (llmResult.isSuccess) {
            val polished = llmResult.getOrThrow()
            Log.d(TAG, "POLISHED LLM: '$polished'")
            PipelineOutput(text = sanitizeAndFormatText(polished), isFallback = false)
        } else {
            val err = llmResult.exceptionOrNull()?.message ?: "Errore sconosciuto"
            Log.e(TAG, "LLM FALLITO: $err. Uso fallback Whisper grezzo.", llmResult.exceptionOrNull())
            PipelineOutput(
                text = sanitizeAndFormatText(rawTranscription),
                isFallback = true,
                errorMessage = err
            )
        }

        Result.success(finalOutput)
    }

    private fun processGeminiMultimodalAudio(audioFile: File, apiKey: String): Result<String> {
        val audioBytes = try {
            audioFile.readBytes()
        } catch (e: Exception) {
            return Result.failure(IOException("Impossibile leggere file audio per Gemini: ${e.message}"))
        }

        val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val candidateModels = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")

        var lastError: Exception? = null
        for (modelName in candidateModels) {
            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prefs.activePreset.prompt) })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Trascrivi e formatta il parlato di questo file audio secondo le istruzioni del sistema (virgolette caporali per citazioni o app, paragrafi con a capo, punteggiatura d'autore e rimozione totale degli intercalari). Restituisci ESCLUSIVAMENTE il testo elaborato.")
                            })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "audio/mp4")
                                    put("data", base64Audio)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 1200)
                })
            }

            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${apiKey.trim()}")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text", "").trim()
                            if (text.isNotBlank()) {
                                return Result.success(text)
                            }
                        }
                    }
                    return Result.failure(IOException("Risposta Gemini priva di testo generato."))
                } else {
                    lastError = IOException("Gemini ($modelName) HTTP ${response.code}: $responseBody")
                    // Se è 404, proseguiamo al modello successivo
                    if (response.code != 404) {
                        return Result.failure(lastError)
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return Result.failure(lastError ?: IOException("Tutti i modelli Gemini hanno fallito"))
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
        apiKey: String,
        provider: com.openflow.android.data.ApiProvider
    ): Result<String> {
        val cleanKey = apiKey.trim()
        val cleanBaseUrl = provider.baseUrl.trim().removeSuffix("/")

        // 1. Tentativo Primario
        val primaryResult = executeLlmCall(rawText, cleanKey, cleanBaseUrl, provider.llmModel)
        if (primaryResult.isSuccess) {
            return primaryResult
        }

        val primaryError = primaryResult.exceptionOrNull()?.message ?: "Errore sconosciuto"
        Log.w(TAG, "Tentativo primario fallito con ${provider.llmModel}: $primaryError")

        // 2. Per Groq: fallback su modelli attivi della nuova generazione
        if (provider == com.openflow.android.data.ApiProvider.GROQ) {
            val fallbackCandidates = listOf(
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "qwen/qwen3.8-27b"
            )
            for (candidate in fallbackCandidates) {
                if (candidate != provider.llmModel) {
                    Log.i(TAG, "Tentativo fallback Groq con '$candidate'...")
                    val res = executeLlmCall(rawText, cleanKey, cleanBaseUrl, candidate)
                    if (res.isSuccess) {
                        Log.i(TAG, "Fallback Groq riuscito con $candidate!")
                        return res
                    }
                }
            }

            // 3. Fallback dinamico: interroga /models su Groq
            Log.i(TAG, "Tentativo auto-discovery modelli attivi su Groq...")
            val discoveredModel = discoverActiveGroqModel(cleanKey)
            if (discoveredModel != null && discoveredModel != provider.llmModel) {
                Log.i(TAG, "Trovato modello attivo Groq: $discoveredModel")
                val res = executeLlmCall(rawText, cleanKey, cleanBaseUrl, discoveredModel)
                if (res.isSuccess) {
                    return res
                }
            }
        }

        return primaryResult
    }

    private fun discoverActiveGroqModel(apiKey: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return null
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id", "")
                if (id.isNotBlank() && !id.contains("whisper") && !id.contains("guard") && !id.contains("moderation")) {
                    return id
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun executeLlmCall(
        rawText: String,
        apiKey: String,
        baseUrl: String,
        modelName: String
    ): Result<String> {
        val preset = prefs.activePreset
        val systemInstruction = preset.prompt

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
            put("model", modelName)
            put("messages", messagesArray)
            put("temperature", 0.3)
            put("max_tokens", 800) // 800 token riducono la riserva TPM del 60%, azzerando il rischio di HTTP 429 su Groq
        }

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestPayload.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
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
                val content = messageObj?.optString("content", "")?.trim() ?: ""
                if (content.isNotBlank()) {
                    Result.success(content)
                } else {
                    Result.failure(IOException("Risposta LLM vuota da $modelName"))
                }
            } else {
                val errorObj = json.optJSONObject("error")
                val errorMsg = errorObj?.optString("message") ?: "Nessun testo generato da $modelName"
                Result.failure(IOException("Errore LLM ($modelName): $errorMsg"))
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
            var singleWordIter = 0
            while (duplicateSingleWordRegex.containsMatchIn(clean) && singleWordIter < 10) {
                val updated = clean.replace(duplicateSingleWordRegex) { match -> match.groupValues[1] }
                if (updated == clean) break
                clean = updated
                singleWordIter++
            }

            // Deduplicazione per frasi di due parole duplicate consecutive (es. "sto facendo, sto facendo" -> "sto facendo")
            val duplicateTwoWordsRegex = Regex("""\b(\p{L}+[\s,]+\p{L}+)[\s,.;:-]+\1\b""", RegexOption.IGNORE_CASE)
            var twoWordsIter = 0
            while (duplicateTwoWordsRegex.containsMatchIn(clean) && twoWordsIter < 10) {
                val updated = clean.replace(duplicateTwoWordsRegex) { match -> match.groupValues[1] }
                if (updated == clean) break
                clean = updated
                twoWordsIter++
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
