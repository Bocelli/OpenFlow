package com.openflow.android.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Gestore universale dell'ottimizzazione energetica e del comportamento anti-kill
 * per tutti i principali produttori Android (Samsung, Xiaomi, OnePlus/Oppo, Huawei, Vivo, Asus, Pixel).
 * Riferimento standard del settore: DontKillMyApp (https://dontkillmyapp.com).
 */
enum class DeviceBrand(
    val title: String,
    val summary: String,
    val steps: List<String>,
    val guideUrl: String
) {
    XIAOMI(
        title = "Xiaomi / POCO / Redmi (HyperOS & MIUI)",
        summary = "HyperOS e MIUI congelano forzatamente i processi in background nel cgroup /frozen dopo 10 minuti di inattività.",
        steps = listOf(
            "1. Avvio Automatico: Abilita 'Avvio automatico' per OpenFlow nella schermata di sicurezza.",
            "2. Risparmio Energetico: Imposta il risparmio energetico dell'app su 'Nessuna restrizione'.",
            "3. Blocco nei Recenti: Apri la schermata delle app recenti e blocca OpenFlow con l'icona del lucchetto."
        ),
        guideUrl = "https://dontkillmyapp.com/xiaomi"
    ),
    SAMSUNG(
        title = "Samsung Galaxy (One UI)",
        summary = "One UI inserisce le app non aperte quotidianamente tra le 'App in sospensione avanzata', congelando i servizi d'accessibilità.",
        steps = listOf(
            "1. Batteria Senza Restrizioni: In Info App > Batteria, seleziona 'Senza restrizioni'.",
            "2. Limiti Uso in Background: In Impostazioni > Assistenza dispositivo > Batteria > 'Limiti per l'uso in background', rimuovi OpenFlow da 'App in sospensione' e aggiungila a 'App mai in sospensione'.",
            "3. Disattiva Sospensione Automatica: Disabilita l'opzione 'Metti in sospensione le app non usate'."
        ),
        guideUrl = "https://dontkillmyapp.com/samsung"
    ),
    HUAWEI(
        title = "Huawei & Honor (EMUI / Magic UI / HarmonyOS)",
        summary = "EMUI chiude aggressivamente qualsiasi servizio in background entro 2 minuti dal blocco dello schermo se impostato su 'Gestione automatica'.",
        steps = listOf(
            "1. Avvio App: Vai in Impostazioni > Batteria > 'Avvio app' e cerca OpenFlow.",
            "2. Gestione Manuale: Disattiva l'interruttore 'Gestisci automaticamente'.",
            "3. Consenti Tutto: Attiva manualmente 'Avvio automatico', 'Avvio secondario' ed 'Esecuzione in background'."
        ),
        guideUrl = "https://dontkillmyapp.com/huawei"
    ),
    OPPO_ONEPLUS(
        title = "OnePlus / Oppo / Realme (OxygenOS, ColorOS, Realme UI)",
        summary = "I sistemi ColorOS e OxygenOS hanno il modulo 'Power Genius' che interrompe l'esecuzione in background e congela l'overlay.",
        steps = listOf(
            "1. Attività in Background: In Info App > Consumo batteria, attiva 'Consenti attività in background' e 'Consenti avvio automatico'.",
            "2. Standby Sonno: Nelle impostazioni avanzate della batteria, escludi OpenFlow da 'Ottimizzazione standby sonno'.",
            "3. Blocco Recenti: Apri le app recenti e blocca la scheda di OpenFlow premendo sui tre puntini o sull'icona del lucchetto."
        ),
        guideUrl = "https://dontkillmyapp.com/oneplus"
    ),
    VIVO(
        title = "Vivo & iQOO (Funtouch OS / OriginOS)",
        summary = "Funtouch OS termina i servizi che consumano energia in background a meno che non siano autorizzati con deroga specifica.",
        steps = listOf(
            "1. Consumo Elevato: In Impostazioni > Batteria > 'Consumo elevato in background', trova OpenFlow e imposta su Consenti.",
            "2. Gestione Avvio: In Gestione permessi > 'Avvio automatico', autorizza OpenFlow.",
            "3. Blocco nei Recenti: Trascina verso il basso la scheda di OpenFlow nelle app recenti per bloccarla."
        ),
        guideUrl = "https://dontkillmyapp.com/vivo"
    ),
    ASUS(
        title = "Asus (ZenUI / ROG UI)",
        summary = "ZenUI integra l'Auto-start Manager nell'app Gestione Mobile che blocca il risveglio delle app in standby.",
        steps = listOf(
            "1. Gestione Mobile: Apri l'app preinstallata 'Gestione Mobile'.",
            "2. Auto-start Manager: Trova OpenFlow e sposta l'interruttore su 'Consenti'.",
            "3. Risparmio Batteria: Imposta la batteria di OpenFlow su 'Nessuna restrizione'."
        ),
        guideUrl = "https://dontkillmyapp.com/asus"
    ),
    GENERIC(
        title = "Android Standard (Google Pixel, Motorola, Sony)",
        summary = "Android AOSP standard gestisce il risparmio energetico tramite Doze e App Standby Buckets.",
        steps = listOf(
            "1. Esenzione Batteria: Concedi l'esclusione dal risparmio batteria quando richiesto.",
            "2. Impostazioni Batteria: In Info App > Utilizzo batteria, seleziona 'Senza restrizioni'.",
            "3. Dati in Background: Verifica che sia abilitato l'uso illimitato dei dati in background."
        ),
        guideUrl = "https://dontkillmyapp.com/google"
    )
}

object PowerOptimizationHelper {

    private const val TAG = "PowerOptimizationHelper"

    /**
     * Rileva la marca del dispositivo corrente per personalizzare la guida e le schermate di sistema.
     */
    fun getDeviceBrand(): DeviceBrand {
        val mfg = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()

        return when {
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") ||
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> DeviceBrand.XIAOMI

            mfg.contains("samsung") || brand.contains("samsung") -> DeviceBrand.SAMSUNG

            mfg.contains("huawei") || mfg.contains("honor") ||
            brand.contains("huawei") || brand.contains("honor") -> DeviceBrand.HUAWEI

            mfg.contains("oneplus") || mfg.contains("oppo") || mfg.contains("realme") ||
            brand.contains("oneplus") || brand.contains("oppo") || brand.contains("realme") -> DeviceBrand.OPPO_ONEPLUS

            mfg.contains("vivo") || mfg.contains("iqoo") ||
            brand.contains("vivo") || brand.contains("iqoo") -> DeviceBrand.VIVO

            mfg.contains("asus") || brand.contains("asus") -> DeviceBrand.ASUS

            else -> DeviceBrand.GENERIC
        }
    }

    /**
     * Verifica se OpenFlow è escluso dalle ottimizzazioni della batteria AOSP (Doze / mDeviceIdleAllowlist).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
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
     * Tenta di aprire la schermata nativa delle impostazioni di gestione avvio e batteria del produttore OEM.
     * Testa in sequenza tutti i package/activity noti per il brand rilevato.
     */
    fun openManufacturerSettings(context: Context, brand: DeviceBrand = getDeviceBrand()): Boolean {
        val candidates = when (brand) {
            DeviceBrand.XIAOMI -> listOf(
                Intent().apply {
                    component = ComponentName("com.miui.permcenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", "OpenFlow")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.securitycenter.MainActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )

            DeviceBrand.SAMSUNG -> listOf(
                Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.AppSleepListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )

            DeviceBrand.HUAWEI -> listOf(
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.honor.systemmanager", "com.honor.systemmanager.optimize.process.ProtectActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )

            DeviceBrand.OPPO_ONEPLUS -> listOf(
                Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.oplus.battery", "com.oplus.battery.AppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )

            DeviceBrand.VIVO -> listOf(
                Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftManagerTabActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )

            DeviceBrand.ASUS -> listOf(
                Intent().apply {
                    component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                Intent().apply {
                    component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )

            DeviceBrand.GENERIC -> listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Tentativo intent OEM fallito: ${e.message}")
            }
        }

        // Fallback 1: Schermata generale ottimizzazioni batteria
        try {
            val generalBattery = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (generalBattery.resolveActivity(context.packageManager) != null) {
                context.startActivity(generalBattery)
                return true
            }
        } catch (_: Exception) {}

        // Fallback 2: Dettagli applicazione
        return openAppDetailsSettings(context)
    }

    /**
     * Apre la schermata Informazioni Applicazione nelle impostazioni di sistema.
     */
    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
