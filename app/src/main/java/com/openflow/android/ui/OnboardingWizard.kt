package com.openflow.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openflow.android.data.ApiProvider
import com.openflow.android.data.PreferencesManager

/**
 * Percorso di onboarding guidato per OpenFlow.
 * Design coerente Swiss Minimalist / Modern Technical Dark:
 * - Canvas obsidian (#0C0D0F)
 * - Hairline borders (#23252E)
 * - Accenti smeraldo (#10B981)
 * - Zero bloat o elementi non funzionali
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingWizard(
    prefs: PreferencesManager,
    resumeTrigger: Int = 0,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = 4

    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasAccessibilityPermission by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    LaunchedEffect(resumeTrigger, currentStep) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        containerColor = CanvasBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Configurazione OpenFlow",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Text(
                            text = "Passo ${currentStep + 1} di $totalSteps",
                            fontSize = 12.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    if (currentStep > 0) {
                        IconButton(onClick = { currentStep-- }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Indietro",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { onComplete() }) {
                        Text("Salta", color = TextMuted, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasBg)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Hairline Progress Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in 0 until totalSteps) {
                    val isActive = i <= currentStep
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(if (isActive) StatusEmerald else CardElevated)
                    )
                }
            }

            // Step Content Scrollable
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120))
                    },
                    label = "onboarding_step"
                ) { step ->
                    when (step) {
                        0 -> OnboardingStepWelcome()
                        1 -> OnboardingStepEngine(prefs = prefs)
                        2 -> OnboardingStepPermissions(
                            hasAccessibility = hasAccessibilityPermission,
                            hasMic = hasMicPermission,
                            hasOverlay = hasOverlayPermission,
                            onAccessibilityClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onOverlayClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        )
                        3 -> OnboardingStepVocabAndGestures(prefs = prefs)
                    }
                }
            }

            // Bottom Navigation CTA
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                HorizontalDivider(color = HairlineBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (currentStep < totalSteps - 1) {
                            currentStep++
                        } else {
                            onComplete()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardHighlight),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorderFocus)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (currentStep < totalSteps - 1) "Continua" else "Inizia a Usare OpenFlow 🚀",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (currentStep < totalSteps - 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepWelcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // OpenFlow Acoustic Wave Visual
        Row(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardHighlight)
                .border(1.dp, HairlineBorderFocus, RoundedCornerShape(16.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(4.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(TextSecondary))
            Spacer(modifier = Modifier.width(4.dp))
            Box(modifier = Modifier.width(4.dp).height(26.dp).clip(RoundedCornerShape(2.dp)).background(TextPrimary))
            Spacer(modifier = Modifier.width(4.dp))
            Box(modifier = Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(TextSecondary))
        }

        Text(
            text = "Scrittura vocale God-Tier.\nPrivata, veloce, tua.",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            lineHeight = 30.sp,
            letterSpacing = (-0.5).sp
        )

        Text(
            text = "OpenFlow è la controparte open-source a Wispr Flow: niente server terzi, niente tracciamento. Colleghi le tue chiavi API personali e detti con formattazione d'autore ovunque.",
            fontSize = 13.5.sp,
            color = TextSecondary,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 3 Pillars
        FeaturePillarItem(
            icon = Icons.Default.VpnKey,
            title = "Architettura BYOK (Bring Your Own Key)",
            description = "Zero abbonamenti da 15€/mese. Usi chip LPU gratuiti Groq, Google AI Studio o OpenAI al costo puro di pochi millesimi."
        )

        FeaturePillarItem(
            icon = Icons.Default.Shield,
            title = "Zero Data Retention (Privacy Assoluta)",
            description = "Nessun server intermediario memorizza il tuo audio. Le chiamate API partono crittografate dal tuo telefono direttamente ai provider ufficiali."
        )

        FeaturePillarItem(
            icon = Icons.Default.AutoFixHigh,
            title = "Formattazione Editoriale d'Autore",
            description = "Spezzetta le frasi lunghe, rimuove automaticamente intercalari orali ('diciamo', 'praticamente') e va a capo con criterio logico."
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun OnboardingStepEngine(prefs: PreferencesManager) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    var selectedProvider by remember { mutableStateOf(prefs.activeProvider) }
    var keyText by remember {
        mutableStateOf(
            when (selectedProvider) {
                ApiProvider.GROQ -> prefs.groqKey
                ApiProvider.GEMINI -> prefs.geminiKey
                ApiProvider.OPENAI -> prefs.openAiKey
            }
        )
    }
    var isKeyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Scegli il tuo Motore IA",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "Seleziona il provider da utilizzare. Puoi cambiare o aggiungere altre chiavi in qualsiasi momento.",
            fontSize = 13.5.sp,
            color = TextSecondary,
            lineHeight = 19.sp
        )

        // Providers Cards
        val providers = listOf(
            Triple(ApiProvider.GROQ, "Groq (Free LPU)", "Consigliato • ~200ms • 100% Gratuito (console.groq.com)"),
            Triple(ApiProvider.GEMINI, "Google Gemini", "Flash Multimodale • 100% Gratuito (aistudio.google.com)"),
            Triple(ApiProvider.OPENAI, "OpenAI Nativo", "Whisper-1 originale Wispr Flow (~0.003$ per vocale)")
        )

        providers.forEach { (prov, name, desc) ->
            val isSelected = prov == selectedProvider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) CardElevated else CardBg)
                    .border(
                        1.dp,
                        if (isSelected) HairlineBorderFocus else HairlineBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        selectedProvider = prov
                        prefs.activeProvider = prov
                        keyText = when (prov) {
                            ApiProvider.GROQ -> prefs.groqKey
                            ApiProvider.GEMINI -> prefs.geminiKey
                            ApiProvider.OPENAI -> prefs.openAiKey
                        }
                    }
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) StatusEmerald else TextMuted.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = name, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = desc, color = TextSecondary, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // Key Input Field
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chiave API per ${selectedProvider.displayName.substringBefore(" ")}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )

                // Direct Portal Link Button
                val portalUrl = when (selectedProvider) {
                    ApiProvider.GROQ -> "https://console.groq.com/keys"
                    ApiProvider.GEMINI -> "https://aistudio.google.com/app/apikey"
                    ApiProvider.OPENAI -> "https://platform.openai.com/api-keys"
                }

                Text(
                    text = "Ottieni Chiave Gratis ↗",
                    color = StatusEmerald,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                    }
                )
            }

            OutlinedTextField(
                value = keyText,
                onValueChange = {
                    keyText = it
                    when (selectedProvider) {
                        ApiProvider.GROQ -> prefs.groqKey = it
                        ApiProvider.GEMINI -> prefs.geminiKey = it
                        ApiProvider.OPENAI -> prefs.openAiKey = it
                    }
                },
                placeholder = {
                    Text(
                        when (selectedProvider) {
                            ApiProvider.GROQ -> "gsk_..."
                            ApiProvider.GEMINI -> "AIzaSy..."
                            ApiProvider.OPENAI -> "sk-..."
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (keyText.isBlank() && clipboard != null && clipboard.hasPrimaryClip()) {
                            Text(
                                text = "Incolla",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardHighlight)
                                    .clickable {
                                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                        if (clip.isNotBlank()) {
                                            keyText = clip
                                            when (selectedProvider) {
                                                ApiProvider.GROQ -> prefs.groqKey = clip
                                                ApiProvider.GEMINI -> prefs.geminiKey = clip
                                                ApiProvider.OPENAI -> prefs.openAiKey = clip
                                            }
                                            Toast.makeText(context, "Chiave incollata!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HairlineBorderFocus,
                    unfocusedBorderColor = HairlineBorder,
                    focusedContainerColor = CardElevated,
                    unfocusedContainerColor = CardElevated,
                    cursorColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun OnboardingStepPermissions(
    hasAccessibility: Boolean,
    hasMic: Boolean,
    hasOverlay: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit
) {
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Permessi di Sistema",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "Per funzionare in sovrimpressione sopra WhatsApp, Telegram e qualsiasi app di testo, OpenFlow richiede i seguenti permessi Android.",
            fontSize = 13.5.sp,
            color = TextSecondary,
            lineHeight = 19.sp
        )

        // Permission Items
        PermissionStepCard(
            title = "1. Servizio di Accessibilità OpenFlow",
            description = "Indispensabile per rilevare il cursore di testo e incollare il parlato elaborato all'istante.",
            isGranted = hasAccessibility,
            actionLabel = "Attiva in Impostazioni",
            onClick = onAccessibilityClick
        )

        PermissionStepCard(
            title = "2. Microfono ad Alta Fedeltà",
            description = "Per registrare l'audio della tua voce con DSP hardware di cancellazione rumore ed eco.",
            isGranted = hasMic,
            actionLabel = "Concedi Microfono",
            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        )

        PermissionStepCard(
            title = "3. Sovrapposizione Overlay",
            description = "Consente alla bolla acustica di galleggiare sopra qualsiasi applicazione.",
            isGranted = hasOverlay,
            actionLabel = "Abilita Overlay",
            onClick = onOverlayClick
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun OnboardingStepVocabAndGestures(prefs: PreferencesManager) {
    var vocabText by remember { mutableStateOf(prefs.customVocabulary) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Vocabolario & Gesti Rapidi",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "Personalizza il glossario per evitare errori di trascrizione sui nomi propri, slang o termini tecnici.",
            fontSize = 13.5.sp,
            color = TextSecondary,
            lineHeight = 19.sp
        )

        // Custom Vocab Field
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Glossario Personalizzato (separato da virgole)",
                fontSize = 12.sp,
                color = TextSecondary,
                fontWeight = FontWeight.Medium
            )

            OutlinedTextField(
                value = vocabText,
                onValueChange = {
                    vocabText = it
                    prefs.customVocabulary = it
                },
                placeholder = { Text("Michele, OpenFlow, Wispr Flow, 5800X3D...") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HairlineBorderFocus,
                    unfocusedBorderColor = HairlineBorder,
                    focusedContainerColor = CardElevated,
                    unfocusedContainerColor = CardElevated,
                    cursorColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Text(
                text = "Viene iniettato automaticamente nel prompt di Whisper e dell'LLM.",
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        HorizontalDivider(color = HairlineBorder, thickness = 1.dp)

        // Gesture Cheat Sheet
        Text(
            text = "GESTI DELLA BOLLA ACUSTICA",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        GestureGuideCard(
            title = "1 Tocco Singolo",
            description = "Avvia la registrazione; tocca di nuovo per completare e inserire il testo."
        )

        GestureGuideCard(
            title = "2 Tocchi Rapidi (Doppio Tocco)",
            description = "Cambia stile editoriale al volo: Wispr God-Tier, Prompt AI, Pro, o Elenchi."
        )

        GestureGuideCard(
            title = "Trascina verso il Basso",
            description = "Trascina la pillola verso il basso durante la registrazione per annullarla all'istante senza inviare nulla."
        )

        GestureGuideCard(
            title = "Stealth Mode (Auto-dim)",
            description = "Dopo 3.5s di inattività la bolla diventa trasparente per non intralciare lo schermo."
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun FeaturePillarItem(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(CardHighlight),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.5.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.5.sp)
        }
    }
}

@Composable
private fun PermissionStepCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
            if (isGranted) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(StatusEmerald.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(StatusEmerald))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Attivo", color = StatusEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Text(text = description, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)

        if (!isGranted) {
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardHighlight),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorderFocus)
            ) {
                Text(text = actionLabel, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GestureGuideCard(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardElevated)
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(StatusEmerald)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(1.dp))
            Text(text = description, color = TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    if (com.openflow.android.service.FlowAccessibilityService.instance != null) return true
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
