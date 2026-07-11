package com.freekiosk.hub

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native bridge for the Dentrio clinic hub (see ClinicHubClient).
 *
 * Mirrors MqttModule's architecture: a long-lived native network client owned
 * by the module, JS drives lifecycle (start/stop from settings + KioskScreen
 * autostart) and renders status; the OverlayService watchdog calls
 * [checkAndReconnect] to heal a dropped socket.
 *
 * Events emitted to JS:
 * - onHubConnectionChanged { connected }
 * - onHubConnectionError   { message }
 * - onHubSummaryChanged    { summary: String (JSON) }
 */
class HubModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "HubModule"
        private const val NAME = "HubModule"

        /** Static reference for the OverlayService watchdog. */
        @Volatile
        private var instance: HubModule? = null

        fun checkAndReconnect() {
            instance?.client?.checkAndReconnect()
        }
    }

    init {
        instance = this
    }

    private var client: ClinicHubClient? = null

    override fun getName(): String = NAME

    /**
     * Start the hub client.
     *
     * Expected keys: serverUrl (String), socketUrl (String), deviceId (String),
     * deviceToken (String), pollIntervalSec (Int?).
     */
    @ReactMethod
    fun startHub(configMap: ReadableMap, promise: Promise) {
        try {
            if (client != null) {
                promise.reject("ALREADY_RUNNING", "Hub client is already running")
                return
            }
            val serverUrl = configMap.getString("serverUrl")?.trimEnd('/')
            val socketUrl = configMap.getString("socketUrl")?.trimEnd('/')
            val deviceId = configMap.getString("deviceId")
            val deviceToken = configMap.getString("deviceToken")
            if (serverUrl.isNullOrEmpty() || socketUrl.isNullOrEmpty() ||
                deviceId.isNullOrEmpty() || deviceToken.isNullOrEmpty()
            ) {
                promise.reject("INVALID_CONFIG", "serverUrl, socketUrl, deviceId and deviceToken are required")
                return
            }

            val hubClient = ClinicHubClient(
                HubConfig(
                    serverUrl = serverUrl,
                    socketUrl = socketUrl,
                    deviceId = deviceId,
                    deviceToken = deviceToken,
                    pollIntervalSec = if (configMap.hasKey("pollIntervalSec")) {
                        configMap.getInt("pollIntervalSec").toLong()
                    } else 60L,
                    restApiEnabled = configMap.hasKey("restApiEnabled") &&
                        configMap.getBoolean("restApiEnabled"),
                    restApiPort = if (configMap.hasKey("restApiPort")) {
                        configMap.getInt("restApiPort")
                    } else 8080,
                    restApiKey = configMap.getString("restApiKey"),
                ),
                reactContext.applicationContext,
            )

            hubClient.onConnectionChanged = { connected ->
                sendEvent("onHubConnectionChanged", Arguments.createMap().apply {
                    putBoolean("connected", connected)
                })
            }
            hubClient.onError = { message ->
                sendEvent("onHubConnectionError", Arguments.createMap().apply {
                    putString("message", message)
                })
            }
            hubClient.onSummaryChanged = { summary ->
                sendEvent("onHubSummaryChanged", Arguments.createMap().apply {
                    putString("summary", summary.toString())
                })
            }

            client = hubClient
            hubClient.start()
            Log.i(TAG, "Hub client started for $serverUrl (device $deviceId)")
            promise.resolve(true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start hub client", e)
            promise.reject("START_ERROR", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @ReactMethod
    fun stopHub(promise: Promise) {
        try {
            client?.stop()
            client = null
            Log.i(TAG, "Hub client stopped")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop hub client", e)
            promise.reject("STOP_ERROR", e.message)
        }
    }

    @ReactMethod
    fun isHubConnected(promise: Promise) {
        promise.resolve(client?.isSocketConnected() == true)
    }

    @ReactMethod
    fun isHubRunning(promise: Promise) {
        promise.resolve(client?.isRunning() == true)
    }

    /** Last summary JSON (string) or null — for instant settings-status UI. */
    @ReactMethod
    fun getHubSummary(promise: Promise) {
        promise.resolve(client?.lastSummary?.toString())
    }

    @ReactMethod
    fun sendQuickReply(content: String, promise: Promise) {
        val hubClient = client ?: return promise.reject("NOT_RUNNING", "Hub is not running")
        hubClient.execute {
            try {
                promise.resolve(hubClient.sendQuickReply(content).toString())
            } catch (e: Exception) {
                promise.reject("SEND_ERROR", e.message)
            }
        }
    }

    /** Resolves true when accepted, false during the server-side cooldown. */
    @ReactMethod
    fun sendHelpCall(note: String?, promise: Promise) {
        val hubClient = client ?: return promise.reject("NOT_RUNNING", "Hub is not running")
        hubClient.execute {
            try {
                promise.resolve(hubClient.sendHelpCall(note))
            } catch (e: Exception) {
                promise.reject("SEND_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun toggleReaction(messageId: String, emoji: String, promise: Promise) {
        val hubClient = client ?: return promise.reject("NOT_RUNNING", "Hub is not running")
        hubClient.execute {
            try {
                promise.resolve(hubClient.toggleReaction(messageId, emoji).toString())
            } catch (e: Exception) {
                promise.reject("SEND_ERROR", e.message)
            }
        }
    }

    /**
     * NextAuth session cookie ({name, value} or null) for the WebView
     * transplant — JS sets it via CookieManager so the kiosk WebView is
     * logged in without ever seeing the deviceToken.
     */
    @ReactMethod
    fun getSessionCookie(promise: Promise) {
        val cookie = client?.getSessionCookie()
        if (cookie == null) {
            promise.resolve(null)
            return
        }
        promise.resolve(Arguments.createMap().apply {
            putString("name", cookie.first)
            putString("value", cookie.second)
        })
    }

    /** Watch battery relayed from the Wear OS Data Layer (Etap B2). */
    @ReactMethod
    fun updateWatchBattery(level: Int, charging: Boolean, promise: Promise) {
        val hubClient = client ?: return promise.reject("NOT_RUNNING", "Hub is not running")
        hubClient.updateWatchBattery(level, charging)
        promise.resolve(true)
    }

    /** Force a full self-report (endpoint + battery) — used after pairing. */
    @ReactMethod
    fun reportStatusNow(promise: Promise) {
        val hubClient = client ?: return promise.reject("NOT_RUNNING", "Hub is not running")
        hubClient.reportStatusNow()
        promise.resolve(true)
    }

    /** Best-effort clear of the reported endpoint + battery (unpair). */
    @ReactMethod
    fun reportUnpaired(promise: Promise) {
        val hubClient = client ?: return promise.resolve(false)
        hubClient.reportUnpaired()
        promise.resolve(true)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN event emitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN event emitter
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        try {
            client?.stop()
            client = null
            instance = null
            Log.d(TAG, "HubModule cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }
}
