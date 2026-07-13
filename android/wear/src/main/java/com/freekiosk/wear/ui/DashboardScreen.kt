package com.freekiosk.wear.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.freekiosk.wear.R
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.model.WatchNextAppointment
import com.freekiosk.wear.model.WatchSummary
import kotlinx.coroutines.delay

private const val STALE_AFTER_MS = 3 * 60_000L

private val WaitingRed = Color(0xFFB71C1C)
private val AccentBlue = Color(0xFF41BDF5)
private val WarnOrange = Color(0xFFFFB74D)

/** #rrggbb from the summary → Compose color; null on garbage. */
internal fun parseHexColor(hex: String?): Color? = hex?.let {
    try {
        Color(android.graphics.Color.parseColor(it))
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Visit-type accent dot; renders nothing without a parsable color. */
@Composable
private fun CategoryDot(colorHex: String?, size: Dp = 8.dp) {
    val color = parseHexColor(colorHex) ?: return
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape),
    )
}

/**
 * Glanceable, non-scrolling entry screen: the room's single most important
 * state fills the screen (waiting patient trumps everything and pulses red),
 * with the only two actions — chat and SOS — as round buttons at the bottom.
 */
@Composable
fun DashboardScreen(
    onOpenChat: () -> Unit,
    onOpenSos: () -> Unit,
) {
    val summary by WatchStateHolder.summary.collectAsStateWithLifecycle()

    // Staleness re-evaluated on a slow tick.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val waiting = summary?.nextAppointment?.takeIf { it.isWaiting }
    // A waiting patient owns the whole screen ONLY when no visit is running —
    // during a visit the current patient stays on top and CZEKA becomes an
    // inline alert line (an early arrival must not hide the ongoing visit).
    val fullScreenWaiting = waiting != null && summary?.currentVisit == null
    val pulse = rememberInfiniteTransition(label = "waitingPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "waitingAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (fullScreenWaiting) WaitingRed.copy(alpha = pulseAlpha) else Color.Black,
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 26.dp),
        ) {
            RoomHeader(summary, nowMs)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    summary == null -> Text(
                        text = LocalContext.current.getString(R.string.waiting_for_data),
                        style = MaterialTheme.typography.body2,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                    )
                    fullScreenWaiting -> WaitingState(summary!!)
                    else -> CalmState(summary!!)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = onOpenChat,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Text(text = "💬", style = MaterialTheme.typography.title2)
                }
                Button(
                    onClick = onOpenSos,
                    colors = ButtonDefaults.primaryButtonColors(
                        backgroundColor = WaitingRed,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Text(text = "🆘", style = MaterialTheme.typography.title2)
                }
            }
        }
    }
}

@Composable
private fun RoomHeader(summary: WatchSummary?, nowMs: Long) {
    val context = LocalContext.current
    val stale = summary != null && nowMs - summary.updatedAtMs > STALE_AFTER_MS
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when {
                summary == null -> context.getString(R.string.app_name)
                summary.noRoom -> context.getString(R.string.no_room)
                else -> summary.roomName ?: context.getString(R.string.app_name)
            },
            style = MaterialTheme.typography.caption1,
            color = if (stale) WarnOrange else AccentBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (stale) {
            Text(
                text = " ⚠",
                style = MaterialTheme.typography.caption1,
                color = WarnOrange,
            )
        }
    }
}

/** Patient waiting with NO visit running — the state that owns the screen. */
@Composable
private fun WaitingState(summary: WatchSummary) {
    val context = LocalContext.current
    val next = summary.nextAppointment ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = context.getString(R.string.waiting_title),
            style = MaterialTheme.typography.caption1,
            color = Color.White.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = next.patientName,
            style = MaterialTheme.typography.title1,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        next.minutesWaiting?.let { minutes ->
            Text(
                text = context.getString(R.string.waiting_minutes, minutes),
                style = MaterialTheme.typography.title3,
                color = Color.White,
            )
        }
    }
}

/**
 * Pulsing red CZEKA line shown UNDER the current visit — a waiting early
 * arrival alerts without hiding the ongoing visit.
 */
@Composable
private fun WaitingInlineRow(next: WatchNextAppointment) {
    val context = LocalContext.current
    val pulse = rememberInfiniteTransition(label = "inlineWaitingPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "inlineWaitingAlpha",
    )
    val minutes = next.minutesWaiting
    Text(
        text = if (minutes != null) {
            context.getString(R.string.waiting_inline_minutes, next.patientName, minutes)
        } else {
            context.getString(R.string.waiting_inline, next.patientName)
        },
        style = MaterialTheme.typography.title3,
        color = Color(0xFFEF5350).copy(alpha = alpha),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Current visit first (with an inline red CZEKA line when someone already
 * waits), else the next appointment, else quiet.
 */
@Composable
private fun CalmState(summary: WatchSummary) {
    val context = LocalContext.current
    val current = summary.currentVisit
    val next = summary.nextAppointment

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            current != null -> {
                Text(
                    text = context.getString(R.string.now_label),
                    style = MaterialTheme.typography.caption1,
                    color = AccentBlue,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = current.patientName,
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${current.startTime}–${current.endTime}" +
                        if (current.durationMinutes > 0) {
                            " · " + context.getString(
                                R.string.duration_minutes, current.durationMinutes,
                            )
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.body2,
                    color = Color.Gray,
                )
                current.categoryName?.let { name ->
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CategoryDot(current.categoryColor, size = 6.dp)
                        Text(
                            text = name,
                            style = MaterialTheme.typography.caption1,
                            color = Color.LightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (current.minutesOverrun > 0) {
                    Text(
                        text = context.getString(
                            R.string.overrun_format, current.minutesOverrun,
                        ),
                        style = MaterialTheme.typography.title3,
                        color = Color(0xFFEF5350),
                    )
                }
                next?.let {
                    Spacer(Modifier.height(6.dp))
                    if (it.isWaiting) {
                        WaitingInlineRow(it)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CategoryDot(it.categoryColor, size = 6.dp)
                            Text(
                                text = "→ ${it.startTime} ${it.patientName}",
                                style = MaterialTheme.typography.caption1,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            next != null -> {
                // Just the dot carries the visit type here — the full type
                // name is shown only for the current visit.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    CategoryDot(next.categoryColor)
                    Text(
                        text = context.getString(R.string.next_label, next.startTime),
                        style = MaterialTheme.typography.caption1,
                        color = AccentBlue,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = next.patientName,
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            else -> Text(
                text = context.getString(R.string.no_visits),
                style = MaterialTheme.typography.body1,
                color = Color.Gray,
            )
        }
    }
}
