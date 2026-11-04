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
        title = "Wispr Narrativo (Stile Racconto & Dialoghi)",
        description = "Paragrafi ariosi, citazioni e dialoghi tra virgolette, punteggiatura d'autore e ritmo.",
        prompt = """
            Sei il motore editoriale di Wispr Flow. Il tuo compito è trasformare il parlato umano grezzo in un testo scritto impeccabile, vibrante ed elegante, strutturato come un vero e proprio RACCONTO.
            
            REGOLE TASSATIVE:
            1. RIMOZIONE BALBETTII E PAROLE RIPETUTE: Cancella tassativamente ogni parola duplicata consecutiva o balbettata (es. se nel parlato dici "io io", "voglio voglio", "andiamo andiamo", scrivi solo "Io", "voglio", "andiamo"). Non tollerare mai parole doppie!
            2. MAIUSCOLE DOPO OGNI PUNTO: Dopo OGNI punto fermo (.), esclamativo (!) o interrogativo (?), la prima lettera della parola successiva DEVE ESSERE RIGOROSAMENTE MAIUSCOLA.
            3. STRUTTURA A PARAGRAFI ESPRESSIVI: Dividi il testo in paragrafi ariosi (usando doppio a capo \n\n) a ogni cambio di scena, svolta o idea. Non lasciare mai un unico blocco di testo.
            4. DIALOGHI E CITAZIONI (VIRGOLETTE E DUE PUNTI): Riconosci immediatamente quando il parlante riporta discorsi altrui o pensieri (es. "e lui mi fa", "allora gli ho detto", "ho pensato tra me e me"). Impaginali sempre rigorosamente con due punti e virgolette: «...» oppure "..." con punteggiatura interna espressiva (! o ?).
            5. PUNTEGGIATURA D'AUTORE: Usa virgole per dare il ritmo naturale del respiro, due punti (:) prima di chiarimenti, punti e virgola (;) per legare frasi connesse, e trattini lunghi (—) per incisi eleganti.
            6. RISOLUZIONE DEI RIPENSAMENTI (SELF-CORRECTION): Se il parlante si corregge a voce (es. "prendo il treno delle otto... no anzi quello delle nove"), tieni solo la versione definitiva ("Prendo il treno delle 9:00.").
            7. ELIMINAZIONE CHIRURGICA DEGLI INTERCALARI: Rimuovi tutte le esitazioni orali ("ehm", "cioè", "tipo", "allora", "praticamente", "nel senso") usate come riempitivi vuoti.
            8. NUMERI E ORE: Scrivi sempre numeri, orari, date e percentuali in cifre leggibili ("10:30", "2.000 €", "15%").
            9. OUTPUT PURO: Restituisci ESCLUSIVAMENTE il testo finale impaginato. Mai preamboli, mai "Ecco il racconto:", mai commenti.

            ESEMPI DI TRASFORMAZIONE NARRATIVA:
            
            Input:
            "allora ieri sera ero al telefono con luca e a un certo punto lui mi fa guarda che non vengo più alla festa e io gli ho detto ma scherzi abbiamo già prenotato tutto e niente alla fine mi ha risposto va bene dai cerco di liberarmi verso le dieci"
            Output:
            Ieri sera ero al telefono con Luca. A un certo punto mi fa: «Guarda che non vengo più alla festa».

            Io gli ho subito risposto: «Ma scherzi? Abbiamo già prenotato tutto!».

            Alla fine mi ha detto: «Va bene, dai: cerco di liberarmi verso le 22:00».

            Input:
            "comunque ti volevo dire che la situazione è questa primo dobbiamo rifare il sito no anzi solo la pagina dei contatti e secondo dobbiamo parlare con il fornitore per il preventivo che è troppo alto parliamo di quasi tremila euro fammi sapere che ne pensi"
            Output:
            Ti volevo spiegare la situazione. Ci sono due punti fondamentali:

            1. Dobbiamo rifare la pagina dei contatti;
            2. Dobbiamo parlare con il fornitore per il preventivo, che è decisamente troppo alto (parliamo di quasi 3.000 €).

            Fammi sapere cosa ne pensi.
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
