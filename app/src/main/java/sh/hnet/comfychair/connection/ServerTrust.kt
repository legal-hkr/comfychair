package sh.hnet.comfychair.connection

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Result of the certificate check of the last HTTPS attempt.
 */
enum class CertificateIssue {
    /** Certificate trusted by the system or matching the fingerprint the user accepted earlier */
    NONE,
    /** Certificate not trusted by the system and never accepted by the user (self-signed, private CA, hostname mismatch) */
    UNTRUSTED,
    /** The user accepted a different certificate for this server earlier */
    CHANGED
}

/**
 * Certificate that failed validation and waits for the user's decision.
 */
data class PendingCertificate(
    val certificate: X509Certificate,
    val fingerprint: String,
    val issue: CertificateIssue
) {
    /** Common name of the subject, or the full distinguished name if it has none. */
    val subjectName: String get() = commonName(certificate.subjectX500Principal.name)

    /** Common name of the issuer, or the full distinguished name if it has none. */
    val issuerName: String get() = commonName(certificate.issuerX500Principal.name)

    private fun commonName(dn: String): String =
        Regex("""(?:^|,)CN=([^,]+)""").find(dn)?.groupValues?.get(1) ?: dn
}

/**
 * Per-endpoint trust decisions: the SHA-256 fingerprint of a certificate the user accepted (trust on first use) and the protocol that worked last time.
 * Keyed by host and port, so several server entries for the same endpoint share one decision.
 */
class ServerTrustStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPinnedFingerprint(host: String, port: Int): String? =
        prefs.getString(KEY_PIN_PREFIX + endpointKey(host, port), null)

    fun pinFingerprint(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(KEY_PIN_PREFIX + endpointKey(host, port), fingerprint).apply()
    }

    fun isPinned(host: String, port: Int, certificate: X509Certificate): Boolean =
        getPinnedFingerprint(host, port) == fingerprint(certificate)

    /** Protocol that last worked for this endpoint ("http" or "https"), or null if never connected. */
    fun getProtocol(host: String, port: Int): String? =
        prefs.getString(KEY_PROTOCOL_PREFIX + endpointKey(host, port), null)

    fun setProtocol(host: String, port: Int, protocol: String) {
        prefs.edit().putString(KEY_PROTOCOL_PREFIX + endpointKey(host, port), protocol).apply()
    }

    companion object {
        private const val PREFS_NAME = "ServerTrustPrefs"
        private const val KEY_PIN_PREFIX = "pin_"
        private const val KEY_PROTOCOL_PREFIX = "protocol_"

        private fun endpointKey(host: String, port: Int) = "${host.lowercase()}:$port"

        /** SHA-256 of the DER-encoded certificate, as colon-separated uppercase hex. */
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}

/**
 * TLS trust for one server endpoint, shared by all OkHttp clients of a [sh.hnet.comfychair.ComfyUIClient].
 *
 * A certificate is accepted when the system trusts it and it matches the hostname, or when its fingerprint matches the one the user accepted for this endpoint.
 * Anything else fails the handshake and is kept in [pendingCertificate], so the login screen can ask the user.
 * Without a store (no context) only system-trusted certificates are accepted.
 */
class ServerTrust(
    private val hostname: String,
    private val port: Int,
    private val store: ServerTrustStore?
) {
    private val systemTrustManager: X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    private val systemHostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    /** Certificate rejected by the last handshake, waiting for the user's decision. */
    @Volatile
    var pendingCertificate: PendingCertificate? = null
        private set

    fun clearPending() {
        pendingCertificate = null
    }

    private fun isPinned(certificate: X509Certificate): Boolean =
        store?.isPinned(hostname, port, certificate) == true

    private fun reject(certificate: X509Certificate) {
        val fingerprint = ServerTrustStore.fingerprint(certificate)
        val pinned = store?.getPinnedFingerprint(hostname, port)
        pendingCertificate = PendingCertificate(
            certificate = certificate,
            fingerprint = fingerprint,
            issue = if (pinned != null && pinned != fingerprint) CertificateIssue.CHANGED else CertificateIssue.UNTRUSTED
        )
    }

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            systemTrustManager.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
            if (isPinned(chain[0])) return
            try {
                systemTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                reject(chain[0])
                throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    // A pinned certificate is accepted for this endpoint whatever names it lists (local servers are often reached by IP);
    // a system-trusted one must match the hostname, otherwise the user is asked like for an untrusted one.
    private val hostnameVerifier = HostnameVerifier { host, session ->
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            ?: return@HostnameVerifier false
        when {
            isPinned(certificate) -> true
            systemHostnameVerifier.verify(host, session) -> true
            else -> {
                reject(certificate)
                false
            }
        }
    }

    /**
     * Apply this trust to an OkHttp client builder.
     */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
    }
}
