package com.osone.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(viewModel: OsoneViewModel, onMic: () -> Unit, onAttach: () -> Unit, permissionError: Boolean,
    onSettings: () -> Unit, onWriting: () -> Unit, onRoutines: () -> Unit, diagnostics: AppDiagnostics, onDiagnostics: () -> Unit,
    readAloud: Boolean, onReadAloud: () -> Unit, liveActive: Boolean, onOpenCode: (String, String) -> Unit,
    onAnswer: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    LaunchedEffect(viewModel.incomingText) {
        viewModel.incomingText?.let { shared -> draft = if (draft.isBlank()) shared else "$draft\n$shared"; viewModel.consumeIncoming() }
    }
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) scroll.animateScrollToItem(viewModel.messages.lastIndex)
    }
    LaunchedEffect(viewModel.streamingText.length) {
        if (viewModel.streamingText.isNotBlank()) scroll.scrollToItem(viewModel.messages.size)
    }
    val send: () -> Unit = { if (viewModel.send(draft, onAnswer)) draft = "" }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OstieTopBar(title = "OSTIE", subtitle = viewModel.selectedChatLabel,
            navigation = {
                Box {
                    BarIcon(OstieIcons.Menu, "Abrir menu", { menuExpanded = true })
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Aba de Escrita") },
                            leadingIcon = { Icon(OstieIcons.Document, contentDescription = null) },
                            onClick = { menuExpanded = false; onWriting() })
                        DropdownMenuItem(text = { Text("Rotinas") },
                            leadingIcon = { Icon(OstieIcons.Tune, contentDescription = null) },
                            onClick = { menuExpanded = false; onRoutines() })
                        DropdownMenuItem(text = { Text(if (liveActive) "Voltar ao Live" else "Conversa Live") },
                            leadingIcon = { Icon(OstieIcons.Wave, contentDescription = null) },
                            onClick = { menuExpanded = false; onMic() })
                        DropdownMenuItem(text = { Text("Ajustes") },
                            leadingIcon = { Icon(OstieIcons.Settings, contentDescription = null) },
                            onClick = { menuExpanded = false; onSettings() })
                    }
                }
            },
            leading = { OrbLogo(34.dp) }) {
            BarIcon(if (readAloud) OstieIcons.VolumeOn else OstieIcons.VolumeOff,
                if (readAloud) "Desligar leitura das respostas" else "Ler respostas em voz alta", onReadAloud,
                tint = if (readAloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            DiagnosticsDot(diagnostics, onDiagnostics)
            BarIcon(OstieIcons.Settings, "Ajustes", onSettings)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (viewModel.messages.isEmpty() && viewModel.streamingText.isBlank()) EmptyChat(onMic)
            else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(viewModel.messages) { message -> MessageBubble(message.text, message.role == "user", onOpenCode) }
                if (viewModel.streamingText.isNotBlank()) item { MessageBubble(viewModel.streamingText, false, null) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (viewModel.busy) {
                Hint("Consultando ${viewModel.activeTextModel ?: viewModel.selectedChatLabel}…")
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                Spacer(Modifier.height(6.dp))
            } else if (viewModel.provider == ChatProvider.GEMINI && viewModel.lastAnswerModel != null &&
                viewModel.lastAnswerModel != viewModel.selectedModel) {
                Hint("Última resposta: ${viewModel.lastAnswerModel?.label}")
            }
            viewModel.error?.let { message ->
                Hint(if (viewModel.attachment != null) message else "Falha no chat · toque no indicador vermelho para ver o erro.",
                    MaterialTheme.colorScheme.error)
            }
            if (permissionError) Hint("Permita o microfone para conversar por voz.", MaterialTheme.colorScheme.error)
            viewModel.attachment?.let { selected ->
                Spacer(Modifier.height(6.dp))
                InputChip(selected = true, onClick = viewModel::removeAttachment,
                    label = { Text(selected.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(OstieIcons.Attach, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { Icon(OstieIcons.Close, contentDescription = "Remover anexo", modifier = Modifier.size(18.dp)) })
            }
        }
        Composer(draft, { draft = it }, canSend = (draft.isNotBlank() || viewModel.attachment != null) && !viewModel.busy,
            busy = viewModel.busy, liveActive = liveActive, onAttach = onAttach, onMic = onMic, onSend = send)
    }
}

@Composable
private fun EmptyChat(onMic: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        OrbLogo(110.dp)
        Spacer(Modifier.height(18.dp))
        Text("Como posso ajudar?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Escreva abaixo ou toque na onda para conversar por voz.", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onMic) {
            Icon(OstieIcons.Wave, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Iniciar Live")
        }
    }
}

@Composable
private fun MessageBubble(text: String, user: Boolean, onOpenCode: ((String, String) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top) {
        if (!user) {
            OrbLogo(26.dp)
            Spacer(Modifier.width(8.dp))
        }
        Surface(color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column {
                val linkColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                val content = remember(text, linkColor) { linkify(text, linkColor) }
                Text(content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge)
                val code = if (user || onOpenCode == null) null else remember(text) { DocumentPreview.codeBlock(text) }
                if (code != null && onOpenCode != null) {
                    val markup = DocumentPreview.detectFormat(code.first, code.second) == "html"
                    TextButton(onClick = { onOpenCode(code.second, code.first) },
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                        Icon(if (markup) OstieIcons.Play else OstieIcons.Document, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (markup) "Visualizar na Aba de Escrita" else "Abrir na Aba de Escrita")
                    }
                }
            }
        }
    }
}

private val urlPattern = Regex("https?://[^\\s)\\]]+")

/** Links (como as fontes da Pesquisa Google) ficam tocáveis e abrem no navegador. */
private fun linkify(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    urlPattern.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withLink(LinkAnnotation.Url(match.value, TextLinkStyles(SpanStyle(color = color,
            textDecoration = TextDecoration.Underline)))) { append(match.value) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, canSend: Boolean, busy: Boolean, liveActive: Boolean,
    onAttach: () -> Unit, onMic: () -> Unit, onSend: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
            BarIcon(OstieIcons.Attach, "Anexar arquivo", onAttach, enabled = !busy,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f), maxLines = 5,
                placeholder = { Text("Mensagem") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
            if (canSend || draft.isNotBlank()) {
                FilledIconButton(onClick = onSend, enabled = canSend, shape = CircleShape,
                    modifier = Modifier.padding(4.dp).size(48.dp)) {
                    Icon(OstieIcons.Send, contentDescription = "Enviar", modifier = Modifier.size(20.dp))
                }
            } else {
                FilledIconButton(onClick = onMic, shape = CircleShape, modifier = Modifier.padding(4.dp).size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (liveActive) OstieColors.Success else MaterialTheme.colorScheme.primary)) {
                    Icon(OstieIcons.Wave, contentDescription = if (liveActive) "Voltar ao Live" else "Conversa Live por voz",
                        modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
