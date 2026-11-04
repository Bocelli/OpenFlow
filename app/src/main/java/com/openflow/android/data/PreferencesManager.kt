package com.openflow.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

enum class ApiProvider(val displayName: String, val baseUrl: String, val asrModel: String, val llmModel: String) {
    GROQ(
        displayName = "Groq (Free LPU ~200ms)",
        baseUrl = "https://api.groq.com/openai/v1",
        asrModel = "whisper-large-v3-turbo",
        llmModel = "llama-3.3-70b-versatile"
    ),
    GEMINI(
        displayName = "Google Gemini (Free AI Studio)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        asrModel = "multimodal",
        llmModel = "gemini-2.5-flash"
    ),
    OPENAI(
        displayName = "OpenAI (Wispr Flow Nativo)",
        baseUrl = "https://api.openai.com/v1",
        asrModel = "whisper-1",
        llmModel = "gpt-4o-mini"
    )
}

enum class PromptPreset(val title: String, val description: String, val prompt: String) {
    NATURAL(
        title = "Naturale (Spontaneo per Chat)",
        description = "Fluido e pronto all'invio: corregge refusi vocali, accordi e punteggiatura naturale.",
        prompt = """
            Sei il motore di editing vocale intelligente di OpenFlow (in perfetto stile Wispr Flow).
            Il tuo compito è prendere la trascrizione grezza del parlato e renderla un testo naturale, scorrevole e impeccabile, pronto da inviare su WhatsApp, Telegram o chat quotidiane.

            OBIETTIVO FONDAMENTALE:
            Prendi il testo, sistemalo con intelligenza per renderlo scorrevole e pronto all'invio, preservando rigorosamente la voce, il vocabolario e l'intento autentico dell'utente.

            REGOLE CHIAVE:
            1. CORREZIONE ERRORI FONETICI E ACCORDI (FONDAMENTALE):
               - Correggi parole storpiate, troncate o fraintese dal motore vocale basandoti sul contesto logico della frase (es. se trovi "tecnica può prendere" correggilo in "tecnicamente si può prendere" o "in teoria si può prendere"; "a bici" in "ABC").
               - Risolvi accordi grammaticali imprecisi tipici del parlato veloce (es. "il nuovo settings dato" -> "i nuovi settings dati" o "il nuovo setting dato").
            2. FLUIDITÀ E NATURALEZZA (STILE WHATSAPP / CHAT):
               - La frase deve scorrere in modo fresco, spontaneo e naturale. Niente rigidità, niente burocratese, niente toni da saggio accademico.
               - Se l'audio è un messaggio breve o una battuta ("Ok perfetto, ci vediamo dopo!"), lascialo breve e diretto. Non aggiungere parole superflue e non creare paragrafi forzati.
               - NON usare MAI le virgolette caporali «...». Usa virgolette normali "..." solo per discorsi diretti o citazioni.
               - NON usare due punti (:) prima di ogni frase o trattini lunghi (—) per spiegare le cose.
            3. PULIZIA DEL PARLATO SPONTANEO:
               - Rimuovi esitazioni orali pure ("ehm", "uhm", "cioè", "diciamo") e balbettii ("sto sto").
               - Se chi parla corregge un lapsus al volo ("ci vediamo alle tre no anzi alle quattro"), mantieni solo la versione corretta finale ("Ci vediamo alle quattro").
               - Mantieni le espressioni colloquiali autentiche volute dall'utente ("boh", "onestamente", "dai", "tranquillo").
            4. BRAND E TERMINI TECNICI:
               - Scrivi correttamente i nomi propri e tecnologici (Groq, Gemini, WhatsApp, Telegram, OpenAI, OpenFlow, Android, Google, ecc.).
            5. OUTPUT PURO:
               - Restituisci ESCLUSIVAMENTE il testo finale pulito, pronto da inviare, senza commenti, senza preamboli ("Ecco il testo:"), spiegazioni o virgolette all'inizio/fine.
        """.trimIndent()
    ),
    RAW_WHISPER(
        title = "Whisper Diretto (Latenza Zero ~200ms)",
        description = "Trascrizione pura istantanea senza riscrittura IA (massima fedeltà e velocità).",
        prompt = ""
    ),
    PROFESSIONAL(
        title = "Professionale / Email",
        description = "Registro formale, paragrafi strutturati, ideale per comunicazioni aziendali.",
        prompt = """
            Sei un editor vocale professionista per comunicazioni aziendali e formali.
            Trasforma il parlato grezzo in una comunicazione scritta impeccabile, cortese, chiara e ben strutturata.
            
            REGOLE:
            1. Registro formale ma contemporaneo, senza burocratese arcaico.
            2. Organizza il testo in paragrafi logici con a capo puliti.
            3. Rimuovi ogni esitazione, ripensamento e intercalare.
            4. Scrivi numeri, date e valute in formato numerico (es. "1.500 €", "24 ottobre").
            5. Preserva rigorosamente acronimi, codici e nomi propri.
            6. Restituisci ESCLUSIVAMENTE il testo finale pronto per essere inviato, senza prefissi o virgolette.
        """.trimIndent()
    ),
    BULLETS(
        title = "Appunti & Elenchi Puntati",
        description = "Organizza il parlato in punti chiave chiari e leggibili.",
        prompt = """
            Riorganizza il discorso trascritto in un elenco puntato chiaro, conciso e leggibile (bullet points).
            Estrai i punti salienti eliminando ridondanze e intercalari.
            Restituisci ESCLUSIVAMENTE l'elenco finale senza prefazioni.
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

    var geminiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value.trim()).apply()

    var activeProvider: ApiProvider
        get() {
            val name = prefs.getString(KEY_ACTIVE_PROVIDER, ApiProvider.GROQ.name)
            return try { ApiProvider.valueOf(name!!) } catch (_: Exception) { ApiProvider.GROQ }
        }
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROVIDER, value.name).apply()

    var activePreset: PromptPreset
        get() {
            val name = prefs.getString(KEY_ACTIVE_PRESET, PromptPreset.NATURAL.name)
            return try { PromptPreset.valueOf(name!!) } catch (_: Exception) { PromptPreset.NATURAL }
        }
        set(value) = prefs.edit().putString(KEY_ACTIVE_PRESET, value.name).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "it") ?: "it"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var showOnlyWhenFocused: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED_V4, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED_V4, value).apply()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var customVocabulary: String
        get() = prefs.getString(KEY_CUSTOM_VOCABULARY, "Michele, OpenFlow, Wispr Flow, Antigravity") ?: "Michele, OpenFlow, Wispr Flow, Antigravity"
        set(value) = prefs.edit().putString(KEY_CUSTOM_VOCABULARY, value.trim()).apply()

    var stealthModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEALTH_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_STEALTH_MODE, value).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

    var notchOffsetY: Float
        get() = prefs.getFloat(KEY_NOTCH_OFFSET_Y, 0f)
        set(value) = prefs.edit().putFloat(KEY_NOTCH_OFFSET_Y, value).apply()

    var notchOffsetX: Float
        get() = prefs.getFloat(KEY_NOTCH_OFFSET_X, 0f)
        set(value) = prefs.edit().putFloat(KEY_NOTCH_OFFSET_X, value).apply()

    var cachedGeminiModel: String?
        get() = prefs.getString("cached_gemini_model", null)
        set(value) = prefs.edit().putString("cached_gemini_model", value).apply()

    val currentApiKey: String
        get() = when (activeProvider) {
            ApiProvider.OPENAI -> openAiKey
            ApiProvider.GROQ -> groqKey
            ApiProvider.GEMINI -> geminiKey
        }

    fun isConfigured(): Boolean = currentApiKey.isNotBlank()

    companion object {
        private const val KEY_OPENAI_KEY = "key_openai_token"
        private const val KEY_GROQ_KEY = "key_groq_token"
        private const val KEY_GEMINI_KEY = "key_gemini_token"
        private const val KEY_ACTIVE_PROVIDER = "key_active_provider"
        private const val KEY_ACTIVE_PRESET = "key_active_preset"
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SHOW_ONLY_WHEN_FOCUSED_V4 = "key_show_focused_v4"
        private const val KEY_HAPTIC_ENABLED = "key_haptic_feedback"
        private const val KEY_ONBOARDING_DONE = "key_onboarding_completed"
        private const val KEY_CUSTOM_VOCABULARY = "key_custom_vocabulary"
        private const val KEY_STEALTH_MODE = "key_stealth_mode"
        private const val KEY_NOTCH_OFFSET_Y = "key_notch_offset_y"
        private const val KEY_NOTCH_OFFSET_X = "key_notch_offset_x"
    }
}
