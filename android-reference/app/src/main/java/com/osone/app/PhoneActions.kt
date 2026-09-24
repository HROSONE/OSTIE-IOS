package com.osone.app

import android.Manifest
import android.app.RemoteInput
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Mantém as notificações ativas disponíveis ao agente, só depois que o usuário ativar o acesso. */
class OstieNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var active: OstieNotificationListener? = null
            private set

        fun enabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it)?.packageName == context.packageName } == true
    }

    override fun onListenerConnected() { active = this }
    override fun onListenerDisconnected() { active = null }
    override fun onDestroy() { active = null; super.onDestroy() }

    fun snapshot(): List<StatusBarNotification> = try { activeNotifications?.toList().orEmpty() } catch (_: Exception) { emptyList() }
}

/**
 * Ações diretas do Android por intents e APIs públicas: mais rápidas e confiáveis que tocar na tela.
 * Mensagens e ligações só abrem a tela pronta; quem envia ou liga é o usuário.
 */
class PhoneActions(private val context: Context) {
    private val gate = ConfirmGate
    private val memory = MemoryStore.get(context)
    /** Ids curtos da última leitura de notificações para a resposta referenciar. */
    private val notificationIds = LinkedHashMap<String, String>()

    fun declarations(): JSONArray = JSONArray().apply {
        put(tool("set_alarm", "Crie um alarme no relógio do aparelho.", listOf(
            field("hora", "INTEGER", "Hora de 0 a 23"), field("minuto", "INTEGER", "Minuto de 0 a 59"),
            field("rotulo", "STRING", "Nome do alarme (opcional)")), listOf("hora", "minuto")))
        put(tool("set_timer", "Inicie um timer (cronômetro regressivo).", listOf(
            field("segundos", "INTEGER", "Duração em segundos"), field("rotulo", "STRING", "Nome do timer (opcional)")),
            listOf("segundos")))
        put(tool("create_event", "Abra a agenda com um evento preenchido para o usuário salvar.", listOf(
            field("titulo", "STRING", "Título"), field("inicio", "STRING", "Data e hora no formato AAAA-MM-DD HH:MM"),
            field("duracao_minutos", "INTEGER", "Duração (padrão 60)"), field("local", "STRING", "Local (opcional)"),
            field("descricao", "STRING", "Detalhes (opcional)")), listOf("titulo", "inicio")))
        put(tool("read_calendar", "Leia os compromissos da agenda do aparelho (exige permissão de agenda).", listOf(
            field("dias", "INTEGER", "Quantos dias a partir de hoje; 1 = só hoje (padrão)"),
            field("busca", "STRING", "Filtrar pelo título (opcional)"))))
        put(tool("find_contact", "Procure um contato salvo e seus números de telefone.", listOf(
            field("nome", "STRING", "Nome ou parte do nome")), listOf("nome")))
        put(tool("dial", "Abra o discador com o número pronto; o usuário toca para ligar.", listOf(
            field("numero", "STRING", "Número de telefone")), listOf("numero")))
        put(tool("compose_message", "Abra SMS ou WhatsApp com a mensagem pronta; o usuário toca em enviar.", listOf(
            field("numero", "STRING", "Número com DDD (e código do país para WhatsApp)"),
            field("texto", "STRING", "Mensagem"), field("app", "STRING", "sms ou whatsapp")), listOf("numero", "texto")))
        put(tool("navigate", "Abra rotas no mapa até um destino.", listOf(
            field("destino", "STRING", "Endereço ou lugar"), field("modo", "STRING", "carro, a_pe, bicicleta ou transporte")),
            listOf("destino")))
        put(tool("open_url", "Abra um link HTTPS no navegador.", listOf(field("url", "STRING", "Endereço https://")), listOf("url")))
        put(tool("share_text", "Abra o menu Compartilhar do Android com um texto.", listOf(field("texto", "STRING", "Texto")), listOf("texto")))
        put(tool("media_control", "Controle a música ou vídeo tocando em qualquer app.", listOf(
            field("acao", "STRING", "tocar, pausar, alternar, proxima ou anterior")), listOf("acao")))
        put(tool("flashlight", "Ligue ou desligue a lanterna.", listOf(field("ligada", "BOOLEAN", "true liga, false desliga")), listOf("ligada")))
        put(tool("read_notifications", "Leia as notificações atuais (exige acesso a notificações ativado pelo usuário).", listOf(
            field("app", "STRING", "Filtrar por nome do app (opcional)"), field("limite", "INTEGER", "Máximo, padrão 10"))))
        put(tool("reply_notification", "Responda uma notificação de mensagem (WhatsApp, Telegram, SMS…) pela resposta rápida. O usuário confirma na tela antes do envio.", listOf(
            field("id", "STRING", "id retornado por read_notifications"), field("texto", "STRING", "Resposta")), listOf("id", "texto")))
        put(tool("set_user_name", "Salve como o usuário prefere ser chamado, quando ele disser o nome ou pedir para mudar.", listOf(
            field("nome", "STRING", "Nome ou apelido")), listOf("nome")))
        put(tool("create_routine", "Crie uma rotina agendada: um lembrete simples ou uma tarefa que o modelo de texto executa no horário e entrega por notificação (ex.: resumo das notícias às 8h, previsão do tempo antes de sair).", listOf(
            field("titulo", "STRING", "Nome curto"), field("instrucao", "STRING", "O que fazer ou lembrar, detalhado"),
            field("hora", "INTEGER", "Hora 0 a 23"), field("minuto", "INTEGER", "Minuto 0 a 59"),
            field("dias", "STRING", "todos, uteis, fim_de_semana ou lista como seg,qua,sex"),
            field("tipo", "STRING", "lembrete (texto fixo) ou tarefa (o modelo pesquisa e escreve)")),
            listOf("titulo", "instrucao", "hora", "minuto")))
        put(tool("list_routines", "Liste as rotinas agendadas.", emptyList()))
        put(tool("delete_routine", "Apague ou pause uma rotina pelo nome ou id.", listOf(
            field("rotina", "STRING", "Nome ou id"), field("acao", "STRING", "apagar, pausar ou ativar")), listOf("rotina")))
        put(tool("memory_read", "Leia o arquivo de memória do OSTIE (Documentos/OSTIE/memoria.md) completo e atualizado.", emptyList()))
        put(tool("memory_note", "Anote por conta própria na sua memória algo duradouro e útil sobre o usuário: fatos, preferências, pessoas, rotina, projetos ou combinados. Uma frase por anotação.", listOf(
            field("secao", "STRING", "Sobre o usuário, Preferências, Pessoas, Rotina e agenda, Projetos ou Anotações"),
            field("texto", "STRING", "Anotação curta e clara")), listOf("secao", "texto")))
        put(tool("memory_rewrite", "Reorganize uma seção inteira da memória: junte repetições, corrija informações que mudaram e mantenha em tópicos '- '.", listOf(
            field("secao", "STRING", "Nome da seção"), field("conteudo", "STRING", "Novo conteúdo completo da seção, em tópicos")),
            listOf("secao", "conteudo")))
        put(tool("memory_forget", "Apague da memória anotações que contenham um trecho, quando o usuário pedir para esquecer.", listOf(
            field("trecho", "STRING", "Trecho da anotação")), listOf("trecho")))
    }

    /** Ferramentas que precisam de confirmação respondem depois, por [respond]. */
    fun handles(name: String) = name in NAMES

    fun execute(name: String, args: JSONObject, respond: (JSONObject) -> Unit) {
        val result = try { run(name, args, respond) } catch (failure: SecurityException) {
            JSONObject().put("erro", "O Android negou a ação (${failure.javaClass.simpleName}).")
        } catch (failure: Exception) {
            JSONObject().put("erro", failure.message?.take(160) ?: "Falha ao executar $name.")
        }
        if (result != null) respond(result)
    }

    private fun run(name: String, args: JSONObject, respond: (JSONObject) -> Unit): JSONObject? = when (name) {
        "set_alarm" -> {
            val hour = args.optInt("hora", -1); val minute = args.optInt("minuto", -1)
            require(hour in 0..23 && minute in 0..59) { "Hora ou minuto inválido." }
            start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { args.optString("rotulo").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
                "Alarme pedido ao relógio para %02d:%02d.".format(hour, minute))
        }
        "set_timer" -> {
            val seconds = args.optInt("segundos", 0)
            require(seconds in 1..86_400) { "Duração inválida." }
            start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { args.optString("rotulo").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
                "Timer de $seconds s pedido ao relógio.")
        }
        "create_event" -> {
            val begin = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
                .parse(args.optString("inicio").trim())?.time ?: error("Data inválida; use AAAA-MM-DD HH:MM.")
            val minutes = args.optInt("duracao_minutos", 60).coerceIn(5, 24 * 60)
            start(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, args.optString("titulo").take(200))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + minutes * 60_000L)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("local").take(200))
                .putExtra(CalendarContract.Events.DESCRIPTION, args.optString("descricao").take(1000)),
                "Agenda aberta com o evento preenchido. O usuário precisa tocar em salvar.")
        }
        "read_calendar" -> readCalendar(args.optInt("dias", 1).coerceIn(1, 31), args.optString("busca").trim())
        "find_contact" -> findContacts(args.optString("nome"))
        "dial" -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone(args)))),
            "Discador aberto com o número. O usuário toca para ligar.")
        "compose_message" -> {
            val number = phone(args)
            val text = args.optString("texto").take(2000)
            if (args.optString("app").contains("whats", true)) {
                val digits = number.filter(Char::isDigit)
                require(digits.length >= 10) { "Para WhatsApp, informe o número com código do país e DDD." }
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(text)}")),
                    "WhatsApp aberto com a mensagem pronta. O usuário toca em enviar.")
            } else start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text),
                "SMS aberto com a mensagem pronta. O usuário toca em enviar.")
        }
        "navigate" -> {
            val destination = args.optString("destino").trim().take(300)
            require(destination.isNotEmpty()) { "Informe o destino." }
            val mode = when (args.optString("modo")) { "a_pe" -> "w"; "bicicleta" -> "b"; "transporte" -> "r"; else -> "d" }
            val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode"))
            if (maps.resolveActivity(context.packageManager) != null) start(maps, "Rota iniciada até $destination.")
            else start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")), "Mapa aberto em $destination.")
        }
        "open_url" -> {
            val url = Uri.parse(args.optString("url").trim())
            require(url.scheme.equals("https", true)) { "Só abro links https://." }
            start(Intent(Intent.ACTION_VIEW, url), "Link aberto no navegador.")
        }
        "share_text" -> start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, args.optString("texto").take(5000)), "Compartilhar"), "Menu Compartilhar aberto.")
        "media_control" -> {
            val code = when (args.optString("acao")) {
                "tocar" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "proxima" -> KeyEvent.KEYCODE_MEDIA_NEXT; "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val audio = context.getSystemService(AudioManager::class.java)
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            JSONObject().put("resultado", "Comando de mídia enviado ao app que está tocando.")
        }
        "flashlight" -> {
            val on = args.optBoolean("ligada", true)
            val cameras = context.getSystemService(CameraManager::class.java)
            val id = cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: error("Este aparelho não tem lanterna disponível.")
            cameras.setTorchMode(id, on)
            JSONObject().put("resultado", if (on) "Lanterna ligada." else "Lanterna desligada.")
        }
        "read_notifications" -> readNotifications(args.optString("app"), args.optInt("limite", 10).coerceIn(1, 25))
        "reply_notification" -> { replyNotification(args, respond); null }
        "set_user_name" -> {
            val name = args.optString("nome").trim()
            require(name.length in 1..40) { "Nome inválido." }
            UserProfile.get(context).updateName(name)
            memory.rewriteLine("Sobre o usuário", "Prefere ser chamado de", "Prefere ser chamado de $name")
            JSONObject().put("resultado", "Nome salvo: $name.")
        }
        "create_routine" -> {
            val routine = RoutineStore.get(context).add(args.optString("titulo"), args.optString("instrucao"),
                args.optInt("hora", -1), args.optInt("minuto", 0), RoutineSchedule.parseDays(args.optString("dias")),
                reminder = args.optString("tipo").startsWith("lembr", true))
            JSONObject().put("resultado", "Rotina \"${routine.title}\" criada: ${routine.timeLabel}, ${routine.daysLabel}.")
                .put("id", routine.id)
        }
        "list_routines" -> JSONObject().put("rotinas", JSONArray(RoutineStore.get(context).routines.map {
            JSONObject().put("id", it.id).put("titulo", it.title).put("horario", it.timeLabel).put("dias", it.daysLabel)
                .put("tipo", if (it.reminder) "lembrete" else "tarefa").put("ativa", it.enabled).put("instrucao", it.instruction.take(200))
        }))
        "delete_routine" -> {
            val store = RoutineStore.get(context)
            val found = store.matching(args.optString("rotina"))
            when {
                found.isEmpty() -> JSONObject().put("erro", "Nenhuma rotina com esse nome.")
                found.size > 1 -> JSONObject().put("erro", "Mais de uma rotina combina; use o id.")
                    .put("opcoes", JSONArray(found.map { "${it.title} (${it.id})" }))
                else -> {
                    val routine = found[0]
                    when (args.optString("acao").lowercase(Locale.ROOT)) {
                        "pausar" -> { store.setEnabled(routine.id, false); JSONObject().put("resultado", "Rotina pausada.") }
                        "ativar" -> { store.setEnabled(routine.id, true); JSONObject().put("resultado", "Rotina ativada.") }
                        else -> { store.remove(routine.id); JSONObject().put("resultado", "Rotina apagada.") }
                    }
                }
            }
        }
        "memory_read" -> { memory.refresh(); JSONObject().put("arquivo", memory.location)
            .put("persistente", memory.persistent).put("conteudo", memory.text.takeLast(12_000)) }
        "memory_note" -> memory.note(args.optString("secao"), args.optString("texto"))
        "memory_rewrite" -> memory.rewrite(args.optString("secao"), args.optString("conteudo"))
        "memory_forget" -> memory.forget(args.optString("trecho"))
        else -> JSONObject().put("erro", "Ação desconhecida.")
    }

    private fun start(intent: Intent, done: String): JSONObject {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.action != Intent.ACTION_CHOOSER && intent.resolveActivity(context.packageManager) == null)
            return JSONObject().put("erro", "Nenhum app do aparelho atende a este pedido.")
        context.startActivity(intent)
        return JSONObject().put("resultado", done)
    }

    private fun phone(args: JSONObject): String {
        val number = args.optString("numero").trim()
        require(number.count(Char::isDigit) in 3..16 && number.all { it.isDigit() || it in "+-() " }) { "Número inválido." }
        return number
    }

    private fun findContacts(name: String): JSONObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("erro", "Sem permissão de contatos. Peça ao usuário para tocar em Contatos no painel do Live.")
        val query = name.trim()
        require(query.length >= 2) { "Informe o nome." }
        val found = JSONArray()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { cursor ->
            while (cursor.moveToNext() && found.length() < 8)
                found.put(JSONObject().put("nome", cursor.getString(0)).put("numero", cursor.getString(1)))
        }
        return if (found.length() == 0) JSONObject().put("erro", "Nenhum contato com esse nome.")
            else JSONObject().put("contatos", found)
    }

    private fun readCalendar(days: Int, search: String): JSONObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("erro", "Sem permissão de agenda. Peça ao usuário para tocar em Agenda no painel do Live.")
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start); ContentUris.appendId(it, start + days * 86_400_000L)
        }.build()
        val local = SimpleDateFormat("EEE dd/MM HH:mm", Locale("pt", "BR"))
        // Eventos de dia inteiro são gravados em UTC.
        val allDayFormat = SimpleDateFormat("EEE dd/MM", Locale("pt", "BR")).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val items = JSONArray()
        context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext() && items.length() < 30) {
                val title = cursor.getString(0).orEmpty()
                if (search.isNotEmpty() && !title.contains(search, true)) continue
                val allDay = cursor.getInt(3) == 1
                items.put(JSONObject().put("titulo", title.take(150))
                    .put("quando", if (allDay) allDayFormat.format(Date(cursor.getLong(1))) + " (dia inteiro)"
                        else local.format(Date(cursor.getLong(1))) + " até " + SimpleDateFormat("HH:mm", Locale.US).format(Date(cursor.getLong(2))))
                    .put("local", cursor.getString(4).orEmpty().take(150)))
            }
        }
        return JSONObject().put("periodo", if (days == 1) "hoje" else "próximos $days dias").put("eventos", items)
            .apply { if (items.length() == 0) put("resultado", "Nenhum compromisso no período.") }
    }

    private fun readNotifications(filter: String, limit: Int): JSONObject {
        val listener = OstieNotificationListener.active
            ?: return JSONObject().put("erro", "Acesso a notificações desativado. Peça ao usuário para tocar em Notificações no painel do Live.")
        val manager = context.packageManager
        notificationIds.clear()
        val items = JSONArray()
        listener.snapshot().filter { it.packageName != context.packageName && !it.isOngoing &&
            (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }
            .sortedByDescending { it.postTime }
            .forEach { item ->
                if (items.length() >= limit) return@forEach
                val app = try { manager.getApplicationLabel(manager.getApplicationInfo(item.packageName, 0)).toString() }
                    catch (_: Exception) { item.packageName }
                if (filter.isNotBlank() && !app.contains(filter, true) && !item.packageName.contains(filter, true)) return@forEach
                val extras = item.notification.extras
                val id = (notificationIds.size + 1).toString()
                notificationIds[id] = item.key
                items.put(JSONObject().put("id", id).put("app", app)
                    .put("titulo", extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().take(120))
                    .put("texto", (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty().take(400))
                    .put("minutos_atras", (System.currentTimeMillis() - item.postTime) / 60_000)
                    .put("pode_responder", replyAction(item) != null))
            }
        return JSONObject().put("notificacoes", items)
    }

    private fun replyAction(item: StatusBarNotification) =
        item.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    private fun replyNotification(args: JSONObject, respond: (JSONObject) -> Unit) {
        val key = notificationIds[args.optString("id")]
        val text = args.optString("texto").trim().take(1000)
        val item = OstieNotificationListener.active?.snapshot()?.firstOrNull { it.key == key }
        val action = item?.let(::replyAction)
        when {
            text.isEmpty() -> respond(JSONObject().put("erro", "Resposta vazia."))
            item == null -> respond(JSONObject().put("erro", "Notificação não encontrada; leia as notificações de novo."))
            action == null -> respond(JSONObject().put("erro", "Essa notificação não aceita resposta rápida."))
            else -> {
                val title = item.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                gate.request("Enviar resposta?", "Para ${title.ifBlank { "o contato" }}:\n\n“$text”", confirmLabel = "Enviar",
                    respond = respond) {
                    val inputs = action.remoteInputs
                    val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
                    val fill = Intent()
                    RemoteInput.addResultsToIntent(inputs, fill, results)
                    action.actionIntent.send(context, 0, fill)
                    JSONObject().put("resultado", "Resposta enviada pela notificação.")
                }
            }
        }
    }

    private fun tool(name: String, description: String, fields: List<JSONObject>, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", description).apply {
            if (fields.isNotEmpty()) put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().apply { fields.forEach { put(it.getString("_name"), it.apply { remove("_name") }) } })
                .put("required", JSONArray(required)))
        }

    private fun field(name: String, type: String, description: String) =
        JSONObject().put("_name", name).put("type", type).put("description", description)

    private companion object {
        val NAMES = setOf("set_alarm", "set_timer", "create_event", "read_calendar", "find_contact", "dial", "compose_message", "navigate",
            "open_url", "share_text", "media_control", "flashlight", "read_notifications", "reply_notification",
            "memory_read", "memory_note", "memory_rewrite", "memory_forget", "set_user_name",
            "create_routine", "list_routines", "delete_routine")
    }
}
