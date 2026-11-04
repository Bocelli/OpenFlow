package com.openflow.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.openflow.android.ui.MainActivity

/**
 * Quick Settings Tile per Android 7.0+ (Nougat e superiori).
 * Permette di mostrare o nascondere la bolla con un singolo tocco dai quick toggles delle notifiche.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FlowTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val service = FlowAccessibilityService.instance
        if (service != null) {
            val isVisible = service.isBubbleVisible()
            if (isVisible) {
                service.triggerHideBubble()
            } else {
                service.triggerShowBubble()
            }
            updateTileState()
        } else {
            // Se il servizio accessibilità non è attivo, apri l'app per permettere l'abilitazione
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val service = FlowAccessibilityService.instance
        if (service != null) {
            val isVisible = service.isBubbleVisible()
            tile.state = if (isVisible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "OpenFlow"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (isVisible) "Bolla Attiva" else "Bolla Nascosta"
            }
            tile.contentDescription = if (isVisible) "Nascondi bolla OpenFlow" else "Mostra bolla OpenFlow"
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "OpenFlow"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Servizio Disattivo"
            }
        }
        tile.updateTile()
    }
}
