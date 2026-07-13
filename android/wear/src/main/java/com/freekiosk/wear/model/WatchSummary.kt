package com.freekiosk.wear.model

import org.json.JSONObject

/**
 * Watch-side mirror of the clinic hub summary
 * (GET /api/tablet/watch/summary, relayed by WearRelay as a DataItem).
 * Parsing is defensive — a missing/renamed field degrades to null/empty
 * instead of crashing the watch.
 */
data class WatchSummary(
    val roomName: String?,
    val currentVisit: WatchVisit?,
    val nextAppointment: WatchNextAppointment?,
    val messages: List<WatchChatMessage>,
    val noRoom: Boolean,
    /** Wall-clock of the tablet push (DataItem `updatedAt`). */
    val updatedAtMs: Long,
)

data class WatchVisit(
    val appointmentId: String,
    val patientName: String,
    val doctorName: String,
    val startTime: String,
    val endTime: String,
    val minutesOverrun: Int,
    /** Scheduled length in minutes (end − start). */
    val durationMinutes: Int,
    /** Visit-type (appointment category) name, e.g. "Higienizacja". */
    val categoryName: String?,
    /** Visit-type accent color (#rrggbb) — category color, else doctor color. */
    val categoryColor: String?,
)

data class WatchNextAppointment(
    val appointmentId: String,
    val patientName: String,
    val startTime: String,
    val isWaiting: Boolean,
    val minutesWaiting: Int?,
    /** Visit-type (appointment category) name, e.g. "Higienizacja". */
    val categoryName: String?,
    /** Visit-type accent color (#rrggbb) — category color, else doctor color. */
    val categoryColor: String?,
)

data class WatchChatMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val isSystem: Boolean,
    /** emoji → count, insertion-ordered. */
    val reactions: List<Pair<String, Int>>,
    /** Emojis this room device has already toggled on. */
    val myReactions: Set<String>,
)

/** optString that maps both a missing key and JSON null to Kotlin null. */
private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

fun parseWatchSummary(json: JSONObject, updatedAtMs: Long): WatchSummary {
    val room = json.optJSONObject("room")
    val current = json.optJSONObject("currentVisit")?.let {
        WatchVisit(
            appointmentId = it.optString("appointmentId"),
            patientName = it.optString("patientName"),
            doctorName = it.optString("doctorName"),
            startTime = it.optString("startTime"),
            endTime = it.optString("endTime"),
            minutesOverrun = it.optInt("minutesOverrun", 0),
            durationMinutes = it.optInt("durationMinutes", 0),
            categoryName = it.optNullableString("categoryName"),
            categoryColor = it.optNullableString("categoryColor"),
        )
    }
    val next = json.optJSONObject("nextAppointment")?.let {
        WatchNextAppointment(
            appointmentId = it.optString("appointmentId"),
            patientName = it.optString("patientName"),
            startTime = it.optString("startTime"),
            isWaiting = it.optBoolean("isWaiting", false),
            minutesWaiting = if (it.isNull("minutesWaiting")) null else it.optInt("minutesWaiting"),
            categoryName = it.optNullableString("categoryName"),
            categoryColor = it.optNullableString("categoryColor"),
        )
    }

    val messages = mutableListOf<WatchChatMessage>()
    val rawMessages = json.optJSONArray("messages")
    if (rawMessages != null) {
        for (i in 0 until rawMessages.length()) {
            val message = rawMessages.optJSONObject(i) ?: continue
            messages.add(parseChatMessage(message))
        }
    }

    return WatchSummary(
        roomName = room?.optString("name")?.takeIf { it.isNotBlank() },
        currentVisit = current,
        nextAppointment = next,
        messages = messages,
        noRoom = json.optString("reason") == "no_room",
        updatedAtMs = updatedAtMs,
    )
}

/**
 * Parses one wire message — either a summary `messages[]` entry or the
 * `message-new` socket payload relayed to [/watch/chat-message] (the latter
 * may nest the message under a `message` key).
 */
fun parseChatMessage(raw: JSONObject): WatchChatMessage {
    val message = raw.optJSONObject("message") ?: raw
    val sender = message.optJSONObject("sender")
    val senderName = listOfNotNull(
        sender?.optString("firstName")?.takeIf { it.isNotBlank() },
        sender?.optString("lastName")?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { "Ktoś" }

    val reactionCounts = linkedMapOf<String, Int>()
    val reactions = message.optJSONArray("reactions")
    if (reactions != null) {
        for (i in 0 until reactions.length()) {
            val emoji = reactions.optJSONObject(i)?.optString("emoji") ?: continue
            if (emoji.isNotBlank()) {
                reactionCounts[emoji] = (reactionCounts[emoji] ?: 0) + 1
            }
        }
    }

    val myReactions = mutableSetOf<String>()
    val mine = message.optJSONArray("myReactions")
    if (mine != null) {
        for (i in 0 until mine.length()) {
            mine.optString(i)?.takeIf { it.isNotBlank() }?.let { myReactions.add(it) }
        }
    }

    return WatchChatMessage(
        id = message.optString("id"),
        senderName = senderName,
        content = message.optString("content"),
        isSystem = message.optString("messageType") == "system",
        reactions = reactionCounts.toList(),
        myReactions = myReactions,
    )
}
