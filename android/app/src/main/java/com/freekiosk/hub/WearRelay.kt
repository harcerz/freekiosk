package com.freekiosk.hub

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * Wear OS Data Layer bridge between the tablet hub and the room's watch.
 *
 * Tablet → watch:
 * - [PATH_SUMMARY] (DataClient, urgent): the full watch summary JSON. Pushed
 *   on every hub summary refresh when the payload changed, plus a keepalive
 *   re-push so the watch can grey out stale data. DataItems survive
 *   reconnects — the watch always sees the latest state.
 * - [PATH_CHAT_MESSAGE] (MessageClient): one-shot "new chat message" events
 *   for notifications.
 * - [PATH_ACTION_RESULT] (MessageClient): outcome of a watch action
 *   ({action, ok, message?}) — e.g. help-call cooldown feedback.
 *
 * Watch → tablet (received here, executed on the hub's executor):
 * - [PATH_ACTION_REACTION]    {messageId, emoji}   → toggle reaction
 * - [PATH_ACTION_QUICK_REPLY] {content}            → room-channel message
 * - [PATH_ACTION_HELP_CALL]   {note?}              → 🆘 (false = cooldown)
 * - [PATH_BATTERY]            {level, charging}    → self-report to clinic
 * - [PATH_SUMMARY_REQUEST]    {}                   → re-push current summary
 *
 * Both sides MUST share applicationId + signing certificate — the Data Layer
 * only delivers events between identical packages.
 */
class WearRelay(
    private val context: Context,
    private val hub: ClinicHubClient,
) : MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "WearRelay"
        private const val SUMMARY_KEEPALIVE_MS = 5 * 60_000L

        const val PATH_SUMMARY = "/watch/summary"
        const val PATH_CHAT_MESSAGE = "/watch/chat-message"
        const val PATH_ACTION_REACTION = "/watch/action/reaction"
        const val PATH_ACTION_QUICK_REPLY = "/watch/action/quick-reply"
        const val PATH_ACTION_HELP_CALL = "/watch/action/help-call"
        const val PATH_ACTION_RESULT = "/watch/action-result"
        const val PATH_BATTERY = "/watch/battery"
        const val PATH_SUMMARY_REQUEST = "/watch/summary-request"
    }

    @Volatile private var lastPushedSummary: String? = null
    @Volatile private var lastPushAt = 0L

    fun start() {
        try {
            Wearable.getMessageClient(context).addListener(this)
            Log.i(TAG, "Wear relay listening for watch messages")
        } catch (e: Exception) {
            // Devices without Google Play services just skip the watch bridge.
            Log.w(TAG, "Wear relay unavailable: ${e.message}")
        }
    }

    fun stop() {
        try {
            Wearable.getMessageClient(context).removeListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Wear relay stop failed: ${e.message}")
        }
    }

    /** Push the summary DataItem when it changed (or as a periodic keepalive). */
    fun pushSummary(summary: JSONObject) {
        try {
            val payload = summary.toString()
            val now = System.currentTimeMillis()
            if (payload == lastPushedSummary && now - lastPushAt < SUMMARY_KEEPALIVE_MS) {
                return
            }
            lastPushedSummary = payload
            lastPushAt = now

            val request = PutDataMapRequest.create(PATH_SUMMARY).apply {
                dataMap.putString("summary", payload)
                dataMap.putLong("updatedAt", now)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Summary push failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Summary push error: ${e.message}")
        }
    }

    /** New room-chat message → one-shot event to every connected node. */
    fun pushChatMessage(payload: JSONObject) {
        broadcast(PATH_CHAT_MESSAGE, payload.toString())
    }

    private fun broadcast(path: String, data: String) {
        try {
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes ->
                    for (node in nodes) {
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, path, data.toByteArray(Charsets.UTF_8))
                            .addOnFailureListener { e ->
                                Log.w(TAG, "send $path → ${node.displayName} failed: ${e.message}")
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "connectedNodes failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "broadcast $path error: ${e.message}")
        }
    }

    private fun reply(nodeId: String, action: String, ok: Boolean, message: String? = null) {
        try {
            val payload = JSONObject().put("action", action).put("ok", ok)
            if (message != null) payload.put("message", message)
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, PATH_ACTION_RESULT, payload.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "action-result reply failed: ${e.message}")
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val data = try {
            val raw = String(event.data, Charsets.UTF_8)
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed watch payload on ${event.path}: ${e.message}")
            JSONObject()
        }
        Log.d(TAG, "Watch message: ${event.path}")

        when (event.path) {
            PATH_ACTION_REACTION -> hub.execute {
                try {
                    hub.toggleReaction(data.getString("messageId"), data.getString("emoji"))
                    reply(event.sourceNodeId, "reaction", true)
                } catch (e: Exception) {
                    Log.w(TAG, "Watch reaction failed: ${e.message}")
                    reply(event.sourceNodeId, "reaction", false, e.message)
                }
            }

            PATH_ACTION_QUICK_REPLY -> hub.execute {
                try {
                    hub.sendQuickReply(data.getString("content"))
                    reply(event.sourceNodeId, "quick-reply", true)
                } catch (e: Exception) {
                    Log.w(TAG, "Watch quick reply failed: ${e.message}")
                    reply(event.sourceNodeId, "quick-reply", false, e.message)
                }
            }

            PATH_ACTION_HELP_CALL -> hub.execute {
                try {
                    val note = data.optString("note").takeIf { it.isNotBlank() }
                    val accepted = hub.sendHelpCall(note)
                    reply(
                        event.sourceNodeId, "help-call", accepted,
                        if (accepted) null else "cooldown",
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Watch help call failed: ${e.message}")
                    reply(event.sourceNodeId, "help-call", false, e.message)
                }
            }

            PATH_BATTERY -> {
                val level = data.optInt("level", -1)
                if (level in 0..100) {
                    hub.updateWatchBattery(level, data.optBoolean("charging", false))
                }
            }

            PATH_SUMMARY_REQUEST -> {
                // Force a re-push even if unchanged — the watch just (re)started.
                lastPushedSummary = null
                hub.lastSummary?.let { pushSummary(it) }
            }
        }
    }
}
