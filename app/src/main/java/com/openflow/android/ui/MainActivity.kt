package com.openflow.android.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.openflow.android.util.DeviceBrand
import com.openflow.android.util.PowerOptimizationHelper

// Swiss Minimalist & Modern Technical Dark Palette (Anti-Slop)
val CanvasBg = Color(0xFF0C0D0F)
val CardBg = Color(0xFF141519)
val CardElevated = Color(0xFF1B1C22)
val CardHighlight = Color(0xFF242630)
val HairlineBorder = Color(0xFF23252E)
val HairlineBorderFocus = Color(0xFF3E4150)
val TextPrimary = Color(0xFFF1F1F3)
val TextSecondary = Color(0xFF8F919C)
val TextMuted = Color(0xFF5A5C66)
val StatusEmerald = Color(0xFF10B981)
val StatusAmber = Color(0xFFF59E0B)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private val resumeCount = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            OpenFlowTheme {
                var showOnboarding by remember { mutableStateOf(!prefs.hasCompletedOnboarding) }
                if (showOnboarding) {
                    OnboardingWizard(
                        prefs = prefs,
                        resumeTrigger = resumeCount.value,
                        onComplete = {
                            prefs.hasCompletedOnboarding = true
                            showOnboarding = false
                            if (FlowAccessibilityService.instance != null && !prefs.showOnlyWhenFocused) {
                                FlowAccessibilityService.instance?.triggerShowBubble()
                            }
                        }
                    )
                } else {
                    MainSettingsScreen(
                        prefs = prefs,
                        resumeTrigger = resumeCount.value,
                        onOpenOnboarding = { showOnboarding = true }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.value++
        if (isAccessibilityServiceEnabled(this) && FlowAccessibilityService.instance == null) {
            FlowAccessibilityService.restartAccessibilityServiceIfPermitted(this)
        }
    }
}

@Composable
fun OpenFlowTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        background = CanvasBg,
        surface = CardBg,
        primary = TextPrimary,
        secondary = StatusEmerald,
        onBackground = TextPrimary,
        onSurface = TextPrimary
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    prefs: PreferencesManager,
    resumeTrigger: Int = 0,
    onOpenOnboarding: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasAccessibilityPermission by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }
    var isBatteryOptIgnored by remember {
        mutableStateOf(PowerOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }
    val currentBrand = remember { PowerOptimizationHelper.getDeviceBrand() }
    var showBatteryGuideDialog by remember { mutableStateOf(false) }

    var activeProvider by remember { mutableStateOf(prefs.activeProvider) }
    var openAiKey by remember { mutableStateOf(prefs.openAiKey) }
    var groqKey by remember { mutableStateOf(prefs.groqKey) }
    var geminiKey by remember { mutableStateOf(prefs.geminiKey) }
    var selectedLanguage by remember { mutableStateOf(prefs.language) }
    var activePreset by remember { mutableStateOf(prefs.activePreset) }
    var showOnlyWhenFocused by remember { mutableStateOf(prefs.showOnlyWhenFocused) }
    var stealthModeEnabled by remember { mutableStateOf(prefs.stealthModeEnabled) }
    var customVocabulary by remember { mutableStateOf(prefs.customVocabulary) }
    var hapticEnabled by remember { mutableStateOf(prefs.hapticFeedbackEnabled) }

    var notchOffsetY by remember { mutableStateOf(prefs.notchOffsetY) }

    var notchOffsetX by remember { mutableStateOf(prefs.notchOffsetX) }
    var isKeyVisible by remember { mutableStateOf(false) }
    var showHistoryVault by remember { mutableStateOf(false) }
    var showLogDumpSheet by remember { mutableStateOf(false) }
    var showUpdateSheet by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<com.openflow.android.util.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        val updateMgr = com.openflow.android.util.UpdateManager(context)
        val res = updateMgr.checkForUpdates()
        res.onSuccess { info ->
            if (info != null && info.isNewer) {
                availableUpdate = info
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    LaunchedEffect(resumeTrigger) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        isBatteryOptIgnored = PowerOptimizationHelper.isIgnoringBatteryOptimizations(context)
        if (FlowAccessibilityService.instance != null && !prefs.showOnlyWhenFocused) {
            FlowAccessibilityService.instance?.triggerShowBubble()
        }
    }

    Scaffold(
        containerColor = CanvasBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CardHighlight)
                                    .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp)),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.width(3.dp).height(10.dp).clip(RoundedCornerShape(1.5.dp)).background(TextSecondary))
                                Spacer(modifier = Modifier.width(3.dp))
                                Box(modifier = Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(1.5.dp)).background(TextPrimary))
                                Spacer(modifier = Modifier.width(3.dp))
                                Box(modifier = Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(1.5.dp)).background(TextSecondary))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "OpenFlow",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    letterSpacing = (-0.3).sp
                                )
                                Text(
                                    text = "Wispr Voice Engine",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // Status Badge in Top Bar
                        val isFullyConfigured = hasAccessibilityPermission && hasMicPermission && isBatteryOptIgnored
                        val statusBg = if (isFullyConfigured) StatusEmerald.copy(alpha = 0.12f) else StatusAmber.copy(alpha = 0.12f)
                        val statusText = if (isFullyConfigured) StatusEmerald else StatusAmber

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(statusBg)
                                .border(1.dp, statusText.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusText)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isFullyConfigured) "Pronto" else "Permessi",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = statusText
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showHistoryVault = true }) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History Vault",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasBg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Banner aggiornamento disponibile
            availableUpdate?.let { info ->
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showUpdateSheet = true },
                        color = StatusEmerald.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusEmerald.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = StatusEmerald,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Nuova Versione Disponibile (v${info.versionName})",
                                    fontWeight = FontWeight.Bold,
                                    color = StatusEmerald,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Tocca per scaricare e installare con 1 tocco",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = StatusEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // 1. Action Card: Test Bolla & Trigger
            item {
                TactileCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bolla Fluttuante",
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (FlowAccessibilityService.instance != null)
                                    "Servizio connesso. Tocca per mostrare la bolla."
                                else
                                    "Disconnesso. Tocca per riavviare il servizio.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))

                        TactileButton(
                            text = "Mostra Ora",
                            onClick = {
                                val service = FlowAccessibilityService.instance
                                if (service != null) {
                                    service.triggerShowBubble()
                                    Toast.makeText(context, "Bolla attiva a schermo", Toast.LENGTH_SHORT).show()
                                } else {
                                    val autoRestored = FlowAccessibilityService.restartAccessibilityServiceIfPermitted(context)
                                    if (autoRestored) {
                                        Toast.makeText(
                                            context,
                                            "⚡ OpenFlow ripristinato automaticamente!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Riavvio servizio: disattiva e riattiva OpenFlow nelle impostazioni.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // 2. Sezione Motore IA & Segmented Control
            item {
                SectionHeader(title = "MOTORE INTELLIGENZA ARTIFICIALE")
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Modern Fluid Segmented Pill Bar
                    ProviderSegmentedBar(
                        selectedProvider = activeProvider,
                        onProviderSelected = { provider ->
                            activeProvider = provider
                            prefs.activeProvider = provider
                        }
                    )

                    // API Key Field with clean styling
                    val currentKey = when (activeProvider) {
                        ApiProvider.GROQ -> groqKey
                        ApiProvider.GEMINI -> geminiKey
                        ApiProvider.OPENAI -> openAiKey
                    }

                    OutlinedTextField(
                        value = currentKey,
                        onValueChange = { newValue ->
                            when (activeProvider) {
                                ApiProvider.GROQ -> {
                                    groqKey = newValue
                                    prefs.groqKey = newValue
                                }
                                ApiProvider.GEMINI -> {
                                    geminiKey = newValue
                                    prefs.geminiKey = newValue
                                }
                                ApiProvider.OPENAI -> {
                                    openAiKey = newValue
                                    prefs.openAiKey = newValue
                                }
                            }
                        },
                        label = { Text("Chiave API ${activeProvider.displayName}") },
                        placeholder = {
                            Text(
                                when (activeProvider) {
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
                                if (currentKey.isBlank() && clipboard != null && clipboard.hasPrimaryClip()) {
                                    Text(
                                        text = "Incolla",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(CardHighlight)
                                            .clickable {
                                                val clipData = clipboard.primaryClip
                                                if (clipData != null && clipData.itemCount > 0) {
                                                    val pasted = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                                                    if (pasted.isNotBlank()) {
                                                        when (activeProvider) {
                                                            ApiProvider.GROQ -> { groqKey = pasted; prefs.groqKey = pasted }
                                                            ApiProvider.GEMINI -> { geminiKey = pasted; prefs.geminiKey = pasted }
                                                            ApiProvider.OPENAI -> { openAiKey = pasted; prefs.openAiKey = pasted }
                                                        }
                                                        Toast.makeText(context, "Chiave incollata!", Toast.LENGTH_SHORT).show()
                                                    }
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
                            focusedLabelColor = TextPrimary,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Minimal metadata caption
                    val providerDesc = when (activeProvider) {
                        ApiProvider.GROQ -> "⚡ 100% Gratis • Latenza ~200ms su chip LPU (console.groq.com)"
                        ApiProvider.GEMINI -> "🌟 100% Gratis • Trascrizione multimodale God-Tier (aistudio.google.com)"
                        ApiProvider.OPENAI -> "👑 Motore originale Wispr Flow (~0.003$ per vocale, platform.openai.com)"
                    }
                    Text(text = providerDesc, fontSize = 11.sp, color = TextSecondary)

                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)

                    // Lingua Dettatura
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lingua Dettatura",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val languages = listOf("it" to "IT", "en" to "EN", "auto" to "AUTO")
                            languages.forEach { (code, label) ->
                                val isSel = selectedLanguage == code
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) CardHighlight else Color.Transparent)
                                        .border(1.dp, if (isSel) HairlineBorderFocus else HairlineBorder, RoundedCornerShape(6.dp))
                                        .clickable {
                                            selectedLanguage = code
                                            prefs.language = code
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) TextPrimary else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Stile di Scrittura & Preset
            item {
                SectionHeader(title = "STILE EDITORIALE")
                Spacer(modifier = Modifier.height(6.dp))

                PresetSelector(
                    activePreset = activePreset,
                    onPresetSelected = { preset ->
                        activePreset = preset
                        prefs.activePreset = preset
                    }
                )
            }

            // 4. Permessi & Integrazione Sistema
            item {
                SectionHeader(title = "STATO SISTEMA & PERMESSI")
                Spacer(modifier = Modifier.height(6.dp))

                SystemStatusCard(
                    hasAccessibility = hasAccessibilityPermission,
                    hasMic = hasMicPermission,
                    hasOverlay = hasOverlayPermission,
                    isBatteryOptIgnored = isBatteryOptIgnored,
                    brand = currentBrand,
                    onOpenAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onRequestBatteryOpt = {
                        PowerOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                    },
                    onOpenBrandGuide = {
                        showBatteryGuideDialog = true
                    }
                )
            }

            // 5. Vocabolario Personalizzato (Glossary)
            item {
                SectionHeader(title = "VOCABOLARIO PERSONALIZZATO")
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Termini, brand, modelli hardware e nomi che non devono mai essere storpiati dai modelli Whisper o LLM (separati da virgole).",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.5.sp
                    )

                    OutlinedTextField(
                        value = customVocabulary,
                        onValueChange = {
                            customVocabulary = it
                            prefs.customVocabulary = it
                        },
                        placeholder = { Text("Michele, OpenFlow, Wispr Flow, 5800X3D...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HairlineBorderFocus,
                            unfocusedBorderColor = HairlineBorder,
                            focusedContainerColor = CardElevated,
                            unfocusedContainerColor = CardElevated,
                            cursorColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Iniettato in Whisper prompt & LLM",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        if (customVocabulary != "Michele, OpenFlow, Wispr Flow, Antigravity") {
                            Text(
                                text = "Ripristina predefiniti",
                                fontSize = 11.sp,
                                color = StatusEmerald,
                                modifier = Modifier.clickable {
                                    val defaultVocab = "Michele, OpenFlow, Wispr Flow, Antigravity"
                                    customVocabulary = defaultVocab
                                    prefs.customVocabulary = defaultVocab
                                }
                            )
                        }
                    }
                }
            }

            // 6. Opzioni Comportamento
            item {
                SectionHeader(title = "COMPORTAMENTO OVERLAY")
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    SwitchSettingRow(
                        title = "Mostra solo su campi di testo",
                        subtitle = "Nasconde la bolla quando la tastiera non è attiva",
                        checked = showOnlyWhenFocused,
                        onCheckedChange = {
                            showOnlyWhenFocused = it
                            prefs.showOnlyWhenFocused = it
                            if (!it) {
                                FlowAccessibilityService.instance?.triggerShowBubble()
                            } else {
                                FlowAccessibilityService.instance?.triggerHideBubble()
                            }
                        }
                    )

                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)

                    SwitchSettingRow(
                        title = "Stealth Mode (Auto-dim)",
                        subtitle = "Sfuma l'opacità al 28% dopo 3.5s in idle per non coprire i contenuti",
                        checked = stealthModeEnabled,
                        onCheckedChange = {
                            stealthModeEnabled = it
                            prefs.stealthModeEnabled = it
                        }
                    )

                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)

                    SwitchSettingRow(
                        title = "Feedback Aptico",
                        subtitle = "Vibrazione tattile discreta all'avvio e rilascio",
                        checked = hapticEnabled,
                        onCheckedChange = {
                            hapticEnabled = it
                            prefs.hapticFeedbackEnabled = it
                        }
                    )
                }
            }

            // Calibrazione Dynamic Island
            item {
                SectionHeader(title = "CALIBRAZIONE DYNAMIC ISLAND")
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "Allineamento Verticale (Su / Giù)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Offset Y: ${notchOffsetY.toInt()} dp (regola finemente l'altezza sul notch)",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val newY = (notchOffsetY - 1f).coerceIn(-15f, 20f)
                                notchOffsetY = newY
                                prefs.notchOffsetY = newY
                                FlowAccessibilityService.instance?.updateNotchPosition()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CardElevated)
                        ) {
                            Text("▲ Su (-1dp)", color = TextPrimary, fontSize = 13.sp)
                        }
                        Button(
                            onClick = {
                                val newY = (notchOffsetY + 1f).coerceIn(-15f, 20f)
                                notchOffsetY = newY
                                prefs.notchOffsetY = newY
                                FlowAccessibilityService.instance?.updateNotchPosition()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CardElevated)
                        ) {
                            Text("▼ Giù (+1dp)", color = TextPrimary, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Allineamento Orizzontale (Sinistra / Destra)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Offset X: ${notchOffsetX.toInt()} dp",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val newX = (notchOffsetX - 1f).coerceIn(-20f, 20f)
                                notchOffsetX = newX
                                prefs.notchOffsetX = newX
                                FlowAccessibilityService.instance?.updateNotchPosition()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CardElevated)
                        ) {
                            Text("◀ Sinistra (-1dp)", color = TextPrimary, fontSize = 13.sp)
                        }
                        Button(
                            onClick = {
                                val newX = (notchOffsetX + 1f).coerceIn(-20f, 20f)
                                notchOffsetX = newX
                                prefs.notchOffsetX = newX
                                FlowAccessibilityService.instance?.updateNotchPosition()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CardElevated)
                        ) {
                            Text("▶ Destra (+1dp)", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }



            // 7. Strumenti & Guida
            item {
                SectionHeader(title = "STRUMENTI & GUIDA")
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showHistoryVault = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "History Vault (Cronologia Trascrizioni)",
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Consulta, copia o elimina gli ultimi 50 vocali salvati offline",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenOnboarding() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Rivedi Configurazione Guidata",
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Riapri la procedura passo-passo e la guida ai gesti della bolla",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLogDumpSheet = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Diagnostica & Dump Log",
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Genera e invia il report di sistema e logcat per identificare bug",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showUpdateSheet = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Aggiornamenti GitHub",
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                if (availableUpdate != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = StatusEmerald,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "UPDATE",
                                            color = Color.Black,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "v${context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.2"} • Verifica release e scarica l'APK",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = if (availableUpdate != null) StatusEmerald else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showHistoryVault) {
            HistoryVaultSheet(onDismiss = { showHistoryVault = false })
        }

        if (showBatteryGuideDialog) {
            BatteryOptimizationGuideDialog(
                initialBrand = currentBrand,
                onDismiss = { showBatteryGuideDialog = false }
            )
        }

        if (showLogDumpSheet) {
            LogDumpSheet(
                prefs = prefs,
                onDismiss = { showLogDumpSheet = false }
            )
        }

        if (showUpdateSheet) {
            UpdateSheet(
                initialUpdateInfo = availableUpdate,
                onDismiss = { showUpdateSheet = false }
            )
        }
    }
}

// -------------------------------------------------------------
// Componenti UI Minimalisti & Anti-Slop (Swiss / Refactoring UI)
// -------------------------------------------------------------

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

@Composable
fun TactileCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
    ) {
        content()
    }
}

@Composable
fun TactileButton(
    text: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "button_scale"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(9.dp))
            .background(CardHighlight)
            .border(1.dp, HairlineBorderFocus, RoundedCornerShape(9.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}

@Composable
fun ProviderSegmentedBar(
    selectedProvider: ApiProvider,
    onProviderSelected: (ApiProvider) -> Unit
) {
    val providers = listOf(
        Triple(ApiProvider.GROQ, "Groq", "Free LPU"),
        Triple(ApiProvider.GEMINI, "Gemini", "Free AI Studio"),
        Triple(ApiProvider.OPENAI, "OpenAI", "Wispr Nativo")
    )
    val selectedIndex = providers.indexOfFirst { it.first == selectedProvider }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardElevated)
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(3.dp)
    ) {
        val tabWidth = maxWidth / providers.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "tab_slide"
        )

        // Pillola mobile animata
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(CardHighlight)
                .border(1.dp, HairlineBorderFocus, RoundedCornerShape(8.dp))
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            providers.forEach { (provider, label, sub) ->
                val isSelected = provider == selectedProvider
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(targetValue = if (isPressed) 0.96f else 1f, label = "tab_press")

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onProviderSelected(provider) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) TextPrimary else TextSecondary
                        )
                        Text(
                            text = sub,
                            fontSize = 10.sp,
                            color = if (isSelected) StatusEmerald else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PresetSelector(
    activePreset: PromptPreset,
    onPresetSelected: (PromptPreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
    ) {
        val presets = PromptPreset.values()
        presets.forEachIndexed { index, preset ->
            val isSelected = preset == activePreset
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(targetValue = if (isPressed) 0.985f else 1f, label = "preset_scale")
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) CardElevated else Color.Transparent,
                animationSpec = tween(150),
                label = "preset_bg"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(bgColor)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onPresetSelected(preset) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicatore di selezione minimal
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) StatusEmerald else TextMuted.copy(alpha = 0.4f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.title,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preset.description,
                        color = if (isSelected) TextSecondary else TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (index < presets.size - 1) {
                HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)
            }
        }
    }
}

@Composable
fun SystemStatusCard(
    hasAccessibility: Boolean,
    hasMic: Boolean,
    hasOverlay: Boolean,
    isBatteryOptIgnored: Boolean,
    brand: DeviceBrand,
    onOpenAccessibility: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBatteryOpt: () -> Unit,
    onOpenBrandGuide: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
    ) {
        StatusRow(
            title = "Accessibilità OpenFlow",
            subtitle = "Intercetta i campi testo e inserisce la trascrizione",
            isGranted = hasAccessibility,
            onClick = onOpenAccessibility
        )
        HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)
        StatusRow(
            title = "Microfono ad Alta Fedeltà",
            subtitle = "Cattura l'audio con DSP di cancellazione rumore",
            isGranted = hasMic,
            onClick = onRequestMic
        )
        HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)
        StatusRow(
            title = "Sovrapposizione Overlay",
            subtitle = "Consente alla bolla di fluttuare sopra le altre app",
            isGranted = hasOverlay,
            onClick = onRequestOverlay
        )
        HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)
        StatusRow(
            title = "Esenzione Batteria Standard (Doze)",
            subtitle = "Consente l'esecuzione senza restrizioni a livello Android",
            isGranted = isBatteryOptIgnored,
            onClick = onRequestBatteryOpt
        )
        HorizontalDivider(color = HairlineBorder.copy(alpha = 0.6f), thickness = 1.dp)
        StatusRow(
            title = "⚡ Ottimizzazione ${brand.title.substringBefore(" (")}",
            subtitle = "Guida e configurazione anti-chiusura per il tuo dispositivo",
            isGranted = false,
            actionText = "Guida & Setup",
            onClick = onOpenBrandGuide
        )
    }
}

@Composable
fun BatteryOptimizationGuideDialog(
    initialBrand: DeviceBrand,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedBrand by remember { mutableStateOf(initialBrand) }
    val brands = DeviceBrand.values()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardElevated,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.border(1.dp, HairlineBorderFocus, RoundedCornerShape(18.dp)),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(StatusEmerald.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = StatusEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Guida Standby & Anti-Kill",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Alcuni produttori chiudono aggressivamente le app in background anche con i permessi attivi. Ecco i passaggi consigliati per evitare freeze:",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.5.sp
                )

                // Brand Selector Row
                Text(
                    text = "PRODUTTORE SELEZIONATO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 0.5.sp
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(brands) { b ->
                        val isSelected = b == selectedBrand
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StatusEmerald.copy(alpha = 0.18f) else CardHighlight)
                                .border(
                                    1.dp,
                                    if (isSelected) StatusEmerald else HairlineBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedBrand = b }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = b.title.substringBefore(" (").substringBefore(" /"),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) StatusEmerald else TextPrimary
                            )
                        }
                    }
                }

                // Brand Summary Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = selectedBrand.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = selectedBrand.summary,
                        fontSize = 11.5.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }

                // Steps Checklist
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    selectedBrand.steps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(StatusEmerald)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = step,
                                fontSize = 11.5.sp,
                                color = TextPrimary,
                                lineHeight = 16.5.sp
                            )
                        }
                    }
                }

                // Action Button: Open Manufacturer Settings
                Button(
                    onClick = {
                        PowerOptimizationHelper.openManufacturerSettings(context, selectedBrand)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardHighlight),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorderFocus)
                ) {
                    Text(
                        text = "Apri Impostazioni di Sistema",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                // Link DontKillMyApp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(selectedBrand.guideUrl)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Consulta la guida DontKillMyApp.com ↗",
                        fontSize = 11.sp,
                        color = StatusEmerald,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
fun StatusRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionText: String = "Abilita",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = TextPrimary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(1.dp))
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        if (isGranted) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(StatusEmerald.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(StatusEmerald)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("Attivo", color = StatusEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardHighlight)
                    .border(1.dp, HairlineBorderFocus, RoundedCornerShape(8.dp))
                    .clickable { onClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(actionText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
        }
    }
}

@Composable
fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = TextPrimary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(1.dp))
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = CardHighlight,
                checkedBorderColor = HairlineBorderFocus,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = CardElevated,
                uncheckedBorderColor = HairlineBorder
            )
        )
    }
}

internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    if (FlowAccessibilityService.instance != null) return true
    try {
        val enabledSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (enabledSetting.contains(context.packageName)) return true
    } catch (_: Exception) {}
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
