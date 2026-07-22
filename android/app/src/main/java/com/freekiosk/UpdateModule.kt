package com.freekiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.facebook.react.bridge.*
import com.freekiosk.net.AcceptedCertTrust
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class UpdateModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "UpdateModule"
    }

    /**
     * Expose ENABLE_SELF_UPDATE to JavaScript as a constant.
     * When building with -Pplaystore, this is false and all update methods become no-ops.
     */
    override fun getConstants(): MutableMap<String, Any> {
        return mutableMapOf(
            "ENABLE_SELF_UPDATE" to BuildConfig.ENABLE_SELF_UPDATE
        )
    }

    companion object {
        // A real APK is tens of MB; anything below this is an error page.
        private const val MIN_VALID_APK_BYTES = 50_000L
    }

    @ReactMethod
    fun getCurrentVersion(promise: Promise) {
        try {
            val packageInfo = reactApplicationContext.packageManager.getPackageInfo(
                reactApplicationContext.packageName,
                0
            )
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            
            val result = Arguments.createMap().apply {
                putString("versionName", versionName)
                putInt("versionCode", versionCode)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to get current version: ${e.message}")
        }
    }

    /**
     * Check for updates - stable channel only (backward compatible)
     */
    @ReactMethod
    fun checkForUpdates(promise: Promise) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            promise.reject("DISABLED", "Self-update is disabled in Play Store builds")
            return
        }
        checkForUpdatesWithChannel(false, promise)
    }

    /**
     * Check for updates with optional beta/pre-release channel support.
     * When includeBeta is false, uses /releases/latest (stable only).
     * When includeBeta is true, uses /releases and picks the first release (beta or stable).
     */
    @ReactMethod
    fun checkForUpdatesWithChannel(includeBeta: Boolean, promise: Promise) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            promise.reject("DISABLED", "Self-update is disabled in Play Store builds")
            return
        }
        Thread {
            try {
                val apiUrl = if (includeBeta) {
                    "https://api.github.com/repos/rushb-fr/freekiosk/releases?per_page=10"
                } else {
                    "https://api.github.com/repos/rushb-fr/freekiosk/releases/latest"
                }
                
                android.util.Log.d("UpdateModule", "Checking updates: includeBeta=$includeBeta, url=$apiUrl")
                
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    // Parse the release object
                    val jsonObject = if (includeBeta) {
                        // /releases returns an array — pick the first one (most recent, beta or stable)
                        val releasesArray = org.json.JSONArray(response)
                        if (releasesArray.length() == 0) {
                            promise.reject("ERROR", "No releases found")
                            connection.disconnect()
                            return@Thread
                        }
                        releasesArray.getJSONObject(0)
                    } else {
                        // /releases/latest returns a single object
                        JSONObject(response)
                    }
                    
                    val tagName = jsonObject.getString("tag_name").removePrefix("v")
                    val releaseName = jsonObject.optString("name", "")
                    val releaseNotes = jsonObject.optString("body", "")
                    val publishedAt = jsonObject.optString("published_at", "")
                    val isPrerelease = jsonObject.optBoolean("prerelease", false)
                    
                    // Get the actual APK download URL from release assets
                    var apkUrl = ""
                    val assetsArray = jsonObject.optJSONArray("assets")
                    if (assetsArray != null && assetsArray.length() > 0) {
                        for (i in 0 until assetsArray.length()) {
                            val asset = assetsArray.getJSONObject(i)
                            val assetName = asset.getString("name")
                            if (assetName.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.getString("browser_download_url")
                                android.util.Log.d("UpdateModule", "Found APK asset: $assetName")
                                break
                            }
                        }
                    }
                    
                    // Fallback to constructed URL if no asset found (should not happen)
                    if (apkUrl.isEmpty()) {
                        apkUrl = "https://github.com/rushb-fr/freekiosk/releases/download/v${tagName}/FreeKiosk-v${tagName}.apk"
                        android.util.Log.w("UpdateModule", "No APK asset found, using fallback URL")
                    }
                    
                    android.util.Log.d("UpdateModule", "Tag from GitHub: ${jsonObject.getString("tag_name")}")
                    android.util.Log.d("UpdateModule", "Version after removePrefix: $tagName")
                    android.util.Log.d("UpdateModule", "APK Download URL: $apkUrl")
                    android.util.Log.d("UpdateModule", "Is pre-release: $isPrerelease")
                    
                    val result = Arguments.createMap().apply {
                        putString("version", tagName)
                        putString("name", releaseName)
                        putString("notes", releaseNotes)
                        putString("publishedAt", publishedAt)
                        putString("downloadUrl", apkUrl)
                        putBoolean("isPrerelease", isPrerelease)
                    }
                    
                    android.util.Log.d("UpdateModule", "Update available: $tagName at $apkUrl (prerelease=$isPrerelease)")
                    promise.resolve(result)
                } else {
                    promise.reject("ERROR", "GitHub API returned code: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                promise.reject("ERROR", "Failed to check for updates: ${e.message}")
            }
        }.start()
    }

    /**
     * Check for updates from a custom update server instead of GitHub.
     *
     * The server hosts a JSON manifest (e.g. served by a fleet-management
     * panel) with the shape:
     *   { "version": "1.3.0", "name": "...", "notes": "...",
     *     "publishedAt": "2026-07-10T12:00:00Z", "downloadUrl": "https://.../kiosk.apk" }
     * `version` and `downloadUrl` are required; the result map matches
     * checkForUpdatesWithChannel so the JS update flow is identical.
     */
    @ReactMethod
    fun checkForUpdatesFromUrl(manifestUrl: String, promise: Promise) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            promise.reject("DISABLED", "Self-update is disabled in Play Store builds")
            return
        }
        if (manifestUrl.isBlank()) {
            promise.reject("INVALID_URL", "Manifest URL is empty")
            return
        }
        Thread {
            try {
                android.util.Log.d("UpdateModule", "Checking updates from custom server: $manifestUrl")

                val connection = URL(manifestUrl).openConnection() as HttpURLConnection
                // Custom update server = the clinic portal, often self-signed —
                // honor certificates the user accepted in the kiosk WebView.
                com.freekiosk.net.AcceptedCertTrust.configure(connection, reactApplicationContext)
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "FreeKiosk-Updater")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val manifest = JSONObject(response)

                    val version = manifest.getString("version").removePrefix("v")
                    val downloadUrl = manifest.getString("downloadUrl")

                    val result = Arguments.createMap().apply {
                        putString("version", version)
                        putString("name", manifest.optString("name", ""))
                        putString("notes", manifest.optString("notes", ""))
                        putString("publishedAt", manifest.optString("publishedAt", ""))
                        putString("downloadUrl", downloadUrl)
                        putBoolean("isPrerelease", manifest.optBoolean("isPrerelease", false))
                    }

                    android.util.Log.d("UpdateModule", "Custom server update: $version at $downloadUrl")
                    promise.resolve(result)
                } else {
                    promise.reject("ERROR", "Update server returned code: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                promise.reject("ERROR", "Failed to check custom update server: ${e.message}")
            }
        }.start()
    }

    /**
     * Check if the app has permission to install APKs from unknown sources.
     * On API < 26, this is always true (global setting, not per-app).
     */
    @ReactMethod
    fun checkInstallPermission(promise: Promise) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            promise.reject("DISABLED", "Self-update is disabled in Play Store builds")
            return
        }
        try {
            val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactApplicationContext.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
            promise.resolve(canInstall)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to check install permission: ${e.message}")
        }
    }

    /**
     * Open the system settings page to allow installing from unknown sources.
     * On Fire OS / restricted devices this may not be available.
     */
    @ReactMethod
    fun openInstallPermissionSettings(promise: Promise) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            promise.reject("DISABLED", "Self-update is disabled in Play Store builds")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${reactApplicationContext.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                reactApplicationContext.startActivity(intent)
                promise.resolve(true)
            } else {
                // On older Android, open general security settings
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                reactApplicationContext.startActivity(intent)
                promise.resolve(true)
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateModule", "Cannot open install permission settings: ${e.message}")
            promise.reject("SETTINGS_UNAVAILABLE", "Cannot open install permission settings. This device may not support installing apps from unknown sources. Use 'adb install -r <apk>' instead.")
        }
    }

    /**
     * Download the APK with our own OkHttp client and install it.
     *
     * Deliberately NOT the system DownloadManager: it runs in a separate
     * process with the system trust store only, so a clinic portal with a
     * user-accepted (self-signed) certificate would fail the TLS handshake.
     * OkHttp + AcceptedCertTrust honors the same certificates as the rest of
     * the app (WebView consent dialog / QR pairing).
     */
    @ReactMethod
    fun downloadAndInstall(downloadUrl: String, version: String, promise: Promise) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            promise.reject("DISABLED", "Self-update is disabled in Play Store builds")
            return
        }
        if (downloadUrl.isEmpty()) {
            promise.reject("ERROR", "Download URL is empty")
            return
        }
        Thread {
            try {
                android.util.Log.d("UpdateModule", "Starting download from: $downloadUrl")
                val downloadsDir =
                    reactApplicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("External files directory unavailable")

                // Clean up old downloaded APKs
                downloadsDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("FreeKiosk-") && file.name.endsWith(".apk")) {
                        file.delete()
                        android.util.Log.d("UpdateModule", "Cleaned up old APK: ${file.name}")
                    }
                }

                val file = File(downloadsDir, "FreeKiosk-${version}.apk")
                val client = AcceptedCertTrust.configure(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS),
                    reactApplicationContext,
                ).build()
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "FreeKiosk-Updater")
                    .header("Accept", "application/vnd.android.package-archive")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed: HTTP ${response.code}")
                    }
                    val body = response.body
                        ?: throw IllegalStateException("Download failed: empty response body")
                    body.byteStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                if (file.length() < MIN_VALID_APK_BYTES) {
                    val size = file.length()
                    file.delete()
                    throw IllegalStateException(
                        "Downloaded file is too small ($size bytes) — probably an HTML page, not an APK"
                    )
                }

                android.util.Log.d(
                    "UpdateModule",
                    "Downloaded ${file.length()} bytes to ${file.absolutePath} — installing"
                )
                installApkFile(file)
                promise.resolve(true)
            } catch (e: Exception) {
                android.util.Log.e("UpdateModule", "Download/install failed: ${e.message}", e)
                promise.reject("ERROR", "Failed to download update: ${e.message}")
            }
        }.start()
    }

    private fun installApkFile(file: File) {
        try {
            // Try silent install if in Device Owner mode
            val dpm = reactApplicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(reactApplicationContext.packageName)) {
                android.util.Log.d("UpdateModule", "Device Owner detected - attempting silent install")

                val packageInstaller = reactApplicationContext.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                session.openWrite("package", 0, -1).use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                    session.fsync(output)
                }

                val intent = Intent(reactApplicationContext, UpdateInstallReceiver::class.java)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    reactApplicationContext,
                    0,
                    intent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        android.app.PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                )
                session.commit(pendingIntent.intentSender)
                session.close()

                android.util.Log.d("UpdateModule", "Silent install initiated")
                return
            } else {
                android.util.Log.d("UpdateModule", "Not in Device Owner mode - using normal install")
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateModule", "Silent install failed: ${e.message}", e)
            android.util.Log.d("UpdateModule", "Falling back to normal install method")
        }

        // Fallback: system install prompt via FileProvider
        // Check install permission on API 26+ (non-Device Owner)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!reactApplicationContext.packageManager.canRequestPackageInstalls()) {
                android.util.Log.w("UpdateModule", "Install from unknown sources not permitted, opening settings")
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${reactApplicationContext.packageName}")
                    )
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    reactApplicationContext.startActivity(settingsIntent)
                } catch (e: Exception) {
                    android.util.Log.e("UpdateModule", "Cannot open install permission settings: ${e.message}")
                }
                // Still attempt the install - the system may prompt the user
            }
        }

        val uri = FileProvider.getUriForFile(
            reactApplicationContext,
            "${reactApplicationContext.packageName}.fileprovider",
            file,
        )
        android.util.Log.d("UpdateModule", "Starting normal APK install from URI: $uri")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        reactApplicationContext.startActivity(intent)

        // Monitor installation completion for auto-restart (non-Device Owner mode)
        // We can't get a callback for ACTION_VIEW install, so we monitor package changes
        monitorInstallationCompletion()
    }
    
    private fun monitorInstallationCompletion() {
        // Register a receiver to detect when our package is replaced
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        
        val installMonitor = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val packageName = intent?.data?.schemeSpecificPart
                if (packageName == reactApplicationContext.packageName) {
                    android.util.Log.d("UpdateModule", "Package replaced detected - app will restart automatically")
                    // The system will restart our app automatically after package replacement
                    try {
                        reactApplicationContext.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reactApplicationContext.registerReceiver(installMonitor, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            reactApplicationContext.registerReceiver(installMonitor, filter)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for EventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for EventEmitter
    }
}

/**
 * BroadcastReceiver for silent installation results
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val status = intent?.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                android.util.Log.d("UpdateInstallReceiver", "Installation requires user action")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context?.startActivity(confirmIntent)
                }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                android.util.Log.d("UpdateInstallReceiver", "Installation succeeded - restarting app")
                // Restart the app after successful installation
                context?.let { ctx ->
                    val packageManager = ctx.packageManager
                    val launchIntent = packageManager.getLaunchIntentForPackage(ctx.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            ctx.startActivity(launchIntent)
                        }, 1000) // Wait 1 second to ensure installation is fully complete
                    }
                }
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE,
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,
            android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED,
            android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT,
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID,
            android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                android.util.Log.e("UpdateInstallReceiver", "Installation failed: $message (status: $status)")
            }
        }
    }
}
