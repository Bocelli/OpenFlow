# Audit Trail - OpenFlow Android

## Project Information
- **Repository**: `open-flow-android`
- **Audit Standard**: Trail Code (v1.0.0)
- **Development Style**: Ponytail Minimalist (Full)
- **Target OS**: Android (API 26+)

## Change Log
- **2026-11-04**:
  - Initialized repository, completed Agent Reach research on Wispr Flow alternatives.
  - Completed conceptual map and architectural blueprint in `trail_audit_and_architecture.md`.
  - Conducted UI interview: selected auto-popup bubble on focus, hybrid tap/push-to-talk, and Wispr Dark aesthetic.
  - Successfully configured standalone Android CLI SDK & Gradle 8.7.
  - Resolved WhatsApp/Telegram placeholder bug: sanitized field hints ("Message") preventing prepending ghost text.
  - Upgraded audio capture to VOICE_RECOGNITION DSP and 64kbps AAC for phonetic clarity.
  - Added Whisper prompt priming, deterministic temperature 0, and explicit language parameter ("it") to prevent spelling hallucinations like "a bici".
  - Eliminated prompt-leak hallucination: removed Whisper prompt parameter entirely to stop ghost prefixes like "Broadcast".
  - Upgraded translation engine to Wispr God Tier: Few-shot prompting, paragraph auto-splitting (\n\n), intelligent self-correction, currency/time normalization, and temperature 0.15 on Llama 3.3 70B.
  - Implemented Wispr Narrativo Engine: literary style prompts with dynamic dialog quotes («...»), story paragraphs (\n\n), expressive punctuation, and elevated temperature 0.35 on Llama 3.3 70B.
  - Added programmatic Kotlin post-processing: automated consecutive duplicate word removal (deduplication) and guaranteed uppercase capitalization after punctuation (. ! ? \n\n).
  - Added on-screen Toast alerts for LLM failure transparency.
  - Compiled and verified debug APK: `app-debug.apk` (16.3 MB) ready for deployment.
  - Implemented core Android architecture:
    - `data/PreferencesManager.kt`: EncryptedSharedPreferences, OpenAI & Groq endpoints, prompt presets.
    - `audio/AudioRecorderManager.kt`: High-efficiency AAC/M4A 16kHz audio capture with live amplitude metering.
    - `ai/AiPipelineClient.kt`: Two-stage pipeline (Whisper STT + LLM formatting/cleaner) with zero heavy SDKs.
    - `ui/FloatingBubbleOverlay.kt`: Floating hardware-accelerated canvas bubble with hybrid touch, dynamic waveform, and Wispr Dark styling.
    - `service/FlowAccessibilityService.kt`: Intercepts focused editable fields and injects polished text seamlessly via `ACTION_SET_TEXT`.
    - `ui/MainActivity.kt`: Jetpack Compose OLED Dark configuration and permissions onboarding.
    - `test/PromptPresetTest.kt`: Unit tests verifying prompt contract integrity.
