package com.openflow.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openflow.android.data.PreferencesManager
import com.openflow.android.service.FlowAccessibilityService
import com.openflow.android.util.LogDumpManager
import com.openflow.android.util.PowerOptimizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDumpSheet(
    prefs: PreferencesManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    var logContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    fun refreshDump() {
        isLoading = true
        scope.launch {
            val dump = withContext(Dispatchers.IO) {
                LogDumpManager.generateLogDump(context, prefs)
            }
            logContent = dump
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshDump()
    }

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
                .fillMaxHeight(0.9f)
                .padding(horizontal = 18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Diagnostica & Dump Log",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Report di sistema e logcat per identificare bug o crash",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { refreshDump() }, enabled = !isLoading) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Ricarica log",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Chiudi",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Quick Status Chips
            val isServiceAlive = FlowAccessibilityService.instance != null
            val isPowerOptIgnored = PowerOptimizationHelper.isIgnoringBatteryOptimizations(context)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Accessibilità Chip
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isServiceAlive) StatusEmerald.copy(alpha = 0.12f) else StatusAmber.copy(alpha = 0.12f))
                        .border(1.dp, (if (isServiceAlive) StatusEmerald else StatusAmber).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isServiceAlive) StatusEmerald else StatusAmber)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isServiceAlive) "Accessibilità OK" else "Servizio Inattivo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isServiceAlive) StatusEmerald else StatusAmber
                    )
                }

                // Batteria Chip
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isPowerOptIgnored) StatusEmerald.copy(alpha = 0.12f) else StatusAmber.copy(alpha = 0.12f))
                        .border(1.dp, (if (isPowerOptIgnored) StatusEmerald else StatusAmber).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isPowerOptIgnored) StatusEmerald else StatusAmber)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPowerOptIgnored) "Batteria No-Limiti" else "Batteria Ottimizzata",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isPowerOptIgnored) StatusEmerald else StatusAmber
                    )
                }
            }

            // Action Buttons Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (logContent.isNotBlank()) {
                            LogDumpManager.shareLog(context, logContent)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && logContent.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardHighlight),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorderFocus)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Condividi Log",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                Button(
                    onClick = {
                        if (logContent.isNotBlank()) {
                            clipboard?.setPrimaryClip(ClipData.newPlainText("OpenFlow Diagnostic Log", logContent))
                            Toast.makeText(context, "Log copiato negli appunti!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && logContent.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Copia Testo",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            }

            // Content Area (Terminal View)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardElevated)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = StatusEmerald,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Acquisizione log e metriche in corso...",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    val verticalScroll = rememberScrollState()
                    val horizontalScroll = rememberScrollState()

                    Text(
                        text = logContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = Color(0xFFA1A1AA),
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScroll)
                            .horizontalScroll(horizontalScroll)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
