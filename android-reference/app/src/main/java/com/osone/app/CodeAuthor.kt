package com.osone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CodeAuthorChoice(val value: String, val label: String) {
    ASK("ask", "Perguntar sempre"), VOICE("voice", "Modelo de voz"), TEXT("text", "Modelo de texto");
    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: ASK
    }
}

/** Pedido de código feito por voz, aguardando a escolha de quem vai escrevê-lo. */
class CodeRequest(val callId: String?, val title: String, val request: String, val format: String,
    val modify: Boolean, val respond: (JSONObject) -> Unit)

/**
 * Decide quem escreve código pedido no Live: o próprio modelo de voz ou o modelo de texto
 * escolhido para o chat ("cérebro"), que costuma produzir código mais longo e cuidadoso.
 */
class CodeAuthor private constructor(context: Context) {
    companion object {
        @Volatile private var instance: CodeAuthor? = null
        fun get(context: Context): CodeAuthor = instance ?: synchronized(this) {
            instance ?: CodeAuthor(context.applicationContext).also { instance = it }
        }

        private const val SYSTEM = "Você é um programador sênior escrevendo para a Aba de Escrita do OSTIE, um app Android. " +
            "Responda SOMENTE com o código completo e funcional pedido, sem explicações, sem comentários fora do código e sem blocos markdown. " +
            "Para páginas HTML, entregue um único arquivo autocontido começando com <!doctype html>, com CSS e JavaScript embutidos, " +
            "visual moderno e responsivo para tela de celular. O preview não tem internet: não use CDN, fontes, imagens ou scripts externos. " +
            "Para SVG, entregue apenas o elemento <svg> completo com viewBox. Para outras linguagens, apenas o código-fonte. " +
            "Nunca omita partes com reticências ou 'resto do código'."
    }

    private val app = context
    private val preferences = context.getSharedPreferences("osone_config", 0)
    private val writing = WritingWorkspace.get(context)
    private val diagnostics = AppDiagnostics.get(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var preference by mutableStateOf(CodeAuthorChoice.fromValue(preferences.getString("code_author", null)))
        private set
    var pending by mutableStateOf<CodeRequest?>(null)
        private set
    /** Modelo que está escrevendo agora; nulo quando nada está sendo gerado. */
    var writingWith by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    fun updatePreference(choice: CodeAuthorChoice) {
        preference = choice
        preferences.edit().putString("code_author", choice.value).apply()
    }

    /** Nome do modelo de texto configurado em Ajustes > Chat escrito. */
    fun textModelLabel(): String = TextModel.label(app)

    /** Chamado pelo Live na thread principal. A resposta da ferramenta sai depois da escolha. */
    fun request(callId: String?, args: JSONObject, respond: (JSONObject) -> Unit) {
        val request = CodeRequest(callId,
            title = args.optString("titulo").trim().take(80).ifEmpty { "Código" },
            request = args.optString("pedido").trim().ifEmpty { args.optString("titulo") },
            format = args.optString("formato"),
            modify = args.optString("operacao").equals("alterar", true),
            respond = respond)
        if (request.request.isBlank()) {
            respond(JSONObject().put("erro", "Descreva o pedido em 'pedido'."))
            return
        }
        when (preference) {
            CodeAuthorChoice.VOICE -> resolve(request, useText = false)
            CodeAuthorChoice.TEXT -> resolve(request, useText = true)
            CodeAuthorChoice.ASK -> {
                pending?.respond?.invoke(JSONObject().put("resultado", "Pedido anterior substituído por um novo."))
                pending = request
            }
        }
    }

    fun choose(useText: Boolean, remember: Boolean) {
        val request = pending ?: return
        pending = null
        if (remember) updatePreference(if (useText) CodeAuthorChoice.TEXT else CodeAuthorChoice.VOICE)
        resolve(request, useText)
    }

    fun dismiss() {
        val request = pending ?: return
        pending = null
        request.respond(JSONObject().put("resultado", "O usuário cancelou o pedido de código. Não escreva o código."))
    }

    /** O Live cancelou a chamada (por exemplo, o usuário interrompeu a fala). */
    fun cancelCall(ids: Set<String>) {
        val id = pending?.callId
        if (id != null && id in ids) pending = null
    }

    fun cancelGeneration() {
        job?.cancel()
        job = null
        writingWith = null
        draft = ""
    }

    private fun resolve(request: CodeRequest, useText: Boolean) {
        if (!useText) {
            request.respond(JSONObject().put("resultado",
                "O usuário escolheu o modelo de voz. Agora chame write_document com o código completo deste pedido: ${request.request}"))
            return
        }
        val label = textModelLabel()
        request.respond(JSONObject().put("resultado",
            "O modelo de texto $label está escrevendo o código na Aba de Escrita. Não chame write_document para este pedido; " +
                "diga ao usuário, em uma frase, que o resultado aparece sozinho na aba em instantes."))
        generate(request, label)
    }

    private fun generate(request: CodeRequest, label: String) {
        job?.cancel()
        lastError = null
        draft = ""
        writingWith = label
        val current = writing.content
        val prompt = buildString {
            append("Pedido do usuário: ").append(request.request)
            if (request.format.isNotBlank()) append("\nFormato desejado: ").append(request.format)
            if (request.modify && current.isNotBlank())
                append("\n\nAltere o documento atual abaixo e devolva a versão completa revisada:\n\n").append(current)
        }
        val started = System.currentTimeMillis()
        job = scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    TextModel.ask(app, prompt, SYSTEM) { partial -> main.post { if (writingWith == label) draft = partial } }
                }
                if (!isActive) return@launch
                val code = DocumentPreview.stripFence(DocumentPreview.codeBlock(answer)?.second ?: answer)
                val result = writing.publish(JSONObject().put("titulo", request.title)
                    .put("conteudo", code).put("formato", request.format))
                if (result.has("erro")) lastError = result.optString("erro")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastError = when (failure) {
                    is GeminiHttpException -> "Gemini respondeu HTTP ${failure.status}. Confira a chave e a cota."
                    is ChatProviderHttpException -> failure.message
                    else -> failure.message ?: "O modelo de texto não respondeu."
                }
                diagnostics.record("Código", "$label: ${failure.javaClass.simpleName} após " +
                    "${(System.currentTimeMillis() - started) / 1000} s.")
            } finally {
                if (writingWith == label) { writingWith = null; draft = "" }
            }
        }
    }
}
