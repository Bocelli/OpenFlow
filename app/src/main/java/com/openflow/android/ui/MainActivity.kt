package com.openflow.android.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openflow.android.data.ApiProvider
import com.openflow.android.data.PreferencesManager
import com.openflow.android.data.PromptPreset
import com.openflow.android.service.FlowAccessibilityService

// Wispr Dark Color Palette
val FlowBlack = Color(0xFF0B0B0E)
val FlowSurface = Color(0xFF141419)
val FlowSurfaceElevated = Color(0xFF1C1C24)
val FlowBorder = Color(0xFF272734)
val FlowViolet = Color(0xFF7C3AED)
val FlowVioletLight = Color(0xFFA855F7)
val FlowEmerald = Color(0xFF10B981)
val FlowTextPrimary = Color(0xFFF9FAFB)
val FlowTextSecondary = Color(0xFF9CA3AF)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            OpenFlowTheme {
                MainSettingsScreen(prefs = prefs)
            }
        }
    }
}

@Composable
fun OpenFlowTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        background = FlowBlack,
        surface = FlowSurface,
        primary = FlowViolet,
        secondary = FlowEmerald,
        onBackground = FlowTextPrimary,
        onSurface = FlowTextPrimary
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(prefs: PreferencesManager) {
    val context = LocalContext.current

    // Stati reattivi
    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasAccessibilityPermission by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    var activeProvider by remember { mutableStateOf(prefs.activeProvider) }
    var openAiKey by remember { mutableStateOf(prefs.openAiKey) }
    var groqKey by remember { mutableStateOf(prefs.groqKey) }
    var activePreset by remember { mutableStateOf(prefs.activePreset) }
    var showOnlyWhenFocused by remember { mutableStateOf(prefs.showOnlyWhenFocused) }
    var hapticEnabled by remember { mutableStateOf(prefs.hapticFeedbackEnabled) }
    var isKeyVisible by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    LaunchedEffect(Unit) {
        // Refresh permessi al ritorno a schermo
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
    }

    Scaffold(
        containerColor = FlowBlack,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(FlowViolet, FlowVioletLight))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "OpenFlow",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = FlowTextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "BYOK Free",
                            fontSize = 11.sp,
                            color = FlowEmerald,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .border(1.dp, FlowEmerald.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FlowBlack)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Sezione 1: Checklist Permessi
            item {
                SectionHeader(title = "STATO PERMESSI")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = FlowSurface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(FlowBorder, FlowSurfaceElevated))),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PermissionRow(
                            title = "Microfono",
                            desc = "Per registrare la tua voce ad alta fedeltà",
                            isGranted = hasMicPermission,
                            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                        Divider(color = FlowBorder.copy(alpha = 0.5f))
                        PermissionRow(
                            title = "Overlay Schermo",
                            desc = "Per visualizzare la bollicina fluttuante sopra le app",
                            isGranted = hasOverlayPermission,
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        )
                        Divider(color = FlowBorder.copy(alpha = 0.5f))
                        PermissionRow(
                            title = "Accessibilità",
                            desc = "Per rilevare la tastiera e inserire il testo trascritto",
                            isGranted = hasAccessibilityPermission,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )
                    }
                }
            }

            // Sezione 2: Motore IA & Chiave API
            item {
                SectionHeader(title = "MOTORE INTELLIGENZA ARTIFICIALE")
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = FlowSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(FlowBorder, FlowSurfaceElevated)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Switch Provider
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProviderTab(
                                title = "Groq (Consigliato)",
                                subtitle = "Latenza ~200ms • Free",
                                isSelected = activeProvider == ApiProvider.GROQ,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    activeProvider = ApiProvider.GROQ
                                    prefs.activeProvider = ApiProvider.GROQ
                                }
                            )
                            ProviderTab(
                                title = "OpenAI",
                                subtitle = "Whisper Originale",
                                isSelected = activeProvider == ApiProvider.OPENAI,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    activeProvider = ApiProvider.OPENAI
                                    prefs.activeProvider = ApiProvider.OPENAI
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val currentKey = if (activeProvider == ApiProvider.GROQ) groqKey else openAiKey
                        OutlinedTextField(
                            value = currentKey,
                            onValueChange = { newValue ->
                                if (activeProvider == ApiProvider.GROQ) {
                                    groqKey = newValue
                                    prefs.groqKey = newValue
                                } else {
                                    openAiKey = newValue
                                    prefs.openAiKey = newValue
                                }
                            },
                            label = { Text("Chiave API ${activeProvider.displayName}") },
                            placeholder = { Text(if (activeProvider == ApiProvider.GROQ) "gsk_..." else "sk-...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                                    Icon(
                                        imageVector = if (isKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = FlowTextSecondary
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FlowViolet,
                                unfocusedBorderColor = FlowBorder,
                                focusedLabelColor = FlowVioletLight,
                                cursorColor = FlowViolet
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (activeProvider == ApiProvider.GROQ)
                                "⚡ Groq offre token gratuiti e un tempo di risposta di soli 200ms per Whisper v3 Turbo."
                            else
                                "🔑 OpenAI Whisper costa $0.006 al minuto: con $1 detti per quasi 3 ore.",
                            fontSize = 12.sp,
                            color = FlowTextSecondary
                        )
                    }
                }
            }

            // Sezione 3: Stile & Preset di Scrittura
            item {
                SectionHeader(title = "STILE DI SCRITTURA & RIFINITURA")
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptPreset.values().forEach { preset ->
                        PresetCard(
                            preset = preset,
                            isSelected = activePreset == preset,
                            onClick = {
                                activePreset = preset
                                prefs.activePreset = preset
                                Toast.makeText(context, "Preset: ${preset.title}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // Sezione 4: Preferenze Comportamento
            item {
                SectionHeader(title = "COMPORTAMENTO OVERLAY")
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = FlowSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.verticalGradient(listOf(FlowBorder, FlowSurfaceElevated)))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("A comparsa solo su campi testo", fontWeight = FontWeight.SemiBold, color = FlowTextPrimary, fontSize = 15.sp)
                                Text("Nasconde la bollicina quando non stai digitando", color = FlowTextSecondary, fontSize = 12.sp)
                            }
                            Switch(
                                checked = showOnlyWhenFocused,
                                onCheckedChange = {
                                    showOnlyWhenFocused = it
                                    prefs.showOnlyWhenFocused = it
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = FlowVioletLight, checkedTrackColor = FlowViolet.copy(alpha = 0.5f))
                            )
                        }

                        Divider(color = FlowBorder.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Feedback Aptico", fontWeight = FontWeight.SemiBold, color = FlowTextPrimary, fontSize = 15.sp)
                                Text("Micro-vibrazioni all'avvio e termine della registrazione", color = FlowTextSecondary, fontSize = 12.sp)
                            }
                            Switch(
                                checked = hapticEnabled,
                                onCheckedChange = {
                                    hapticEnabled = it
                                    prefs.hapticFeedbackEnabled = it
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = FlowVioletLight, checkedTrackColor = FlowViolet.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = FlowVioletLight,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
fun PermissionRow(title: String, desc: String, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted) { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = FlowTextPrimary, fontSize = 15.sp)
            Text(desc, color = FlowTextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isGranted) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(FlowEmerald.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Attivo ✓", color = FlowEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = FlowViolet),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Concedi", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ProviderTab(title: String, subtitle: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) FlowViolet.copy(alpha = 0.2f) else FlowSurfaceElevated)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) FlowViolet else FlowBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = if (isSelected) FlowTextPrimary else FlowTextSecondary, fontSize = 13.sp)
            Text(subtitle, color = if (isSelected) FlowEmerald else FlowTextSecondary.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
fun PresetCard(preset: PromptPreset, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) FlowSurfaceElevated else FlowSurface
        ),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                if (isSelected) listOf(FlowViolet, FlowVioletLight) else listOf(FlowBorder, FlowBorder)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = FlowVioletLight, unselectedColor = FlowBorder)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(preset.title, fontWeight = FontWeight.Bold, color = FlowTextPrimary, fontSize = 14.sp)
                Text(preset.description, color = FlowTextSecondary, fontSize = 12.sp)
            }
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
