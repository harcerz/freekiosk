package com.freekiosk

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * HomeActivity - Launcher transparent pour External App Mode
 *
 * Cette activité agit comme un launcher transparent qui:
 * 1. Lance l'application externe configurée
 * 2. Démarre l'OverlayService avec le bouton de retour
 * 3. Se ferme immédiatement pour rester en arrière-plan
 *
 * Utilisé uniquement en mode External App (non-Device Owner)
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lire la configuration depuis AsyncStorage
        val prefs = getSharedPreferences("RCTAsyncLocalStorage", MODE_PRIVATE)
        val displayMode = prefs.getString("@kiosk_display_mode", "webview")
        val externalAppPackage = prefs.getString("@kiosk_external_app_package", "")
        val externalAppActivity = prefs.getString("@kiosk_external_app_activity", "")

        DebugLog.d("HomeActivity", "Display mode: $displayMode")
        DebugLog.d("HomeActivity", "External app: $externalAppPackage / $externalAppActivity")

        if (displayMode == "external_app" && !externalAppPackage.isNullOrEmpty()) {
            // Démarrer l'OverlayService avec le bouton de retour
            startOverlayService()

            // Lancer l'application externe
            launchExternalApp(externalAppPackage, externalAppActivity)
        } else {
            // Sinon, lancer FreeKiosk normalement
            launchFreeKiosk()
        }

        // Fermer HomeActivity immédiatement
        finish()
    }

    private fun startOverlayService() {
        try {
            // Vérifier la permission overlay (Android M+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    DebugLog.d("HomeActivity", "Overlay permission not granted, skipping OverlayService")
                    return
                }
            }

            val serviceIntent = Intent(this, OverlayService::class.java)
            startService(serviceIntent)
            DebugLog.d("HomeActivity", "Started OverlayService from HomeActivity")
        } catch (e: Exception) {
            DebugLog.errorProduction("HomeActivity", "Error starting OverlayService: ${e.message}")
        }
    }

    private fun launchExternalApp(packageName: String, activityName: String?) {
        try {
            val intent = if (!activityName.isNullOrEmpty()) {
                Intent().apply {
                    setClassName(packageName, activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (intent != null) {
                startActivity(intent)
                DebugLog.d("HomeActivity", "Launched external app: $packageName")
            } else {
                DebugLog.errorProduction("HomeActivity", "Cannot find launch intent for: $packageName")
                launchFreeKiosk()
            }
        } catch (e: Exception) {
            DebugLog.errorProduction("HomeActivity", "Error launching external app: ${e.message}")
            launchFreeKiosk()
        }
    }

    private fun launchFreeKiosk() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            DebugLog.d("HomeActivity", "Launched MainActivity (fallback)")
        } catch (e: Exception) {
            DebugLog.errorProduction("HomeActivity", "Error launching MainActivity: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Empêcher HomeActivity de rester visible
        finish()
    }
}
