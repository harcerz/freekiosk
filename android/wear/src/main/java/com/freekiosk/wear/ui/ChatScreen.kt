package com.freekiosk.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.freekiosk.wear.R
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.model.WatchChatMessage

private const val REACTION_EMOJI = "👍"

/**
 * Second-level screen: the room channel. Newest messages first, tap a bubble
 * to toggle 👍, quick replies at the end of the list. Swipe right to go back.
 */
@Composable
fun ChatScreen(
    onToggleReaction: (messageId: String) -> Unit,
    onQuickReply: (text: String) -> Unit,
) {
    val context = LocalContext.current
    val summary by WatchStateHolder.summary.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val messages = summary?.messages?.takeLast(6)?.reversed() ?: emptyList()

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = context.getString(R.string.chat_section),
                    style = MaterialTheme.typography.caption1,
                    color = Color(0xFF41BDF5),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }

            if (messages.isEmpty()) {
                item {
                    Text(
                        text = context.getString(R.string.no_messages),
                        style = MaterialTheme.typography.body2,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                    )
                }
            } else {
                items(messages, key = { it.id.ifBlank { it.hashCode().toString() } }) { message ->
                    MessageBubble(message) {
                        if (!message.isSystem && message.id.isNotBlank()) {
                            onToggleReaction(message.id)
                        }
                    }
                }
            }

            item {
                QuickReplyChip(
                    text = context.getString(R.string.quick_reply_1),
                    onClick = onQuickReply,
                )
            }
            item {
                QuickReplyChip(
                    text = context.getString(R.string.quick_reply_2),
                    onClick = onQuickReply,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: WatchChatMessage, onTap: () -> Unit) {
    Card(onClick = onTap, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.caption2,
                color = Color(0xFF41BDF5),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message.content,
                style = MaterialTheme.typography.body2,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            val mine = REACTION_EMOJI in message.myReactions
            if (message.reactions.isNotEmpty() || mine) {
                Text(
                    text = message.reactions
                        .joinToString(" ") { (emoji, count) -> "$emoji$count" }
                        .ifBlank { REACTION_EMOJI },
                    style = MaterialTheme.typography.caption1,
                    color = if (mine) Color(0xFF41BDF5) else Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun QuickReplyChip(text: String, onClick: (String) -> Unit) {
    Chip(
        onClick = { onClick(text) },
        label = {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
