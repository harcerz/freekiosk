package com.freekiosk

import android.app.DownloadManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.facebook.react.bridge.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "UpdateModule"
    }

    private var downloadId: Long = -1
    private var updatePromise: Promise? = null

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

    @ReactMethod
    fun checkForUpdates(promise: Promise) {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/rushb-fr/freekiosk/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    
                    val tagName = jsonObject.getString("tag_name").removePrefix("v")
                    val releaseName = jsonObject.optString("name", "")
                    val releaseNotes = jsonObject.optString("body", "")
                    val publishedAt = jsonObject.optString("published_at", "")
                    
                    // Construire l'URL de téléchargement directement à partir du tag
                    // Pattern: https://github.com/rushb-fr/freekiosk/releases/download/v{VERSION}/freeKiosk-v{VERSION}.apk
                    val apkUrl = "https://github.com/rushb-fr/freekiosk/releases/download/v${tagName}/freeKiosk-v${tagName}.apk"
                    android.util.Log.d("UpdateModule", "Tag from GitHub: ${jsonObject.getString("tag_name")}")
                    android.util.Log.d("UpdateModule", "Version after removePrefix: $tagName")
                    android.util.Log.d("UpdateModule", "Constructed APK URL: $apkUrl")
                    
                    val result = Arguments.createMap().apply {
                        putString("version", tagName)
                        putString("name", releaseName)
                        putString("notes", releaseNotes)
                        putString("publishedAt", publishedAt)
                        putString("downloadUrl", apkUrl)
                    }
                    
                    android.util.Log.d("UpdateModule", "Update available: $tagName at $apkUrl")
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

    @ReactMethod
    fun downloadAndInstall(downloadUrl: String, version: String, promise: Promise) {
        try {
            android.util.Log.d("UpdateModule", "Starting download from: $downloadUrl")
            
            if (downloadUrl.isEmpty()) {
                promise.reject("ERROR", "Download URL is empty")
                return
            }
            
            updatePromise = promise
            
            val fileName = "FreeKiosk-${version}.apk"
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("FreeKiosk Update")
                setDescription("Downloading version $version")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", "FreeKiosk-Updater")
                addRequestHeader("Accept", "application/vnd.android.package-archive")
            }
            
            android.util.Log.d("UpdateModule", "Download request configured for file: $fileName")
            
            val downloadManager = reactApplicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)
            
            // Register receiver for download completion
            // RECEIVER_EXPORTED est nécessaire pour recevoir les broadcasts système du DownloadManager
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactApplicationContext.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                reactApplicationContext.registerReceiver(downloadReceiver, filter)
            }
            
            android.util.Log.d("UpdateModule", "Download started with ID: $downloadId")
        } catch (e: Exception) {
            android.util.Log.e("UpdateModule", "Failed to start download: ${e.message}")
            promise.reject("ERROR", "Failed to start download: ${e.message}")
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == downloadId) {
                android.util.Log.d("UpdateModule", "Download completed with ID: $id")
                
                try {
                    val downloadManager = reactApplicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    
                    // Vérifier le statut du téléchargement
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)
                        
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                android.util.Log.d("UpdateModule", "Download successful")
                                
                                // Vérifier les informations du fichier téléchargé
                                val mimeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                                val sizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                
                                val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) else "unknown"
                                val fileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                                val localUri = if (uriIndex >= 0) cursor.getString(uriIndex) else "unknown"
                                
                                android.util.Log.d("UpdateModule", "Downloaded file info:")
                                android.util.Log.d("UpdateModule", "  - MIME type: $mimeType")
                                android.util.Log.d("UpdateModule", "  - File size: $fileSize bytes")
                                android.util.Log.d("UpdateModule", "  - Local URI: $localUri")
                                
                                // Vérifier que le fichier n'est pas trop petit (un HTML ferait < 50KB)
                                if (fileSize > 0 && fileSize < 50000) {
                                    android.util.Log.e("UpdateModule", "Downloaded file too small ($fileSize bytes), probably not a valid APK")
                                    updatePromise?.reject("ERROR", "Downloaded file is too small ($fileSize bytes). Probably got an HTML page instead of APK.")
                                    cursor.close()
                                    return
                                }
                                
                                val uri = downloadManager.getUriForDownloadedFile(downloadId)
                                
                                if (uri != null) {
                                    android.util.Log.d("UpdateModule", "Installing APK from: $uri")
                                    installApk(uri)
                                    updatePromise?.resolve(true)
                                } else {
                                    android.util.Log.e("UpdateModule", "Failed to get downloaded file URI")
                                    updatePromise?.reject("ERROR", "Failed to get downloaded file URI")
                                }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = cursor.getInt(reasonIndex)
                                val reasonText = when (reason) {
                                    DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
                                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "No external storage device found"
                                    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
                                    DownloadManager.ERROR_FILE_ERROR -> "Storage issue"
                                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
                                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient storage space"
                                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
                                    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP response code"
                                    DownloadManager.ERROR_UNKNOWN -> "Unknown error"
                                    else -> "Error code: $reason"
                                }
                                android.util.Log.e("UpdateModule", "Download failed: $reasonText")
                                updatePromise?.reject("ERROR", "Download failed: $reasonText")
                            }
                            else -> {
                                android.util.Log.e("UpdateModule", "Download status: $status")
                                updatePromise?.reject("ERROR", "Unexpected download status: $status")
                            }
                        }
                    } else {
                        android.util.Log.e("UpdateModule", "Download query returned no results")
                        updatePromise?.reject("ERROR", "Download not found in download manager")
                    }
                    cursor.close()
                } catch (e: Exception) {
                    android.util.Log.e("UpdateModule", "Error processing download: ${e.message}", e)
                    updatePromise?.reject("ERROR", "Failed to process download: ${e.message}")
                } finally {
                    updatePromise = null
                    try {
                        reactApplicationContext.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }
            }
        }
    }

    private fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        reactApplicationContext.startActivity(intent)
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
