package com.freekiosk.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Reactions offered on the wrist: agree / disagree. */
private val WATCH_REACTIONS = listOf("👍", "👎")

private val AccentBlue = Color(0xFF41BDF5)

/**
 * Second-level screen: the room channel. Newest messages first, each with an
 * explicit 👍 / 👎 toggle row, quick replies at the end. Swipe right to
 * go back.
 */
@Composable
fun ChatScreen(
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
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
                    color = AccentBlue,
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
                    MessageBubble(message, onToggleReaction)
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
            item {
                QuickReplyChip(
                    text = context.getString(R.string.quick_reply_no),
                    onClick = onQuickReply,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: WatchChatMessage,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.caption2,
                color = AccentBlue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message.content,
                style = MaterialTheme.typography.body2,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            // Other reactions (beyond the wrist pair) as plain text.
            val other = message.reactions.filter { (emoji, _) -> emoji !in WATCH_REACTIONS }
            if (other.isNotEmpty()) {
                Text(
                    text = other.joinToString(" ") { (emoji, count) -> "$emoji$count" },
                    style = MaterialTheme.typography.caption2,
                    color = Color.Gray,
                )
            }

            if (!message.isSystem && message.id.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    WATCH_REACTIONS.forEach { emoji ->
                        ReactionToggle(
                            emoji = emoji,
                            count = message.reactions.firstOrNull { it.first == emoji }?.second ?: 0,
                            mine = emoji in message.myReactions,
                            onClick = { onToggleReaction(message.id, emoji) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionToggle(
    emoji: String,
    count: Int,
    mine: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (count > 0) "$emoji $count" else emoji,
        style = MaterialTheme.typography.caption1,
        color = if (mine) Color.Black else Color.White,
        modifier = Modifier
            .background(
                if (mine) AccentBlue else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
