package com.osone.app

import android.content.Context

/** O "cérebro" de texto escolhido em Ajustes > Chat escrito, usado fora do chat (código, rotinas). */
object TextModel {
    private fun preferences(context: Context) = context.getSharedPreferences("osone_config", 0)
    private fun provider(context: Context) = ChatProvider.fromValue(preferences(context).getString("chat_provider", null))
    private fun groqModel(context: Context) = preferences(context).getString("groq_model", null) ?: GroqModel.GPT_OSS_20B.id
    private fun openRouterModel(context: Context) =
        preferences(context).getString("openrouter_model", "openrouter/free") ?: "openrouter/free"

    fun label(context: Context): String = when (provider(context)) {
        ChatProvider.GEMINI -> "Gemini · ${ChatModel.fromId(preferences(context).getString("model", null)).label}"
        ChatProvider.GROQ -> "Groq · ${GroqModel.label(groqModel(context))}"
        ChatProvider.OPENROUTER -> "OpenRouter · ${openRouterModel(context)}"
    }

    /** Chamada bloqueante (rodar fora da thread principal), com o fallback Gemini se ligado. */
    fun ask(context: Context, prompt: String, system: String, googleSearch: Boolean = false,
        readTimeoutMs: Int = 180_000, tools: AgentTools? = null, onPartial: (String) -> Unit = {}): String {
        val provider = provider(context)
        val preferences = preferences(context)
        val key = SecureKeyStore(context, when (provider) {
            ChatProvider.GEMINI -> "key"
            ChatProvider.OPENROUTER -> "key_openrouter"
            ChatProvider.GROQ -> "key_groq"
        }).read() ?: throw IllegalStateException("Salve a chave ${provider.label} em Ajustes para usar o modelo de texto.")
        val history = listOf(ChatMessage("user", prompt))
        if (provider != ChatProvider.GEMINI) {
            val model = if (provider == ChatProvider.GROQ) groqModel(context) else openRouterModel(context)
            return ChatCompletionClient().streamAnswer(provider, key, model, history, system, readTimeoutMs, onPartial)
        }
        val selected = ChatModel.fromId(preferences.getString("model", null))
        val mode = ThinkingMode.fromValue(preferences.getString("thinking_mode", null))
        val choices = ChatModel.candidates(selected, preferences.getBoolean("chat_fallback", true))
        for ((index, choice) in choices.withIndex()) {
            try {
                return gemini(key, choice, history, mode, null, system, readTimeoutMs, googleSearch, tools,
                    onDowngrade = { AppDiagnostics.get(context).record("Modelo de texto", it) }, onPartial = onPartial)
            } catch (failure: GeminiHttpException) {
                if (!failure.allowsFallback || index == choices.lastIndex) throw failure
            }
        }
        throw IllegalStateException("Nenhum modelo Gemini respondeu.")
    }

    /** Combinação de ferramentas que cada modelo aceitou nesta execução do app (índice do plano). */
    private val acceptedPlan = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Uma resposta Gemini com as ferramentas do app e a Pesquisa Google. Se o modelo recusar a combinação
     * (HTTP 400), troca a pesquisa embutida pela ferramenta web_search e, por fim, responde sem ferramentas.
     */
    fun gemini(key: String, model: ChatModel, history: List<ChatMessage>, mode: ThinkingMode, attachment: org.json.JSONObject?,
        system: String, readTimeoutMs: Int, googleSearch: Boolean, tools: AgentTools?,
        onDowngrade: (String) -> Unit = {}, onPartial: (String) -> Unit): String {
        val plans = buildList<Pair<Boolean, org.json.JSONArray?>> {
            if (tools != null) {
                add(googleSearch to tools.declarations(webSearch = false))
                if (googleSearch) add(false to tools.declarations(webSearch = true))
            } else if (googleSearch) add(true to null)
            add(false to null)
        }
        val planKey = "${model.id}:${tools != null}:$googleSearch"
        var failure: GeminiHttpException? = null
        for (index in (acceptedPlan[planKey] ?: 0) until plans.size) {
            val (search, functions) = plans[index]
            try {
                return GeminiClient().streamAnswer(key, model, history, mode, attachment, system, readTimeoutMs,
                    googleSearch = search, functions = functions, runTool = tools?.let { it::run }, onPartial = onPartial)
                    .also { acceptedPlan[planKey] = index }
            } catch (refused: GeminiHttpException) {
                if (refused.status != 400 || index == plans.lastIndex) throw refused
                failure = refused
                onDowngrade("${model.label} recusou " + (if (functions != null && search) "ferramentas com a Pesquisa Google"
                    else if (functions != null) "as ferramentas do app" else "a Pesquisa Google") + " (HTTP 400); tentando sem.")
            }
        }
        throw failure ?: IllegalStateException("Nenhuma combinação de ferramentas funcionou.")
    }
}
