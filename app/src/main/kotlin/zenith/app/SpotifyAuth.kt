package zenith.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** The driver's Spotify account as the screens see it. */
data class SpotifyAccount(
    /** A client id was built into the app (repository variable SPOTIFY_CLIENT_ID). */
    val configured: Boolean,
    val connected: Boolean = false,
    val userName: String? = null,
    /** Premium account: playback controls work through the Web API. Null until known. */
    val premium: Boolean? = null,
)

/** A Web API answer: status, body and, on a 429, how many seconds Spotify asks to wait. */
data class SpotifyAnswer(val status: Int, val body: String, val retryAfterS: Long?) {
    /** The error's reason ("PREMIUM_REQUIRED", "NO_ACTIVE_DEVICE"…), when Spotify gives one. */
    val reason: String? get() = runCatching { JSONObject(body).optJSONObject("error")?.text("reason") }.getOrNull()
}

/**
 * The driver's Spotify account, for the music card without the notification access (SpotifyAuth.swift
 * on iOS): Authorization Code with PKCE (no client secret) in the browser, back through
 * `zenith://spotify-callback` to [MainActivity]. Tokens are kept in the app's private preferences and
 * refreshed a minute before they expire. The client id is the author's Spotify app, built in by the
 * CI (empty: not configured). Since February 2026 the profile no longer says Premium: it is learned
 * from the first playback command (403 PREMIUM_REQUIRED), and relearned at each drive. Main thread.
 */
object SpotifyAuth {
    private val clientId = BuildConfig.SPOTIFY_CLIENT_ID.trim()
    private val _state = MutableStateFlow(SpotifyAccount(configured = clientId.isNotEmpty()))
    val state: StateFlow<SpotifyAccount> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefs: SharedPreferences? = null
    private var tokens: Tokens? = null
    private var refreshing: Deferred<String?>? = null

    private const val REDIRECT_URI = "zenith://spotify-callback"
    private const val SCOPES =
        "user-read-currently-playing user-read-playback-state user-modify-playback-state user-library-read user-library-modify"
    private const val API = "https://api.spotify.com"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val PREFS = "spotify"
    private const val KEY_ACCESS = "access"
    private const val KEY_REFRESH = "refresh"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_USER = "user"
    private const val KEY_VERIFIER = "pkce_verifier"
    private const val KEY_STATE = "pkce_state"

    /** Reads the saved account once; cheap and idempotent (the activity and the drive service both call it). */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (!_state.value.configured) return
        val access = p.getString(KEY_ACCESS, null)
        val refresh = p.getString(KEY_REFRESH, null)
        if (access != null && refresh != null) {
            tokens = Tokens(access, refresh, p.getLong(KEY_EXPIRES, 0L))
            _state.value = _state.value.copy(connected = true, userName = p.getString(KEY_USER, null))
        }
    }

    /** Opens Spotify's sign-in page in the browser; the account is connected when it comes back here. */
    fun connect(context: Context) {
        init(context)
        val p = prefs ?: return
        if (!_state.value.configured) return
        val verifier = randomString(64)
        val state = randomString(24)
        // Kept on disk: Android may close Zenith while the driver signs in.
        p.edit { putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state) }
        val query = encode(
            listOf(
                "client_id" to clientId,
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge(verifier),
                "scope" to SCOPES,
                "state" to state,
            ),
        )
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.spotify.com/authorize?$query")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w("Zenith", "spotify sign-in", it) }
    }

    /** The browser came back to `zenith://spotify-callback`. */
    fun isCallback(uri: Uri?): Boolean = uri?.scheme == "zenith" && uri.host == "spotify-callback"

    /** Ends the sign-in: the state must be the one sent, then the code is traded for tokens. */
    fun finish(context: Context, uri: Uri) {
        init(context)
        val p = prefs ?: return
        val expected = p.getString(KEY_STATE, null)
        val verifier = p.getString(KEY_VERIFIER, null)
        // Used once: the same intent delivered again (activity recreated) does nothing.
        p.edit { remove(KEY_STATE).remove(KEY_VERIFIER) }
        val code = uri.getQueryParameter("code")
        if (expected == null || verifier == null || code == null || uri.getQueryParameter("state") != expected) {
            uri.getQueryParameter("error")?.let { Log.w("Zenith", "spotify sign-in: $it") }
            return
        }
        scope.launch {
            val result = tokenRequest(
                listOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                ),
                keeping = null,
            )
            if (result !is TokenResult.Granted) return@launch
            store(result.tokens)
            _state.value = _state.value.copy(connected = true, premium = null)
            refreshProfile()
            NowPlaying.spotifyAccountChanged()
        }
    }

    /** Forgets the account here (Spotify keeps the app's access until removed in the account page). */
    fun disconnect() {
        refreshing = null
        tokens = null
        prefs?.edit { remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).remove(KEY_USER) }
        _state.value = SpotifyAccount(configured = _state.value.configured)
        NowPlaying.spotifyAccountChanged()
    }

    /**
     * An authorized Web API request ([path] from "/v1/…"); refreshes the token when it expires or
     * Spotify answers 401. Null when not connected or the network failed.
     */
    suspend fun request(method: String, path: String, query: List<Pair<String, String>> = emptyList()): SpotifyAnswer? {
        if (!_state.value.connected) return null
        val token = accessToken(renew = false) ?: return null
        val first = send(method, path, query, token) ?: return null
        if (first.status != 401) return first
        val renewed = accessToken(renew = true) ?: return first
        return send(method, path, query, renewed) ?: first
    }

    /** Name of the account, and Premium when Spotify still says it (else learned again from the controls). */
    suspend fun refreshProfile() {
        val answer = request("GET", "/v1/me") ?: return
        if (answer.status != 200) return
        val me = runCatching { JSONObject(answer.body) }.getOrNull() ?: return
        val name = me.text("display_name") ?: me.text("id")
        if (name != _state.value.userName) prefs?.edit { putString(KEY_USER, name) }
        val premium = me.text("product")?.let { it == "premium" }
        if (_state.value.connected) _state.value = _state.value.copy(userName = name, premium = premium)
    }

    /** A playback command told whether the account has Premium. */
    fun notePremium(value: Boolean) {
        if (_state.value.premium != value) _state.value = _state.value.copy(premium = value)
    }

    // Tokens

    /** A valid access token; [renew]: Spotify refused the current one. Concurrent refreshes share one request. */
    private suspend fun accessToken(renew: Boolean): String? {
        val current = tokens ?: return null
        if (!renew && current.expiresAtMs - System.currentTimeMillis() > 60_000) return current.access
        refreshing?.let { return it.await() }
        val job = scope.async { renew(current) }
        refreshing = job
        return try {
            job.await()
        } finally {
            if (refreshing === job) refreshing = null
        }
    }

    private suspend fun renew(old: Tokens): String? {
        val result = tokenRequest(
            listOf("grant_type" to "refresh_token", "refresh_token" to old.refresh, "client_id" to clientId),
            keeping = old.refresh,
        )
        // Signed out or in again meanwhile: this answer is about tokens no longer used.
        if (tokens?.refresh != old.refresh) return tokens?.access
        return when (result) {
            is TokenResult.Granted -> {
                store(result.tokens)
                result.tokens.access
            }
            // Access removed in the Spotify account: connected no more.
            TokenResult.Refused -> {
                disconnect()
                null
            }
            TokenResult.Failed -> null
        }
    }

    private fun store(fresh: Tokens) {
        tokens = fresh
        prefs?.edit { putString(KEY_ACCESS, fresh.access).putString(KEY_REFRESH, fresh.refresh).putLong(KEY_EXPIRES, fresh.expiresAtMs) }
    }

    /** Access and refresh tokens of the account; the expiry on the wall clock (kept across restarts). */
    private data class Tokens(val access: String, val refresh: String, val expiresAtMs: Long)

    private sealed interface TokenResult {
        data class Granted(val tokens: Tokens) : TokenResult
        data object Refused : TokenResult
        data object Failed : TokenResult
    }

    /** The token endpoint; a refresh answer may come without a new refresh token: [keeping] stays. */
    private suspend fun tokenRequest(form: List<Pair<String, String>>, keeping: String?): TokenResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = encode(form).encodeToByteArray()
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                if (status != 200) {
                    Log.w("Zenith", "spotify token $status")
                    if (status == 400) TokenResult.Refused else TokenResult.Failed
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val access = json.text("access_token")
                    val refresh = json.text("refresh_token") ?: keeping
                    if (access == null || refresh == null) {
                        TokenResult.Failed
                    } else {
                        TokenResult.Granted(Tokens(access, refresh, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000))
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(TokenResult.Failed)
    }

    private suspend fun send(method: String, path: String, query: List<Pair<String, String>>, token: String): SpotifyAnswer? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(API + path + (if (query.isEmpty()) "" else "?" + encode(query))).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.useCaches = false
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    // PUT and POST without a body still say "Content-Length: 0" (Spotify answers 411 otherwise).
                    if (method == "PUT" || method == "POST") {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(0)
                        conn.outputStream.close()
                    }
                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText().take(256 * 1024) }.orEmpty()
                    SpotifyAnswer(status, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w("Zenith", "spotify $path", it) }.getOrNull()
        }

    // Helpers

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    /** Query or form encoding, every reserved character escaped. */
    private fun encode(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (name, value) -> escape(name) + "=" + escape(value) }

    private fun escape(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c < 128 && c.toChar() in UNRESERVED) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }

    /** PKCE verifier or state: the system's secure random generator over the allowed characters. */
    private fun randomString(count: Int): String {
        val random = SecureRandom()
        return String(CharArray(count) { UNRESERVED[random.nextInt(UNRESERVED.length)] })
    }

    /** S256 challenge: SHA-256 of the verifier in URL-safe base64, no padding. */
    private fun challenge(verifier: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
}

/** A string field, null when missing, JSON null or empty (optString would give "null"). */
internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
