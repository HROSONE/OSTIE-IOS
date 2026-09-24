package com.osone.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/** Paleta derivada da esfera azul do logo. */
object OstieColors {
    val Blue = Color(0xFF3D7BFF)
    val Cyan = Color(0xFF3ED6FF)
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF2FBF71)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F6BFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF0A1F5C),
    secondary = Color(0xFF0B8FB8), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F3FF), onSecondaryContainer = Color(0xFF003546),
    background = Color(0xFFF4F6FB), onBackground = Color(0xFF111827),
    surface = Color(0xFFF4F6FB), onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE4E8F2), onSurfaceVariant = Color(0xFF4A5468),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFCFF),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFECEFF7),
    surfaceContainerHighest = Color(0xFFE2E6F0),
    outline = Color(0xFFBCC4D4), outlineVariant = Color(0xFFDDE2EC),
    error = Color(0xFFD13A40), onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8DB2FF), onPrimary = Color(0xFF0A1F5C),
    primaryContainer = Color(0xFF1F3C8F), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF6ADDFF), onSecondary = Color(0xFF003546),
    secondaryContainer = Color(0xFF0E4356), onSecondaryContainer = Color(0xFFD5F3FF),
    background = Color(0xFF090D18), onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF090D18), onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1A2133), onSurfaceVariant = Color(0xFFA6AFC3),
    surfaceContainerLowest = Color(0xFF060911), surfaceContainerLow = Color(0xFF0E1320),
    surfaceContainer = Color(0xFF121828), surfaceContainerHigh = Color(0xFF192032),
    surfaceContainerHighest = Color(0xFF212A3F),
    outline = Color(0xFF3A4459), outlineVariant = Color(0xFF263045),
    error = Color(0xFFFF7070), onError = Color(0xFF410002)
)

@Composable
fun OstieTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)),
        content = content)
}

/** Barra superior compacta: navegação, título com subtítulo opcional e ações em ícones. */
@Composable
fun OstieTopBar(title: String, subtitle: String? = null, navigation: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (navigation != null) navigation() else Spacer(Modifier.width(12.dp))
        leading?.let { it(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

@Composable
fun OrbLogo(size: Dp) {
    Image(painterResource(R.drawable.ostie_orb), contentDescription = null, modifier = Modifier.size(size))
}

/** Botão redondo só com ícone; o nome fica na descrição de acessibilidade. */
@Composable
fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit, active: Boolean = false,
    enabled: Boolean = true, container: Color? = null, size: Dp = 56.dp) {
    val colors = MaterialTheme.colorScheme
    val background = container ?: if (active) colors.primary else colors.surfaceContainerHighest
    val foreground = when {
        container != null -> Color.White
        active -> colors.onPrimary
        else -> colors.onSurface
    }
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape,
        color = if (enabled) background else background.copy(alpha = 0.4f),
        modifier = Modifier.size(size).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (enabled) foreground else foreground.copy(alpha = 0.45f),
                modifier = Modifier.size(size * 0.43f))
        }
    }
}

@Composable
fun SectionCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp, shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Campo de escolha único para modelos, vozes e provedores. */
@Composable
fun <T> OptionPicker(label: String, value: String, options: List<T>, optionLabel: (T) -> String,
    onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(OstieIcons.ArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option ->
                val text = optionLabel(option)
                val mark: (@Composable () -> Unit)? = if (text == value) {
                    { Icon(OstieIcons.Check, contentDescription = "Selecionado", tint = MaterialTheme.colorScheme.primary) }
                } else null
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(option); expanded = false },
                    trailingIcon = mark)
            }
        }
    }
}

@Composable
fun LiveModelPicker(live: LiveVoiceViewModel) {
    OptionPicker("Modelo de voz", live.selected.label, live.models, { it.label }) { model ->
        live.select(model)
        if (live.connected || live.active != null) live.start()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("ID: ${live.selected.id}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = live::refreshModels, enabled = !live.modelsLoading) {
            Text(if (live.modelsLoading) "Consultando…" else "Atualizar lista")
        }
    }
    live.modelsStatus?.let { Hint(it) }
}

@Composable
fun LiveVoicePicker(live: LiveVoiceViewModel) {
    OptionPicker("Voz", live.voice, LiveVoices.names, { it }) { live.selectVoice(it) }
}

@Composable
fun DiagnosticsDot(diagnostics: AppDiagnostics, onOpen: () -> Unit) {
    IconButton(onClick = onOpen, modifier = Modifier.semantics {
        contentDescription = if (diagnostics.unread > 0) "Erros novos: ${diagnostics.unread}. Abrir diagnóstico"
            else "Estado do aplicativo: sem erros novos. Abrir diagnóstico"
    }) {
        val color = if (diagnostics.unread > 0) OstieColors.Danger else OstieColors.Success
        Canvas(Modifier.size(12.dp)) {
            drawCircle(color.copy(alpha = 0.25f), radius = size.minDimension / 2)
            drawCircle(color, radius = size.minDimension / 3.2f)
        }
    }
}

@Composable
fun DiagnosticsDialog(diagnostics: AppDiagnostics, onClose: () -> Unit) {
    LaunchedEffect(Unit) { diagnostics.markRead() }
    val format = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR")) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Diagnóstico") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (diagnostics.events.isEmpty()) Text("Nenhuma falha registrada neste aparelho.")
                diagnostics.events.asReversed().forEach { event ->
                    Column {
                        Text("${format.format(event.timestamp)} · ${event.area}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(event.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = diagnostics::clear) { Text("Limpar log") } })
}

/** Pergunta quem escreve o código pedido por voz: o modelo Live ou o modelo de texto do chat. */
@Composable
fun CodeAuthorDialog(request: CodeRequest, voiceLabel: String?, textLabel: String,
    onChoose: (useText: Boolean, remember: Boolean) -> Unit, onDismiss: () -> Unit) {
    var remember by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(OstieIcons.Document, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Qual modelo deve codar?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.request, style = MaterialTheme.typography.bodyMedium, maxLines = 4,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AuthorOption(OstieIcons.Wave, "Modelo de voz", voiceLabel ?: "Live desconectado",
                    enabled = voiceLabel != null) { onChoose(false, remember) }
                AuthorOption(OstieIcons.Chat, "Modelo de texto", "$textLabel · mais cuidadoso para código",
                    enabled = true) { onChoose(true, remember) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Lembrar minha escolha", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun AuthorOption(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
                Hint(subtitle)
            }
        }
    }
}

@Composable
fun CodeAuthorPicker(author: CodeAuthor) {
    OptionPicker("Quem escreve código pedido por voz", author.preference.label, CodeAuthorChoice.entries,
        { it.label }, author::updatePreference)
}

@Composable
fun UpdatePromptDialog(release: OstieUpdate, onUpdate: () -> Unit, onLater: () -> Unit) {
    AlertDialog(onDismissRequest = onLater,
        icon = { Icon(OstieIcons.ArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("OSTIE ${release.versionName} disponível") },
        text = { Text(release.notes.ifBlank { "Uma nova versão está pronta. Seus dados e chaves são mantidos." }) },
        confirmButton = { Button(onClick = onUpdate) { Text("Atualizar agora") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Depois") } })
}
