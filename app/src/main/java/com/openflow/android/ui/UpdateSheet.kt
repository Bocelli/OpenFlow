package com.openflow.android.ui

import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openflow.android.util.UpdateInfo
import com.openflow.android.util.UpdateManager
import kotlinx.coroutines.launch
import java.io.File

private enum class SheetStatus {
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    initialUpdateInfo: UpdateInfo? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val updateManager = remember { UpdateManager(context) }

    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
    }
    val currentVersionName = packageInfo?.versionName ?: "1.0.2"
    val currentVersionCode = if (packageInfo != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode.toInt() else packageInfo.versionCode
    } else 2

    var status by remember {
        mutableStateOf(
            if (initialUpdateInfo != null) {
                if (initialUpdateInfo.isNewer) SheetStatus.AVAILABLE else SheetStatus.UP_TO_DATE
            } else {
                SheetStatus.CHECKING
            }
        )
    }

    var updateInfo by remember { mutableStateOf(initialUpdateInfo) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun runCheck() {
        status = SheetStatus.CHECKING
        errorMessage = null
        scope.launch {
            val result = updateManager.checkForUpdates()
            result.onSuccess { info ->
                updateInfo = info
                if (info != null && info.isNewer) {
                    status = SheetStatus.AVAILABLE
                } else {
                    status = SheetStatus.UP_TO_DATE
                }
            }.onFailure { err ->
                errorMessage = err.message ?: "Errore sconosciuto di connessione a GitHub."
                status = SheetStatus.ERROR
            }
        }
    }

    fun startDownload(url: String) {
        status = SheetStatus.DOWNLOADING
        downloadProgress = 0f
        errorMessage = null
        scope.launch {
            val result = updateManager.downloadApk(url) { prog, dBytes, tBytes ->
                downloadProgress = prog
                downloadedBytes = dBytes
                totalBytes = tBytes
            }
            result.onSuccess { apkFile ->
                downloadedApkFile = apkFile
                status = SheetStatus.READY_TO_INSTALL
                if (activity != null) {
                    val installRes = updateManager.installApk(activity, apkFile)
                    if (installRes.isFailure) {
                        Toast.makeText(context, installRes.exceptionOrNull()?.message ?: "Errore", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { err ->
                errorMessage = err.message ?: "Errore durante il download dell'aggiornamento."
                status = SheetStatus.ERROR
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialUpdateInfo == null) {
            runCheck()
        }
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
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(CardHighlight, RoundedCornerShape(12.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Aggiornamenti GitHub",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Repository OpenFlow (Bocelli/OpenFlow)",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Chiudi",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Body in base allo stato
            when (status) {
                SheetStatus.CHECKING -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Verifica release su GitHub in corso...",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }

                SheetStatus.UP_TO_DATE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(16.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(StatusEmerald.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusEmerald,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "OpenFlow è aggiornato!",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Versione installata: v$currentVersionName (build $currentVersionCode)",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = { runCheck() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(HairlineBorderFocus)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ricontrolla ora")
                        }
                    }
                }

                SheetStatus.AVAILABLE -> {
                    val info = updateInfo ?: return@Column
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(16.dp))
                            .border(1.dp, HairlineBorderFocus, RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Nuova Versione Disponibile",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusEmerald
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "v${info.versionName} (Build ${info.versionCode})",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary
                                )
                            }
                            Surface(
                                color = StatusEmerald.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StatusEmerald.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "UPDATE",
                                    color = StatusEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = HairlineBorder, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "COSA C'È DI NUOVO:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .background(CardElevated, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = info.releaseNotes,
                                fontSize = 13.sp,
                                color = TextSecondary,
                                lineHeight = 19.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { startDownload(info.downloadUrl) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusEmerald,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Scarica e Installa Aggiornamento",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                SheetStatus.DOWNLOADING -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(16.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Download dell'aggiornamento in corso...",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val mbDownloaded = downloadedBytes / (1024f * 1024f)
                        val mbTotal = totalBytes / (1024f * 1024f)
                        val percent = (downloadProgress * 100).toInt()

                        Text(
                            text = if (totalBytes > 0) {
                                "${"%.1f".format(mbDownloaded)} MB di ${"%.1f".format(mbTotal)} MB ($percent%)"
                            } else {
                                "${"%.1f".format(mbDownloaded)} MB scaricati..."
                            },
                            fontSize = 13.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = StatusEmerald,
                            trackColor = CardHighlight
                        )
                    }
                }

                SheetStatus.READY_TO_INSTALL -> {
                    val file = downloadedApkFile
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(16.dp))
                            .border(1.dp, StatusEmerald.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(StatusEmerald.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = StatusEmerald,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Download completato!",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "L'APK è pronto per essere installato sul tuo dispositivo.",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                if (activity != null && file != null) {
                                    val res = updateManager.installApk(activity, file)
                                    if (res.isFailure) {
                                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "Errore", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusEmerald,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.InstallMobile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Installa Ora", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                SheetStatus.ERROR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Errore durante l'aggiornamento",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = errorMessage ?: "Impossibile contattare GitHub.",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = { runCheck() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(HairlineBorderFocus)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Riprova")
                        }
                    }
                }
            }
        }
    }
}
