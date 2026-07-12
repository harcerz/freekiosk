package com.freekiosk.wear.comm

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.freekiosk.wear.data.WatchStateHolder
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Watch → tablet messaging over the Data Layer (paths mirror WearRelay in
 * the tablet app). All senders are best-effort suspend functions returning
 * whether the message reached at least one connected node.
 */
object WatchComm {

    private const val TAG = "WatchComm"
    private const val BATTERY_REPORT_INTERVAL_MS = 15 * 60_000L

    const val PATH_ACTION_REACTION = "/watch/action/reaction"
    const val PATH_ACTION_QUICK_REPLY = "/watch/action/quick-reply"
    const val PATH_ACTION_HELP_CALL = "/watch/action/help-call"
    const val PATH_BATTERY = "/watch/battery"
    const val PATH_SUMMARY_REQUEST = "/watch/summary-request"

    private suspend fun send(context: Context, path: String, payload: JSONObject): Boolean {
        Log.i(TAG, "send($path) — resolving nodes…")
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            Log.i(TAG, "send($path): ${nodes.size} connected node(s)")
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected tablet for $path")
                return false
            }
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            var delivered = false
            for (node in nodes) {
                try {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, path, bytes)
                        .await()
                    Log.i(TAG, "send($path) → ${node.displayName}: OK")
                    delivered = true
                } catch (e: Exception) {
                    Log.w(TAG, "send $path → ${node.displayName} failed: ${e.message}")
                }
            }
            delivered
        } catch (e: Exception) {
            Log.w(TAG, "send $path failed: ${e.message}")
            false
        }
    }

    suspend fun sendReaction(context: Context, messageId: String, emoji: String): Boolean =
        send(
            context, PATH_ACTION_REACTION,
            JSONObject().put("messageId", messageId).put("emoji", emoji),
        )

    suspend fun sendQuickReply(context: Context, content: String): Boolean =
        send(context, PATH_ACTION_QUICK_REPLY, JSONObject().put("content", content))

    suspend fun sendHelpCall(context: Context): Boolean =
        send(context, PATH_ACTION_HELP_CALL, JSONObject())

    suspend fun requestSummary(context: Context): Boolean =
        send(context, PATH_SUMMARY_REQUEST, JSONObject())

    /**
     * Report the watch battery to the tablet (which forwards it to the
     * clinic). Sends on level/charging change, at most every 15 min otherwise.
     */
    suspend fun reportBatteryIfNeeded(context: Context, force: Boolean = false): Boolean {
        val reading = readBattery(context) ?: return false
        val now = System.currentTimeMillis()
        val unchanged = reading == WatchStateHolder.lastBatterySent
        val fresh = now - WatchStateHolder.lastBatterySentAt < BATTERY_REPORT_INTERVAL_MS
        if (!force && unchanged && fresh) return false

        val sent = send(
            context, PATH_BATTERY,
            JSONObject().put("level", reading.first).put("charging", reading.second),
        )
        if (sent) {
            WatchStateHolder.lastBatterySent = reading
            WatchStateHolder.lastBatterySentAt = now
        }
        return sent
    }

    private fun readBattery(context: Context): Pair<Int, Boolean>? {
        return try {
            val sticky = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
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
}
