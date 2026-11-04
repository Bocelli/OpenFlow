# OpenFlow Android — Wispr Flow Alternative (Open Source & BYOK)

Un'applicazione Android nativa che replica l'esperienza di **Wispr Flow** senza costi di abbonamento.
Usa la tua chiave API (Groq, Google Gemini o OpenAI) pagando solo per l'effettivo consumo (pochi centesimi al mese anziché 15€/mese).

---

## 📦 Download Diretto APK

Scarica direttamente l'APK precompilato pronto all'installazione sul tuo dispositivo Android:

| Versione | File APK | Descrizione | Requisiti |
| :--- | :--- | :--- | :--- |
| **Release C (Consigliata)** | [📥 **Download OpenFlow-Release-C-NotchDocked.apk**](releases/OpenFlow-Release-C-NotchDocked.apk) | **Dynamic Island Universale**: incastonata sul punch-hole della fotocamera con architettura simmetrica a due ali, equalizzatore audio live, diagnostica e dump log integrato. | **Zero Root**, qualsiasi Android (Xiaomi, Samsung, Pixel, OnePlus, ecc.) |
| **Release A (Xiaomi)** | [📥 **Download OpenFlow-Release-A-HyperOS.apk**](releases/OpenFlow-Release-A-HyperOS.apk) | Notifiche native Xiaomi HyperOS Super Island (`miui.focus.param`) + modulo HyperIsland LSPosed. | Xiaomi HyperOS stock o dispositivo con LSPosed |

---

## 🚀 Caratteristiche Principali

- **Dynamic Island Notch-Docked (Release C)**: Ancorata fisicamente al punch-hole della fotocamera con `DisplayCutout`. In riposo è un anello ciano discreto attorno al sensore; in registrazione si espande fluidamente a due ali (equalizzatore vocale a sinistra, timer a destra) mantenendo la fotocamera libera.
- **Interazione Vocale Ibrida**:
  - *Tap-to-start / Tap-to-stop*: tocca per iniziare e tocca per terminare.
  - *Push-to-Talk*: tieni premuto per parlare, rilascia per trascrivere e inserire istantaneamente.
  - *Swipe to cancel*: scorri verso il basso (>48dp) per annullare la registrazione o l'elaborazione.
- **Pipeline IA a Due Stadi**:
  1. **ASR (Speech-to-Text)**: Groq Whisper LPU (~200ms di latenza!), Google Gemini Multimodale, oppure OpenAI Whisper-1.
  2. **LLM Polishing**: Formattazione avanzata dello stile parlato (Wispr God-Tier, Formale/Email, Punti elenco, Prompt IA).
- **Iniezione Diretta**: Inserisce il testo direttamente nel campo tramite `AccessibilityNodeInfo.ACTION_SET_TEXT` (con fallback su clipboard + incolla).
- **Sicurezza & Privacy**:
  - Blocco automatico dell'overlay e soppressione clipboard/cronologia su campi password e app bancarie.
  - Chiavi API crittografate nell'hardware Keystore del dispositivo (`EncryptedSharedPreferences`).
- **Diagnostica & Dump Log**: Menù dedicato per estrarre con un tocco il report completo hardware/OS/logcat e condividerlo via Telegram/WhatsApp senza esporre credenziali.

## 🛠 Installazione Rapida

1. Scarica l'APK da [releases/OpenFlow-Release-C-NotchDocked.apk](releases/OpenFlow-Release-C-NotchDocked.apk).
2. Se installi via PC tramite ADB:
   ```bash
   adb install -r -d releases/OpenFlow-Release-C-NotchDocked.apk
   ```
3. All'avvio dell'app:
   - Concedi i permessi: **Microfono**, **Disegna su altre app (Overlay)** e **Accessibilità (OpenFlow)**.
   - Disattiva le ottimizzazioni batteria seguendo la guida per il tuo brand integrata nell'app.
   - Inserisci la tua chiave API (Groq è gratuita e consigliata per latenze ultra-rapide).
4. Tocca un qualsiasi campo di testo o la capsula della Dynamic Island per iniziare a dettare!
