package com.freekiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.facebook.react.bridge.*

/**
 * React Native bridge module for AccessibilityService status and management.
 * Exposes methods to check if the service is enabled and to open settings.
 */
class AccessibilityModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AccessibilityModule"
        const val NAME = "AccessibilityModule"
    }

    override fun getName(): String = NAME

    /**
     * Check if FreeKiosk's AccessibilityService is currently enabled in system settings.
     */
    @ReactMethod
    fun isAccessibilityServiceEnabled(promise: Promise) {
        try {
            val enabled = isServiceEnabled()
            promise.resolve(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility service: ${e.message}")
            promise.reject("ERROR", "Failed to check accessibility service: ${e.message}")
        }
    }

    /**
     * Check if the AccessibilityService is actually running (connected).
     */
    @ReactMethod
    fun isAccessibilityServiceRunning(promise: Promise) {
        try {
            promise.resolve(FreeKioskAccessibilityService.isRunning())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    /**
     * Open Android's Accessibility Settings page so the user can enable the service.
     */
    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            reactContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
            promise.reject("ERROR", "Failed to open accessibility settings: ${e.message}")
        }
    }

    /**
     * In Device Owner mode, programmatically enable the AccessibilityService.
     * This avoids requiring manual user intervention.
     */
    @ReactMethod
    fun enableViaDeviceOwner(promise: Promise) {
        try {
            val dpm = reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(reactContext, DeviceAdminReceiver::class.java)
            
            if (!dpm.isDeviceOwnerApp(reactContext.packageName)) {
                promise.reject("NOT_DEVICE_OWNER", "This feature requires Device Owner mode")
                return
            }

            // Allow FreeKiosk's accessibility service
            val serviceComponent = ComponentName(reactContext, FreeKioskAccessibilityService::class.java)
            val permitted = listOf(reactContext.packageName)
            dpm.setPermittedAccessibilityServices(adminComponent, permitted)
            
            // Enable it via secure settings (Device Owner can write secure settings)
            val serviceName = "${reactContext.packageName}/${serviceComponent.className}"
            val currentServices = Settings.Secure.getString(
                reactContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            if (!currentServices.contains(serviceName)) {
                val newServices = if (currentServices.isEmpty()) serviceName 
                                  else "$currentServices:$serviceName"
                Settings.Secure.putString(
                    reactContext.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
                Settings.Secure.putString(
                    reactContext.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    "1"
                )
                Log.d(TAG, "Accessibility service enabled via Device Owner: $serviceName")
            }
            
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable via Device Owner: ${e.message}")
            promise.reject("ERROR", "Failed to enable via Device Owner: ${e.message}")
        }
    }

    /**
     * Check if the accessibility service is listed in the enabled services setting.
     */
    private fun isServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(reactContext, FreeKioskAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            reactContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }
}
