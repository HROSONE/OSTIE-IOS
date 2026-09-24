package com.osone.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Controla uma única chamada Live; nunca grava áudio ou transcrições em disco. */
class LiveVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("osone_config", 0)
    private val secrets = SecureKeyStore(application)
    private val diagnostics = AppDiagnostics.get(application)
    private val localTools = AndroidLocalTools(application)
    private val writing = WritingWorkspace.get(application)
    private val codeAuthor = CodeAuthor.get(application)
    private val phoneActions = PhoneActions(application)
    private val memory = MemoryStore.get(application)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val endpoint = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** Modelos Live que a chave aceita (ListModels), em cache; padrões até a primeira consulta. */
    var models by mutableStateOf(LiveModel.fromJson(preferences.getString("live_models", null)).ifEmpty { LiveModel.DEFAULTS })
        private set
    var modelsStatus by mutableStateOf<String?>(null)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var selected by mutableStateOf(LiveModel.fromId(preferences.getString("live_model", null), models))
        private set
    var voice by mutableStateOf(LiveVoices.fromName(preferences.getString("live_voice", null)))
        private set
    var fallback by mutableStateOf(preferences.getBoolean("live_fallback", true))
        private set
    var echoGuard by mutableStateOf(preferences.getBoolean("live_echo_guard", true))
        private set
    var active by mutableStateOf<LiveModel?>(null)
        private set
    var status by mutableStateOf("Pronto para conversar")
        private set
    var connected by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var inputLevel by mutableStateOf(0f)
        private set
    var outputLevel by mutableStateOf(0f)
        private set
    var attempts by mutableStateOf<List<String>>(emptyList())
        private set
    var screenSharing by mutableStateOf(false)
    var screenFramesSent by mutableStateOf(0)
        private set
    var screenFramesCaptured by mutableStateOf(0)
        private set
    var screenFramesSkipped by mutableStateOf(0)
        private set
    var lastScreenFrameAt by mutableStateOf(0L)
        private set
    var cameraSharing by mutableStateOf(false)
    var cameraFront by mutableStateOf(false)
    var cameraPreview by mutableStateOf<Bitmap?>(null)
        private set
    var cameraFramesCaptured by mutableStateOf(0)
        private set
    var cameraFramesSent by mutableStateOf(0)
        private set
    var cameraFramesSkipped by mutableStateOf(0)
        private set
    var lastCameraFrameAt by mutableStateOf(0L)
        private set
    var localToolsAvailable by mutableStateOf(true)
        private set
    /** Pesquisa Google do próprio Gemini Live; desliga sozinha se o modelo recusar. */
    var searchAvailable by mutableStateOf(true)
        private set
    /** Descrição do modo reduzido quando o modelo recusou parte da configuração; nulo = completo. */
    var reducedMode by mutableStateOf<String?>(null)
        private set
    /** 0 completo · 1 sem pesquisa · 2 sem legendas/retomada · 3 só ferramentas básicas · 4 só voz (24 h por modelo). */
    private var setupLevel = 0
    private var extendedTools = true
    private var extrasAvailable = true
    /** Retomada de sessão: o servidor manda um identificador e a reconexão continua a mesma conversa. */
    @Volatile private var resumeHandle: String? = null
    /** true depois que o handshake recusou a chave no cabeçalho. */
    private var keyInUrl = false
    private var resumeModel: String? = null
    private var sentHandle = false
    /** Legendas do turno atual (transcrição da API Live). */
    var captionUser by mutableStateOf("")
        private set
    var captionModel by mutableStateOf("")
        private set
    var captions by mutableStateOf(preferences.getBoolean("live_captions", true))
        private set
    var saveTranscript by mutableStateOf(preferences.getBoolean("live_save_transcript", true))
        private set
    private val turnUser = StringBuilder()
    private val turnModel = StringBuilder()
    private var readyAt = 0L
    @Volatile private var heardFromModel = false

    private var candidates = emptyList<LiveModel>()
    private var candidateIndex = 0
    private var key: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var audio: LiveAudioEngine? = null
    private val lastInputUi = AtomicLong(0L)
    private val lastOutputUi = AtomicLong(0L)
    private var interruptedAt = 0L
    private var interruptions = 0
    private var lastBackpressure = 0L
    private var reconnectOnce = false
    @Volatile private var running = false

    fun select(model: LiveModel) {
        selected = model
        preferences.edit().putString("live_model", model.id).apply()
    }

    fun updateCaptions(enabled: Boolean) {
        captions = enabled
        preferences.edit().putBoolean("live_captions", enabled).apply()
    }

    fun updateSaveTranscript(enabled: Boolean) {
        saveTranscript = enabled
        preferences.edit().putBoolean("live_save_transcript", enabled).apply()
    }

    /** Consulta v1beta/models com a chave Gemini e guarda só os modelos da API Live. */
    fun refreshModels() {
        val apiKey = secrets.read() ?: run { modelsStatus = "Salve a chave Gemini em Ajustes."; return }
        if (modelsLoading) return
        modelsLoading = true
        modelsStatus = "Consultando modelos Live desta chave…"
        Thread({
            val result = try {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                    .header("x-goog-api-key", apiKey).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    LiveModel.parseCatalog(response.body.string())
                }
            } catch (failure: Exception) {
                main.post {
                    modelsLoading = false
                    modelsStatus = "Não consegui listar os modelos (${failure.message?.take(60) ?: failure.javaClass.simpleName})."
                }
                return@Thread
            }
            main.post {
                modelsLoading = false
                if (result.isEmpty()) { modelsStatus = "A chave não listou nenhum modelo Live."; return@post }
                models = result
                preferences.edit().putString("live_models", LiveModel.toJson(result))
                    .putLong("live_models_at", System.currentTimeMillis()).apply()
                val known = result.firstOrNull { it.id == selected.id }
                modelsStatus = "${result.size} modelos Live disponíveis nesta chave." +
                    if (known == null) " O modelo escolhido (${selected.id}) não está entre eles; escolha um da lista." else ""
                if (known != null) selected = known
            }
        }, "ostie-live-models").start()
    }

    fun updateFallback(enabled: Boolean) {
        fallback = enabled
        preferences.edit().putBoolean("live_fallback", enabled).apply()
    }

    /** Estado da calibração de eco, lido ao abrir o painel. */
    var echoCalibration by mutableStateOf(readEchoCalibration().label)
        private set

    private fun readEchoCalibration() = EchoCalibration.fromJson(getApplication<Application>()
        .getSharedPreferences(LiveAudioEngine.CALIBRATION_PREFS, 0).getString("calibration", null))

    fun refreshEchoCalibration() { echoCalibration = readEchoCalibration().label }

    fun resetEchoCalibration() {
        audio?.resetCalibration()
        getApplication<Application>().getSharedPreferences(LiveAudioEngine.CALIBRATION_PREFS, 0).edit().clear().apply()
        echoCalibration = EchoCalibration().label
    }

    fun updateEchoGuard(enabled: Boolean) {
        echoGuard = enabled
        audio?.echoGuard = enabled
        preferences.edit().putBoolean("live_echo_guard", enabled).apply()
    }

    fun selectVoice(name: String) {
        voice = LiveVoices.fromName(name)
        preferences.edit().putString("live_voice", voice).apply()
        if (running) start() // A voz pertence à configuração inicial de cada sessão.
    }

    fun start() {
        stop()
        val saved = secrets.read()
        if (saved == null) { status = "Salve sua chave Gemini em Ajustes antes de iniciar."; return }
        key = saved
        running = true
        candidates = LiveModel.candidates(selected, fallback, models)
        resumeHandle = null; resumeModel = null // Nova chamada = nova conversa.
        captionUser = ""; captionModel = ""; turnUser.setLength(0); turnModel.setLength(0)
        // Atualiza a lista de modelos da chave em segundo plano (no máximo 1x por dia).
        if (System.currentTimeMillis() - preferences.getLong("live_models_at", 0L) > 24 * 3600_000L) refreshModels()
        candidateIndex = 0
        reconnectOnce = false
        memory.refresh() // Relê a pasta: pode ter sido editada fora do app ou restaurada após reinstalação.
        attempts = emptyList()
        screenFramesSent = 0
        screenFramesCaptured = 0
        screenFramesSkipped = 0
        lastScreenFrameAt = 0L
        interruptions = 0
        connect()
    }

    fun toggleMute() {
        muted = !muted
        audio?.muted = muted
        if (muted) inputLevel = 0f
    }

    fun screenFrameCaptured() { main.post { screenFramesCaptured++ } }

    fun resetCameraCounters() {
        cameraFramesCaptured = 0
        cameraFramesSent = 0
        cameraFramesSkipped = 0
        lastCameraFrameAt = 0L
    }

    fun clearCameraPreview() { cameraPreview = null }

    /** O JPEG mais recente aparece no app; o envio usa a mesma conexão da voz, sem fila de vídeo. */
    fun sendCameraFrame(jpeg: ByteArray, capturedAt: Long) {
        if (!cameraSharing) return
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        main.post {
            if (cameraSharing) {
                cameraFramesCaptured++
                if (image != null) cameraPreview = image
            }
        }
        val current = socket
        if (!ready || current == null) return
        if (System.currentTimeMillis() - capturedAt > 1500 || current.queueSize() > 32_000L) {
            main.post { cameraFramesSkipped++ }
            if (System.currentTimeMillis() - lastBackpressure > 5000) {
                lastBackpressure = System.currentTimeMillis()
                diagnostics.record("Câmera Live", "Quadro descartado: a conexão está ocupada com áudio.")
            }
            return
        }
        val sent = current.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))).toString())
        main.post {
            if (sent) { cameraFramesSent++; lastCameraFrameAt = System.currentTimeMillis() }
            else cameraFramesSkipped++
        }
    }

    /** Um quadro JPEG por segundo; se a conexão atrasar, nunca enfileira imagens antigas. */
    fun sendScreenFrame(encodedJpeg: String, capturedAt: Long) {
        val current = socket
        if (!ready || !screenSharing || current == null) return
        // Vídeo e microfone compartilham o WebSocket. Descarta a imagem se atrasaria o áudio.
        if (System.currentTimeMillis() - capturedAt > 1500 || current.queueSize() > 32_000L) {
            main.post { screenFramesSkipped++ }
            if (System.currentTimeMillis() - lastBackpressure > 5000) {
                lastBackpressure = System.currentTimeMillis()
                diagnostics.record("Tela Live", "Imagem antiga descartada: conexão ocupada. O microfone tem prioridade.")
            }
            return
        }
        val sent = current.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("mimeType", "image/jpeg").put("data", encodedJpeg))).toString())
        main.post {
            if (sent) { screenFramesSent++; lastScreenFrameAt = System.currentTimeMillis() }
            else screenFramesSkipped++
        }
    }


    fun stop() {
        running = false
        ready = false
        connected = false
        active = null
        socket?.close(1000, "Conversa encerrada")
        socket = null
        audio?.stop(); audio = null
        key = null
        inputLevel = 0f; outputLevel = 0f
        status = "Conversa encerrada"
        lastScreenFrameAt = 0L
    }

    private fun connect() {
        val apiKey = key ?: return
        val model = candidates.getOrNull(candidateIndex) ?: return
        ready = false
        connected = false
        active = model
        setupLevel = savedLevel(model)
        searchAvailable = preferences.getBoolean("google_search", true) && setupLevel < 1
        extrasAvailable = setupLevel < 2
        extendedTools = setupLevel < 3
        localToolsAvailable = setupLevel < 4
        reducedMode = LEVEL_NAMES.getOrNull(setupLevel)?.takeIf { setupLevel > 0 }
        heardFromModel = false
        status = if (reconnectOnce) "Reconectando ${model.label}…" else "Conectando ${model.label}…"
        // Chave no cabeçalho, fora do endereço (que pode aparecer em logs de rede e proxies). Se o serviço
        // recusar o cabeçalho no handshake, volta à chave no endereço até o app reiniciar.
        val url = endpoint.toHttpUrl().newBuilder().scheme("https")
            .apply { if (keyInUrl) addQueryParameter("key", apiKey) }.build()
        val request = Request.Builder().url(url).apply { if (!keyInUrl) header("x-goog-api-key", apiKey) }.build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (!running || socket !== webSocket) return@post
                    // Começa com o setup mínimo da documentação, comum aos modelos Live.
                    val generation = JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                            .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
                    // Variantes "extended thinking" exigem o nível de raciocínio (senão fecham com 1007).
                    if (model.id.contains("thinking")) generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
                    val generationSetup = JSONObject()
                    val setup = JSONObject().put("setup", generationSetup
                        .put("model", "models/${model.id}")
                        .put("generationConfig", generation)
                        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject()
                            .put("text", "Você é OSTIE, assistente pessoal no Android. Converse naturalmente em português brasileiro. Quando o usuário pedir um texto escrito para a Aba de Escrita, escreva o conteúdo integral usando write_document e então diga que está disponível para editar, copiar ou visualizar. Quando ele pedir código, HTML, SVG, página, jogo ou app, NÃO escreva o código de imediato: primeiro chame request_code com um pedido detalhado (tudo o que ele pediu: funções, estilo, cores, textos) e siga exatamente o resultado. Se o resultado disser que ele escolheu o modelo de voz, chame write_document com o código completo; se disser que o modelo de texto está escrevendo, não escreva o código e apenas avise em uma frase. Para páginas HTML ou desenhos SVG, envie o código completo, sem blocos markdown, e formato html (SVG também usa html); a aba mostra o resultado automaticamente. Não transcreva toda a conversa por voz; a aba recebe apenas textos ou códigos pedidos. Se ele pedir uma continuação, use operacao adicionar; se pedir alteração, envie o documento completo revisado com operacao substituir. Use ferramentas locais quando o usuário pedir para agir. Para Configurações, use open_settings ou open_app_settings, examine os controles e ajude a ajustar a opção pedida; mude volume de mídia, brilho, tempo de tela ou rotação automática apenas quando solicitado. Não tente alterar Wi-Fi, Bluetooth ou permissões diretamente sem a tela do Android. Descubra apps com busca dinâmica, incluindo apps do sistema se necessário. Após um toque, escrita ou gesto, inspecione novamente ou use check_ui para verificar o resultado antes de dizer que conseguiu. Um gesto aceito não significa que uma tarefa terminou. As imagens da tela e da câmera só chegam quando o usuário liga o compartilhamento correspondente; não são armazenadas. Converse normalmente enquanto analisa a imagem mais recente. Não afirme ter executado ações externas que não realizou. Quando a pergunta depender de informação atual ou que você não sabe com certeza (notícias, preços, placares, clima, horários, lançamentos), use a Pesquisa Google (ou a ferramenta web_search, quando for ela a disponível) e diga de onde veio a informação." + AGENT_GUIDE + UserProfile.get(getApplication()).identity(canSave = localToolsAvailable) +
                                memory.promptBlock())))))
                    sentHandle = false
                    if (extrasAvailable) {
                        val resume = JSONObject()
                        if (resumeHandle != null && resumeModel == model.id) { resume.put("handle", resumeHandle); sentHandle = true }
                        generationSetup.put("sessionResumption", resume)
                            .put("inputAudioTranscription", JSONObject())
                            .put("outputAudioTranscription", JSONObject())
                    }
                    val tools = JSONArray()
                    if (localToolsAvailable) tools.put(JSONObject().put("functionDeclarations", localTools.declarations()
                        .put(writeDocumentDeclaration()).put(requestCodeDeclaration()).also { list ->
                            if (extendedTools) {
                                val direct = phoneActions.declarations()
                                for (i in 0 until direct.length()) list.put(direct.get(i))
                            }
                            // Sem a pesquisa embutida (ex.: cota do Live 3.x), pesquisa pelo modelo de texto.
                            if (!searchAvailable && preferences.getBoolean("google_search", true)) list.put(WebSearch.declaration())
                        }))
                    if (searchAvailable) tools.put(JSONObject().put("googleSearch", JSONObject()))
                    if (tools.length() > 0) setup.getJSONObject("setup").put("tools", tools)
                    if (!webSocket.send(setup.toString())) fail(webSocket, "envio da configuração falhou")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatchMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // O Gemini pode enviar setupComplete e áudio em quadros binários JSON.
                dispatchMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    val code = response?.code
                    if (code != null && code in HEADER_REFUSED && !keyInUrl && running && socket === webSocket && !ready) {
                        keyInUrl = true
                        diagnostics.record("Live", "O serviço recusou a chave no cabeçalho (HTTP $code); usando a chave no endereço.")
                        connect()
                        return@post
                    }
                    val cause = when {
                        code != null -> "HTTP $code"
                        t is java.net.UnknownHostException -> "DNS ou internet indisponível"
                        t is javax.net.ssl.SSLException -> "falha TLS"
                        t is java.net.SocketTimeoutException -> "tempo de conexão esgotado"
                        else -> "falha de rede (${t.javaClass.simpleName.take(40)})"
                    }
                    fail(webSocket, cause, code == 401 || code == 403,
                        code == 401 || code == 403)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    val invalidKey = reason.contains("api key", true) || reason.contains("unauth", true) ||
                        reason.contains("permission denied", true)
                    if (invalidKey && !keyInUrl && running && socket === webSocket && !ready) {
                        keyInUrl = true
                        diagnostics.record("Live", "O serviço não leu a chave no cabeçalho; usando a chave no endereço.")
                        connect()
                        return@post
                    }
                    val quota = reason.contains("quota", true) || reason.contains("exhausted", true) ||
                        reason.contains("rate limit", true)
                    fail(webSocket, "WebSocket $code" + when {
                        invalidKey -> " (chave recusada)"
                        quota -> " (cota esgotada)"
                        code == 1007 -> " (configuração ou modelo recusado)"
                        code == 1011 -> " (erro interno do serviço)"
                        else -> ""
                    } + LiveCloseReason.excerpt(reason), invalidKey, code == 1006)
                }
            }
        }
        val newSocket = try { client.newWebSocket(request, listener) } catch (_: Exception) {
            stop(); status = "Não foi possível abrir a conexão Live."; return
        }
        socket = newSocket
        main.postDelayed({ if (running && socket === newSocket && !ready)
            fail(newSocket, "servidor não confirmou a sessão em 15 s") }, 15_000)
    }

    private fun dispatchMessage(webSocket: WebSocket, data: String) {
        if (!running || socket !== webSocket) return
        val message = try { JSONObject(data) } catch (_: Exception) {
            main.post { fail(webSocket, "resposta Live inválida", terminal = true) }
            return
        }
        val content = message.optJSONObject("serverContent")
        if (content != null || message.has("toolCall")) heardFromModel = true
        message.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable") && handle.isNotBlank()) { resumeHandle = handle; resumeModel = active?.id }
        }
        val heardUser = content?.optJSONObject("inputTranscription")?.optString("text").orEmpty()
        val heardModel = content?.optJSONObject("outputTranscription")?.optString("text").orEmpty()
        val turnDone = content?.optBoolean("turnComplete") == true || content?.optBoolean("interrupted") == true
        if (heardUser.isNotEmpty() || heardModel.isNotEmpty() || turnDone) main.post { onTranscript(heardUser, heardModel, turnDone) }
        if (content?.optBoolean("interrupted") == true) {
            audio?.interrupt()
            main.post {
                val now = System.currentTimeMillis()
                interruptions = if (now - interruptedAt < 10_000) interruptions + 1 else 1
                interruptedAt = now
                if (interruptions == 3) diagnostics.record("Live", if (echoGuard)
                    "Três interrupções de voz em 10 s. Ruído alto ou outra pessoa falando perto do microfone."
                    else "Três interrupções de voz em 10 s. Ative a proteção de eco no painel do Live ou use fones.")
            }
        }
        val parts = content?.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            if (inline.optString("mimeType").startsWith("audio/pcm")) {
                val encoded = inline.optString("data")
                if (encoded.isNotBlank()) audio?.receive(encoded)
            }
        }
        if (content?.optBoolean("turnComplete") == true || content?.optBoolean("generationComplete") == true)
            audio?.finishTurn()
        message.optJSONObject("toolCall")?.let { call ->
            main.post { if (running && socket === webSocket) handleToolCall(webSocket, call) }
        }
        message.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            val cancelled = (0 until ids.length()).map { ids.optString(it) }.toSet()
            main.post { codeAuthor.cancelCall(cancelled) }
        }
        if (message.has("setupComplete") || message.has("goAway") || message.has("error")) {
            main.post { if (running && socket === webSocket) handleControl(webSocket, message) }
        }
    }

    /** Legendas ao vivo; no fim do turno, a troca vai para o histórico do chat (se ativado). */
    private fun onTranscript(user: String, model: String, turnDone: Boolean) {
        if (!running) return
        if (user.isNotBlank()) audio?.userSpoke()
        if (user.isNotEmpty()) { turnUser.append(user); captionUser = turnUser.toString().takeLast(240).trim() }
        if (model.isNotEmpty()) { turnModel.append(model); captionModel = turnModel.toString().takeLast(240).trim() }
        if (turnDone && (turnUser.isNotBlank() || turnModel.isNotBlank())) {
            if (saveTranscript) LiveTranscriptInbox.push(getApplication(), turnUser.toString(), turnModel.toString())
            turnUser.setLength(0); turnModel.setLength(0)
        }
    }

    private fun handleToolCall(ws: WebSocket, call: JSONObject) {
        val calls = call.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()
        for (index in 0 until calls.length()) {
            val action = calls.optJSONObject(index) ?: continue
            val name = action.optString("name")
            val args = action.optJSONObject("args") ?: JSONObject()
            val id = if (action.has("id")) action.optString("id") else null
            if (phoneActions.handles(name)) {
                // Ações diretas; as sensíveis só respondem depois da confirmação na tela.
                phoneActions.execute(name, args) { answer -> sendToolResponse(ws, name, id, answer) }
                continue
            }
            if (name == WebSearch.NAME) {
                WebSearch.run(getApplication(), args) { answer -> sendToolResponse(ws, name, id, answer) }
                continue
            }
            if (name == "request_code") {
                // Responde só depois que o usuário escolher quem escreve o código.
                codeAuthor.request(id, args) { answer -> sendToolResponse(ws, name, id, answer) }
                continue
            }
            val answer = if (name == "write_document") writing.publish(args) else localTools.execute(name, args)
            val response = JSONObject().put("name", name).put("response", JSONObject().put("result", answer))
            if (id != null) response.put("id", id)
            responses.put(response)
        }
        if (responses.length() > 0 && socket === ws && running)
            ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }

    private fun sendToolResponse(ws: WebSocket, name: String, id: String?, answer: JSONObject) {
        val response = JSONObject().put("name", name).put("response", JSONObject().put("result", answer))
        if (id != null) response.put("id", id)
        main.post {
            if (running && socket === ws)
                ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(response))).toString())
        }
    }

    private fun requestCodeDeclaration(): JSONObject = JSONObject()
        .put("name", "request_code")
        .put("description", "Chame antes de escrever qualquer código, HTML, SVG, página, jogo ou app. O app pergunta ao usuário se o código deve ser escrito por você (modelo de voz) ou pelo modelo de texto, e o resultado diz o que fazer em seguida.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("titulo", JSONObject().put("type", "STRING").put("description", "Título curto do documento"))
                .put("pedido", JSONObject().put("type", "STRING").put("description", "Descrição completa e detalhada do que o usuário pediu: objetivo, funcionalidades, estilo, cores, textos e restrições"))
                .put("formato", JSONObject().put("type", "STRING").put("description", "html, svg ou o nome da linguagem de programação"))
                .put("operacao", JSONObject().put("type", "STRING").put("description", "novo para criar do zero, alterar para modificar o documento atual da Aba de Escrita")))
            .put("required", JSONArray().put("titulo").put("pedido").put("formato")))

    private fun writeDocumentDeclaration(): JSONObject = JSONObject()
        .put("name", "write_document")
        .put("description", "Publique o texto ou código completo solicitado pelo usuário na Aba de Escrita editável. Use formato html para páginas HTML e imagens SVG, que são exibidas no preview. Não use para transcrever a conversa.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("titulo", JSONObject().put("type", "STRING").put("description", "Título curto do documento"))
                .put("conteudo", JSONObject().put("type", "STRING").put("description", "Texto ou código integral, sem omissões nem blocos markdown em torno de HTML"))
                .put("formato", JSONObject().put("type", "STRING").put("description", "html para HTML ou SVG, text para os demais textos e códigos"))
                .put("operacao", JSONObject().put("type", "STRING").put("description", "substituir para nova versão, adicionar para continuar o documento")))
            .put("required", JSONArray().put("titulo").put("conteudo").put("formato")))

    private fun handleControl(ws: WebSocket, message: JSONObject) {
        if (message.has("setupComplete")) {
            ready = true
            connected = true
            readyAt = System.currentTimeMillis()
            status = "Ouvindo · ${active?.label.orEmpty()}"
            if (audio == null) {
                try {
                    audio = LiveAudioEngine(
                        context = getApplication(),
                        send = { data ->
                            val current = socket
                            if (ready && current != null && current.queueSize() < 512_000L) {
                                current.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                    .put("data", data).put("mimeType", "audio/pcm;rate=16000"))).toString())
                            } else if (ready && System.currentTimeMillis() - lastBackpressure > 5000) {
                                lastBackpressure = System.currentTimeMillis()
                                diagnostics.record("Live", "Envio do microfone atrasou: fila WebSocket cheia.")
                            }
                        },
                        inputLevel = { value ->
                            val now = System.currentTimeMillis()
                            if (now - lastInputUi.get() >= 80) {
                                lastInputUi.set(now)
                                main.post { if (running) inputLevel = value }
                            }
                        },
                        outputLevel = { value ->
                            val now = System.currentTimeMillis()
                            if (now - lastOutputUi.get() >= 70) {
                                lastOutputUi.set(now)
                                main.post { if (running) outputLevel = value }
                            }
                        },
                        onError = { main.post { if (running) {
                            diagnostics.record("Áudio Live", "Microfone ou alto-falante parou durante a chamada.")
                            stop(); status = "Áudio indisponível. Veja o diagnóstico."
                        } } },
                        onDiagnostic = { detail -> diagnostics.record("Áudio Live", detail) }
                    ).also { it.muted = muted; it.echoGuard = echoGuard; it.canConfirm = extrasAvailable; it.start() }
                } catch (failure: Exception) {
                    diagnostics.record("Áudio Live", "Não iniciou microfone/alto-falante (${failure.javaClass.simpleName}).")
                    stop(); status = "Não foi possível iniciar o áudio."
                }
            }
        }
        if (message.has("goAway")) { fail(ws, "servidor pediu reconexão"); return }
        if (message.has("error")) { fail(ws, "erro comunicado pelo serviço"); return }
    }

    private fun fail(ws: WebSocket, cause: String, unauthorized: Boolean = false, terminal: Boolean = false) {
        if (!running || socket !== ws) return
        val wasReady = ready
        attempts = attempts + "${active?.label.orEmpty()}: $cause"
        diagnostics.record("Conexão Live", "${active?.label.orEmpty()}: $cause")
        ready = false
        connected = false
        socket = null
        ws.cancel()
        audio?.stop(); audio = null
        if (unauthorized) { stop(); status = "Chave Gemini recusada. Confira em Ajustes."; return }
        if (terminal) { stop(); status = "Live indisponível: $cause."; return }
        // Fechamento antes de o modelo responder qualquer coisa costuma ser a configuração recusada
        // (ferramentas, pesquisa). Reduz um degrau e tenta o mesmo modelo de novo; cota não conta.
        val model = active
        val early = !wasReady || (!heardFromModel && System.currentTimeMillis() - readyAt < 20_000)
        if (sentHandle && early) {
            // A retomada foi recusada (identificador expirado): começa a conversa de novo, sem rebaixar nada.
            resumeHandle = null; resumeModel = null
            connect()
            return
        }
        // Sessão que ficou de pé por um tempo volta a ter direito a reconectar (quedas e goAway periódicos).
        if (wasReady && System.currentTimeMillis() - readyAt > 30_000) reconnectOnce = false
        if (model != null && setupLevel < 1 && LiveCloseReason.isSearchQuota(cause, wasReady, heardFromModel,
                System.currentTimeMillis() - readyAt, searchAvailable)) {
            preferences.edit().putInt("live_level_${model.id}", 1)
                .putLong("live_level_at_${model.id}", System.currentTimeMillis()).apply()
            diagnostics.record("Pesquisa Google", "${model.label}: sem cota de pesquisa no Live desta chave; reconectando sem pesquisa.")
            connect()
            return
        }
        if (model != null && setupLevel < 4 && LiveCloseReason.isSetupRejection(cause, wasReady,
                heardFromModel, System.currentTimeMillis() - readyAt)) {
            val next = setupLevel + 1
            preferences.edit().putInt("live_level_${model.id}", next)
                .putLong("live_level_at_${model.id}", System.currentTimeMillis()).apply()
            diagnostics.record("Conexão Live", "${model.label}: reconectando ${LEVEL_NAMES[next]}.")
            connect()
            return
        }
        if (wasReady && !reconnectOnce) {
            reconnectOnce = true
            connect()
            return
        }
        reconnectOnce = false
        candidateIndex++
        if (candidateIndex < candidates.size) {
            connect()
        } else {
            stop()
            status = "Nenhum modelo Live conectou. Veja o diagnóstico abaixo."
        }
    }

    private fun savedLevel(model: LiveModel): Int {
        val at = preferences.getLong("live_level_at_${model.id}", 0L)
        if (System.currentTimeMillis() - at > 24 * 3600_000L) return 0
        return preferences.getInt("live_level_${model.id}", 0).coerceIn(0, 4)
    }

    /** Volta a tentar a configuração completa em todos os modelos (pesquisa e todas as ações). */
    fun resetCapabilities() {
        val editor = preferences.edit()
        (models + LiveModel.DEFAULTS).forEach { editor.remove("live_level_${it.id}").remove("live_level_at_${it.id}") }
        editor.apply()
        reducedMode = null
        if (running) start()
    }

    private companion object {
        val HEADER_REFUSED = setOf(400, 401, 403)
        val LEVEL_NAMES = listOf("com configuração completa", "sem Pesquisa Google embutida",
            "sem legendas e retomada de conversa", "só com as ferramentas básicas", "somente voz")
        const val AGENT_GUIDE = " Para alarmes, timers, agenda, contatos, ligações, mensagens, rotas, mídia, lanterna, links e compartilhar, prefira as ferramentas diretas (set_alarm, set_timer, create_event, find_contact, dial, compose_message, navigate, media_control, flashlight, open_url, share_text) em vez de tocar na tela; use a acessibilidade só quando não houver ferramenta direta. Para ligar ou mandar mensagem a alguém pelo nome, use find_contact antes. Mensagens e ligações abrem prontas e o usuário confirma o envio; reply_notification pede confirmação na tela, então avise que ele precisa confirmar. Use read_notifications quando ele perguntar o que chegou. Você tem uma memória própria em Documentos/OSTIE/memoria.md, organizada em seções: sempre que aprender algo duradouro e útil sobre o usuário (preferências, pessoas, rotina, projetos, combinados), anote por conta própria com memory_note, sem pedir permissão e sem anunciar cada anotação; quando uma seção ficar repetida ou desatualizada, reorganize com memory_rewrite; se ele pedir para esquecer, use memory_forget. Nunca anote senhas, códigos, dados bancários ou documentos. Para coisas repetidas ou em horário marcado (\"todo dia às 8h me dá as notícias\", \"me lembra às 18h de tomar remédio\"), crie uma rotina com create_routine: tipo lembrete para avisos fixos, tipo tarefa quando precisar pesquisar ou escrever algo na hora; confirme horário e dias ao usuário."
    }

    override fun onCleared() { stop(); client.dispatcher.executorService.shutdown(); super.onCleared() }
}
