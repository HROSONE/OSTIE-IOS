package com.osone.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

data class AttachmentRef(val uri: Uri, val name: String, val mime: String)

/** O URI vem exclusivamente do seletor do Android; nenhum caminho arbitrário é lido. */
class AttachmentClient(private val context: Context) {
    data class Payload(val part: JSONObject, val remoteName: String? = null)

    fun describe(uri: Uri): AttachmentRef {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.take(100)?.replace('\n', ' ') ?: "arquivo"
        val mime = context.contentResolver.getType(uri)?.substringBefore(';') ?: "application/octet-stream"
        return AttachmentRef(uri, name, mime)
    }

    /** Limite explícito de 50 MB: evita copiar arquivos gigantes para o cache do aparelho. */
    fun prepare(ref: AttachmentRef, key: String): Payload {
        val temp = File.createTempFile("osone-attach-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(ref.uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 50L * 1024 * 1024) throw IllegalArgumentException("Arquivo acima de 50 MB. Escolha um menor.")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException("O Android não permitiu ler este arquivo.")
            if (temp.length() == 0L) throw IllegalArgumentException("O arquivo está vazio.")
            val ext = ref.name.substringAfterLast('.', "").lowercase()
            if (ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")) {
                val text = extractOffice(temp)
                if (text.isBlank()) throw IllegalArgumentException("Não encontrei texto legível neste documento.")
                return Payload(JSONObject().put("text", "Conteúdo extraído de ${ref.name}:\n$text"))
            }
            if (ext in setOf("zip", "jar", "apk")) {
                val names = ZipFile(temp).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.take(200)
                        .map { it.name.take(160) }.joinToString("\n")
                }
                return Payload(JSONObject().put("text", "Lista parcial de arquivos em ${ref.name} (não li o conteúdo interno):\n$names"))
            }
            val source = ext in setOf("kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "json", "xml", "md", "txt", "csv", "log", "yaml", "yml", "sql")
            if (source || ref.mime.startsWith("text/")) {
                if (temp.length() > 2_000_000) throw IllegalArgumentException("Documento de texto acima de 2 MB. Divida em partes menores.")
                return Payload(JSONObject().put("text", "Arquivo ${ref.name}:\n${temp.readText(Charsets.UTF_8).take(1_500_000)}"))
            }
            if (temp.length() <= 8_000_000 && (ref.mime.startsWith("image/") || ref.mime == "application/pdf" ||
                    ref.mime.startsWith("audio/") || ref.mime.startsWith("video/"))) {
                return Payload(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", ref.mime).put("data", Base64.encodeToString(temp.readBytes(), Base64.NO_WRAP))))
            }
            return upload(temp, ref, key)
        } finally { temp.delete() }
    }

    private fun extractOffice(file: File): String = ZipFile(file).use { zip ->
        val document = zip.entries().asSequence()
            .filter { it.name.matches(Regex("(word/document|ppt/slides/slide[0-9]+|xl/sharedStrings|content)\\.xml")) }
            .take(40).map { entry ->
                zip.getInputStream(entry).use { stream ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (bytes.size() < 1_000_000) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, 1_000_000 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                    val xml = bytes.toString("UTF-8")
                    android.text.Html.fromHtml(xml.replace(Regex("</(?:w:p|a:p|text:p|row|si)>"), "\n"),
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                }
            }.filter { it.isNotBlank() }.take(30).joinToString("\n")
        document.take(1_500_000)
    }

    private fun upload(file: File, ref: AttachmentRef, key: String): Payload {
        val start = URL("https://generativelanguage.googleapis.com/upload/v1beta/files").openConnection() as HttpURLConnection
        val uploadUrl = try {
            start.requestMethod = "POST"; start.connectTimeout = 12_000; start.readTimeout = 30_000
            start.doOutput = true
            start.setRequestProperty("x-goog-api-key", key)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", ref.mime)
            start.setRequestProperty("Content-Type", "application/json")
            start.outputStream.use { it.write(JSONObject().put("file", JSONObject()
                .put("display_name", ref.name)).toString().toByteArray(Charsets.UTF_8)) }
            if (start.responseCode !in 200..299) throw GeminiHttpException(start.responseCode)
            start.getHeaderField("X-Goog-Upload-URL") ?: throw IllegalStateException("Upload não retornou endereço.")
        } finally { start.disconnect() }
        require(URL(uploadUrl).host == "generativelanguage.googleapis.com") { "Endereço de upload inválido." }
        val send = URL(uploadUrl).openConnection() as HttpURLConnection
        val stored = try {
            send.requestMethod = "POST"; send.connectTimeout = 12_000; send.readTimeout = 90_000
            send.doOutput = true; send.setFixedLengthStreamingMode(file.length())
            send.setRequestProperty("X-Goog-Upload-Offset", "0")
            send.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            send.setRequestProperty("Content-Type", ref.mime)
            file.inputStream().use { input -> send.outputStream.use { output -> input.copyTo(output) } }
            if (send.responseCode !in 200..299) throw GeminiHttpException(send.responseCode)
            JSONObject(send.inputStream.bufferedReader().use { it.readText() }).getJSONObject("file")
        } finally { send.disconnect() }
        val name = stored.getString("name")
        try {
            var metadata = stored
            repeat(15) {
                when (metadata.optString("state")) {
                    "ACTIVE", "" -> return Payload(JSONObject().put("fileData", JSONObject()
                        .put("fileUri", metadata.getString("uri")).put("mimeType", ref.mime)), name)
                    "FAILED" -> throw IllegalStateException("O serviço não conseguiu processar o arquivo.")
                }
                Thread.sleep(2000)
                metadata = fileInfo(name, key)
            }
            throw IllegalStateException("O serviço demorou demais para processar o arquivo.")
        } catch (failure: Exception) {
            try { delete(name, key) } catch (_: Exception) {}
            throw failure
        }
    }

    private fun fileInfo(name: String, key: String): JSONObject {
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/$name").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("x-goog-api-key", key)
            if (connection.responseCode !in 200..299) throw GeminiHttpException(connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun delete(name: String, key: String) {
        if (!name.matches(Regex("files/[a-zA-Z0-9_-]+"))) return
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/$name").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"; connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.setRequestProperty("x-goog-api-key", key)
            connection.responseCode
        } finally { connection.disconnect() }
    }
}
