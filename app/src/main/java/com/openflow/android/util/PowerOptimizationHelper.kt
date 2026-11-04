package com.openflow.android.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object PowerOptimizationHelper {

    private const val TAG = "PowerOptimizationHelper"

    /**
     * Verifica se OpenFlow è escluso dalle ottimizzazioni della batteria (Doze / App Standby).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Richiede direttamente al sistema di esentare OpenFlow dal risparmio batteria.
     * Mostra il dialog di sistema standard per "Consenti esecuzione in background senza restrizioni".
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile aprire ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: ${e.message}")
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Impossibile aprire ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS: ${e2.message}")
            }
        }
    }

    /**
     * Verifica se il dispositivo è un terminale Xiaomi / POCO / Redmi con HyperOS o MIUI.
     */
    fun isXiaomiDevice(): Boolean {
        val mfg = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") ||
                brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }

    /**
     * Tenta di aprire la schermata di gestione "Avvio Automatico" (Autostart) di Xiaomi HyperOS / MIUI.
     * Essenziale per evitare che l'AccessibilityService venga terminato o bloccato dopo standby prolungato.
     */
    fun openXiaomiAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            // Xiaomi HyperOS / MIUI Autostart Management
            Intent().apply {
                component = ComponentName("com.miui.permcenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            // Xiaomi Battery Saver / PowerKeeper
            Intent().apply {
                component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                putExtra("package_name", context.packageName)
                putExtra("package_label", "OpenFlow")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            // Security Center main screen
            Intent().apply {
                component = ComponentName("com.miui.securitycenter", "com.miui.securitycenter.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Tentativo intent Xiaomi fallito: ${e.message}")
            }
        }

        // Fallback: Apri la pagina dettagli applicazione nelle impostazioni
        try {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(appDetails)
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
