# OpenFlow Android — Wispr Flow Alternative (Open Source & BYOK)

Un'applicazione Android nativa che replica l'esperienza di **Wispr Flow** senza costi di abbonamento.
Usa la tua chiave API (OpenAI o Groq) pagando solo per l'effettivo consumo (pochi centesimi al mese anziché 15€/mese).

## 🚀 Caratteristiche Principali

- **Floating Bubble a Scomparsa**: Appare automaticamente non appena tocchi un campo di testo in qualsiasi app (WhatsApp, Telegram, Gmail, Slack, browser, ecc.) grazie all'Accessibility Service.
- **Interazione Vocale Ibrida**:
  - *Tap-to-start / Tap-to-stop*: tocca per iniziare e tocca per terminare (comodo per lunghi dettati).
  - *Push-to-Talk*: tieni premuto per parlare, rilascia per trascrivere e inserire istantaneamente.
- **Waveform Dinamica Reattiva**: Stile Wispr Dark (Nero OLED, bordi al neon violetto, 5 barre audio reattive alla voce).
- **Pipeline IA a Due Stadi**:
  1. **ASR (Speech-to-Text)**: OpenAI Whisper oppure Groq Whisper (~200ms di latenza!).
  2. **LLM Polishing**: GPT-4o-mini o Llama 3.3 per eliminare intercalari (*ehm, cioè, tipo*), formattare punteggiatura e correggere ripensamenti vocali al volo.
- **Iniezione Diretta**: Inserisce il testo direttamente nel campo tramite `AccessibilityNodeInfo.ACTION_SET_TEXT` (con fallback su clipboard + incolla).
- **Sicurezza Hardware**: Chiavi API conservate nell'hardware Keystore del dispositivo tramite `EncryptedSharedPreferences`. Nessun server proxy esterno.

## 🛠 Come Configurare ed Eseguire

1. Apri la cartella `open-flow-android` in **Android Studio**.
2. Connetti il tuo smartphone Android (o emulatore) con Debug USB abilitato.
3. Esegui la build (`Run 'app'`).
4. All'avvio dell'app:
   - Concedi i 3 permessi: **Microfono**, **Disegna su altre app (Overlay)** e **Accessibilità (OpenFlow)**.
   - Scegli tra **Groq** (consigliato per velocità ultra-rapida) o **OpenAI** e incolla la tua chiave API.
   - Scegli lo stile di dettatura (Naturale, Professionale, Punti Elenco o Prompt IA).
5. Tocca un qualsiasi campo di testo su WhatsApp o qualsiasi altra app: apparirà la bollicina pronta per dettare!
