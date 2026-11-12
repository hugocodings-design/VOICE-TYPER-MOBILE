package com.hugocodings.voicetyper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hugocodings.voicetyper.core.BubbleMode
import com.hugocodings.voicetyper.core.Lang
import com.hugocodings.voicetyper.core.Preset
import com.hugocodings.voicetyper.core.Replacement
import com.hugocodings.voicetyper.core.Settings
import com.hugocodings.voicetyper.core.Style
import com.hugocodings.voicetyper.core.VoiceCommands

private val Green = Color(0xFF22C55E)
private val PRESET_COLORS = listOf(0xFF22C55E, 0xFF3B82F6, 0xFFF59E0B, 0xFFA855F7, 0xFFEC4899, 0xFF14B8A6, 0xFF6B7280)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.init(this)
        enableEdgeToEdge()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Green, secondary = Green)
            else lightColorScheme(primary = Color(0xFF16A34A), secondary = Green)
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
    }
}

private fun update(f: (Settings) -> Settings) = SettingsRepository.update(f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen() {
    val s by SettingsRepository.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Preset?>(null) }
    var pickingApp by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Voice Typer", fontWeight = FontWeight.Bold) }) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StatusCard() }
            item { TryCard() }
            item { LanguageCard(s) }
            item { PresetsCard(s, onEdit = { editing = it }) }
            item { AppRulesCard(s, onAdd = { pickingApp = true }) }
            item { DictionaryCard(s) }
            item { SignatureCard(s) }
            item { BubbleCard(s) }
            item { CommandsCard() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    editing?.let { p -> PresetDialog(p, isNew = s.preset(p.id) == null, onDismiss = { editing = null }) }
    if (pickingApp) AppPickerDialog(s, onDismiss = { pickingApp = false })
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

// ---------------- status / setup ----------------

private fun accessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
    val me = ComponentName(context, VoiceAccessibilityService::class.java)
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.let { si -> ComponentName(si.packageName, si.name) == me } }
}

@Composable
private fun StatusCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    key(tick) {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val service = accessibilityEnabled(context)
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        Section("Mise en route", "Trois étapes, une seule fois.") {
            StepRow("1. Micro", mic, "Autoriser") { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            StepRow("2. Bulle de dictée (accessibilité)", service, "Activer") {
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                StepRow("3. Notification pendant la dictée", notif, "Autoriser") {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            HorizontalDivider()
            Text(
                if (onDevice) "✓ Reconnaissance vocale sur l'appareil disponible : rien ne quitte le téléphone."
                else "Reconnaissance sur l'appareil non détectée. Installez la langue hors ligne ci-dessous " +
                    "ou désactivez « Hors ligne seulement » dans Bulle.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { openOfflineLanguages(context) }) { Text("Reconnaissance hors ligne (langues)…") }
            if (!service) {
                Text(
                    "Android affiche un avertissement en activant un service d'accessibilité : Voice Typer ne lit " +
                        "que le champ de texte actif et le nom de l'application, et n'a pas accès à Internet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    HorizontalDivider()
                    Text("« Accès refusé » / « Paramètre restreint » ?", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android bloque l'accessibilité des apps installées hors Play Store. Touchez le bouton, " +
                            "puis ⋮ (en haut à droite) → « Autoriser les paramètres restreints », confirmez, " +
                            "et revenez activer la bulle (étape 2). Sur Samsung, si l'option n'apparaît pas : " +
                            "Paramètres → Sécurité et confidentialité → Blocage automatique → désactiver le temps de l'activer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Ouvrir les infos de Voice Typer") }
                }
            }
        }
    }
}

private fun openOfflineLanguages(context: Context) {
    val tries = listOf(
        Intent("com.google.android.settings.SPEECH_RECOGNITION_SETTINGS"),
        Intent().setComponent(ComponentName("com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.greco3.languagepack.InstallActivity")),
        Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS),
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (done) Green else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f))
        if (!done) FilledTonalButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun TryCard() {
    var text by remember { mutableStateOf("") }
    Section("Essayer", "Touchez le champ : la bulle apparaît. Touchez-la, parlez, touchez-la encore. Appui long = autre préréglage.") {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp), placeholder = { Text("Dictez ici…") })
    }
}

// ---------------- language ----------------

@Composable
private fun LanguageCard(s: Settings) {
    Section("Langue de dictée") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Auto", Lang.FR to "Français", Lang.EN to "English").forEach { (lang, label) ->
                FilterChip(selected = s.language == lang, onClick = { update { it.copy(language = lang) } }, label = { Text(label) })
            }
        }
        Text("Auto = la langue du téléphone (${SpeechEngine.languageTag(null)}).", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- presets ----------------

@Composable
private fun ColorDot(color: Long, size: Int = 14) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)))
}

@Composable
private fun PresetsCard(s: Settings, onEdit: (Preset) -> Unit) {
    Section("Préréglages", "La façon d'écrire. Le préréglage par défaut sert partout où aucune règle d'application ne s'applique.") {
        s.presets.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { onEdit(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(p.color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = if (p.id == s.defaultPresetId) FontWeight.Bold else FontWeight.Normal)
                    Text(p.style.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.id == s.defaultPresetId) AssistChip(onClick = {}, label = { Text("Par défaut") })
                else TextButton(onClick = { update { it.copy(defaultPresetId = p.id) } }) { Text("Défaut") }
            }
        }
        OutlinedButton(onClick = {
            onEdit(Preset(id = "p" + System.currentTimeMillis().toString(36), name = "Nouveau", style = Style.CLEAN, color = PRESET_COLORS[4]))
        }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Nouveau préréglage") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDialog(initial: Preset, isNew: Boolean, onDismiss: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nouveau préréglage" else "Modifier « ${initial.name} »") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Nom") }, singleLine = true)
                Text("Style", style = MaterialTheme.typography.labelLarge)
                Style.entries.forEach { st ->
                    Row(Modifier.fillMaxWidth().clickable { p = p.copy(style = st) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.style == st, onClick = { p = p.copy(style = st) })
                        Column { Text(st.label); Text(st.description, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("Langue", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Comme réglé", Lang.FR to "FR", Lang.EN to "EN").forEach { (l, label) ->
                        FilterChip(selected = p.language == l, onClick = { p = p.copy(language = l) }, label = { Text(label) })
                    }
                }
                SwitchRow("Commandes vocales (« à la ligne », « virgule »…)", p.voiceCommands) { p = p.copy(voiceCommands = it) }
                SwitchRow("Retirer les hésitations (euh, hum…)", p.removeFillers) { p = p.copy(removeFillers = it) }
                OutlinedTextField(p.prefix, { p = p.copy(prefix = it) }, label = { Text("Texte avant (optionnel)") })
                OutlinedTextField(p.suffix, { p = p.copy(suffix = it) }, label = { Text("Texte après (optionnel)") })
                Text("Couleur de la bulle", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PRESET_COLORS.forEach { c ->
                        Box(Modifier.size(if (p.color == c) 30.dp else 24.dp).clip(CircleShape).background(Color(c)).clickable { p = p.copy(color = c) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = p.name.isNotBlank(), onClick = {
                update { st ->
                    val list = if (st.presets.any { it.id == p.id }) st.presets.map { if (it.id == p.id) p else it } else st.presets + p
                    st.copy(presets = list)
                }
                onDismiss()
            }) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = {
                    update { st -> st.copy(presets = st.presets.filterNot { it.id == p.id }) }
                    onDismiss()
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

// ---------------- per-app ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(s: Settings, selectedId: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sel = s.preset(selectedId)
    Box {
        AssistChip(onClick = { open = true }, label = { Text(sel?.name ?: "?") }, leadingIcon = { sel?.let { ColorDot(it.color, 10) } })
        DropdownMenu(open, { open = false }) {
            s.presets.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, leadingIcon = { ColorDot(p.color, 10) }, onClick = { onPick(p.id); open = false })
            }
        }
    }
}

@Composable
private fun AppRulesCard(s: Settings, onAdd: () -> Unit) {
    Section("Par application", "Le préréglage choisi automatiquement selon l'app (comme Outlook → Courriel sur PC). " +
        "Aussi possible depuis la bulle : appui long → « Toujours dans … ».") {
        s.appRules.sortedBy { it.label.ifEmpty { it.packageName }.lowercase() }.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.label.ifEmpty { r.packageName }, Modifier.weight(1f))
                PresetPicker(s, r.presetId) { id -> update { it.withAppPreset(r.packageName, id, r.label) } }
                IconButton(onClick = { update { it.withAppPreset(r.packageName, null) } }) { Icon(Icons.Filled.Close, "Retirer") }
            }
        }
        OutlinedButton(onClick = onAdd) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter une application") }
        if (s.hiddenApps.isNotEmpty()) {
            HorizontalDivider()
            Text("Bulle masquée dans :", style = MaterialTheme.typography.labelLarge)
            s.hiddenApps.forEach { pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { update { it.copy(hiddenApps = it.hiddenApps - pkg) } }) { Text("Réafficher") }
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(s: Settings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .sortedBy { it.second.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir une application") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Rechercher") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(apps.filter { query.isBlank() || it.second.contains(query, true) }) { (pkg, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable {
                            update { it.withAppPreset(pkg, s.defaultPresetId, label) }
                            onDismiss()
                        }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

// ---------------- dictionary / signature ----------------

@Composable
private fun DictionaryCard(s: Settings) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    Section("Dictionnaire", "Remplacements automatiques après la dictée (« chat GPT » → « ChatGPT »).") {
        s.replacements.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.from}  →  ${r.to}", Modifier.weight(1f))
                IconButton(onClick = { update { it.copy(replacements = it.replacements - r) } }) { Icon(Icons.Filled.Close, "Retirer") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(from, { from = it }, Modifier.weight(1f), label = { Text("Entendu") }, singleLine = true)
            OutlinedTextField(to, { to = it }, Modifier.weight(1f), label = { Text("Écrire") }, singleLine = true)
            IconButton(enabled = from.isNotBlank(), onClick = {
                update { it.copy(replacements = it.replacements + Replacement(from.trim(), to)) }
                from = ""; to = ""
            }) { Icon(Icons.Filled.Add, "Ajouter") }
        }
    }
}

@Composable
private fun SignatureCard(s: Settings) {
    var text by remember { mutableStateOf(s.signature) }
    Section("Signature des courriels", "Ajoutée à la fin du préréglage Courriel.") {
        OutlinedTextField(text, { text = it; update { st -> st.copy(signature = it) } }, Modifier.fillMaxWidth(),
            placeholder = { Text("Hugo\n514 555-0123") }, minLines = 2)
    }
}

// ---------------- bubble ----------------

@Composable
private fun BubbleCard(s: Settings) {
    Section("Bulle") {
        Text("Afficher la bulle", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.bubbleMode == BubbleMode.WHEN_TYPING, { update { it.copy(bubbleMode = BubbleMode.WHEN_TYPING) } }, { Text("Quand j'écris") })
            FilterChip(s.bubbleMode == BubbleMode.ALWAYS, { update { it.copy(bubbleMode = BubbleMode.ALWAYS) } }, { Text("Toujours") })
        }
        Text("Taille : ${s.bubbleSizeDp} dp", style = MaterialTheme.typography.labelLarge)
        Slider(s.bubbleSizeDp.toFloat(), { v -> update { it.copy(bubbleSizeDp = v.toInt()) } }, valueRange = 40f..80f, steps = 7)
        SwitchRow("Toucher pour arrêter (sinon arrêt au silence)", s.tapToStop) { v -> update { it.copy(tapToStop = v) } }
        SwitchRow("Hors ligne seulement (recommandé)", s.offlineOnly) { v -> update { it.copy(offlineOnly = v) } }
        SwitchRow("Espace après chaque dictée", s.trailingSpace) { v -> update { it.copy(trailingSpace = v) } }
        SwitchRow("Vibrations", s.haptics) { v -> update { it.copy(haptics = v) } }
        TextButton(onClick = { update { it.copy(bubbleX = -1, bubbleY = -1) } }) { Text("Remettre la bulle à sa place") }
    }
}

@Composable
private fun CommandsCard() {
    var lang by remember { mutableStateOf(Lang.FR) }
    Section("Commandes vocales", "Dites-les pendant la dictée. « efface ça » dit seul efface la dernière dictée.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(lang == Lang.FR, { lang = Lang.FR }, { Text("Français") })
            FilterChip(lang == Lang.EN, { lang = Lang.EN }, { Text("English") })
        }
        VoiceCommands.COMMANDS.getValue(lang).forEach { c ->
            Row {
                Text(c.phrases.first(), Modifier.weight(1f))
                Text(c.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Appui long sur la bulle pendant la dictée = annuler.", style = MaterialTheme.typography.bodySmall)
    }
}
