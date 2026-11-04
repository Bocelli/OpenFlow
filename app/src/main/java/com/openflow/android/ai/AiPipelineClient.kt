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

    suspend fun processSpeechToPolishedText(audioFile: File): Result<PipelineOutput> = withContext(Dispatchers.IO) {
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
                Log.d(TAG, "GEMINI SUCCESS: ${polished.length} caratteri")
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

        Log.d(TAG, "RAW WHISPER: ${rawTranscription.length} caratteri")

        // 2. STADIO 2: Polishing e formattazione con LLM
        val llmResult = polishTextWithLlm(rawTranscription, apiKey, provider)

        val finalOutput = if (llmResult.isSuccess) {
            val polished = llmResult.getOrThrow()
            Log.d(TAG, "POLISHED LLM: ${polished.length} caratteri")
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

    private var cachedGeminiModel: String? = null

    private fun processGeminiMultimodalAudio(audioFile: File, apiKey: String): Result<String> {
        val audioBytes = try {
            audioFile.readBytes()
        } catch (e: Exception) {
            return Result.failure(IOException("Impossibile leggere file audio per Gemini: ${e.message}"))
        }

        val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        
        // Candidati in ordine: cache -> ListModels -> default. La sequenza è lazy: ListModels (un round trip)
        // parte solo se il modello in cache non basta, e i tentativi sono limitati perché ognuno ricarica l'audio.
        val candidateModels = sequence {
            cachedGeminiModel?.let { yield(it) }
            yieldAll(discoverActiveGeminiModels(apiKey))
            yieldAll(GEMINI_DEFAULT_MODELS)
        }.map { it.removePrefix("models/") }.distinct().take(MAX_GEMINI_ATTEMPTS)

        var lastError: Exception? = null
        for (modelName in candidateModels) {
            val path = "models/$modelName"
            val vocab = prefs.customVocabulary
            val systemInstruction = if (vocab.isNotBlank()) {
                "${prefs.activePreset.prompt}\n\nGLOSSARIO E TERMINI PERSONALIZZATI (usa sempre l'ortografia esatta di questi nomi/termini):\n$vocab"
            } else {
                prefs.activePreset.prompt
            }

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Trascrivi e formatta il parlato contenuto in questa registrazione secondo le istruzioni del sistema: raggruppa le frasi in paragrafi coesi e solidi (NON spezzettare ogni frase a capo), usa le virgolette caporali «...» per i nomi di progetti o app, sistema la punteggiatura con ritmo naturale ed espressivo, ed elimina rigorosamente gli intercalari ed esitazioni orali (es. 'praticamente', 'e praticamente', 'diciamo'). Scrivi correttamente i brand e modelli hardware (Groq, OpenAI, Gemini, Flash-Lite, Antigravity, OpenFlow). Restituisci ESCLUSIVAMENTE il testo finale elaborato pronto da inviare.")
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
                    // Cresce con la durata (~64 kbps): un tetto fisso taglia le dettature lunghe
                    put("maxOutputTokens", maxOf(1200, audioBytes.size / 50))
                })
            }

            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$path:generateContent")
                .header("x-goog-api-key", apiKey.trim())
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                Log.d(TAG, "Tentativo chiamata Gemini con modello: $path")
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val first = candidates.getJSONObject(0)
                        if (first.optString("finishReason") == "MAX_TOKENS") {
                            // Testo tagliato: meglio un altro modello che una dettatura inserita a metà
                            lastError = IOException("Gemini ($modelName): risposta troncata (MAX_TOKENS)")
                            continue
                        }
                        val content = first.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text", "").trim()
                            if (text.isNotBlank()) {
                                cachedGeminiModel = modelName
                                Log.i(TAG, "Gemini riuscito con successo su modello: $modelName!")
                                return Result.success(text)
                            }
                        }
                    }
                    lastError = IOException("Risposta Gemini priva di testo generato.")
                } else {
                    lastError = IOException("Gemini ($modelName) HTTP ${response.code}: $responseBody")
                    Log.w(TAG, "Modello $modelName fallito (HTTP ${response.code}). Tento modello successivo...")
                    // Se il modello in cache fallisce (es. 503 sovraccarico temporaneo, 429 rate limit o 404), azzera la cache e prova subito il prossimo candidato
                    if (cachedGeminiModel == modelName) {
                        cachedGeminiModel = null
                    }
                    // Chiave non valida o accesso negato: ogni altro modello darebbe lo stesso errore
                    if (response.code == 401 || response.code == 403 || responseBody.contains("API_KEY_INVALID")) break
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Eccezione durante chiamata a $modelName: ${e.message}. Tento successivo...")
            }
        }

        return Result.failure(lastError ?: IOException("Tutti i modelli Gemini hanno fallito"))
    }

    private fun discoverActiveGeminiModels(apiKey: String): List<String> {
        return try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                .header("x-goog-api-key", apiKey.trim())
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "ListModels Gemini fallito HTTP ${response.code}: $body")
                return emptyList()
            }

            val json = JSONObject(body)
            val modelsArray = json.optJSONArray("models") ?: return emptyList()
            val candidates = mutableListOf<String>()

            for (i in 0 until modelsArray.length()) {
                val modelObj = modelsArray.getJSONObject(i)
                val name = modelObj.optString("name", "") // es: "models/gemini-3.5-flash"
                val methods = modelObj.optJSONArray("supportedGenerationMethods")
                var supportsGenerateContent = false
                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") {
                            supportsGenerateContent = true
                            break
                        }
                    }
                }
                if (supportsGenerateContent && name.isNotBlank()) {
                    candidates.add(name)
                }
            }

            Log.i(TAG, "Modelli Gemini disponibili trovati (${candidates.size}): $candidates")

            // Ordiniamo dando priorità ai modelli ad altissima disponibilità e velocità (Flash-Lite e 3.5 Flash)
            // in modo da evitare sovraccarichi (HTTP 503) sui modelli in anteprima
            candidates.sortedWith(Comparator { a, b ->
                fun score(name: String): Int {
                    val n = name.lowercase()
                    return when {
                        n.contains("3.5-flash-lite") -> 100 // Altissima capacità, throughput continuo
                        n.contains("3.5-flash") -> 90
                        n.contains("3.8-flash") -> 80
                        n.contains("2.5-flash") -> 70
                        n.contains("flash") -> 60
                        n.contains("gemini") -> 50
                        else -> 10
                    }
                }
                score(b).compareTo(score(a))
            })
        } catch (e: Exception) {
            Log.e(TAG, "Eccezione durante ListModels Gemini: ${e.message}", e)
            emptyList()
        }
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

        val vocab = prefs.customVocabulary
        if (vocab.isNotBlank()) {
            multipartBuilder.addFormDataPart("prompt", vocab)
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
                "openai/gpt-oss-20b",
                "openai/gpt-oss-120b",
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
        val vocab = prefs.customVocabulary
        val systemInstruction = if (vocab.isNotBlank()) {
            "${preset.prompt}\n\nGLOSSARIO E TERMINI PERSONALIZZATI (preserva e usa sempre l'ortografia esatta di questi nomi/termini):\n$vocab"
        } else {
            preset.prompt
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
            put("model", modelName)
            put("messages", messagesArray)
            put("temperature", 0.3)
            // Minimo 800: tiene bassa la riserva TPM di Groq (niente HTTP 429). Cresce col testo perché l'output è
            // lungo quanto l'input e un tetto fisso taglia le dettature lunghe (1 token >= 1 carattere).
            put("max_tokens", maxOf(800, rawText.length).coerceAtMost(4000))
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
                val choice = choices.getJSONObject(0)
                if (choice.optString("finish_reason") == "length") {
                    return Result.failure(IOException("Risposta LLM troncata da $modelName (max_tokens)"))
                }
                val messageObj = choice.optJSONObject("message")
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
        private const val MAX_GEMINI_ATTEMPTS = 4
        private val GEMINI_DEFAULT_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.8-flash",
            "gemini-2.5-flash",
            "gemini-2.0-flash"
        )

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
            // "Broadcast" è un'allucinazione di Whisper; \b evita di tagliare parole vere come "Broadcasting"
            clean = clean.replace(Regex("""^Broadcast\b\s*:?\s*""", RegexOption.IGNORE_CASE), "")
            val prefixesToRemove = listOf(
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

            // 4. Correzione fonetica e normalizzazione brand tecnologici
            clean = clean.replace(Regex("""\b(abici|abbiccì|a\s+bici)\b""", RegexOption.IGNORE_CASE), "A B C")
            clean = clean.replace(Regex("""\b(grok)\b""", RegexOption.IGNORE_CASE), "Groq")
            clean = clean.replace(Regex("""\bopen\s+ai\b""", RegexOption.IGNORE_CASE), "OpenAI")
            clean = clean.replace(Regex("""\bflash\s+light\b""", RegexOption.IGNORE_CASE), "Flash-Lite")
            clean = clean.replace(Regex("""\b(antigravity|anti\s+gravity)\b""", RegexOption.IGNORE_CASE), "Antigravity")
            clean = clean.replace(Regex("""\b(wisper\s+flow|whisper\s+flow)\b""", RegexOption.IGNORE_CASE), "Wispr Flow")
            clean = clean.replace(Regex("""\bopen\s+flow\b""", RegexOption.IGNORE_CASE), "OpenFlow")

            // 5. Rimozione chirurgica di attacchi orali pleonastici a inizio paragrafo o frase ("E praticamente...", "Praticamente...")
            clean = clean.replace(Regex("""(?m)^[Ee]\s+praticamente\s*,?\s*"""), "")
            clean = clean.replace(Regex("""(?m)^[Pp]raticamente\s*,?\s*"""), "")
            clean = clean.replace(Regex("""([.!?]\s+)[Ee]\s+praticamente\s*,?\s*""")) { match -> match.groupValues[1] }
            clean = clean.replace(Regex("""([.!?]\s+)[Pp]raticamente\s*,?\s*""")) { match -> match.groupValues[1] }

            // 6. Deduplicazione automatica parole consecutive (elimina balbettii con pause, virgole o spazi)
            // Usa \p{L} per supportare qualsiasi carattere Unicode (inclusi accenti italiani: à è é ì ò ù)
            // Separatori solo spazio/virgola: un punto o un a capo divide due frasi ("Ho visto Anna. Anna ha detto...")
            // Lookaround al posto di \b: da JDK 19 \b è solo ASCII e non riconosce "però" (su Android/ICU sì)
            val duplicateSingleWordRegex = Regex("""(?<!\p{L})(\p{L}+)[ \t,]+\1(?!\p{L})""", RegexOption.IGNORE_CASE)
            var singleWordIter = 0
            while (duplicateSingleWordRegex.containsMatchIn(clean) && singleWordIter < 10) {
                val updated = clean.replace(duplicateSingleWordRegex) { match -> match.groupValues[1] }
                if (updated == clean) break
                clean = updated
                singleWordIter++
            }

            // Deduplicazione per frasi di due parole duplicate consecutive (es. "sto facendo, sto facendo" -> "sto facendo")
            val duplicateTwoWordsRegex = Regex("""(?<!\p{L})(\p{L}+[ \t,]+\p{L}+)[ \t,]+\1(?!\p{L})""", RegexOption.IGNORE_CASE)
            var twoWordsIter = 0
            while (duplicateTwoWordsRegex.containsMatchIn(clean) && twoWordsIter < 10) {
                val updated = clean.replace(duplicateTwoWordsRegex) { match -> match.groupValues[1] }
                if (updated == clean) break
                clean = updated
                twoWordsIter++
            }

            // 7. Normalizzazione spaziature tipografiche
            // Elimina spazi prima di virgole, punti, due punti, punti e virgola, interrogativi ed esclamativi
            clean = clean.replace(Regex("""\s+([,.:;!?])"""), "$1")

            // Rimuove spazi interni alle virgolette: « ciao » -> «ciao», " test " -> "test"
            clean = clean.replace(Regex("""([«"“])\s+"""), "$1")
            clean = clean.replace(Regex("""\s+([»"”])"""), "$1")

            // Normalizza spazi orizzontali multipli (preservando i ritorni a capo \n)
            clean = clean.replace(Regex("""[^\S\r\n]{2,}"""), " ")

            // 7. Forza MAIUSCOLA dopo ogni punto (. ! ?) anche in presenza di virgolette o parentesi
            // \s+ (non \s*): senza spazio il punto è parte di un dominio o di un'email (gmail.com, www.sito.it)
            clean = clean.replace(Regex("""([.!?]\s+["'«“‘(\[]?\s*)([\p{Ll}])""")) { match ->
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
