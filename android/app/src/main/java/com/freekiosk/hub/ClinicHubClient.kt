package com.freekiosk.hub

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.freekiosk.net.AcceptedCertTrust
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Configuration for the clinic hub connection. */
data class HubConfig(
    val serverUrl: String,
    val socketUrl: String,
    val deviceId: String,
    val deviceToken: String,
    /** Fallback summary poll (seconds) when no socket events arrive. */
    val pollIntervalSec: Long = 60L,
    /**
     * Embedded FreeKiosk REST API (NanoHTTPD) — when enabled, the hub
     * self-reports `http://{localIp}:{restApiPort}` (+ the key) to the clinic
     * via POST /api/tablet/report-status so the admin panel's remote
     * management works without anyone typing IPs.
     */
    val restApiEnabled: Boolean = false,
    val restApiPort: Int = 8080,
    val restApiKey: String? = null,
)

/**
 * Clinic-server client for the Dentrio watch hub.
 *
 * Authenticates as the room's TabletDevice (NextAuth credentials provider
 * "tablet": deviceToken + deviceId -> long-lived session cookie), keeps the
 * watch summary fresh (REST) and reacts to realtime signals (Socket.IO).
 *
 * Frozen server contract (see docs/dentrio-watch-hub.md):
 * - GET  /api/auth/csrf + POST /api/auth/callback/tablet   (login)
 * - GET  /api/tablet/watch/summary                          (state)
 * - POST /api/tablet/watch/message {content}                (quick reply)
 * - POST /api/tablet/watch/help-call {note?}                (429 = cooldown)
 * - POST /api/messenger/messages/{id}/reactions {emoji}     (toggle)
 * - socket: listen `appointment-updated` (filter by our roomId) and, after
 *   `join-conversation`, `message-new` / `message-reaction-update`.
 *   NEVER emit `messenger-auth` (would pollute staff presence).
 *
 * All network work runs on a single background executor; callbacks are
 * invoked from that thread.
 *
 * Self-report: after login and on every poll tick the client pushes its LAN
 * REST endpoint and battery state (tablet + paired watch) to
 * POST /api/tablet/report-status — only when something changed, plus a 5 min
 * battery keepalive so the panel can gray out stale readings.
 */
class ClinicHubClient(
    private val config: HubConfig,
    /** Android context for battery telemetry; null disables battery reports. */
    private val context: Context? = null,
) {

    companion object {
        private const val TAG = "ClinicHubClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val SUMMARY_DEBOUNCE_MS = 500L
        private const val BATTERY_KEEPALIVE_MS = 5 * 60_000L
    }

    /** Full watch summary changed (parsed response of /watch/summary). */
    var onSummaryChanged: ((JSONObject) -> Unit)? = null

    /** Socket connectivity changed (REST may still work when false). */
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    var onError: ((String) -> Unit)? = null

    /** Raw realtime chat signal (message-new / message-reaction-update). */
    var onChatEvent: ((type: String, payload: JSONObject) -> Unit)? = null

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ClinicHub").apply { isDaemon = true }
    }

    // In-memory cookie jar — the tablet session cookie lives for ~100 years,
    // and login is cheap, so re-login after process restart is fine.
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val http = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val existing = cookieStore[url.host].orEmpty().filter { old ->
                    cookies.none { it.name == old.name }
                }
                cookieStore[url.host] = existing + cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host].orEmpty().filter { !it.expiresAt.let { e -> e < System.currentTimeMillis() } }
        })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Trust certificates the user accepted in the kiosk WebView
        // (self-signed clinic servers) — same store, same rules.
        .let { builder ->
            context?.let { AcceptedCertTrust.configure(builder, it) } ?: builder
        }
        .build()

    private var socket: Socket? = null
    @Volatile private var running = false
    @Volatile private var loggedIn = false
    @Volatile private var socketConnected = false

    /** Current room + conversation resolved from the last summary. */
    @Volatile var roomId: String? = null
        private set
    @Volatile private var conversationId: String? = null
    @Volatile var lastSummary: JSONObject? = null
        private set

    private var pollTask: ScheduledFuture<*>? = null
    private var pendingRefresh: ScheduledFuture<*>? = null

    // Self-report state — what the server last acknowledged, so the watchdog
    // only POSTs on change (plus a keepalive for battery freshness).
    private var lastReportedKioskUrl: String? = null
    private var lastReportedBattery: Pair<Int, Boolean>? = null
    private var lastBatteryReportAt = 0L
    private var reportedApiKey = false
    @Volatile private var watchBatteryLevel: Int? = null
    @Volatile private var watchBatteryCharging: Boolean = false
    @Volatile private var watchBatteryDirty = false

    fun isRunning(): Boolean = running
    fun isSocketConnected(): Boolean = socketConnected

    // ==================== lifecycle ====================

    fun start() {
        running = true
        executor.execute {
            try {
                login()
                reportStatusIfNeeded(force = true)
                refreshSummaryNow()
                connectSocket()
            } catch (e: Exception) {
                Log.e(TAG, "Hub start failed: ${e.message}")
                onError?.invoke(e.message ?: "start failed")
            }
        }
        pollTask = executor.scheduleWithFixedDelay(
            {
                if (running) {
                    safeRefresh()
                    reportStatusIfNeeded()
                }
            },
            config.pollIntervalSec, config.pollIntervalSec, TimeUnit.SECONDS,
        )
    }

    fun stop() {
        running = false
        pollTask?.cancel(false)
        pendingRefresh?.cancel(false)
        try {
            socket?.off()
            socket?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Socket disconnect failed: ${e.message}")
        }
        socket = null
        socketConnected = false
        executor.shutdown()
    }

    /** Watchdog hook (OverlayService) — reconnect the socket if it dropped. */
    fun checkAndReconnect() {
        if (!running) return
        val s = socket
        if (s != null && !s.connected()) {
            Log.i(TAG, "Watchdog: hub socket disconnected, reconnecting")
            s.connect()
        }
    }

    // ==================== auth ====================

    private fun login() {
        val csrfBody = get("${config.serverUrl}/api/auth/csrf")
        val csrfToken = JSONObject(csrfBody).getString("csrfToken")

        val form = FormBody.Builder()
            .add("csrfToken", csrfToken)
            .add("deviceToken", config.deviceToken)
            .add("deviceId", config.deviceId)
            .add("json", "true")
            .build()
        val request = Request.Builder()
            .url("${config.serverUrl}/api/auth/callback/tablet")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            // NextAuth answers 200 (json:true) or 302; success = session cookie.
            val hasSession = cookieStore.values.flatten()
                .any { it.name.contains("session-token") }
            if (!hasSession) {
                throw IllegalStateException("Tablet login failed (HTTP ${response.code}, no session cookie)")
            }
        }
        loggedIn = true
        Log.i(TAG, "Tablet session established for device ${config.deviceId}")
    }

    // ==================== REST ====================

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HubHttpException(response.code, url)
            return response.body?.string() ?: ""
        }
    }

    private fun postJson(url: String, body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    /** GET with one automatic re-login on 401 (expired/invalid session). */
    private fun authedGet(url: String): String =
        try {
            get(url)
        } catch (e: HubHttpException) {
            if (e.code == 401) {
                login()
                get(url)
            } else throw e
        }

    private fun authedPost(url: String, body: JSONObject): Pair<Int, String> {
        val first = postJson(url, body)
        if (first.first != 401) return first
        login()
        return postJson(url, body)
    }

    fun fetchSummary(): JSONObject {
        val body = authedGet("${config.serverUrl}/api/tablet/watch/summary")
        val summary = JSONObject(body)
        lastSummary = summary
        roomId = summary.optJSONObject("room")?.optString("id")?.takeIf { it.isNotEmpty() }
        val newConversationId = summary.optString("conversationId").takeIf { it.isNotEmpty() }
        if (newConversationId != conversationId) {
            conversationId = newConversationId
            joinConversation()
        }
        return summary
    }

    fun sendQuickReply(content: String): JSONObject {
        val (code, body) = authedPost(
            "${config.serverUrl}/api/tablet/watch/message",
            JSONObject().put("content", content),
        )
        if (code !in 200..299) throw HubHttpException(code, "watch/message: $body")
        scheduleRefresh()
        return JSONObject(body)
    }

    /** @return true when accepted, false during the 30 s cooldown (429). */
    fun sendHelpCall(note: String?): Boolean {
        val payload = JSONObject().apply { if (!note.isNullOrBlank()) put("note", note) }
        val (code, body) = authedPost("${config.serverUrl}/api/tablet/watch/help-call", payload)
        if (code == 429) return false
        if (code !in 200..299) throw HubHttpException(code, "watch/help-call: $body")
        return true
    }

    fun toggleReaction(messageId: String, emoji: String): JSONObject {
        val (code, body) = authedPost(
            "${config.serverUrl}/api/messenger/messages/$messageId/reactions",
            JSONObject().put("emoji", emoji),
        )
        if (code !in 200..299) throw HubHttpException(code, "reactions: $body")
        scheduleRefresh()
        return JSONObject(body)
    }

    // ==================== self-report (report-status) ====================

    /**
     * Session cookie for the WebView transplant (name + value) or null when
     * not logged in yet. The deviceToken itself never crosses into JS — only
     * the resulting NextAuth session cookie does.
     */
    fun getSessionCookie(): Pair<String, String>? =
        cookieStore.values.flatten()
            .firstOrNull { it.name.contains("session-token") }
            ?.let { it.name to it.value }

    /**
     * Watch battery relayed from the Wear OS Data Layer (WearRelay, Etap B2).
     * Marks the value dirty and pushes a report on the hub executor.
     */
    fun updateWatchBattery(level: Int, charging: Boolean) {
        executor.execute {
            watchBatteryLevel = level.coerceIn(0, 100)
            watchBatteryCharging = charging
            watchBatteryDirty = true
            reportStatusIfNeeded()
        }
    }

    /** Force a full report (used right after pairing). */
    fun reportStatusNow() {
        executor.execute { reportStatusIfNeeded(force = true) }
    }

    /**
     * Best-effort clear of everything this tablet reported (unpair) — the
     * panel drops the address and battery immediately instead of going stale.
     * Synchronous on the hub executor; errors are logged and swallowed.
     */
    fun reportUnpaired() {
        executor.execute {
            try {
                val payload = JSONObject()
                    .put("kioskApiUrl", JSONObject.NULL)
                    .put("kioskApiKey", JSONObject.NULL)
                    .put("battery", JSONObject.NULL)
                    .put("watchBattery", JSONObject.NULL)
                val (code, _) = authedPost(
                    "${config.serverUrl}/api/tablet/report-status", payload,
                )
                Log.i(TAG, "Unpair report sent (HTTP $code)")
            } catch (e: Exception) {
                Log.w(TAG, "Unpair report failed: ${e.message}")
            }
        }
    }

    /**
     * Push endpoint + battery to the clinic when something changed. Never
     * throws — a failed report retries naturally on the next poll tick.
     */
    private fun reportStatusIfNeeded(force: Boolean = false) {
        if (!running || !loggedIn) return
        try {
            val payload = JSONObject()

            if (config.restApiEnabled) {
                val ip = getLocalIpAddress()
                if (ip != null) {
                    val url = "http://$ip:${config.restApiPort}"
                    if (force || url != lastReportedKioskUrl) {
                        payload.put("kioskApiUrl", url)
                        if (!config.restApiKey.isNullOrEmpty() && (force || !reportedApiKey)) {
                            payload.put("kioskApiKey", config.restApiKey)
                        }
                    }
                }
            }

            val battery = readBattery()
            if (battery != null) {
                val keepalive =
                    System.currentTimeMillis() - lastBatteryReportAt > BATTERY_KEEPALIVE_MS
                if (force || battery != lastReportedBattery || keepalive) {
                    payload.put(
                        "battery",
                        JSONObject()
                            .put("level", battery.first)
                            .put("charging", battery.second),
                    )
                }
            }

            val watchLevel = watchBatteryLevel
            if (watchBatteryDirty && watchLevel != null) {
                payload.put(
                    "watchBattery",
                    JSONObject()
                        .put("level", watchLevel)
                        .put("charging", watchBatteryCharging),
                )
            }

            if (payload.length() == 0) return

            val (code, body) = authedPost(
                "${config.serverUrl}/api/tablet/report-status", payload,
            )
            if (code in 200..299) {
                if (payload.has("kioskApiUrl")) {
                    lastReportedKioskUrl = payload.getString("kioskApiUrl")
                }
                if (payload.has("kioskApiKey")) reportedApiKey = true
                if (payload.has("battery")) {
                    lastReportedBattery = battery
                    lastBatteryReportAt = System.currentTimeMillis()
                }
                if (payload.has("watchBattery")) watchBatteryDirty = false
            } else {
                Log.w(TAG, "report-status failed: HTTP $code $body")
            }
        } catch (e: Exception) {
            Log.w(TAG, "report-status error: ${e.message}")
        }
    }

    /**
     * Tablet battery via BatteryManager, with the sticky ACTION_BATTERY_CHANGED
     * intent as fallback (some ROMs return 0/-1 from the capacity property).
     */
    private fun readBattery(): Pair<Int, Boolean>? {
        val ctx = context ?: return null
        return try {
            val sticky = ctx.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val manager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            var level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level <= 0) {
                val raw = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (raw >= 0 && scale > 0) level = raw * 100 / scale
            }
            if (level < 0) null else Pair(level.coerceIn(0, 100), charging)
        } catch (e: Exception) {
            Log.w(TAG, "Battery read failed: ${e.message}")
            null
        }
    }

    /** Site-local IPv4 of this device, WiFi interfaces first. */
    private fun getLocalIpAddress(): String? =
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Local IP lookup failed: ${e.message}")
            null
        }

    // ==================== socket ====================

    private fun connectSocket() {
        val options = IO.Options().apply {
            reconnection = true
            reconnectionDelay = 2000
            reconnectionDelayMax = 30000
            transports = arrayOf("websocket", "polling")
            // Route Socket.IO through the hub's OkHttp client so user-accepted
            // (self-signed) certificates work for the realtime link too.
            callFactory = http
            webSocketFactory = http
        }
        val s = IO.socket(URI.create(config.socketUrl), options)
        socket = s

        s.on(Socket.EVENT_CONNECT) {
            socketConnected = true
            Log.i(TAG, "Hub socket connected")
            onConnectionChanged?.invoke(true)
            // Room membership is lost on reconnect — re-join and re-sync.
            joinConversation()
            scheduleRefresh()
        }
        s.on(Socket.EVENT_DISCONNECT) {
            socketConnected = false
            Log.w(TAG, "Hub socket disconnected")
            onConnectionChanged?.invoke(false)
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val message = args.firstOrNull()?.toString() ?: "connect error"
            Log.w(TAG, "Hub socket connect error: $message")
            onError?.invoke(message)
        }

        // Appointment updates are broadcast globally with ID-only payloads —
        // filter by our room and re-fetch the summary.
        s.on("appointment-updated") { args -> onAppointmentEvent(args) }
        s.on("appointment-created") { args -> onAppointmentEvent(args) }
        s.on("appointment-deleted") { args -> onAppointmentEvent(args) }

        s.on("message-new") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            onChatEvent?.invoke("message-new", payload)
            scheduleRefresh()
        }
        s.on("message-reaction-update") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            onChatEvent?.invoke("message-reaction-update", payload)
            scheduleRefresh()
        }

        s.connect()
    }

    private fun onAppointmentEvent(args: Array<Any>) {
        val payload = args.firstOrNull() as? JSONObject ?: return
        val eventRoomId = payload.optString("roomId")
        val ourRoom = roomId
        if (ourRoom != null && eventRoomId == ourRoom) {
            scheduleRefresh()
        }
    }

    private fun joinConversation() {
        val id = conversationId ?: return
        socket?.takeIf { it.connected() }?.emit("join-conversation", id)
    }

    // ==================== refresh plumbing ====================

    /** Debounced summary refresh — bursts of socket events collapse to one GET. */
    private fun scheduleRefresh() {
        if (!running) return
        pendingRefresh?.cancel(false)
        pendingRefresh = executor.schedule(
            { safeRefresh() }, SUMMARY_DEBOUNCE_MS, TimeUnit.MILLISECONDS,
        )
    }

    private fun safeRefresh() {
        try {
            refreshSummaryNow()
        } catch (e: Exception) {
            Log.e(TAG, "Summary refresh failed: ${e.message}")
            onError?.invoke(e.message ?: "refresh failed")
        }
    }

    private fun refreshSummaryNow() {
        val summary = fetchSummary()
        onSummaryChanged?.invoke(summary)
    }

    /** Run an action on the hub executor (bridge methods must not block JS). */
    fun execute(action: () -> Unit) {
        executor.execute(action)
    }
}

class HubHttpException(val code: Int, detail: String) :
    Exception("HTTP $code ($detail)")
