package com.freekiosk.wear.data

import android.content.Context
import android.util.Log
import com.freekiosk.wear.model.WatchSummary
import com.freekiosk.wear.model.parseWatchSummary
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Outcome of a watch-initiated action, relayed back by the tablet. */
data class ActionResult(
    val action: String,
    val ok: Boolean,
    val message: String?,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * Process-wide state shared by the listener service and the Compose UI.
 * The service feeds it from Data Layer events; the UI just collects.
 */
object WatchStateHolder {

    private const val TAG = "WatchStateHolder"
    private const val PATH_SUMMARY = "/watch/summary"

    private val _summary = MutableStateFlow<WatchSummary?>(null)
    val summary: StateFlow<WatchSummary?> = _summary

    private val _actionResults = MutableSharedFlow<ActionResult>(extraBufferCapacity = 8)
    val actionResults: SharedFlow<ActionResult> = _actionResults

    /** Current-visit id already alerted for overrun (once per visit). */
    @Volatile var overrunAlertedFor: String? = null

    /** Watch battery last reported to the tablet (level to charging). */
    @Volatile var lastBatterySent: Pair<Int, Boolean>? = null
    @Volatile var lastBatterySentAt = 0L

    fun update(json: JSONObject, updatedAtMs: Long) {
        try {
            _summary.value = parseWatchSummary(json, updatedAtMs)
        } catch (e: Exception) {
            Log.w(TAG, "Summary parse failed: ${e.message}")
        }
    }

    fun publishActionResult(result: ActionResult) {
        _actionResults.tryEmit(result)
    }

    /**
     * Seed the state from the persisted summary DataItem — the UI shows data
     * immediately after a (re)start, before the tablet pushes anything new.
     */
    suspend fun loadInitial(context: Context) {
        if (_summary.value != null) return
        try {
            val buffer = Wearable.getDataClient(context).dataItems.await()
            try {
                for (item in buffer) {
                    if (item.uri.path != PATH_SUMMARY) continue
                    val map = DataMapItem.fromDataItem(item).dataMap
                    val raw = map.getString("summary") ?: continue
                    update(JSONObject(raw), map.getLong("updatedAt"))
                    break
                }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial summary load failed: ${e.message}")
        }
    }
}
