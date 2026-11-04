package com.openflow.android.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.openflow.android.ui.MainActivity
import com.openflow.android.R
import org.json.JSONObject

/**
 * Gestore dell'integrazione Xiaomi HyperOS "Super Island" / "Focus Notification" (超级岛 / 焦点通知).
 *
 * Implementa due canali sinergici:
 * 1. Canale Nativo HyperOS (Zero Root): Notifiche con payload extras `miui.focus.param` (JSON) e `miui.focus.pics` (Bundle).
 * 2. Canale HyperIsland Broadcast (LSPosed Hook): Broadcast a `io.github.hyperisland.ACTION_SHOW_ISLAND` per i dispositivi con modulo attivo.
 */
class HyperOsIslandManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createIslandChannel()
    }

    private fun createIslandChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ISLAND_CHANNEL_ID,
                "OpenFlow HyperOS Island",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifiche Super Island per Xiaomi HyperOS"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Mostra la Super Island nello stato di REGISTRAZIONE attiva.
     */
    fun showRecording(seconds: Int) {
        val timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60)
        postNativeFocusNotification(
            title = "OpenFlow",
            content = "Ascolto $timeStr",
            highlightColor = "#38BDF8", // Cyan / Sky blue
            isOngoing = true,
            timeoutSec = 0
        )
        sendHyperIslandBroadcast(
            title = "OpenFlow",
            content = "Ascolto $timeStr",
            highlightColor = "#38BDF8",
            isOngoing = true,
            timeoutSec = 0
        )
    }

    /**
     * Mostra la Super Island nello stato di ELABORAZIONE IA.
     */
    fun showProcessing() {
        postNativeFocusNotification(
            title = "OpenFlow IA",
            content = "Trascrizione...",
            highlightColor = "#818CF8", // Indigo
            isOngoing = true,
            timeoutSec = 0
        )
        sendHyperIslandBroadcast(
            title = "OpenFlow IA",
            content = "Trascrizione...",
            highlightColor = "#818CF8",
            isOngoing = true,
            timeoutSec = 0
        )
    }

    /**
     * Mostra la Super Island nello stato di SUCCESSO.
     */
    fun showSuccess() {
        postNativeFocusNotification(
            title = "OpenFlow",
            content = "Testo inserito ✓",
            highlightColor = "#10B981", // Emerald green
            isOngoing = false,
            timeoutSec = 2
        )
        sendHyperIslandBroadcast(
            title = "OpenFlow",
            content = "Testo inserito ✓",
            highlightColor = "#10B981",
            isOngoing = false,
            timeoutSec = 2
        )
    }

    /**
     * Mostra la Super Island nello stato di ERRORE.
     */
    fun showError(message: String) {
        postNativeFocusNotification(
            title = "OpenFlow",
            content = message.take(24),
            highlightColor = "#EF4444", // Red
            isOngoing = false,
            timeoutSec = 3
        )
        sendHyperIslandBroadcast(
            title = "OpenFlow",
            content = message.take(24),
            highlightColor = "#EF4444",
            isOngoing = false,
            timeoutSec = 3
        )
    }

    /**
     * Chiude e rimuove la notifica Super Island.
     */
    fun dismiss() {
        try {
            notificationManager.cancel(ISLAND_NOTIF_ID)
        } catch (_: Exception) {}

        // Invia annullamento anche a HyperIsland se presente
        try {
            val cancelIntent = Intent(ACTION_HYPER_CANCEL).apply {
                setPackage("com.android.systemui")
                putExtra("notif_id", HYPER_NOTIF_ID)
            }
            context.sendBroadcast(cancelIntent)
        } catch (_: Exception) {}
    }

    /**
     * Costruisce e invia la notifica con extras proprietari Xiaomi HyperOS.
     */
    private fun postNativeFocusNotification(
        title: String,
        content: String,
        highlightColor: String,
        isOngoing: Boolean,
        timeoutSec: Int
    ) {
        try {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val appIcon = createMicIcon()

            // 1. Costruzione JSON miui.focus.param conforme a Xiaomi HyperOS SystemUI
            val jsonParam = buildHyperOsJson(title, content, highlightColor, timeoutSec)

            // 2. Costruzione Bundle miui.focus.pics
            val picsBundle = Bundle().apply {
                putParcelable("key_island_icon", appIcon)
                putParcelable("key_focus_icon", appIcon)
                putParcelable("miui.focus.pic_aod", appIcon)
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, ISLAND_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(contentIntent)
                .setOngoing(isOngoing)
                .setAutoCancel(!isOngoing)
                .setVisibility(Notification.VISIBILITY_PRIVATE)

            // Iniezione degli extras Xiaomi Super Island
            builder.addExtras(Bundle().apply {
                putString("miui.focus.param", jsonParam)
                putBundle("miui.focus.pics", picsBundle)
                putString("miui.bigIsland.effect.src", "outer_glow")
                putString("miui.effect.src", "outer_glow")
                putString("hyperisland_focus_out_effect_color", highlightColor)
                putString("hyperisland_island_outer_glow_color", highlightColor)
            })

            notificationManager.notify(ISLAND_NOTIF_ID, builder.build())
            Log.d(TAG, "Notifica HyperOS Focus inviata con successo: $title - $content")
        } catch (e: Exception) {
            Log.w(TAG, "Errore postNativeFocusNotification: ${e.message}")
        }
    }

    /**
     * Crea il payload JSON `miui.focus.param` per la capsula punch-hole di HyperOS.
     */
    private fun buildHyperOsJson(
        title: String,
        content: String,
        highlightColor: String,
        timeoutSec: Int
    ): String {
        val root = JSONObject()
        val pv2 = JSONObject()

        pv2.put("template", "base_text_with_progress")
        pv2.put("updatable", true)
        pv2.put("outEffectSrc", "outer_glow")
        pv2.put("outEffectColor", highlightColor)

        val paramIsland = JSONObject().apply {
            put("highlightColor", highlightColor)
            put("outEffectSrc", "outer_glow")
            put("outEffectColor", highlightColor)
            if (timeoutSec > 0) {
                put("islandTimeout", timeoutSec)
                put("dismissIsland", true)
            } else {
                put("dismissIsland", false)
            }

            // Vista Small Island (icona punch-hole)
            val smallView = JSONObject().apply {
                put("pic", "key_island_icon")
            }
            put("small_island_view", smallView)

            // Vista Big Island (espansione laterale)
            val bigView = JSONObject().apply {
                val left = JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "key_island_icon")
                    })
                    put("textInfo", JSONObject().apply {
                        put("title", title)
                        put("showHighlightColor", true)
                        put("narrowFont", false)
                    })
                }
                val right = JSONObject().apply {
                    put("type", 2)
                    put("textInfo", JSONObject().apply {
                        put("title", content)
                        put("showHighlightColor", false)
                        put("narrowFont", true)
                    })
                }
                put("left", left)
                put("right", right)
            }
            put("big_island_view", bigView)
        }

        pv2.put("param_island", paramIsland)
        root.put("param_v2", pv2)
        return root.toString()
    }

    /**
     * Invia broadcast a HyperIsland (modulo LSPosed) se presente.
     */
    private fun sendHyperIslandBroadcast(
        title: String,
        content: String,
        highlightColor: String,
        isOngoing: Boolean,
        timeoutSec: Int
    ) {
        try {
            val intent = Intent(ACTION_HYPER_SHOW).apply {
                setPackage("com.android.systemui")
                putExtra("title", title)
                putExtra("content", content)
                putExtra("focus_title", title)
                putExtra("focus_content", content)
                putExtra("highlight_color", highlightColor)
                putExtra("is_ongoing", isOngoing)
                putExtra("timeout", if (timeoutSec > 0) timeoutSec else 0)
                putExtra("enable_float", true)
                putExtra("first_float", true)
                putExtra("show_notification", true)
                putExtra("island_outer_glow", true)
                putExtra("outer_glow", true)
                putExtra("island_outer_glow_color", highlightColor)
                putExtra("out_effect_color", highlightColor)
                putExtra("notif_id", HYPER_NOTIF_ID)
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun createMicIcon(): Icon {
        // Genera una bitmap circolare con icona stilizzata ciano
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        // Micro punto centrale bianco
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 4.5f, dotPaint)

        return Icon.createWithBitmap(bitmap)
    }

    companion object {
        private const val TAG = "HyperOsIslandManager"
        private const val ISLAND_CHANNEL_ID = "openflow_hyperos_island"
        private const val ISLAND_NOTIF_ID = 4004
        private const val HYPER_NOTIF_ID = 0x48594944

        private const val ACTION_HYPER_SHOW = "io.github.hyperisland.ACTION_SHOW_ISLAND"
        private const val ACTION_HYPER_CANCEL = "io.github.hyperisland.ACTION_CANCEL_ISLAND"
    }
}
