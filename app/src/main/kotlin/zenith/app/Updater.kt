package zenith.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState

    /** Asking GitHub right now. */
    data object Checking : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class Ready(val version: String, val file: File) : UpdateState
    data class Installing(val version: String) : UpdateState

    /** Android wants a tap on its own install screen; launched as soon as Zenith is in front. */
    data class NeedsConfirmation(val version: String, val intent: Intent) : UpdateState
    data class Failed(val message: String) : UpdateState

    /** Checked: nothing newer than the installed [version]. */
    data class UpToDate(val version: String) : UpdateState
}

/**
 * Self-update from GitHub releases: when the latest release has a higher build number than the
 * installed app, downloads zenith.apk into the cache, installs it through PackageInstaller and
 * deletes the file afterwards. Once Zenith has installed itself once, Android 12+ lets it update
 * silently (it is then the installer of record); before that, Android asks for one tap.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/LightZirconite/zenith/releases/latest"
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Between two automatic checks; GitHub allows 60 an hour without a key. */
    private const val CHECK_EVERY_MS = 60_000L

    /** An install that never reported back (screen dismissed, installer killed) stops blocking checks. */
    private const val INSTALL_TIMEOUT_MS = 2 * 60_000L

    @Volatile
    private var lastCheckMs = 0L

    @Volatile
    private var installingSinceMs = 0L

    private fun dir(context: Context) = File(context.cacheDir, "update")

    /** Checks GitHub at most once a minute: at each return to the app, and every 2 minutes while it is open. */
    suspend fun checkIfDue(context: Context) {
        if (System.currentTimeMillis() - lastCheckMs < CHECK_EVERY_MS) return
        checkAndDownload(context)
    }

    /**
     * Checks GitHub and downloads a newer APK if there is one. [force] (the settings' button) asks
     * whatever the state, except during a download or an install that is still under way.
     */
    suspend fun checkAndDownload(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val current = _state.value
        val stuck = current is UpdateState.Installing && System.currentTimeMillis() - installingSinceMs > INSTALL_TIMEOUT_MS
        val free = current is UpdateState.Idle || current is UpdateState.Failed || current is UpdateState.UpToDate || stuck
        val busy = current is UpdateState.Checking || current is UpdateState.Downloading || (current is UpdateState.Installing && !stuck)
        if (!free && !(force && !busy)) return@withContext
        lastCheckMs = System.currentTimeMillis()
        _state.value = UpdateState.Checking
        runCatching {
            val release = Json.parseToJsonElement(get(LATEST)).jsonObject
            val tag = release.getValue("tag_name").jsonPrimitive.content // v0.1.N
            val build = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching
            val installed = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            cleanup(context)
            if (build <= installed) {
                _state.value = UpdateState.UpToDate(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
                return@runCatching
            }
            val url = release.getValue("assets").jsonArray.map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content == "zenith.apk" }
                ?.getValue("browser_download_url")?.jsonPrimitive?.content ?: return@runCatching
            val version = tag.removePrefix("v")
            val file = File(dir(context).apply { mkdirs() }, "zenith-$build.apk")
            download(url, file) { _state.value = UpdateState.Downloading(version, it) }
            _state.value = UpdateState.Ready(version, file)
        }.onFailure {
            Log.w("Zenith", "update check", it)
            _state.value = UpdateState.Failed("Mise à jour impossible : ${it.message}")
        }
        // A release without an APK yet (still being published): nothing to say, the next check sees it.
        if (_state.value is UpdateState.Checking) _state.value = UpdateState.Idle
    }

    /** Hands the downloaded APK to the system installer (asks for confirmation if needed). */
    fun install(context: Context) {
        val ready = _state.value as? UpdateState.Ready ?: return
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("zenith.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, 0, Intent(context, UpdateReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
            installingSinceMs = System.currentTimeMillis()
            _state.value = UpdateState.Installing(ready.version)
        }.onFailure {
            Log.w("Zenith", "update install", it)
            _state.value = UpdateState.Failed("Installation impossible : ${it.message}")
        }
    }

    /** The install screen was shown (or dismissed): wait for the installer result. */
    fun confirmationShown() {
        val pending = _state.value as? UpdateState.NeedsConfirmation ?: return
        installingSinceMs = System.currentTimeMillis()
        _state.value = UpdateState.Installing(pending.version)
    }

    internal fun needsConfirmation(intent: Intent) {
        val version = (_state.value as? UpdateState.Installing)?.version ?: return
        _state.value = UpdateState.NeedsConfirmation(version, intent)
    }

    internal fun finished(context: Context, message: String?) {
        cleanup(context)
        _state.value = if (message == null) UpdateState.Idle else UpdateState.Failed(message)
    }

    /** Deletes downloaded APKs (old ones, or after an install). */
    private fun cleanup(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, target: File, progress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) progress(done.toFloat() / total)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Receives the installer result. A confirmation request is handed to [MainActivity] through the
 * state: Android blocks activity starts from a receiver while the app is in the background, which
 * used to leave the update hanging with nothing on screen.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                } ?: return
                Updater.needsConfirmation(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.finished(context, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.finished(context, "Mise à jour annulée, nouvel essai plus tard")
            else -> Updater.finished(
                context,
                "Installation refusée ($status) : ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}",
            )
        }
    }
}
