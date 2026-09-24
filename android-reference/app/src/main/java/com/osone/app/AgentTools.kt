package com.osone.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ferramentas do app para os modelos de texto. No chat escrito, as mesmas ações do Live (alarmes, agenda,
 * rotinas, memória, mensagens prontas); numa rotina em segundo plano, só o que funciona sem tela aberta,
 * e as ações que precisam do usuário viram botões na notificação ([suggestions]).
 */
class AgentTools(private val context: Context, private val background: Boolean) {
    private val phone = PhoneActions(context)
    private val local = AndroidLocalTools(context)
    private val main = Handler(Looper.getMainLooper())
    /** Botões sugeridos por uma rotina (rótulo e tela que abrem), no máximo [MAX_SUGGESTIONS]. */
    val suggestions = ArrayList<Pair<String, Intent>>()

    fun declarations(webSearch: Boolean): JSONArray {
        val list = JSONArray()
        val direct = phone.declarations()
        for (i in 0 until direct.length()) direct.getJSONObject(i).takeIf { !background || it.optString("name") in BACKGROUND }?.let { list.put(it) }
        val device = local.declarations()
        for (i in 0 until device.length()) device.getJSONObject(i)
            .takeIf { it.optString("name") in (if (background) BACKGROUND_LOCAL else CHAT_LOCAL) }?.let { list.put(it) }
        if (background) list.put(suggestDeclaration())
        if (webSearch) list.put(WebSearch.declaration())
        return list
    }

    /** Bloqueante: chame fora da thread principal. As ações rodam na principal, como no Live. */
    fun run(name: String, args: JSONObject): JSONObject {
        if (name == WebSearch.NAME) return await(90) { respond -> WebSearch.run(context, args, respond) }
        if (background) {
            if (name == SUGGEST) return suggest(args)
            if (name !in BACKGROUND && name !in BACKGROUND_LOCAL) return JSONObject().put("erro", "Ação indisponível numa rotina.")
        } else if (!phone.handles(name) && name !in CHAT_LOCAL) return JSONObject().put("erro", "Ação indisponível no chat.")
        // Resposta a notificação espera a confirmação do usuário na tela.
        return await(if (name == "reply_notification") 180 else 30) { respond ->
            main.post {
                if (phone.handles(name)) phone.execute(name, args, respond)
                else respond(local.execute(name, args))
            }
        }
    }

    private fun await(seconds: Long, start: ((JSONObject) -> Unit) -> Unit): JSONObject {
        val latch = CountDownLatch(1)
        val result = AtomicReference<JSONObject>()
        start { answer -> if (result.compareAndSet(null, answer)) latch.countDown() }
        return if (latch.await(seconds, TimeUnit.SECONDS)) result.get()
            else JSONObject().put("erro", "A ação não terminou a tempo (sem confirmação do usuário).")
    }

    private fun suggest(args: JSONObject): JSONObject {
        if (suggestions.size >= MAX_SUGGESTIONS) return JSONObject().put("erro", "Limite de $MAX_SUGGESTIONS botões por notificação.")
        val intent = try { actionIntent(args) } catch (failure: IllegalArgumentException) {
            return JSONObject().put("erro", failure.message ?: "Ação inválida.")
        }
        if (intent.resolveActivity(context.packageManager) == null)
            return JSONObject().put("erro", "Nenhum app do aparelho abre essa ação.")
        val label = args.optString("rotulo").trim().ifEmpty { DEFAULT_LABELS[args.optString("tipo")] ?: "Abrir" }.take(24)
        suggestions += label to intent
        return JSONObject().put("resultado", "Botão \"$label\" adicionado à notificação; o usuário decide se toca.")
    }

    private fun suggestDeclaration(): JSONObject = JSONObject()
        .put("name", SUGGEST)
        .put("description", "Adicione à notificação da rotina um botão com uma ação pronta: mensagem (SMS ou WhatsApp), " +
            "ligação, rota, link ou alarme. Nada acontece sem o toque do usuário. Use só quando a rotina pedir uma ação.")
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("tipo", prop("STRING", "sms, whatsapp, ligar, rota, link ou alarme"))
            .put("rotulo", prop("STRING", "Texto curto do botão (ex.: Avisar a Ana)"))
            .put("numero", prop("STRING", "Telefone, para sms, whatsapp e ligar"))
            .put("texto", prop("STRING", "Mensagem pronta (sms, whatsapp) ou nome do alarme"))
            .put("destino", prop("STRING", "Endereço ou lugar, para rota"))
            .put("url", prop("STRING", "Endereço https://, para link"))
            .put("hora", prop("INTEGER", "Hora 0 a 23, para alarme"))
            .put("minuto", prop("INTEGER", "Minuto 0 a 59, para alarme")))
            .put("required", JSONArray().put("tipo")))

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    companion object {
        const val SUGGEST = "suggest_action"
        const val MAX_SUGGESTIONS = 2
        /** Funcionam sem tela aberta e sem confirmação. */
        private val BACKGROUND = setOf("read_calendar", "read_notifications", "memory_read", "memory_note", "list_routines")
        private val BACKGROUND_LOCAL = setOf("device_status")
        /** Ações de aparelho úteis no chat; controle de tela por acessibilidade fica no Live. */
        private val CHAT_LOCAL = setOf("list_apps", "open_app", "open_app_settings", "open_settings", "set_media_volume",
            "set_brightness", "set_screen_timeout", "set_auto_rotate", "device_status")
        private val DEFAULT_LABELS = mapOf("sms" to "Enviar SMS", "whatsapp" to "Abrir WhatsApp", "ligar" to "Ligar",
            "rota" to "Ver rota", "link" to "Abrir link", "alarme" to "Criar alarme")

        /** Tela pronta para o botão da notificação; o envio ou a ligação ficam com o usuário. */
        fun actionIntent(args: JSONObject): Intent {
            fun number(): String {
                val value = args.optString("numero").trim()
                require(value.count(Char::isDigit) in 3..16 && value.all { it.isDigit() || it in "+-() " }) { "Número inválido." }
                return value
            }
            val text = args.optString("texto").take(2000)
            return when (args.optString("tipo").lowercase()) {
                "sms", "mensagem" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number()))).putExtra("sms_body", text)
                "whatsapp" -> {
                    val digits = number().filter(Char::isDigit)
                    require(digits.length >= 10) { "Para WhatsApp, informe o número com código do país e DDD." }
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(text)}"))
                }
                "ligar" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number())))
                "rota" -> {
                    val destination = args.optString("destino").trim().take(300)
                    require(destination.isNotEmpty()) { "Informe o destino." }
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                }
                "link" -> {
                    val url = Uri.parse(args.optString("url").trim())
                    require(url.scheme.equals("https", true)) { "Só links https://." }
                    Intent(Intent.ACTION_VIEW, url)
                }
                "alarme" -> {
                    val hour = args.optInt("hora", -1); val minute = args.optInt("minuto", -1)
                    require(hour in 0..23 && minute in 0..59) { "Hora ou minuto inválido." }
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .apply { if (text.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, text.take(80)) }
                }
                else -> throw IllegalArgumentException("Tipo de ação desconhecido.")
            }
        }
    }
}
