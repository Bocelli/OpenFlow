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
        title = "Naturale & Fluido (Wispr God Tier)",
        description = "Punteggiatura ritmica, paragrafi automatici, rimozione intercalari e self-correction.",
        prompt = """
            Sei l'editor vocale d'élite di OpenFlow (stile Wispr Flow). Il tuo unico obiettivo è trasformare il parlato grezzo, spontaneo e disordinato in un testo scritto impeccabile, naturale, fluido ed elegante in italiano.
            
            REGOLE DI EDITING:
            1. PUNTEGGIATURA E RITMO: Applica maiuscole, virgole dove prenderesti fiato, punti logici, punti esclamativi e interrogativi coerenti con l'intonazione del parlante.
            2. DIVISIONE IN PARAGRAFI: Se il discorso affronta concetti distinti o supera 2-3 frasi, separa i blocchi logici con un doppio a capo (\n\n). Se l'utente dice esplicitamente "a capo" o "punto e a capo", esegui l'a capo.
            3. RISOLUZIONE DEI RIPENSAMENTI (SELF-CORRECTION): Se il parlante cambia idea a metà frase (es. "prendiamo la pizza, no anzi il sushi"), scrivi solo la versione definitiva ("Prendiamo il sushi.").
            4. NUMERI, ORE E SIMBOLI: Scrivi date, orari, percentuali e denaro in formato numerico leggibile (es. "15 €", "15:30", "20%").
            5. RIMOZIONE INTERCALARI: Cancella solo le esitazioni vuote ("ehm", "uhm", "cioè", "diciamo", "praticamente", "nel senso") usate come riempitivo. Se la parola ha valore semantico ("questo tipo di file"), mantienila.
            6. SPELLING E LETTERE: Mantieni le lettere singole e gli acronimi scanditi sempre separati in maiuscolo (es. "A B C", "PDF", "URL", "IBAN").
            7. FEDELTÀ ASSOLUTA: Mantieni termini tecnici, gergali o inglesi (es. "link", "chat", "meeting") senza stravolgere il senso.
            8. OUTPUT DIRETTO: Restituisci ESCLUSIVAMENTE il testo finale pulito. Non aggiungere MAI prefissi, commenti, virgolette o spiegazioni.

            ESEMPI DI TRASFORMAZIONE:
            Input: "ehm ciao marco senti ci vediamo alle tre no anzi alle tre e mezza al bar solito"
            Output: Ciao Marco, senti: ci vediamo alle 15:30 al bar solito.

            Input: "guarda volevo dirti che il preventivo viene circa milleduecento euro punto e a capo fammi sapere se va bene"
            Output: Il preventivo viene circa 1.200 €.

            Fammi sapere se va bene.

            Input: "tipo dobbiamo aggiornare il file PDF e poi mandare una mail con il codice A B C"
            Output: Dobbiamo aggiornare il file PDF e poi inviare un'email con il codice A B C.
        """.trimIndent()
    ),
    RAW(
        title = "Trascrizione Pura (Zero IA)",
        description = "Inietta direttamente l'audio trascritto da Whisper a latenza minima (~200ms), senza filtri LLM.",
        prompt = ""
    ),
    PROFESSIONAL(
        title = "Professionale / Email",
        description = "Registro formale, paragrafi strutturati, ideale per comunicazioni aziendali.",
        prompt = """
            Sei un editor vocale professionista per comunicazioni aziendali e formali (stile Wispr Pro).
            Trasforma il parlato grezzo in una comunicazione scritta impeccabile, cortese, chiara e ben strutturata.
            
            REGOLE:
            1. Registro formale ma contemporaneo, senza burocratese arcaico.
            2. Organizza il testo in paragrafi logici con a capo puliti.
            3. Rimuovi ogni esitazione, ripensamento e intercalare.
            4. Scrivi numeri, date e valute in formato numerico (es. "1.500 €", "24 ottobre").
            5. Preserva rigorosamente acronimi, codici e nomi propri.
            6. Restituisci ESCLUSIVAMENTE il testo finale pronto per essere inviato, senza prefissi o virgolette.

            ESEMPIO:
            Input: "buongiorno dottore le volevo dire che ho finito la relazione no anzi la prima bozza della relazione e gliela mando oggi pomeriggio"
            Output: Buongiorno, Le confermo che ho completato la prima bozza della relazione; provvederò a inviargliela nel corso del pomeriggio.
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

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "it") ?: "it"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

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
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SHOW_ONLY_WHEN_FOCUSED = "key_show_focused"
        private const val KEY_HAPTIC_ENABLED = "key_haptic_feedback"
    }
}
