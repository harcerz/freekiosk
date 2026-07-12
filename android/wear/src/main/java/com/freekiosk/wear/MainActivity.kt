package com.freekiosk.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Confirmation
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.freekiosk.wear.comm.WatchComm
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.notif.WatchNotifications
import com.freekiosk.wear.ui.ChatScreen
import com.freekiosk.wear.ui.DashboardScreen
import com.freekiosk.wear.ui.SosScreen
import kotlinx.coroutines.launch

/**
 * Watch UI, structured per the Wear OS app-design guidance: a glanceable,
 * non-scrolling dashboard as the single entry screen (state first, actions
 * at the bottom), with chat and SOS as the only second-level screens
 * (swipe-to-dismiss returns). Action outcomes surface as full-screen
 * confirmations with haptics instead of inline banners.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchNotifications.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme {
                WatchRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Seed from the persisted DataItem, ask the tablet for a fresh push
        // and piggyback a battery report.
        lifecycleScope.launch {
            android.util.Log.i("WatchMain", "onResume: loadInitial")
            WatchStateHolder.loadInitial(applicationContext)
            android.util.Log.i("WatchMain", "onResume: requestSummary")
            WatchComm.requestSummary(applicationContext)
            android.util.Log.i("WatchMain", "onResume: reportBattery")
            WatchComm.reportBatteryIfNeeded(applicationContext)
            android.util.Log.i("WatchMain", "onResume: chain done")
        }
    }
}

private data class ConfirmationState(
    val text: String,
    val success: Boolean,
)

@Composable
private fun WatchRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberSwipeDismissableNavController()
    var confirmation by remember { mutableStateOf<ConfirmationState?>(null) }

    fun showResult(text: String, success: Boolean) {
        confirmation = ConfirmationState(text, success)
        vibrate(context, if (success) 120L else 350L)
    }

    // Outcomes relayed back by the tablet (help-call cooldown etc.).
    LaunchedEffect(Unit) {
        WatchStateHolder.actionResults.collect { result ->
            when {
                result.action == "help-call" && result.ok ->
                    showResult(context.getString(R.string.help_call_sent), true)
                result.action == "help-call" && result.message == "cooldown" ->
                    showResult(context.getString(R.string.help_call_cooldown), false)
                result.ok ->
                    showResult(context.getString(R.string.reply_sent), true)
                else ->
                    showResult(context.getString(R.string.action_failed), false)
            }
        }
    }

    /** Send helper: local failure shows immediately, success waits for the tablet ack. */
    fun sendGuarded(block: suspend () -> Boolean) {
        scope.launch {
            val delivered = block()
            if (!delivered) {
                showResult(context.getString(R.string.action_failed), false)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(timeText = { TimeText() }) {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "dashboard",
            ) {
                composable("dashboard") {
                    DashboardScreen(
                        onOpenChat = { navController.navigate("chat") },
                        onOpenSos = { navController.navigate("sos") },
                    )
                }
                composable("chat") {
                    ChatScreen(
                        onToggleReaction = { messageId, emoji ->
                            sendGuarded {
                                WatchComm.sendReaction(context, messageId, emoji)
                            }
                        },
                        onQuickReply = { text ->
                            sendGuarded { WatchComm.sendQuickReply(context, text) }
                        },
                    )
                }
                composable("sos") {
                    SosScreen(
                        onConfirm = {
                            sendGuarded { WatchComm.sendHelpCall(context) }
                            navController.popBackStack()
                        },
                    )
                }
            }
        }

        confirmation?.let { state ->
            Confirmation(
                onTimeout = { confirmation = null },
                durationMillis = 1800,
                icon = {
                    Text(
                        text = if (state.success) "✅" else "⚠️",
                        style = MaterialTheme.typography.display3,
                    )
                },
            ) {
                Text(
                    text = state.text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.title3,
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
