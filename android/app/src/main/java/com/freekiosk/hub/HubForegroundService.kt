package com.freekiosk.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freekiosk.MainActivity
import com.freekiosk.R

/**
 * HubForegroundService — keeps the DenTRIO clinic hub (watch relay) alive.
 *
 * Without a foreground service the whole com.freekiosk process is an ordinary
 * background process the moment another app is on top (Mobvoi wizard, camera,
 * external-app mode without overlay service…). Android then kills it freely,
 * which silently kills the ClinicHubClient socket AND the WearRelay — the
 * watch keeps sending messages into the void (Data Layer sendMessage still
 * returns OK). This service holds foreground priority for the lifetime of the
 * hub, mirroring KioskWatchdogService's specialUse pattern.
 *
 * Lifecycle:
 *  • Started from HubModule.startHub() and stopped from HubModule.stopHub(),
 *    so its lifetime exactly tracks "hub is supposed to be running".
 *  • START_STICKY: if the process is killed anyway (OOM), Android restarts the
 *    service with a null intent. The hub client is gone then (it lives in the
 *    RN process state), so the service relaunches MainActivity — the JS
 *    autostart chain (KioskScreen → hubClient.autoStart) brings the hub and
 *    relay back. KioskWatchdogService covers this only when kiosk mode is
 *    enabled; this covers a paired tablet in any mode.
 */
class HubForegroundService : Service() {

    companion object {
        private const val TAG = "HubForegroundService"
        private const val CHANNEL_ID = "dentrio_clinic_hub"
        private const val NOTIFICATION_ID = 2003
        private const val RECOVERY_CHECK_INTERVAL_MS = 30_000L
        private const val RELAUNCH_COOLDOWN_MS = 60_000L

        fun start(context: Context) {
            try {
                val intent = Intent(context, HubForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Background-start restriction (Android 12+) — the hub itself
                // still runs; we only lose the priority boost until next start.
                Log.w(TAG, "Cannot start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, HubForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot stop foreground service: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recoveryRunning = false
    private var lastRelaunchTime = 0L

    private val recoveryRunnable = object : Runnable {
        override fun run() {
            if (!recoveryRunning) return
            if (HubModule.isClientRunning()) {
                // RN came back and restarted the hub — recovery done.
                Log.i(TAG, "Hub client is running again — recovery finished")
                recoveryRunning = false
                return
            }
            if (!isHubEnabled()) {
                Log.i(TAG, "Hub disabled in settings — stopping service")
                recoveryRunning = false
                stopSelf()
                return
            }
            relaunchMainActivityIfGone()
            handler.postDelayed(this, RECOVERY_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }

        if (intent == null) {
            // System restart after a process kill: the hub client died with the
            // process. If the hub is still meant to run, bring the app back so
            // the JS autostart restores it; otherwise shut down.
            if (!isHubEnabled()) {
                Log.i(TAG, "Restarted but hub is disabled — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            Log.i(TAG, "Restarted after process kill — starting hub recovery")
            startRecovery()
        } else {
            // Normal start from HubModule — the hub client is up; nothing to
            // recover. Cancel any leftover recovery loop.
            recoveryRunning = false
            handler.removeCallbacks(recoveryRunnable)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recoveryRunning = false
        handler.removeCallbacks(recoveryRunnable)
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────────
    // Recovery after process death
    // ────────────────────────────────────────────────────────────────────

    private fun startRecovery() {
        if (recoveryRunning) return
        recoveryRunning = true
        handler.removeCallbacks(recoveryRunnable)
        handler.post(recoveryRunnable)
    }

    private fun relaunchMainActivityIfGone() {
        if (isMainActivityRunning()) return
        val now = System.currentTimeMillis()
        if (now - lastRelaunchTime < RELAUNCH_COOLDOWN_MS) return
        lastRelaunchTime = now
        Log.i(TAG, "MainActivity gone and hub enabled — relaunching")
        try {
            // Allowed from the background thanks to the granted
            // SYSTEM_ALERT_WINDOW permission (same as KioskWatchdogService).
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relaunch MainActivity: ${e.message}")
        }
    }

    private fun isMainActivityRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.any { task ->
                try {
                    val info = task.taskInfo
                    info.baseActivity?.className == MainActivity::class.java.name ||
                        info.topActivity?.className == MainActivity::class.java.name
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            // If we can't check, assume it's running (safer than relaunch-looping).
            true
        }
    }

    /** Reads @kiosk_hub_enabled straight from AsyncStorage's SQLite (RN not running). */
    private fun isHubEnabled(): Boolean {
        return try {
            val dbPath = getDatabasePath("RKStorage").absolutePath
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT value FROM catalystLocalStorage WHERE key = ?",
                arrayOf("@kiosk_hub_enabled"),
            )
            val enabled = if (cursor.moveToFirst()) cursor.getString(0) == "true" else false
            cursor.close()
            db.close()
            enabled
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read hub_enabled: ${e.message}")
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Notification (required for foreground service)
    // ────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hub kliniki",
                NotificationManager.IMPORTANCE_MIN, // silent, no badge
            ).apply {
                description = "Utrzymuje połączenie tabletu z kliniką i zegarkiem"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Hub kliniki aktywny (łączność z zegarkiem)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
