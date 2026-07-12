package com.freekiosk.wear.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.freekiosk.wear.comm.WatchComm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles chat-notification actions (👍 reaction / quick reply) by relaying
 * them to the tablet hub. Uses goAsync so the Data Layer call can finish
 * after onReceive returns.
 */
class WatchActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchActionReceiver"
        const val ACTION_REACT = "com.freekiosk.wear.ACTION_REACT"
        const val ACTION_QUICK_REPLY = "com.freekiosk.wear.ACTION_QUICK_REPLY"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_EMOJI = "emoji"
        const val EXTRA_CONTENT = "content"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REACT -> {
                        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
                        val emoji = intent.getStringExtra(EXTRA_EMOJI) ?: "👍"
                        if (!messageId.isNullOrBlank()) {
                            WatchComm.sendReaction(appContext, messageId, emoji)
                        }
                    }

                    ACTION_QUICK_REPLY -> {
                        val content = intent.getStringExtra(EXTRA_CONTENT)
                        if (!content.isNullOrBlank()) {
                            WatchComm.sendQuickReply(appContext, content)
                        }
                    }
                }
                // Collapse the chat notification after acting on it.
                appContext.getSystemService(NotificationManager::class.java)
                    ?.cancel(100)
            } catch (e: Exception) {
                Log.w(TAG, "Notification action failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
