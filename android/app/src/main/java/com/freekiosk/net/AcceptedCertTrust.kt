package com.freekiosk.net

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * AcceptedCertTrust — makes a certificate the user accepted once in the kiosk
 * WebView trusted by the WHOLE app.
 *
 * The patched react-native-webview (patches/react-native-webview+*.patch)
 * shows a consent dialog on SSL_UNTRUSTED and stores the accepted leaf
 * certificate in SharedPreferences `freekiosk_ssl_certs` as
 * `cert_<sha256-hex-lowercase-of-DER>` with a 1-year `cert_expiry_<fp>`.
 * Native networking (RN fetch → pairing, the hub's OkHttp + Socket.IO)
 * used the system trust store only, so a clinic server with a self-signed
 * certificate worked in the WebView but pairing failed with
 * "Network request failed". This helper reads the SAME store:
 *
 *  - [newTrustManager] delegates to the platform trust manager and, when the
 *    chain is rejected, allows it iff the LEAF certificate is accepted
 *    (fingerprint match, not expired). Client-auth checks stay untouched.
 *  - [newHostnameVerifier] keeps OkHttp's standard verification and, when it
 *    fails, allows the connection iff the presented leaf is accepted — the
 *    WebView `proceed()` also ignores a hostname mismatch for an accepted
 *    cert, so both layers behave identically.
 *
 * Read-only: acceptance/expiry management stays in the WebView layer and
 * CertificateModule (settings UI). Revoking a cert there revokes it here.
 */
object AcceptedCertTrust {

    private const val TAG = "AcceptedCertTrust"
    private const val PREFS_NAME = "freekiosk_ssl_certs"

    /** SHA-256 of the DER encoding, lowercase hex — MUST match the WebView patch. */
    fun fingerprint(cert: X509Certificate): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun isAccepted(context: Context, cert: X509Certificate): Boolean {
        return try {
            val prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val fp = fingerprint(cert)
            if (!prefs.getBoolean("cert_$fp", false)) {
                return false
            }
            val expiry = prefs.getLong("cert_expiry_$fp", 0L)
            expiry > System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Acceptance check failed: ${e.message}")
            false
        }
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun newTrustManager(context: Context): X509TrustManager {
        val appContext = context.applicationContext
        val platform = platformTrustManager()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                platform.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    platform.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    val leaf = chain.firstOrNull() ?: throw e
                    if (isAccepted(appContext, leaf)) {
                        Log.i(TAG, "Allowing user-accepted certificate ${fingerprint(leaf)}")
                    } else {
                        throw e
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                platform.acceptedIssuers
        }
    }

    fun newHostnameVerifier(context: Context): HostnameVerifier {
        val appContext = context.applicationContext
        return HostnameVerifier { hostname: String, session: SSLSession ->
            if (OkHostnameVerifier.verify(hostname, session)) {
                true
            } else {
                val leaf =
                    session.peerCertificates.firstOrNull() as? X509Certificate
                val accepted = leaf != null && isAccepted(appContext, leaf)
                if (accepted) {
                    Log.w(TAG, "Hostname $hostname not in user-accepted certificate — allowing")
                }
                accepted
            }
        }
    }

    /**
     * Apply the accepted-cert trust to a plain HttpsURLConnection (UpdateModule
     * manifest checks). No-op for http connections and on failure.
     * NOTE: APK downloads go through the system DownloadManager, which runs
     * outside this process and only honors the system/user trust store.
     */
    fun configure(connection: java.net.URLConnection, context: Context) {
        val https = connection as? javax.net.ssl.HttpsURLConnection ?: return
        try {
            val trustManager = newTrustManager(context)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            https.sslSocketFactory = sslContext.socketFactory
            https.hostnameVerifier = newHostnameVerifier(context)
        } catch (e: Exception) {
            Log.e(TAG, "HttpsURLConnection trust setup failed: ${e.message}")
        }
    }

    /** Apply the accepted-cert trust to any OkHttp client builder. */
    fun configure(builder: OkHttpClient.Builder, context: Context): OkHttpClient.Builder {
        return try {
            val trustManager = newTrustManager(context)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            builder
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier(newHostnameVerifier(context))
        } catch (e: Exception) {
            // Never break networking over the trust extension — fall back to
            // the stock client (system trust only).
            Log.e(TAG, "Falling back to system trust: ${e.message}")
            builder
        }
    }
}
