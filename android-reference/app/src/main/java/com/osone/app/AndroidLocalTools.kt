package com.osone.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.roundToInt

/** Catálogo dinâmico de apps e ponte opcional com acessibilidade ativada pelo usuário. */
class AndroidLocalTools(private val context: Context) {
    fun declarations(): JSONArray = JSONArray().apply {
        put(function("list_apps", "Busque apps instalados. Por padrão mostra apps que abrem; incluir_sistema inclui apps sem ícone. Use busca e pagina.", mapOf("busca" to "Parte do nome ou pacote (opcional)", "pagina" to "Página começando em 0", "incluir_sistema" to "true para incluir apps do sistema e sem tela de abertura")))
        put(function("open_app", "Abra um aplicativo instalado pelo nome ou pacote, se solicitado.", mapOf("nome" to "Nome do aplicativo"), listOf("nome")))
        put(function("open_app_settings", "Abra a página de informações/permissões de um app pelo nome ou pacote. O usuário decide as alterações no Android.", mapOf("nome" to "Nome ou pacote do aplicativo"), listOf("nome")))
        put(function("open_settings", "Abra a página das Configurações do Android. Áreas: geral, internet, wifi, bluetooth, som, tela, bateria, aplicativos, notificacoes, acessibilidade, privacidade, seguranca, localizacao, armazenamento, idioma, teclado, data_hora, sobre.", mapOf("area" to "Nome da área das Configurações"), listOf("area")))
        put(function("set_media_volume", "Defina o volume de mídia entre 0 e 100 por cento quando o usuário pedir. Não controla volume de chamada.", mapOf("percentual" to "Número inteiro de 0 a 100"), listOf("percentual"), true))
        put(function("set_brightness", "Defina o brilho manual entre 0 e 100 por cento quando pedido; pode exigir autorização do Android. Se brilho automático estiver ativo, abra a tela para o usuário desligá-lo.", mapOf("percentual" to "Número inteiro de 0 a 100"), listOf("percentual"), true))
        put(function("set_screen_timeout", "Defina o tempo até a tela desligar, quando pedido. Exige autorização para modificar configurações; alguns aparelhos impõem limites próprios.", mapOf("minutos" to "1, 2, 5, 10, 15 ou 30 minutos"), listOf("minutos"), true))
        put(function("set_auto_rotate", "Ligue ou desligue a rotação automática da tela quando pedido. Exige autorização para modificar configurações.", mapOf("ativada" to "true para ligar ou false para desligar"), listOf("ativada")))
        put(function("device_status", "Veja bateria e hora locais.", emptyMap()))
        put(function("inspect_screen", "Leia os controles acessíveis visíveis antes de interagir.", emptyMap()))
        put(function("check_ui", "Depois de agir, confira se um texto/controle aparece na janela atual. Resultado encontrado não garante que uma tarefa externa terminou.", mapOf("texto" to "Texto esperado na tela"), listOf("texto")))
        put(function("interact_ui", "Toque ou segure um controle visível pelo texto.", mapOf("texto" to "Texto ou descrição do controle", "acao" to "tocar ou segurar"), listOf("texto", "acao")))
        put(function("type_text", "Escreva em um campo editável visível.", mapOf("campo" to "Texto do campo; vazio usa campo em foco", "texto" to "Conteúdo a escrever"), listOf("texto")))
        put(function("scroll_screen", "Role a tela atual.", mapOf("direcao" to "cima ou baixo"), listOf("direcao")))
        put(function("system_navigation", "Volte ou abra início, recentes, notificações ou ajustes rápidos.", mapOf("acao" to "voltar, inicio, recentes, notificacoes ou ajustes_rapidos"), listOf("acao")))
        put(function("touch_screen", "Toque em coordenadas da tela, ou arraste se informar fim_x e fim_y.", mapOf("x" to "Posição X", "y" to "Posição Y", "fim_x" to "X final opcional", "fim_y" to "Y final opcional"), listOf("x", "y"), true))
    }

    private fun function(name: String, description: String, fields: Map<String, String>,
        required: List<String> = emptyList(), numbers: Boolean = false): JSONObject =
        JSONObject().put("name", name).put("description", description).apply {
            if (fields.isNotEmpty()) put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().apply { fields.forEach { (key, value) ->
                put(key, JSONObject().put("type", if (key == "incluir_sistema" || key == "ativada") "BOOLEAN"
                    else if (numbers && key in listOf("x", "y", "fim_x", "fim_y", "percentual", "minutos")) "INTEGER" else "STRING").put("description", value))
                } }).put("required", JSONArray(required)))
        }

    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "list_apps" -> {
                val search = normalized(args.optString("busca"))
                val all = apps(args.optBoolean("incluir_sistema", false)).filter {
                    search.isEmpty() || normalized(it.first).contains(search) || it.second.contains(search, true) }
                val page = args.optInt("pagina", 0).coerceIn(0, 100)
                JSONObject().put("total", all.size).put("pagina", page)
                    .put("aplicativos", JSONArray(all.drop(page * 50).take(50).map { it.first }))
                    .put("proxima_pagina", if ((page + 1) * 50 < all.size) page + 1 else JSONObject.NULL)
            }
            "open_app" -> {
                val wanted = args.optString("nome").trim()
                require(wanted.length in 2..120) { "Informe o nome do aplicativo." }
                val all = apps()
                val matches = all.filter { normalized(it.first) == normalized(wanted) || it.second.equals(wanted, true) }
                    .ifEmpty { all.filter { normalized(it.first).contains(normalized(wanted)) } }
                when {
                    matches.isEmpty() -> JSONObject().put("erro", "Aplicativo não encontrado. Busque por parte do nome com list_apps.")
                    matches.size > 1 -> JSONObject().put("erro", "Nome ambíguo; escolha um destes.")
                        .put("opcoes", JSONArray(matches.take(12).map { "${it.first} (${it.second})" }))
                    else -> {
                        val launch = context.packageManager.getLaunchIntentForPackage(matches[0].second)
                            ?: throw IllegalStateException("Aplicativo sem tela de abertura.")
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        JSONObject().put("resultado", "Abertura solicitada: ${matches[0].first}")
                    }
                }
            }
            "open_app_settings" -> {
                val match = findApp(args.optString("nome"), includeSystem = true)
                if (match is JSONObject) match else {
                    val app = match as Pair<*, *>
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${app.second}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Informações de ${app.first} abertas. Verifique a tela antes de mudar permissões.")
                }
            }
            "open_settings" -> {
                val area = normalized(args.optString("area"))
                val action = when (area) {
                    "geral", "configuracoes", "configuracao" -> Settings.ACTION_SETTINGS
                    "internet", "rede" -> Settings.ACTION_WIRELESS_SETTINGS
                    "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "som", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
                    "tela", "display", "brilho" -> Settings.ACTION_DISPLAY_SETTINGS
                    "bateria", "economia de bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "aplicativos", "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "notificacoes" -> if (Build.VERSION.SDK_INT >= 33)
                        Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS else Settings.ACTION_APPLICATION_SETTINGS
                    "acessibilidade" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "privacidade", "seguranca" -> Settings.ACTION_SECURITY_SETTINGS
                    "localizacao", "local" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "armazenamento", "espaco" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "idioma", "lingua" -> Settings.ACTION_LOCALE_SETTINGS
                    "teclado" -> Settings.ACTION_INPUT_METHOD_SETTINGS
                    "data_hora", "data e hora" -> Settings.ACTION_DATE_SETTINGS
                    "sobre", "informacoes do telefone" -> Settings.ACTION_DEVICE_INFO_SETTINGS
                    else -> return JSONObject().put("erro", "Área desconhecida. Use uma das áreas descritas em open_settings.")
                }
                open(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "Configurações de $area abertas. Inspecione os controles antes de alterar algo.")
            }
            "set_media_volume" -> {
                val value = percentage(args)
                val audio = context.getSystemService(AudioManager::class.java)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    (maximum * value / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
                JSONObject().put("volume_midia_percentual_aproximado",
                    if (maximum > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum else 0)
            }
            "set_brightness" -> {
                val value = percentage(args)
                if (!Settings.System.canWrite(context)) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Autorize OSTIE a modificar configurações do sistema e repita o pedido de brilho.")
                } else if (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    open(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Brilho automático está ativo. Desative-o em Tela se quiser definir um valor fixo.")
                } else {
                    val level = (255 * value / 100f).roundToInt().coerceIn(1, 255)
                    check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)) {
                        "O Android não permitiu alterar o brilho." }
                    JSONObject().put("brilho_percentual_aproximado", value)
                }
            }
            "set_screen_timeout" -> {
                val minutes = args.optInt("minutos", -1)
                require(minutes in listOf(1, 2, 5, 10, 15, 30)) { "Escolha 1, 2, 5, 10, 15 ou 30 minutos." }
                if (!Settings.System.canWrite(context)) requestWriteSettings() else {
                    check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                        minutes * 60_000)) { "O Android não permitiu mudar o tempo da tela." }
                    JSONObject().put("tempo_solicitado_minutos", minutes)
                        .put("tempo_atual_minutos", Settings.System.getInt(context.contentResolver,
                            Settings.System.SCREEN_OFF_TIMEOUT, 0) / 60_000)
                }
            }
            "set_auto_rotate" -> {
                require(args.opt("ativada") is Boolean) { "Informe ativada como true ou false." }
                val enabled = args.getBoolean("ativada")
                if (!Settings.System.canWrite(context)) requestWriteSettings() else {
                    check(Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                        if (enabled) 1 else 0)) { "O Android não permitiu alterar a rotação." }
                    JSONObject().put("rotacao_automatica", Settings.System.getInt(context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
                }
            }
            "device_status" -> {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val audio = context.getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                JSONObject().put("bateria", if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "indisponível")
                    .put("horario", java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(System.currentTimeMillis()))
                    .put("volume_midia_percentual_aproximado", if (max > 0)
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0)
                    .put("brilho_automatico", Settings.System.getInt(context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            }
            "inspect_screen", "check_ui", "interact_ui", "type_text", "scroll_screen", "system_navigation", "touch_screen" -> {
                val service = OsoneAccessibilityService.active
                    ?: return JSONObject().put("erro", "Ative OSTIE em Ajustes > Acessibilidade para controlar outros apps.")
                when (name) {
                    "inspect_screen" -> service.inspect()
                    "check_ui" -> service.checkUi(args.optString("texto"))
                    "interact_ui" -> service.interact(args.optString("texto"), args.optString("acao"))
                    "type_text" -> service.type(args.optString("campo"), args.optString("texto"))
                    "scroll_screen" -> service.scroll(args.optString("direcao"))
                    "system_navigation" -> service.navigate(args.optString("acao"))
                    else -> service.gesture(args.optInt("x", -1), args.optInt("y", -1),
                        args.optInt("fim_x", -1).takeIf { args.has("fim_x") },
                        args.optInt("fim_y", -1).takeIf { args.has("fim_y") })
                }
            }
            else -> JSONObject().put("erro", "Ação local não autorizada neste app.")
        }
    } catch (failure: Exception) {
        AppDiagnostics.get(context).record("Agente local", "Falha na ação $name (${failure.javaClass.simpleName}).")
        JSONObject().put("erro", "Não consegui executar esta ação neste aparelho (${failure.javaClass.simpleName}).")
    }

    private fun normalized(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    private fun percentage(args: JSONObject): Int {
        val value = args.optInt("percentual", -1)
        require(args.has("percentual") && value in 0..100) { "Use um percentual inteiro entre 0 e 100." }
        return value
    }

    private fun requestWriteSettings(): JSONObject = open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "Autorize OSTIE a modificar configurações do sistema e repita o pedido.")

    private fun open(intent: Intent, message: String): JSONObject = try {
        context.startActivity(intent)
        JSONObject().put("resultado", message)
    } catch (_: android.content.ActivityNotFoundException) {
        JSONObject().put("erro", "Esta tela de Configurações não está disponível neste aparelho.")
    }

    private fun findApp(wanted: String, includeSystem: Boolean): Any {
        require(wanted.trim().length in 2..160) { "Informe o nome ou pacote do aplicativo." }
        val all = apps(includeSystem)
        val matches = all.filter { normalized(it.first) == normalized(wanted) || it.second.equals(wanted, true) }
            .ifEmpty { all.filter { normalized(it.first).contains(normalized(wanted)) } }
        return when {
            matches.isEmpty() -> JSONObject().put("erro", "App não encontrado. Tente list_apps com incluir_sistema.")
            matches.size > 1 -> JSONObject().put("erro", "Nome ambíguo; escolha um pacote.")
                .put("opcoes", JSONArray(matches.take(12).map { "${it.first} (${it.second})" }))
            else -> matches[0]
        }
    }

    @Suppress("DEPRECATION")
    private fun apps(includeSystem: Boolean = false): List<Pair<String, String>> {
        if (includeSystem) return context.packageManager.getInstalledApplications(0)
            .map { it.loadLabel(context.packageManager).toString() to it.packageName }
            .distinctBy { it.second }.sortedBy { normalized(it.first) }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = context.packageManager.queryIntentActivities(query, 0)
        return found.map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { normalized(it.first) }
    }
}
