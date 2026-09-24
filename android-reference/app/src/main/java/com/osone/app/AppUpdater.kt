package com.osone.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class OstieUpdate(val versionCode: Long, val versionName: String, val apkUrl: String,
    val sha256: String, val notes: String)

/** Instalador assistido: só aceita versão superior, pacote idêntico e mesmo certificado. */
class AppUpdater(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences("ostie_updates", 0)
    /** Vazio = canal oficial publicado pela CI; o campo só aparece em "Canal personalizado". */
    var feedUrl by mutableStateOf(preferences.getString("feed_url", "").orEmpty())
        private set
    var autoUpdate by mutableStateOf(preferences.getBoolean("auto_update", true))
        private set
    var status by mutableStateOf("")
        private set
    /** Versão encontrada ao abrir o app, ainda não recusada: mostra o aviso "Atualizar agora". */
    var prompt by mutableStateOf<OstieUpdate?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var available by mutableStateOf<OstieUpdate?>(null)
        private set
    private var prepared: File? = null
    private var waitingForPermission = false

    fun updateFeedUrl(value: String) {
        feedUrl = value.take(2048)
        preferences.edit().putString("feed_url", feedUrl).apply()
        available = null
    }

    fun updateAutoUpdate(enabled: Boolean) {
        autoUpdate = enabled
        preferences.edit().putBoolean("auto_update", enabled).apply()
        UpdateCheckWorker.schedule(activity, enabled)
    }

    val installedVersionName: String get() = installedPackage().versionName.orEmpty()

    /** Ao abrir o app: no máximo a cada 6 h, silencioso se não houver novidade ou se falhar. */
    suspend fun checkOnLaunch() {
        if (!autoUpdate || busy) return
        val now = System.currentTimeMillis()
        if (now - preferences.getLong("last_check", 0L) < 6 * 3600_000L) return
        preferences.edit().putLong("last_check", now).apply()
        val release = try { withContext(Dispatchers.IO) { UpdateFeed.fetch(UpdateFeed.address(activity)) } }
            catch (_: Exception) { return }
        if (release.versionCode > installedVersion()) {
            available = release
            status = "OSTIE ${release.versionName} disponível."
            if (preferences.getLong("dismissed_code", 0L) != release.versionCode) prompt = release
        }
    }

    fun dismissPrompt() {
        prompt?.let { preferences.edit().putLong("dismissed_code", it.versionCode).apply() }
        prompt = null
    }

    /** Procura e, se houver versão nova, já baixa e instala (usado pelo aviso e pela notificação). */
    suspend fun checkAndInstall() {
        prompt = null
        check()
        if (available != null) downloadAndInstall()
    }

    suspend fun check() {
        if (busy) return
        busy = true; available = null; status = "Procurando atualização…"
        try {
            val release = withContext(Dispatchers.IO) { UpdateFeed.fetch(UpdateFeed.address(activity)) }
            preferences.edit().putLong("last_check", System.currentTimeMillis()).apply()
            status = if (release.versionCode > installedVersion()) {
                available = release
                "OSTIE ${release.versionName} disponível."
            } else "OSTIE está atualizado (versão ${installedVersionName})."
        } catch (failure: Exception) { showError(failure) }
        finally { busy = false }
    }

    suspend fun downloadAndInstall() {
        val release = available ?: return
        if (busy) return
        busy = true; status = "Baixando OSTIE ${release.versionName}…"
        try {
            val apk = withContext(Dispatchers.IO) { download(release) }
            prepared = apk
            status = "APK conferido. Instalando…"
            installPrepared()
        } catch (failure: Exception) { showError(failure) }
        finally { busy = false }
    }

    suspend fun chooseApk(uri: Uri) {
        if (busy) return
        busy = true; status = "Verificando arquivo escolhido…"
        try {
            val apk = withContext(Dispatchers.IO) {
                val destination = stagingFile()
                activity.contentResolver.openInputStream(uri)?.use { input -> copyLimited(input, destination) }
                    ?: error("Não consegui abrir o APK selecionado.")
                verifyPackage(destination)
                destination
            }
            prepared = apk
            status = "APK compatível. Instalando…"
            installPrepared()
        } catch (failure: Exception) { showError(failure) }
        finally { busy = false }
    }

    fun resumeAfterPermission() {
        if (waitingForPermission && activity.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            installPrepared()
        }
    }

    fun installPrepared() {
        val apk = prepared?.takeIf { it.isFile } ?: return
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                waitingForPermission = true
                status = "Autorize OSTIE a instalar apps nesta tela e volte para continuar."
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
                return
            }
            // Confere novamente antes de instalar, inclusive após sair para as configurações.
            verifyPackage(apk)
            installWithSession(apk)
            status = "Instalando… Se o Android pedir, confirme. O app reinicia na versão nova com seus dados."
        } catch (failure: Exception) { showError(failure) }
    }

    /**
     * Sessão do PackageInstaller: no Android 12+, quando o próprio OSTIE fez a instalação anterior,
     * o sistema pode aplicar a atualização sem pedir confirmação. Caso contrário, abre a confirmação.
     */
    private fun installWithSession(apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("ostie.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // O sistema preenche o resultado nos extras: precisa ser mutável e explícito.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(activity, sessionId,
                    Intent(activity, UpdateStatusReceiver::class.java).setPackage(activity.packageName), flags)
                session.commit(callback.intentSender)
            }
        } catch (failure: Exception) {
            installer.abandonSession(sessionId)
            throw failure
        }
    }

    private fun installedVersion(): Long = versionOf(installedPackage())

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        return activity.packageManager.getPackageInfo(activity.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun versionOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val candidate = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("O arquivo não é um APK Android válido.")
        require(candidate.packageName == activity.packageName) { "Esse APK pertence a outro aplicativo." }
        require(versionOf(candidate) > installedVersion()) { "Esse APK não é mais novo que a versão instalada." }
        val current = installedPackage()
        val installedSigners = if (Build.VERSION.SDK_INT >= 28)
            current.signingInfo?.apkContentsSigners else current.signatures
        val candidateSigners = if (Build.VERSION.SDK_INT >= 28)
            candidate.signingInfo?.apkContentsSigners else candidate.signatures
        require(!installedSigners.isNullOrEmpty() && !candidateSigners.isNullOrEmpty() &&
            installedSigners.map { it.toCharsString() }.toSet() == candidateSigners.map { it.toCharsString() }.toSet()) {
            "Assinatura diferente. O Android não consegue atualizar esta instalação; o botão não pode contornar isso."
        }
    }

    private fun download(release: OstieUpdate): File {
        val connection = UpdateFeed.openHttps(release.apkUrl)
        val file = stagingFile()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 150_000_000) { "APK excede 150 MB." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == release.sha256) { "O arquivo não passou na verificação de integridade SHA-256." }
            verifyPackage(file)
            require(versionOf(activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: error("APK inválido.")) == release.versionCode) { "A versão do APK não corresponde ao canal." }
            return file
        } catch (failure: Exception) { file.delete(); throw failure }
        finally { connection.disconnect() }
    }

    private fun copyLimited(input: java.io.InputStream, destination: File) {
        try {
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 150_000_000) { "APK excede 150 MB." }
                    output.write(buffer, 0, count)
                }
            }
        } catch (failure: Exception) { destination.delete(); throw failure }
    }

    private fun stagingFile(): File {
        val directory = File(activity.cacheDir, "ostie-updates")
        require(directory.isDirectory || directory.mkdirs()) { "Sem espaço para baixar atualização." }
        return File(directory, "update.apk")
    }

    private fun showError(failure: Exception) {
        status = failure.message?.take(220) ?: "Não consegui verificar esta atualização."
        AppDiagnostics.get(activity).record("Atualização", status)
    }
}

/**
 * Canal de atualizações: a CI publica no repositório público OSONE-AI-releases, compartilhado com o
 * OSONE desktop. O OSTIE usa um Release fixo (ostie-latest) marcado como pré-lançamento, para nunca
 * virar o "latest" que o atualizador do desktop lê.
 */
object UpdateFeed {
    const val OFFICIAL = "https://github.com/HROSONE/OSTIE-AI-releases/releases/download/ostie-latest/latest.json"
    /** Endereços anteriores (nome do repositório e do usuário); reserva enquanto as trocas não acontecem. */
    val LEGACY = listOf(
        "https://github.com/HROSONE/OSONE-AI-releases/releases/download/ostie-latest/latest.json",
        "https://github.com/zerobob623-bit/OSONE-AI-releases/releases/download/ostie-latest/latest.json")

    fun address(context: Context): String = context.getSharedPreferences("ostie_updates", 0)
        .getString("feed_url", "").orEmpty().trim().let { if (it.isEmpty() || it in LEGACY) OFFICIAL else it }

    /** O canal oficial tenta o endereço atual e, se falhar, os anteriores; um canal personalizado é lido direto. */
    fun fetch(address: String): OstieUpdate {
        if (address != OFFICIAL) return read(address)
        var failure: Exception? = null
        for (candidate in listOf(OFFICIAL) + LEGACY) {
            try { return read(candidate) } catch (error: Exception) { if (failure == null) failure = error }
        }
        throw failure ?: IllegalStateException("Canal de atualizações indisponível.")
    }

    private fun read(address: String): OstieUpdate {
        require(address.isNotBlank()) { "Informe o endereço HTTPS do canal de atualizações." }
        val connection = openHttps(address)
        val bytes = try { connection.inputStream.use { input ->
            val buffer = ByteArrayOutputStream()
            val block = ByteArray(4096)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                require(buffer.size() + count <= 32_000) { "Resposta de atualizações grande demais." }
                buffer.write(block, 0, count)
            }
            buffer.toByteArray()
        } } finally { connection.disconnect() }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val hash = json.optString("sha256").lowercase()
        require(Regex("[0-9a-f]{64}").matches(hash)) { "O canal não informou um SHA-256 válido." }
        val apk = json.optString("apkUrl")
        require(URL(apk).protocol.equals("https", true)) { "O endereço do APK precisa ser HTTPS." }
        val code = json.optLong("versionCode", 0)
        require(code > 0) { "O canal não informou versionCode válido." }
        return OstieUpdate(code, json.optString("versionName", code.toString()), apk,
            hash, json.optString("notes").take(500))
    }

    fun openHttps(raw: String): HttpURLConnection {
        var url = URL(raw)
        repeat(6) {
            require(url.protocol.equals("https", true)) { "O link da atualização precisa ser HTTPS." }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 35_000; instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*")
            }
            val code = connection.responseCode
            if (code == 200) return connection
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: error("Redirecionamento sem destino.")
                url = URL(url, next)
                connection.disconnect()
            } else {
                connection.disconnect()
                error(if (code == 404) "Nenhuma versão publicada no canal ainda."
                    else "Servidor de atualização respondeu HTTP $code.")
            }
        }
        error("Atualização teve redirecionamentos demais.")
    }
}
