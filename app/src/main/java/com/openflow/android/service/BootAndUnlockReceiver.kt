package com.openflow.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openflow.android.ui.isAccessibilityServiceEnabled

/**
 * Watchdog silente di sistema:
 * Riceve eventi di sblocco schermo (USER_PRESENT), riavvio (BOOT_COMPLETED) o update (MY_PACKAGE_REPLACED).
 * Se rileva che il servizio di accessibilità è abilitato nelle impostazioni di sistema ma
 * si trova in stato zombie/crashed (istanza null a causa di kill aggressivi di HyperOS/MIUI),
 * ne effettua l'auto-ripristino trasparente in background se è presente WRITE_SECURE_SETTINGS.
 */
class BootAndUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        Log.d(TAG, "Ricevuto evento broadcast: $action")

        val isServiceRunning = FlowAccessibilityService.instance != null
        val isEnabledInSettings = isAccessibilityServiceEnabled(context)

        if (isEnabledInSettings && !isServiceRunning) {
            Log.w(TAG, "Servizio d'accessibilità abilitato nelle impostazioni ma istanza NULL (stato crashed). Eseguo auto-ripristino automatico...")
            FlowAccessibilityService.restartAccessibilityServiceIfPermitted(context)
        }
    }

    companion object {
        private const val TAG = "BootAndUnlockReceiver"
    }
}
