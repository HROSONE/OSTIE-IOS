package com.osone.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memória do OSTIE como anotações Markdown em Documentos/OSTIE/memoria.md.
 * A pasta fica fora do app: sobrevive a reinstalações e pode ser lida ou editada pelo próprio usuário.
 * Uma cópia interna garante que a memória funcione mesmo antes da permissão de arquivos.
 */
class MemoryStore private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: MemoryStore? = null
        fun get(context: Context): MemoryStore = instance ?: synchronized(this) {
            instance ?: MemoryStore(context.applicationContext).also { instance = it }
        }

        const val FOLDER = "OSTIE"
        const val FILE = "memoria.md"
        const val BACKUP = "memoria-anterior.md"
        private const val LIMIT = 60_000
        val SECTIONS = listOf("Sobre o usuário", "Preferências", "Pessoas", "Rotina e agenda", "Projetos", "Anotações")

        fun template(): String = buildString {
            append("# Memória do OSTIE\n\n")
            append("> Anotações que o OSTIE mantém sobre o usuário. Pode editar à vontade; o OSTIE relê ao conectar.\n")
            SECTIONS.forEach { append("\n## ").append(it).append("\n") }
        }
    }

    private val internal = File(context.filesDir, FILE)
    @Suppress("DEPRECATION")
    private val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
    private val shared = File(folder, FILE)

    var text by mutableStateOf("")
        private set
    /** true quando a memória está salva na pasta pública (persistente entre instalações). */
    var persistent by mutableStateOf(false)
        private set

    init { refresh() }

    val location: String get() = "Documentos/$FOLDER/$FILE"

    // Sem armazenamento externo montado, a checagem pode falhar: a memória segue só dentro do app.
    fun hasFolderAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /** Tela do Android para "Acesso a todos os arquivos" (Android 11+). */
    fun accessIntent(): Intent? = if (Build.VERSION.SDK_INT >= 30)
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        else null

    /**
     * Procura a pasta no aparelho. Se já existe (instalação anterior), ela é a fonte da verdade;
     * anotações feitas só no app antes da permissão são somadas ao final.
     */
    @Synchronized fun refresh() {
        val local = read(internal)
        persistent = false
        if (hasFolderAccess()) {
            try {
                val found = read(shared)
                val merged = when {
                    found.isNullOrBlank() -> local ?: template()
                    local.isNullOrBlank() || found.contains(local.trim()) || local == found -> found
                    else -> mergeNotes(found, local)
                }
                folder.mkdirs()
                if (found != merged) shared.writeText(merged)
                persistent = shared.isFile
                internal.writeText(merged)
                text = merged
                return
            } catch (_: Exception) { /* Pasta inacessível: continua com a cópia interna. */ }
        }
        text = local ?: template().also { internal.writeText(it) }
    }

    @Synchronized fun save(content: String) {
        val value = content.take(LIMIT)
        internal.writeText(value)
        persistent = if (hasFolderAccess()) try { folder.mkdirs(); shared.writeText(value); true } catch (_: Exception) { false } else false
        text = value
    }

    /** Cópia da versão anterior, antes de uma reorganização automática. */
    fun backup(content: String) {
        try { File(context.filesDir, BACKUP).writeText(content) } catch (_: Exception) { }
        writeShared(BACKUP, content)
    }

    /** Outros arquivos do OSTIE na mesma pasta (ex.: rotinas.json); nulo sem permissão. */
    fun readShared(name: String): String? = if (hasFolderAccess()) read(File(folder, name)) else null

    fun writeShared(name: String, content: String) {
        if (hasFolderAccess()) try { folder.mkdirs(); File(folder, name).writeText(content) } catch (_: Exception) { }
    }

    /** Acrescenta um item datado na seção (criada se não existir). */
    fun note(section: String, entry: String): JSONObject {
        val clean = entry.trim().replace("\n", " ").take(400)
        require(clean.length >= 3) { "Anotação vazia." }
        val title = sectionName(section)
        val date = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
        val sections = parse(text)
        val body = sections[title].orEmpty().trimEnd()
        sections[title] = (if (body.isEmpty()) "" else "$body\n") + "- $clean ($date)"
        save(render(sections))
        return result("Anotado em \"$title\".")
    }

    /** Reescreve uma seção inteira, para reorganizar, corrigir ou resumir anotações antigas. */
    fun rewrite(section: String, content: String): JSONObject {
        val title = sectionName(section)
        val sections = parse(text)
        sections[title] = content.trim().take(8_000)
        save(render(sections))
        return result("Seção \"$title\" reorganizada.")
    }

    /** Troca (ou cria) a única linha da seção que começa com [prefix]; evita anotações duplicadas. */
    fun rewriteLine(section: String, prefix: String, line: String) {
        val title = sectionName(section)
        val sections = parse(text)
        val lines = sections[title].orEmpty().lines().filterNot { it.removePrefix("- ").startsWith(prefix, true) }
            .filter { it.isNotBlank() }
        sections[title] = (lines + "- $line").joinToString("\n")
        save(render(sections))
    }

    /** Apaga linhas que contenham o trecho pedido. */
    fun forget(fragment: String): JSONObject {
        val needle = fragment.trim()
        require(needle.length >= 3) { "Informe um trecho com pelo menos 3 letras." }
        val lines = text.lines()
        val kept = lines.filterNot { it.startsWith("- ") && it.contains(needle, true) }
        if (kept.size == lines.size) return JSONObject().put("resultado", "Nada parecido na memória.")
        save(kept.joinToString("\n"))
        return result("${lines.size - kept.size} anotação(ões) apagada(s).")
    }

    /** Trecho para as instruções dos modelos; limitado para não pesar a conversa. */
    fun promptBlock(): String {
        val notes = text.lines().filter { it.startsWith("## ") || it.startsWith("- ") || (it.isNotBlank() && !it.startsWith("#") && !it.startsWith(">")) }
        if (notes.none { !it.startsWith("## ") }) return ""
        return "\n\nMemória do OSTIE (anotações que você mesmo mantém sobre o usuário; use com naturalidade, sem recitar):\n" +
            notes.joinToString("\n").takeLast(6_000)
    }

    private fun result(message: String) = JSONObject().put("resultado", message)
        .put("arquivo", location).put("persistente", persistent)
        .apply { if (!persistent) put("aviso", "Salvo só dentro do app. Para sobreviver a reinstalação, o usuário precisa permitir a pasta de memória em Ajustes.") }

    private fun sectionName(value: String): String {
        val wanted = value.trim().removePrefix("#").trim().ifEmpty { "Anotações" }
        return SECTIONS.firstOrNull { it.equals(wanted, true) } ?: parse(text).keys.firstOrNull { it.equals(wanted, true) }
            ?: wanted.replaceFirstChar { it.uppercase() }.take(60)
    }

    /** Cabeçalho (antes da primeira seção) fica na chave vazia. */
    private fun parse(markdown: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        var current = ""
        val buffer = StringBuilder()
        markdown.lines().forEach { line ->
            if (line.startsWith("## ")) {
                map[current] = buffer.toString().trim('\n'); buffer.setLength(0)
                current = line.removePrefix("## ").trim()
            } else buffer.append(line).append('\n')
        }
        map[current] = buffer.toString().trim('\n')
        return map
    }

    private fun render(sections: Map<String, String>): String = buildString {
        append(sections[""].orEmpty().ifBlank { template().substringBefore("\n## ").trim() }).append("\n")
        sections.filterKeys { it.isNotEmpty() }.forEach { (title, body) ->
            append("\n## ").append(title).append("\n")
            if (body.isNotBlank()) append(body.trimEnd()).append("\n")
        }
    }

    private fun mergeNotes(base: String, extra: String): String {
        val existing = base.lines().toSet()
        val additions = extra.lines().filter { it.startsWith("- ") && it !in existing }
        if (additions.isEmpty()) return base
        val sections = parse(base)
        sections["Anotações"] = (sections["Anotações"].orEmpty().trimEnd() + "\n" + additions.joinToString("\n")).trim()
        return render(sections)
    }

    private fun read(file: File): String? = try {
        // Versões antigas usavam um nome fixo no título da seção de perfil.
        if (file.isFile) file.readText().take(LIMIT).replace("\n## Sobre Henrique\n", "\n## Sobre o usuário\n") else null
    } catch (_: Exception) { null }
}

/** Ação sensível (enviar mensagem, por exemplo) aguardando confirmação na tela. */
class PendingConfirmation(val title: String, val detail: String, val confirmLabel: String,
    val action: () -> JSONObject, val respond: (JSONObject) -> Unit)

object ConfirmGate {
    var pending by mutableStateOf<PendingConfirmation?>(null)
        private set

    fun request(title: String, detail: String, confirmLabel: String, respond: (JSONObject) -> Unit, action: () -> JSONObject) {
        pending?.respond?.invoke(JSONObject().put("resultado", "Substituída por outro pedido; nada foi feito."))
        pending = PendingConfirmation(title, detail, confirmLabel, action, respond)
    }

    fun confirm() {
        val item = pending ?: return
        pending = null
        item.respond(try { item.action() } catch (failure: Exception) {
            JSONObject().put("erro", failure.message?.take(160) ?: "Falha ao executar.")
        })
    }

    fun cancel() {
        val item = pending ?: return
        pending = null
        item.respond(JSONObject().put("resultado", "O usuário recusou na tela; nada foi feito."))
    }
}
