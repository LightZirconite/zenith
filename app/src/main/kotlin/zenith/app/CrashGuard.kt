package zenith.app

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * Keeps one crash from becoming a loop that leaves the app unusable (a drive service restarted by
 * Android after a crash, the app reopening straight onto what crashed). Java crashes are dated
 * here; native ones (the map's GPU driver) are caught by [MapGuard].
 */
object CrashGuard {
    private const val PREFS = "zenith"
    private const val KEY_CRASHED_AT = "crashed_at"
    private const val RECENT_MS = 2 * 60_000L

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                // Written at once: the process dies right after.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                    putLong(KEY_CRASHED_AT, System.currentTimeMillis())
                }
                Log.e("Zenith", "crash", error)
                writeReport(app, "Plantage (${thread.name})\n\n${Log.getStackTraceString(error)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Last crash, written for the driver to send (home shows it once): nothing leaves the phone
     * unless they share it themselves.
     */
    fun report(context: Context): String? = runCatching { reportFile(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clearReport(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    internal fun writeReport(context: Context, what: String) {
        runCatching {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            reportFile(context).writeText(
                "Zenith $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE}\n" +
                    "${java.util.Date()}\n\n$what\n",
            )
        }
    }

    private fun reportFile(context: Context) = File(context.filesDir, "crash.txt")

    /** The app crashed in the last two minutes: Android's automatic restart of a drive is declined. */
    fun crashedRecently(context: Context): Boolean =
        System.currentTimeMillis() - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CRASHED_AT, 0) < RECENT_MS
}

/**
 * The map's native code can crash the whole app without a word on a device whose GPU driver it
 * does not get along with (cheap car head units): no Java handler sees it. A mark is left while the
 * map opens and cleared once it has drawn; found again at the next opening, the last try died.
 * After two such deaths in the same version the map stays off (a black backdrop, everything else
 * works); a new version tries again.
 */
object MapGuard {
    private const val PREFS = "zenith"
    private const val KEY_OPENING = "map_opening"
    private const val KEY_FAILS = "map_fails"
    private const val KEY_VERSION = "map_fails_version"
    private const val MAX_FAILS = 2

    /** True when the map may be created; marks the attempt. */
    fun tryOpen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
        var fails = if (prefs.getLong(KEY_VERSION, -1) == version) prefs.getInt(KEY_FAILS, 0) else 0
        val lastTryDied = prefs.getBoolean(KEY_OPENING, false)
        if (lastTryDied) fails++
        val allowed = fails < MAX_FAILS
        if (!allowed) {
            Log.w("Zenith", "map off: it crashed $fails times in this version")
            // Said once, the first time it goes off: the native crash left no other trace.
            if (lastTryDied && fails == MAX_FAILS) {
                CrashGuard.writeReport(
                    context,
                    "La carte a fermé Zenith $fails fois à son ouverture (plantage natif, pilote graphique) : " +
                        "elle est coupée sur cet appareil jusqu'à la prochaine version. GPU : ${gpu()}",
                )
            }
        }
        prefs.edit(commit = true) {
            putLong(KEY_VERSION, version)
            putInt(KEY_FAILS, fails)
            putBoolean(KEY_OPENING, allowed)
        }
        return allowed
    }

    /** The GPU as the system names it, for the report (no GL context needed). */
    private fun gpu(): String = runCatching {
        listOf("ro.hardware.egl", "ro.board.platform", "ro.hardware.vulkan").joinToString(" · ") { key ->
            val value = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
            "$key=${value.orEmpty()}"
        }
    }.getOrDefault("?")

    /** The map drew its first frame, or closed normally: the attempt went fine. */
    fun opened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_OPENING, false)
            putInt(KEY_FAILS, 0)
        }
    }
}
