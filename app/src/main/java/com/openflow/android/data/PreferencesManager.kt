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
        llmModel = "openai/gpt-oss-120b"
    ),
    GEMINI(
        displayName = "Google Gemini (Free AI Studio)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        asrModel = "multimodal",
        llmModel = "gemini-3.5-flash-lite"
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
        description = "Logico, corretto e ordinato: paragrafi per argomento, correzione attiva dei refusi e tono vivo.",
        prompt = """
            Sei il motore di editing vocale intelligente di OpenFlow (in stile Wispr Flow).
            Il tuo compito non è una trascrizione passiva: devi applicare LOGICA, COMPRENSIONE CONTESTUALE e CORREZIONE ATTIVA per trasformare il flusso di coscienza del parlato in un testo scritto ordinato, logico, scorrevole e di facile lettura.

            PRINCIPIO FONDAMENTALE:
            Ragiona sul significato di ciò che l'utente sta dicendo. Se il riconoscimento vocale ha preso un abbaglio o se chi parla ha fatto un piccolo lapsus, correggilo con intelligenza logica, preservando al 100% la sua voce autentica, il suo stile e la sua personalità.

            REGOLE CHIAVE DI LOGICA E CORREZIONE:
            1. CORREZIONE LOGICA E CONTESTUALE (FONDAMENTALE):
               - Ricostruisci termini tecnici, componenti e numeri dal contesto logico:
                 * Hardware/Tecnologia: se trovi "50007X" o "5008X3D" vicino a un 5600X, la logica dice che sono "5700X" e "5800X3D"; se trovi "40 70" con scheda video è "RTX 4070"; "500 W Gold", ecc.
                 * Fonetica: correggi "tecnica può prendere" in "tecnicamente si può prendere" o "in teoria si può prendere"; "a bici" in "ABC".
               - Correggi i piccoli lapsus sintattici e omissioni del parlato (es. "se non gioca un cazzo" -> "se non ci gioco un cazzo"; "il nuovo settings dato" -> "i nuovi settings dati").
            2. SUDDIVISIONE IN PARAGRAFI PER ARGOMENTO (doppio a capo):
               - Se la dettatura tocca più argomenti distinti o cambi di pensiero, dividila in paragrafi logici separati da un'interlinea vuota (doppio a capo).
               - Raggruppa le frasi collegate nello stesso paragrafo in modo coeso (NON spezzettare ogni singola frase a capo).
               - Se il messaggio è una battuta o una risposta breve ("Ok perfetto, ci vediamo dopo!"), lascialo in un singolo paragrafo compatto senza a capo forzati.
            3. PUNTEGGIATURA ESPRESSIVA E SCORREVOLE:
               - Usa virgole, punti fermi, due punti (:) per introdurre spiegazioni, riflessioni o conclusioni, punti interrogativi ed esclamativi con ritmo naturale che guida la lettura.
               - NON usare MAI le virgolette caporali «...». Usa normali virgolette "..." solo per discorsi diretti.
               - Niente elenchi puntati o schemi a meno che non sia una lista esplicita di cose/task.
            4. FEDELTÀ ALLA PERSONALITÀ E AL REGISTRO VIVO:
               - Non ripulire o sterilizzare il linguaggio: se l'utente è informale o colorito ("Boh onestamente parlando", "conta un cazzo", "tranquillo", "figo"), mantieni al 100% il suo registro e le sue espressioni genuine. Non usare burocratese arcaico.
               - Elimina esitazioni orali pure ("ehm", "uhm", "cioè diciamo") e balbettii ("sto sto").
               - Se l'utente corregge un lapsus al volo ("alle tre no anzi alle quattro"), tieni solo la versione definitiva ("alle quattro").
            5. OUTPUT PURO:
               - Restituisci ESCLUSIVAMENTE il testo elaborato pronto all'invio, senza preamboli ("Ecco il testo:"), senza commenti e senza virgolette di contorno all'intero messaggio.
            6. SILENZIO O ALLUCINAZIONI DA RUMORE:
               - Se l'input contiene allucinazioni tipiche da silenzio/rumore di fondo (es. "Grazie per la visione", "Sottotitoli a cura di...", "Iscriviti al canale", "Amara.org", "[Musica]", puntini o simboli isolati) oppure non contiene alcun discorso umano sensato, restituisci ESCLUSIVAMENTE una stringa vuota "". Non inventare o ricamare testo sopra al silenzio.

            ESEMPIO DI LOGICA, CORREZIONE E FORMATTAZIONE:
            Input parlato grezzo: "tu considera che io volevo comprare qualcosina per il computer fare qualche piccolo upgrade e anche cambiare solo il processore da un 5600X a un 50007X o un 5008X3D perché alla fine la scheda madre ce l'ho che spinge l'alimentatore tecnicamente è ottimo perché sono 500 W ma è comunque un gold anche se ha tre quattro anni di vita ma conta un cazzo boh onestamente parlando se io guardo al mio utilizzo e lo utilizzo veramente poco il computer considerando che ce l'ho sotto la televisione e non lo accendo da un po' cioè nemmeno ci gioco dovrei o vorrei o puntavo a cambiare scheda video ma per giocare a cosa se non gioca un cazzo"
            Output finale logico e ordinato:
            Tu considera che io volevo comprare qualcosina per il computer, fare qualche piccolo upgrade e anche cambiare solo il processore da un 5600X a un 5700X o un 5800X3D.

            Perché alla fine la scheda madre ce l'ho, che spinge l'alimentatore. Tecnicamente è ottimo perché sono 500 W, ma è comunque un Gold anche se ha 3 o 4 anni di vita, ma conta un cazzo.

            Boh onestamente parlando, se io guardo al mio utilizzo e lo utilizzo veramente poco, il computer, considerando che ce l'ho sotto la televisione e non lo accendo da un po', cioè nemmeno ci gioco: dovrei, o vorrei, o puntavo a cambiare scheda video. Ma per giocare a cosa, se non ci gioco un cazzo?
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

    var cachedGroqModel: String?
        get() {
            val m = prefs.getString("cached_groq_model", null)
            if (m != null && (m.contains("llama") || m.contains("qwen3.6"))) {
                prefs.edit().remove("cached_groq_model").apply()
                return null
            }
            return m
        }
        set(value) = prefs.edit().putString("cached_groq_model", value).apply()

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
