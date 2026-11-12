package com.openflow.android.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.openflow.android.data.PreferencesManager
import com.openflow.android.data.TranscriptionHistoryManager
import com.openflow.android.service.FlowAccessibilityService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogDumpManager {

    /**
     * Genera un dump diagnostico completo e strutturato:
     * 1. Specifiche Hardware & OS
     * 2. Metriche Schermo & DisplayCutout (Punch-Hole)
     * 3. Stato Permessi di Sistema (Accessibilità, Overlay, Mic, Batteria)
     * 4. Configurazione OpenFlow (Provider, Preset, Offset) con chiavi API mascherate
     * 5. Estratto Logcat recente (con sanitizzazione rigorosa dei token)
     */
    fun generateLogDump(context: Context, prefs: PreferencesManager): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val nowStr = sdf.format(Date())

        sb.appendLine("==================================================")
        sb.appendLine("       OPENFLOW SYSTEM DIAGNOSTIC DUMP REPORT     ")
        sb.appendLine("==================================================")
        sb.appendLine("Generated At    : $nowStr")
        sb.appendLine("Package Name    : ${context.packageName}")

        // 1. App Info
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val vName = pInfo.versionName ?: "N/A"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toString()
            }
            sb.appendLine("App Version     : $vName (build $vCode)")
        } catch (_: Exception) {
            sb.appendLine("App Version     : N/A")
        }

        sb.appendLine()
        sb.appendLine("----------------- 1. DEVICE & OS -----------------")
        sb.appendLine("Manufacturer    : ${Build.MANUFACTURER}")
        sb.appendLine("Brand           : ${Build.BRAND}")
        sb.appendLine("Model           : ${Build.MODEL}")
        sb.appendLine("Device / Board  : ${Build.DEVICE} / ${Build.BOARD}")
        sb.appendLine("Hardware        : ${Build.HARDWARE}")
        sb.appendLine("Product         : ${Build.PRODUCT}")
        sb.appendLine("Android Version : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sb.appendLine("Security Patch  : ${Build.VERSION.SECURITY_PATCH}")
        }
        sb.appendLine("Fingerprint     : ${Build.FINGERPRINT}")
        sb.appendLine("Supported ABIs  : ${Build.SUPPORTED_ABIS.joinToString(", ")}")

        // 2. Display & Notch Cutout
        sb.appendLine()
        sb.appendLine("------------ 2. DISPLAY & PUNCH-HOLE -------------")
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = context.resources.displayMetrics
            sb.appendLine("Display Metrics : ${dm.widthPixels}x${dm.heightPixels} px")
            sb.appendLine("Screen Density  : ${dm.density}x (DPI: ${dm.densityDpi})")

            val orientation = when (context.resources.configuration.orientation) {
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
                else -> "UNDEFINED"
            }
            sb.appendLine("Orientation     : $orientation")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = wm.currentWindowMetrics.windowInsets
                val statusBarTopPx = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                val statusBarTopDp = statusBarTopPx / dm.density
                sb.appendLine("Status Bar Top  : $statusBarTopPx px (~${String.format(Locale.US, "%.1f", statusBarTopDp)} dp)")

                val cutout = insets.displayCutout
                if (cutout != null) {
                    sb.appendLine("DisplayCutout   : Safe Insets = [T:${cutout.safeInsetTop}, B:${cutout.safeInsetBottom}, L:${cutout.safeInsetLeft}, R:${cutout.safeInsetRight}] px")
                    sb.appendLine("Cutout Rects    : ${cutout.boundingRects.joinToString()}")
                } else {
                    sb.appendLine("DisplayCutout   : None reported by WindowInsets")
                }
            } else {
                sb.appendLine("DisplayCutout   : Legacy API (< Android 11)")
            }
        } catch (e: Exception) {
            sb.appendLine("Display Error   : ${e.message}")
        }

        // 3. Permissions & System Services
        sb.appendLine()
        sb.appendLine("----------- 3. PERMISSIONS & SERVICES ------------")
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(context)
        val isServiceAlive = FlowAccessibilityService.instance != null
        val isPowerOptIgnored = PowerOptimizationHelper.isIgnoringBatteryOptimizations(context)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = pm?.isPowerSaveMode ?: false

        sb.appendLine("Audio Recording : ${if (hasMic) "GRANTED" else "DENIED"}")
        sb.appendLine("Overlay Window  : ${if (hasOverlay) "GRANTED" else "DENIED"}")
        sb.appendLine("Accessibility   : ${if (isServiceAlive) "ACTIVE (Service Instance OK)" else "INACTIVE / NOT BOUND"}")
        sb.appendLine("Battery Opt     : ${if (isPowerOptIgnored) "IGNORED (Whitelisted)" else "RESTRICTED (May kill background)"}")
        sb.appendLine("Power Save Mode : $isPowerSave")
        sb.appendLine("Detected Brand  : ${PowerOptimizationHelper.getDeviceBrand().title}")

        // 4. OpenFlow Preferences & Configuration (Sanitized)
        sb.appendLine()
        sb.appendLine("---------- 4. OPENFLOW CONFIGURATION ------------")
        sb.appendLine("Active Provider : ${prefs.activeProvider.name} (${prefs.activeProvider.displayName})")
        sb.appendLine("Active Preset   : ${prefs.activePreset.name} (${prefs.activePreset.title})")
        sb.appendLine("Dictation Lang  : ${prefs.language}")
        sb.appendLine("ShowOnlyFocused : ${prefs.showOnlyWhenFocused}")
        sb.appendLine("Stealth Mode    : ${prefs.stealthModeEnabled}")
        sb.appendLine("Haptic Feedback : ${prefs.hapticFeedbackEnabled}")
        sb.appendLine("Notch Offset X  : ${prefs.notchOffsetX} dp")
        sb.appendLine("Notch Offset Y  : ${prefs.notchOffsetY} dp")

        // API Key Status (Strictly Masked)
        sb.appendLine("Groq Key Status : ${maskKey(prefs.groqKey)}")
        sb.appendLine("Gemini Key Stat : ${maskKey(prefs.geminiKey)}")
        sb.appendLine("OpenAI Key Stat : ${maskKey(prefs.openAiKey)}")
        sb.appendLine("Custom Vocab    : ${prefs.customVocabulary.length} chars (Count: ${prefs.customVocabulary.split(",").size} terms)")

        // History stats
        try {
            val history = TranscriptionHistoryManager(context).getAll()
            sb.appendLine("History Vault   : ${history.size} items stored offline")
            if (history.isNotEmpty()) {
                val lastItem = history.first()
                val lastDate = sdf.format(Date(lastItem.timestamp))
                sb.appendLine("Last Vocale At  : $lastDate via ${lastItem.provider} (Length: ${lastItem.text.length} chars)")
            }
        } catch (_: Exception) {}

        // 5. Recent App Logcat Dump
        sb.appendLine()
        sb.appendLine("-------------- 5. RECENT LOGCAT DUMP -------------")
        val logcatEntries = captureSanitizedLogcat()
        if (logcatEntries.isNotBlank()) {
            sb.appendLine(logcatEntries)
        } else {
            sb.appendLine("[No logcat output available or permissions restricted]")
        }

        sb.appendLine()
        sb.appendLine("================= END OF REPORT =================")
        return sb.toString()
    }

    private fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "NOT CONFIGURED (Empty)"
        if (trimmed.length <= 8) return "CONFIGURED (***${trimmed.takeLast(2)})"
        return "CONFIGURED (${trimmed.take(4)}...${trimmed.takeLast(4)})"
    }

    private fun captureSanitizedLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "350"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = mutableListOf<String>()
            var line: String?

            val relevantKeywords = listOf(
                "OpenFlow", "FlowAccessibility", "NotchIslandOverlay", "AudioRecord",
                "AiPipeline", "AndroidRuntime", "FATAL"
            )

            // Ignora tag e classi OEM proprietarie (ColorOS, MIUI, Snapdragon) che generano falsi allarmi
            val ignoredOemTags = listOf(
                "callGcSupression", "OplusScrollToTopManager", "ScrollOptim", "ViewRootImplExtImpl",
                "Quality", "SurfaceControl", "ActivityThread", "ImeTracker"
            )

            while (reader.readLine().also { line = it } != null) {
                val current = line ?: continue
                if (ignoredOemTags.any { current.contains(it) }) continue
                if (relevantKeywords.any { current.contains(it, ignoreCase = true) } ||
                    (current.contains("Exception", ignoreCase = true) && current.contains("com.openflow.android"))) {
                    lines.add(sanitizeSecrets(current))
                }
            }
            reader.close()
            process.waitFor()

            if (lines.isEmpty()) {
                "[Nessun log recente corrispondente ai filtri dell'applicazione]"
            } else {
                lines.takeLast(250).joinToString("\n")
            }
        } catch (e: Exception) {
            "[Errore cattura logcat: ${e.message}]"
        }
    }

    private fun sanitizeSecrets(raw: String): String {
        // Maschera chiavi OpenAI, Groq e Gemini
        var text = raw
        text = text.replace(Regex("gsk_[a-zA-Z0-9_-]+"), "gsk_***[GROQ_KEY_MASKED]***")
        text = text.replace(Regex("AIzaSy[a-zA-Z0-9_-]+"), "AIzaSy***[GEMINI_KEY_MASKED]***")
        text = text.replace(Regex("sk-[a-zA-Z0-9_-]+"), "sk-***[OPENAI_KEY_MASKED]***")
        return text
    }

    /**
     * Salva il log dump su file temporaneo nella cache e apre la schermata di condivisione nativa Android.
     */
    fun shareLog(context: Context, logContent: String) {
        try {
            val fileName = "openflow_log_dump_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            val file = File(context.cacheDir, fileName)
            file.writeText(logContent)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OpenFlow Diagnostic Dump - ${Build.MANUFACTURER} ${Build.MODEL}")
                putExtra(Intent.EXTRA_TEXT, "In allegato il report diagnostico completo di OpenFlow generato su ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}).")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Invia Log Diagnostico OpenFlow")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback: condivisione testuale pura se FileProvider fallisce
            val textShareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OpenFlow Diagnostic Dump")
                putExtra(Intent.EXTRA_TEXT, logContent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(textShareIntent, "Invia Log Diagnostico"))
        }
    }
}
