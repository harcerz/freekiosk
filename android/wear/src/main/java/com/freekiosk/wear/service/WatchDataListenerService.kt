package com.freekiosk.wear.service

import android.util.Log
import com.freekiosk.wear.comm.WatchComm
import com.freekiosk.wear.data.ActionResult
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.model.parseChatMessage
import com.freekiosk.wear.notif.OngoingStatus
import com.freekiosk.wear.notif.WatchNotifications
import com.freekiosk.wear.tile.DentrioTileService
import androidx.wear.tiles.TileService
import com.freekiosk.wear.R
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Data Layer inbox of the watch — system-started, so summary updates and
 * chat notifications arrive even when the UI is closed.
 */
class WatchDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchDataListener"
        private const val PATH_SUMMARY = "/watch/summary"
        private const val PATH_CHAT_MESSAGE = "/watch/chat-message"
        private const val PATH_ACTION_RESULT = "/watch/action-result"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        Log.i(TAG, "onDataChanged: ${events.count} event(s)")
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH_SUMMARY) continue
            try {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val raw = map.getString("summary") ?: continue
                WatchStateHolder.update(JSONObject(raw), map.getLong("updatedAt"))
                maybeAlertOverrun()
            } catch (e: Exception) {
                Log.w(TAG, "Summary event failed: ${e.message}")
            }
        }
        // Summary pushes double as a heartbeat — piggyback battery reporting
        // and keep the watchface-adjacent surfaces fresh.
        scope.launch { WatchComm.reportBatteryIfNeeded(applicationContext) }
        refreshSurfaces()
    }

    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "onMessageReceived: ${event.path}")
        when (event.path) {
            PATH_CHAT_MESSAGE -> try {
                val payload = JSONObject(String(event.data, Charsets.UTF_8))
                WatchNotifications.showChatMessage(
                    applicationContext,
                    parseChatMessage(payload),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Chat message event failed: ${e.message}")
            }

            PATH_ACTION_RESULT -> try {
                val payload = JSONObject(String(event.data, Charsets.UTF_8))
                WatchStateHolder.publishActionResult(
                    ActionResult(
                        action = payload.optString("action"),
                        ok = payload.optBoolean("ok", false),
                        message = payload.optString("message").takeIf { it.isNotBlank() },
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Action result event failed: ${e.message}")
            }
        }
    }

    /** Tile + ongoing-activity indicator follow the latest summary. */
    private fun refreshSurfaces() {
        try {
            TileService.getUpdater(applicationContext)
                .requestUpdate(DentrioTileService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Tile update request failed: ${e.message}")
        }
        try {
            val summary = WatchStateHolder.summary.value
            OngoingStatus.ensure(
                applicationContext,
                summary?.roomName
                    ?: applicationContext.getString(R.string.ongoing_connected),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ongoing status update failed: ${e.message}")
        }
    }

    /**
     * Vibrating alert when the next patient is already waiting while the
     * current visit overruns — once per current visit.
     */
    private fun maybeAlertOverrun() {
        val summary = WatchStateHolder.summary.value ?: return
        val current = summary.currentVisit ?: return
        val next = summary.nextAppointment ?: return
        if (current.minutesOverrun <= 0 || !next.isWaiting) return
        if (WatchStateHolder.overrunAlertedFor == current.appointmentId) return

        WatchStateHolder.overrunAlertedFor = current.appointmentId
        WatchNotifications.showOverrunAlert(applicationContext, summary)
    }
}
