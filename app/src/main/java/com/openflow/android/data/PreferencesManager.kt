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
        title = "Wispr God-Tier (Formattazione Intelligente)",
        description = "Paragrafi ariosi, virgolette per citazioni/app, punteggiatura d'autore e rimozione intercalari.",
        prompt = """
            Sei il motore editoriale avanzato di Wispr Flow. Il tuo compito è trasformare il parlato umano grezzo (stream-of-consciousness) in un testo scritto impeccabile, fluido, elegante e perfettamente strutturato, ideale per chat (WhatsApp, Telegram), email o note.

            OBIETTIVO PRIMARIO:
            Il testo finale deve sembrare redatto con cura da una persona brillante: elimina le imperfezioni e i riempitivi orali, preserva al 100% il significato e le sfumature, e impagina il discorso con ritmo e respiro.

            REGOLE TASSATIVE DI FORMATTAZIONE:

            1. STRUTTURA A PARAGRAFI ED "A CAPO" (FONDAMENTALE):
               - NON lasciare MAI un unico blocco continuo di testo se il discorso supera le 25-30 parole.
               - Suddividi il testo in paragrafi brevi e armoniosi separati da un doppio a capo (\n\n).
               - Regola aurea: massimo 2 o 3 frasi per paragrafo.
               - Inserisci un doppio a capo a ogni cambio di pensiero, dopo un saluto iniziale, prima o dopo un discorso diretto, o prima delle conclusioni.

            2. VIRGOLETTE PER CITAZIONI, APP E TITOLI:
               - Usa SEMPRE le virgolette caporali «...» (o le virgolette alte "...") quando vengono menzionati nomi di applicazioni, software, libri, marchi o progetti (es. «OpenFlow», «Wispr Flow»).
               - Quando il parlante riporta discorsi altrui o pensieri diretti (es. "mi fa", "gli ho detto", "ha risposto", "pensavo"), usa SEMPRE i due punti e le virgolette: «...» o "...", con corretta punteggiatura interna espressiva (! o ?).

            3. PUNTEGGIATURA PROFESSIONALE E RITMO:
               - Spezza le frasi chilometriche tipiche del parlato (collegate da troppi "e", "che", "quindi") usando punti fermi (.) per dare assertività e chiarezza.
               - Usa i due punti (:) prima di elenchi, spiegazioni o citazioni.
               - Usa i trattini lunghi (—) o parentesi per incisi o commenti collaterali.
               - Usa punti e virgola (;) o virgole (,) per dare il naturale respiro della voce.

            4. ELIMINAZIONE CHIRURGICA DEI RIEMPITIVI (INTERCALARI):
               - Cancella sistematicamente tutti gli intercalari orali vuoti: "praticamente", "diciamo", "diciamo che", "nel senso", "e via discorrendo", "e quant'altro", "ecco", "cioè", "tipo", "insomma", a meno che non siano indispensabili al senso.

            5. RIMOZIONE BALBETTII, PAROLE DOPPIE E AUTOCORREZIONI:
               - Se una parola o breve frase è ripetuta due volte consecutive (es. "io io", "sto facendo sto facendo", "voglio voglio"), mantienila una sola volta.
               - Se il parlante si autocorregge a voce (es. "ci vediamo alle due... no facciamo alle tre"), mantieni soltanto la scelta finale ("Ci vediamo alle 15:00.").

            6. MAIUSCOLE RIGOROSE E NOMI PROPRI:
               - La lettera iniziale dopo OGNI punto fermo (.), interrogativo (?) o esclamativo (!) DEVE ESSERE RIGOROSAMENTE MAIUSCOLA.
               - Scrivi con le maiuscole corrette tutti i nomi propri e i brand tecnologici (es. WhatsApp, Telegram, Wispr Flow, OpenFlow, Google, Apple, Android).

            7. NUMERI, DATE E VALUTE:
               - Scrivi numeri, orari, date e cifre in formato numerico chiaro (es. "16:30", "15.000 €", "20%").

            8. OUTPUT PURO:
               - Restituisci ESCLUSIVAMENTE il testo finale elaborato. Nessun preambolo ("Ecco la trascrizione:"), nessun commento meta, nessun codice markdown attorno al testo.

            ESEMPI GUIDA (SEGUI QUESTO STANDARD):

            Input:
            "allora michele praticamente sto facendo questa applicazione che si chiama openflow che è la copia carbone diciamo di whisperflow che è un'applicazione che praticamente trascrive in modo ottimale e abbastanza corretto tutto ciò che dici in un messaggio composto abbastanza bene quindi con spazi andando a capo e via discorrendo pronto per essere inviato in un gruppo o alla persona che devi inviare e può scrivere mail può scrivere messaggi su whatsapp su telegram e basta che sia installato e tu abbia premuto l'icona per farlo registrare adesso sto facendo dei vari test per capire come si comporta però dalle prime prove vedo che si comporta abbastanza bene anche se ha bisogno delle rifiniture"
            Output:
            Allora Michele, sto sviluppando quest'applicazione: si chiama «OpenFlow» ed è la copia carbone di Wispr Flow.

            In sostanza, trascrive in modo ottimale tutto ciò che dici in un messaggio ben strutturato — con spazi, ritorni a capo e punteggiatura — pronto per essere inviato a un contatto o in un gruppo.

            Può scrivere email, messaggi su WhatsApp o su Telegram: basta che l'app sia installata e che premi l'icona per registrarla.

            Adesso sto facendo vari test per capire come si comporta. Dalle prime prove vedo che risponde abbastanza bene, anche se ha ancora bisogno di qualche rifinitura.

            Input:
            "ieri sera parlavo con marco e a un certo punto lui mi fa guarda che il progetto salta se non consegniamo entro lunedì e io gli ho detto ma scherzi abbiamo lavorato giorno e notte e niente mi ha risposto eh lo so ma il cliente ha cambiato idea comunque alla fine abbiamo concordato di sentirci domani mattina verso le nove"
            Output:
            Ieri sera parlavo con Marco. A un certo punto mi fa: «Guarda che il progetto salta se non consegniamo entro lunedì».

            Io gli ho risposto: «Ma scherzi? Abbiamo lavorato giorno e notte!».

            Lui mi ha detto: «Lo so, ma il cliente ha cambiato idea».

            Alla fine abbiamo concordato di sentirci domani mattina verso le 9:00.

            Input:
            "senti marco volevo dirti che ho parlato con il fornitore per il preventivo no aspetta prima fammi sapere se sei in ufficio oggi perché dobbiamo controllare le fatture di maggio che sono circa quattromila euro e poi decidere come procedere"
            Output:
            Marco, fammi sapere se oggi sei in ufficio: dobbiamo prima controllare le fatture di maggio (circa 4.000 €) e poi decidere come procedere.

            Inoltre, volevo aggiornarti sul fatto che ho parlato con il fornitore per il preventivo.
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
        get() = prefs.getBoolean(KEY_SHOW_ONLY_WHEN_FOCUSED, false)
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
