package com.freekiosk.wear

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.freekiosk.wear.comm.WatchComm
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.model.WatchChatMessage
import com.freekiosk.wear.model.WatchSummary
import com.freekiosk.wear.notif.WatchNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STALE_AFTER_MS = 3 * 60_000L
private const val REACTION_EMOJI = "👍"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchNotifications.ensureChannels(this)
        setContent {
            MaterialTheme {
                WatchApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Seed from the persisted DataItem, ask the tablet for a fresh push
        // and piggyback a battery report.
        lifecycleScope.launch {
            WatchStateHolder.loadInitial(applicationContext)
            WatchComm.requestSummary(applicationContext)
            WatchComm.reportBatteryIfNeeded(applicationContext)
        }
    }
}

@Composable
private fun WatchApp() {
    val context = LocalContext.current
    val summary by WatchStateHolder.summary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    var banner by remember { mutableStateOf<String?>(null) }
    var showHelpConfirm by remember { mutableStateOf(false) }

    // Feedback from the tablet for watch-initiated actions.
    LaunchedEffect(Unit) {
        WatchStateHolder.actionResults.collect { result ->
            banner = when {
                result.action == "help-call" && result.ok ->
                    context.getString(R.string.help_call_sent)
                result.action == "help-call" && result.message == "cooldown" ->
                    context.getString(R.string.help_call_cooldown)
                result.ok -> context.getString(R.string.reply_sent)
                else -> context.getString(R.string.action_failed)
            }
            vibrate(context, if (result.ok) 150L else 400L)
        }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3000)
            banner = null
        }
    }

    // Re-evaluate staleness on a slow tick.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { HeaderItem(summary, nowMs) }

            banner?.let { text ->
                item {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val data = summary
            if (data == null) {
                item {
                    Text(
                        text = LocalContext.current.getString(R.string.waiting_for_data),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
            } else {
                item { CurrentVisitCard(data) }
                item { NextAppointmentCard(data) }

                item {
                    Text(
                        text = LocalContext.current.getString(R.string.chat_section),
                        style = MaterialTheme.typography.caption1,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val recent = data.messages.takeLast(3).reversed()
                if (recent.isEmpty()) {
                    item {
                        Text(
                            text = LocalContext.current.getString(R.string.no_messages),
                            style = MaterialTheme.typography.caption2,
                            color = Color.Gray,
                        )
                    }
                } else {
                    recent.forEach { message ->
                        item {
                            ChatMessageCard(message) {
                                scope.launch {
                                    WatchComm.sendReaction(context, message.id, REACTION_EMOJI)
                                }
                            }
                        }
                    }
                }

                item { QuickReplies { content ->
                    scope.launch { WatchComm.sendQuickReply(context, content) }
                } }
            }

            item {
                Chip(
                    onClick = { showHelpConfirm = true },
                    label = {
                        Text(
                            text = LocalContext.current.getString(R.string.help_call_button),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = Color(0xFFB71C1C),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }

    HelpCallConfirmDialog(
        show = showHelpConfirm,
        onDismiss = { showHelpConfirm = false },
        onConfirm = {
            showHelpConfirm = false
            scope.launch { WatchComm.sendHelpCall(context) }
        },
    )
}

@Composable
private fun HeaderItem(summary: WatchSummary?, nowMs: Long) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Text(
            text = when {
                summary == null -> context.getString(R.string.app_name)
                summary.noRoom -> context.getString(R.string.no_room)
                else -> summary.roomName ?: context.getString(R.string.app_name)
            },
            style = MaterialTheme.typography.title3,
        )
        if (summary != null && nowMs - summary.updatedAtMs > STALE_AFTER_MS) {
            Text(
                text = context.getString(R.string.stale_data),
                style = MaterialTheme.typography.caption2,
                color = Color(0xFFFFB74D),
            )
        }
    }
}

@Composable
private fun CurrentVisitCard(summary: WatchSummary) {
    val context = LocalContext.current
    val visit = summary.currentVisit
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = context.getString(R.string.now_label),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
            )
            if (visit == null) {
                Text(
                    text = context.getString(R.string.no_current_visit),
                    style = MaterialTheme.typography.caption2,
                    color = Color.Gray,
                )
            } else {
                Text(
                    text = visit.patientName,
                    style = MaterialTheme.typography.body1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${visit.startTime}–${visit.endTime} • ${visit.doctorName}",
                    style = MaterialTheme.typography.caption2,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (visit.minutesOverrun > 0) {
                    Text(
                        text = context.getString(
                            R.string.overrun_format, visit.minutesOverrun,
                        ),
                        style = MaterialTheme.typography.caption1,
                        color = Color(0xFFEF5350),
                    )
                }
            }
        }
    }
}

@Composable
private fun NextAppointmentCard(summary: WatchSummary) {
    val context = LocalContext.current
    val next = summary.nextAppointment
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = context.getString(R.string.next_label),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
            )
            if (next == null) {
                Text(
                    text = context.getString(R.string.no_next_appointment),
                    style = MaterialTheme.typography.caption2,
                    color = Color.Gray,
                )
            } else {
                Text(
                    text = "${next.startTime} ${next.patientName}",
                    style = MaterialTheme.typography.body1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (next.isWaiting) {
                    WaitingBadge(next.minutesWaiting)
                }
            }
        }
    }
}

/** Pulsing red badge — consistent with the web's in_waiting_room highlight. */
@Composable
private fun WaitingBadge(minutesWaiting: Int?) {
    val context = LocalContext.current
    val pulse = rememberInfiniteTransition(label = "waitingPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "waitingAlpha",
    )
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .background(
                Color(0xFFD32F2F).copy(alpha = alpha),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (minutesWaiting != null) {
                context.getString(R.string.waiting_badge_minutes, minutesWaiting)
            } else {
                context.getString(R.string.waiting_badge)
            },
            style = MaterialTheme.typography.caption1,
            color = Color.White,
        )
    }
}

@Composable
private fun ChatMessageCard(message: WatchChatMessage, onToggleReaction: () -> Unit) {
    Card(
        onClick = { if (!message.isSystem && message.id.isNotBlank()) onToggleReaction() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message.content,
                style = MaterialTheme.typography.caption1,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val mine = REACTION_EMOJI in message.myReactions
            if (message.reactions.isNotEmpty() || mine) {
                Text(
                    text = message.reactions
                        .joinToString(" ") { (emoji, count) -> "$emoji$count" }
                        .ifBlank { REACTION_EMOJI },
                    style = MaterialTheme.typography.caption2,
                    color = if (mine) MaterialTheme.colors.secondary else Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun QuickReplies(onSend: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        listOf(
            context.getString(R.string.quick_reply_1),
            context.getString(R.string.quick_reply_2),
        ).forEach { reply ->
            Chip(
                onClick = { onSend(reply) },
                label = {
                    Text(
                        text = reply,
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HelpCallConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(showDialog = show, onDismissRequest = onDismiss) {
        Alert(
            title = {
                Text(
                    text = context.getString(R.string.help_call_confirm_title),
                    textAlign = TextAlign.Center,
                )
            },
        ) {
            item {
                Chip(
                    onClick = onConfirm,
                    label = {
                        Text(
                            text = context.getString(R.string.help_call_confirm_yes),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = Color(0xFFB71C1C),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onDismiss,
                    label = {
                        Text(
                            text = context.getString(R.string.help_call_confirm_no),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun vibrate(context: android.content.Context, durationMs: Long) {
    try {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    } catch (_: Exception) {
        // vibration is best-effort
    }
}
