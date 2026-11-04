package com.openflow.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

enum class ApiProvider(val displayName: String, val baseUrl: String, val asrModel: String, val llmModel: String) {
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        asrModel = "whisper-1",
        llmModel = "gpt-4o-mini"
    ),
    GROQ(
        displayName = "Groq (Ultra-Veloce ~250ms)",
        baseUrl = "https://api.groq.com/openai/v1",
        asrModel = "whisper-large-v3-turbo",
        llmModel = "llama-3.3-70b-versatile"
    )
}

enum class PromptPreset(val title: String, val description: String, val prompt: String) {
    NATURAL(
        title = "Naturale & Fluido (Wispr Standard)",
        description = "Rimuove intercalari (ehm, cioè), autocorrezioni e sistema la punteggiatura.",
        prompt = """
            Sei l'assistente di dettatura intelligente OpenFlow. Il tuo compito è trasformare il testo trascritto dal parlato in un testo scritto perfetto, naturale e fluido.
            Regole tassative:
            1. Rimuovi tutti gli intercalari, esitazioni e ripetizioni (es: "ehm", "uh", "cioè", "tipo", "allora", "praticamente").
            2. Risolvi le autocorrezioni del parlante: se dice "ci vediamo alle 3, no anzi alle 4", scrivi solo "Ci vediamo alle 4.".
            3. Applica corretta punteggiatura, maiuscole e spaziatura.
            4. Mantieni la lingua originale usata dall'utente.
            5. NON aggiungere commenti, risposte o spiegazioni: restituisci ESCLUSIVAMENTE il testo finale pulito.
        """.trimIndent()
    ),
    PROFESSIONAL(
        title = "Professionale / Email",
        description = "Tono formale, curato e pronto per email o messaggi di lavoro.",
        prompt = """
            Sei un assistente di scrittura professionale per email e comunicazioni aziendali.
            Trasforma la trascrizione vocale in un messaggio chiaro, cortese, ben strutturato e con registro formale ma moderno.
            Rimuovi intercalari, correggi refusi e struttura in paragrafi puliti se opportuno.
            Restituisci ESCLUSIVAMENTE il testo finale da inviare.
        """.trimIndent()
    ),
    BULLETS(
        title = "Appunti & Elenchi Puntati",
        description = "Organizza il parlato in punti chiave chiari e leggibili.",
        prompt = """
            Riorganizza il discorso trascritto in un elenco puntato chiaro, conciso e ben leggibile (bullet points).
            Estrai i punti salienti eliminando ridondanze e intercalari.
            Restituisci ESCLUSIVAMENTE l'elenco finale.
        """.trimIndent()
    ),
    AI_PROMPT(
        title = "Prompt per LLM",
        description = "Ottimizza la dettatura per trasformarla in un prompt efficace per ChatGPT/Claude.",
        prompt = """
            Riformula il pensiero dettato in una richiesta (prompt) chiara, esplicita, strutturata ed efficace per un modello di intelligenza artificiale.
            Restituisci ESCLUSIVAMENTE il prompt risultante senza prefazioni.
        """.trimIndent()
    )
}

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "openflow_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // shortcut: fallback a SharedPreferences standard se Keystore hardware fallisce su alcune custom ROM
        context.getSharedPreferences("openflow_fallback_prefs", Context.MODE_PRIVATE)
    }

    var openAiKey: String
        get() = prefs.getString(KEY_OPENAI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_KEY, value.trim()).apply()

    var groqKey: String
        get() = prefs.getString(KEY_GROQ_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ_KEY, value.trim()).apply()

    var activeProvider: ApiProvider
        get() {
            val name = prefs.getString(KEY_ACTIVE_PROVIDER, ApiProvider.OPENAI.name)
            return try { ApiProvider.valueOf(name!!) } catch (_: Exception) { ApiProvider.OPENAI }
        }
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROVIDER, value.name).apply()

    var activePreset: PromptPreset
        get() {
            val name = prefs.getString(KEY_ACTIVE_PRESET, PromptPreset.NATURAL.name)
            return try { PromptPreset.valueOf(name!!) } catch (_: Exception) { PromptPreset.NATURAL }
        }
        set(value) = prefs.edit().putString(KEY_ACTIVE_PRESET, value.name).apply()

    var showOnlyWhenFocused: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED, value).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

    val currentApiKey: String
        get() = when (activeProvider) {
            ApiProvider.OPENAI -> openAiKey
            ApiProvider.GROQ -> groqKey
        }

    fun isConfigured(): Boolean = currentApiKey.isNotBlank()

    companion object {
        private const val KEY_OPENAI_KEY = "key_openai_token"
        private const val KEY_GROQ_KEY = "key_groq_token"
        private const val KEY_ACTIVE_PROVIDER = "key_active_provider"
        private const val KEY_ACTIVE_PRESET = "key_active_preset"
        private const val KEY_SHOW_ONLY_WHEN_FOCUSED = "key_show_focused"
        private const val KEY_HAPTIC_ENABLED = "key_haptic_feedback"
    }
}
