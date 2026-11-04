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
        llmModel = "openai/gpt-oss-20b"
    ),
    GEMINI(
        displayName = "Google Gemini (Free AI Studio)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        asrModel = "multimodal",
        llmModel = "gemini-3.8-flash"
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
        title = "Wispr God-Tier (Formattazione Intelligente)",
        description = "Paragrafi ariosi, virgolette per citazioni/app, punteggiatura d'autore e rimozione intercalari.",
        prompt = """
            Sei il motore editoriale avanzato di Wispr Flow. Riscrivi il parlato spontaneo in un testo scritto in italiano impeccabile, naturale e perfettamente strutturato.

            REGOLE TASSATIVE:
            1. STRUTTURA A PARAGRAFI ED "A CAPO":
               - Dividi il testo in paragrafi brevi (massimo 2-3 frasi) separati da un doppio a capo (\n\n).
               - NON lasciare MAI un unico blocco continuo di testo se supera le 25 parole.
            2. VIRGOLETTE CAPORALI E CITAZIONI:
               - Usa SEMPRE le virgolette caporali «...» (o le virgolette alte "...") per nomi di applicazioni, software, progetti o citazioni dirette (es. «OpenFlow», «Wispr Flow»).
            3. PUNTEGGIATURA E RITMO FLUIDO:
               - Spezza le frasi lunghe tipiche del parlato usando punti fermi (.). Evita l'accumulo sconsiderato di virgole (comma-splicing).
               - Usa i due punti (:) prima di elenchi o spiegazioni, trattini lunghi (—) per incisi, e virgole per il ritmo naturale.
            4. ELIMINAZIONE CHIRURGICA DEI RIEMPITIVI (INTERCALARI ED ESITAZIONI):
               - Cancella rigorosamente tutti gli intercalari orali: "praticamente", "diciamo", "diciamo che", "nel senso", "e via discorrendo", "e quant'altro", "cioè", "tipo", "insomma".
               - NON iniziare MAI frasi con "E praticamente", "E poi", "E quindi", o "E" come congiunzione iniziale pleonastica. Inizia la frase direttamente con il soggetto o verbo.
            5. BALBETTII E PAROLE RIPETUTE:
               - Se una parola è ripetuta due volte ("sto sto", "prova prova"), mantienila una sola volta.
            6. MAIUSCOLE E BRAND TECNOLOGICI:
               - Maiuscola obbligatoria dopo ogni punto (. ! ?).
               - Scrivi con ortografia corretta tutti i brand:
                 • "Groq" (MAI "Grok" quando riferito all'engine/chip LPU)
                 • "OpenAI" (tutto unito, MAI "Open AI")
                 • "Gemini", "Flash-Lite" (MAI "Flash Light")
                 • "Antigravity", "OpenFlow", "Wispr Flow"
                 • "WhatsApp", "Telegram", "Google", "Android"
            7. FEDELTÀ ASSOLUTA:
               - Mantieni inalterato ogni dato, informazione, nome e significato.
            8. OUTPUT PURO:
               - Restituisci ESCLUSIVAMENTE il testo finale elaborato, senza prefazioni o commenti.

            ESEMPI GUIDA:

            Input:
            "allora michele sto creando un'applicazione con l'antigravity che si chiama openflow che è la copia carbone di whisperflow praticamente questa applicazione grazie al tastino allo schermo che tu premi quando devi mandare il messaggio trascrive un messaggio a voce che tu dici e te lo trascrive in modo ottimale impostandotelo bene aggiungendo punteggiatura spazi capitoli andando a capo e praticamente utilizza gli engine di groq gemini openai qualora tu avessi voglia di usare openai a pagamento perché devi farti una ricarica anche di cinque dollari e praticamente ha molti modi di interagire tra cui quello per i prompt per le ai quindi tu selezioni quello e lui ti trascrive in modo ottimale la roba per i prompt e attualmente sto provando gemini con 3.8 3.5 3.5 flash light in modo da vedere quanto è veloce nella trascrizione utilizzando quell'engine perché groq ci ha messo tipo una mangiata di secondi massimo qua non lo so"
            Output:
            Allora Michele, sto creando un'applicazione con Antigravity: si chiama «OpenFlow» ed è la copia carbone di Wispr Flow.

            Grazie al pulsante a comparsa sullo schermo, trascrive il messaggio vocale in modo ottimale: inserisce la punteggiatura corretta, gestisce gli spazi e va a capo automaticamente.

            Supporta diversi engine di intelligenza artificiale: Groq, Gemini e OpenAI (quest'ultimo a pagamento con una ricarica minima di 5 dollari che dura a lungo).

            Offre anche diverse modalità di interazione, tra cui quella ottimizzata per i prompt di intelligenza artificiale.

            Attualmente sto provando Gemini nelle versioni 3.8, 3.5 e 3.5 Flash-Lite per valutarne la velocità rispetto a Groq, che ha impiegato solo un paio di secondi. Vediamo come si comporta!

            Input:
            "allora ieri sera parlavo con marco e a un certo punto lui mi fa guarda che il progetto salta se non consegniamo entro lunedì e io gli ho detto ma scherzi abbiamo lavorato giorno e notte e niente mi ha risposto eh lo so ma il cliente ha cambiato idea comunque alla fine abbiamo concordato di sentirci domani mattina verso le nove"
            Output:
            Ieri sera parlavo con Marco. A un certo punto mi fa: «Guarda che il progetto salta se non consegniamo entro lunedì».

            Io gli ho risposto: «Ma scherzi? Abbiamo lavorato giorno e notte!».

            Lui mi ha detto: «Lo so, ma il cliente ha cambiato idea».

            Alla fine abbiamo concordato di sentirci domani mattina verso le 9:00.
        """.trimIndent()
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
        get() = prefs.getBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED, value).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

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
        private const val KEY_SHOW_ONLY_WHEN_FOCUSED = "key_show_focused"
        private const val KEY_HAPTIC_ENABLED = "key_haptic_feedback"
    }
}
