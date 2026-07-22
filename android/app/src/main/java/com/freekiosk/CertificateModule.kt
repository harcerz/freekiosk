package com.freekiosk

import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.Arguments
import android.util.Log
import android.content.SharedPreferences
import com.freekiosk.net.AcceptedCertTrust
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CertificateModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String {
    return "CertificateModule"
  }

  @ReactMethod
  fun clearAcceptedCertificates(promise: Promise) {
    try {
      val prefs = reactApplicationContext.getSharedPreferences(
        "freekiosk_ssl_certs",
        android.content.Context.MODE_PRIVATE
      )
      prefs.edit().clear().apply()
      Log.i("CertificateModule", "All accepted certificates cleared")
      promise.resolve(true)
    } catch (e: Exception) {
      Log.e("CertificateModule", "Error clearing certificates", e)
      promise.reject("ERROR", e.message)
    }
  }

  @ReactMethod
  fun getAcceptedCertificates(promise: Promise) {
    try {
      val prefs = reactApplicationContext.getSharedPreferences(
        "freekiosk_ssl_certs",
        android.content.Context.MODE_PRIVATE
      )

      val certificates = Arguments.createArray()
      val allEntries = prefs.all
      val processedFingerprints = mutableSetOf<String>()

      for ((key, value) in allEntries) {
        // Only process "cert_" keys (not "cert_expiry_" or "cert_url_")
        if (key.startsWith("cert_") && !key.contains("_expiry_") && !key.contains("_url_")) {
          val fingerprint = key.substring(5) // Remove "cert_" prefix

          // Avoid duplicates
          if (processedFingerprints.contains(fingerprint)) {
            continue
          }
          processedFingerprints.add(fingerprint)

          val isAccepted = prefs.getBoolean(key, false)
          if (!isAccepted) continue

          val url = prefs.getString("cert_url_$fingerprint", "Unknown") ?: "Unknown"
          val expiryTime = prefs.getLong("cert_expiry_$fingerprint", 0)

          val certInfo = Arguments.createMap()
          certInfo.putString("fingerprint", fingerprint)
          certInfo.putString("url", url)
          certInfo.putDouble("expiryTime", expiryTime.toDouble())

          // Add human-readable expiry date
          val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
          certInfo.putString("expiryDate", dateFormat.format(Date(expiryTime)))

          // Check if expired
          val isExpired = System.currentTimeMillis() > expiryTime
          certInfo.putBoolean("isExpired", isExpired)

          certificates.pushMap(certInfo)
        }
      }

      Log.i("CertificateModule", "Found ${certificates.size()} accepted certificates")
      promise.resolve(certificates)
    } catch (e: Exception) {
      Log.e("CertificateModule", "Error getting certificates", e)
      promise.reject("ERROR", e.message)
    }
  }

  /**
   * Fetch the TLS certificate a server presents WITHOUT trusting it — used by
   * the QR pairing flow to show an accept dialog on first contact instead of
   * requiring the user to visit the page in the WebView first.
   *
   * Resolves { trusted, fingerprint, fingerprintFormatted, subject, issuer,
   * validUntil }: `trusted` is true when the chain passes the platform trust
   * store OR the leaf was already user-accepted; plain http resolves
   * { trusted: true } (nothing to accept).
   */
  @ReactMethod
  fun fetchServerCertificate(urlString: String, promise: Promise) {
    Thread {
      var socket: Socket? = null
      var sslSocket: SSLSocket? = null
      try {
        val url = URL(urlString)
        if (!url.protocol.equals("https", ignoreCase = true)) {
          val result = Arguments.createMap()
          result.putBoolean("trusted", true)
          promise.resolve(result)
          return@Thread
        }
        val host = url.host
        val port = if (url.port > 0) url.port else 443

        // Trust-all context ONLY to capture the presented chain — nothing is
        // sent over this connection and no trust decision is made here.
        val inspectContext = SSLContext.getInstance("TLS")
        inspectContext.init(null, arrayOf(object : X509TrustManager {
          override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
          override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
          override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())

        val plain = Socket()
        socket = plain
        plain.connect(InetSocketAddress(host, port), 7000)
        plain.soTimeout = 7000
        val factory: SSLSocketFactory = inspectContext.socketFactory
        // Wrapping with (socket, host, port) enables SNI for the handshake.
        val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
        sslSocket = ssl
        ssl.startHandshake()
        val chain = ssl.session.peerCertificates
          .filterIsInstance<X509Certificate>()
          .toTypedArray()
        val leaf = chain.firstOrNull()
          ?: throw IllegalStateException("Server presented no certificate")

        val platformTrusted = try {
          val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
          tmf.init(null as KeyStore?)
          val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
          tm.checkServerTrusted(chain, leaf.publicKey.algorithm ?: "RSA")
          true
        } catch (e: Exception) {
          false
        }
        val alreadyAccepted = AcceptedCertTrust.isAccepted(reactApplicationContext, leaf)
        val fingerprint = AcceptedCertTrust.fingerprint(leaf)

        val result = Arguments.createMap()
        result.putBoolean("trusted", platformTrusted || alreadyAccepted)
        result.putString("fingerprint", fingerprint)
        result.putString(
          "fingerprintFormatted",
          fingerprint.chunked(2).joinToString(":").uppercase(),
        )
        result.putString("subject", leaf.subjectX500Principal.name)
        result.putString("issuer", leaf.issuerX500Principal.name)
        result.putString(
          "validUntil",
          SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(leaf.notAfter),
        )
        promise.resolve(result)
      } catch (e: Exception) {
        Log.e("CertificateModule", "fetchServerCertificate failed", e)
        promise.reject("FETCH_CERT_ERROR", e.message ?: e.javaClass.simpleName)
      } finally {
        try { sslSocket?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
      }
    }.start()
  }

  /**
   * Persist a user's acceptance of a certificate — SAME store and format as
   * the WebView SSL dialog (patches/react-native-webview): trusted for 1 year
   * by the WebView, RN fetch, the hub and update checks (AcceptedCertTrust).
   */
  @ReactMethod
  fun acceptCertificate(fingerprint: String, url: String, promise: Promise) {
    try {
      val prefs = reactApplicationContext.getSharedPreferences(
        "freekiosk_ssl_certs",
        android.content.Context.MODE_PRIVATE
      )
      val oneYearMs = 365L * 24 * 60 * 60 * 1000
      prefs.edit()
        .putBoolean("cert_$fingerprint", true)
        .putLong("cert_expiry_$fingerprint", System.currentTimeMillis() + oneYearMs)
        .putString("cert_url_$fingerprint", url)
        .apply()
      Log.i("CertificateModule", "Certificate accepted from pairing: $fingerprint for $url")
      promise.resolve(true)
    } catch (e: Exception) {
      Log.e("CertificateModule", "Error accepting certificate", e)
      promise.reject("ERROR", e.message)
    }
  }

  @ReactMethod
  fun removeCertificate(fingerprint: String, promise: Promise) {
    try {
      val prefs = reactApplicationContext.getSharedPreferences(
        "freekiosk_ssl_certs",
        android.content.Context.MODE_PRIVATE
      )

      prefs.edit()
        .remove("cert_$fingerprint")
        .remove("cert_expiry_$fingerprint")
        .remove("cert_url_$fingerprint")
        .apply()

      Log.i("CertificateModule", "Certificate removed: $fingerprint")
      promise.resolve(true)
    } catch (e: Exception) {
      Log.e("CertificateModule", "Error removing certificate", e)
      promise.reject("ERROR", e.message)
    }
  }
}
