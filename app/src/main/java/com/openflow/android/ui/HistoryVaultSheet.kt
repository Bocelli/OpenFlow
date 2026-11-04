package com.openflow.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openflow.android.data.TranscriptionHistoryManager
import com.openflow.android.data.TranscriptionItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * Vault locale delle trascrizioni passate (ultimi 50 vocali).
 * 100% offline e memorizzato su file JSON privato nell'app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryVaultSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val historyManager = remember { TranscriptionHistoryManager(context) }
    var items by remember { mutableStateOf(historyManager.getAll()) }
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CanvasBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CardHighlight)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "History Vault",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${items.size} trascrizioni salvate offline",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (items.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                historyManager.clear()
                                items = emptyList()
                                Toast.makeText(context, "Cronologia cancellata", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Svuota", color = StatusAmber, fontSize = 12.5.sp)
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Chiudi", tint = TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = HairlineBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(CardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Nessuna trascrizione salvata",
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "I testi elaborati con la bolla appariranno qui.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { item ->
                        HistoryItemCard(
                            item = item,
                            onCopy = {
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Trascrizione OpenFlow", item.text))
                                Toast.makeText(context, "Copiato negli appunti!", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                historyManager.delete(item.id)
                                items = historyManager.getAll()
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItemCard(
    item: TranscriptionItem,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(item.timestamp) {
        val sdf = SimpleDateFormat("HH:mm • dd MMM", Locale.getDefault())
        sdf.format(Date(item.timestamp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Provider Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardElevated)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.provider.uppercase(Locale.getDefault()),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusEmerald
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                // Preset Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardElevated)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.preset.uppercase(Locale.getDefault()),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                }
            }

            Text(
                text = dateStr,
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = item.text,
            fontSize = 13.sp,
            color = TextPrimary,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardHighlight)
                    .clickable { onCopy() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("Copia", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Elimina",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
