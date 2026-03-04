package sh.hnet.comfychair

import okhttp3.OkHttpClient
import sh.hnet.comfychair.util.DebugLogger
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate issue types that can be detected
 */
enum class CertificateIssue {
    NONE,           // No issue - certificate is valid and trusted
    SELF_SIGNED,    // Certificate is self-signed (issuer == subject)
    UNKNOWN_CA      // Certificate is signed by an unknown/untrusted CA
}

/**
 * SelfSignedCertHelper - Handles SSL certificate validation with system-first approach
 *
 * Strategy:
 * 1. Try the system default TrustManager first (validates Let's Encrypt, etc.)
 * 2. If system validation fails, classify the issue (self-signed vs unknown CA)
 * 3. Accept the certificate but record the issue type for UI feedback
 *
 * This way:
 * - Valid certs (Let's Encrypt, etc.) → NONE, no warning
 * - Self-signed certs → SELF_SIGNED, user sees warning
 * - Unknown CA certs → UNKNOWN_CA, user sees warning
 */
object SelfSignedCertHelper {

    private const val TAG = "SelfSignedCertHelper"

    // Track what type of certificate issue was detected
    var certificateIssue = CertificateIssue.NONE
        private set

    /**
     * Get the system default X509TrustManager.
     */
    private fun getSystemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    /**
     * Create a TrustManager that tries system validation first,
     * then falls back to accepting with classification.
     */
    private fun createSmartTrustManager(): X509TrustManager {
        val systemTrustManager = try {
            getSystemTrustManager()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to get system TrustManager: ${e.message}")
            null
        }

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Accept all client certificates
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) return

                // Try system validation first
                if (systemTrustManager != null) {
                    try {
                        systemTrustManager.checkServerTrusted(chain, authType)
                        // System trusts it — valid cert, no issue
                        certificateIssue = CertificateIssue.NONE
                        DebugLogger.d(TAG, "Certificate validated by system trust store")
                        return
                    } catch (e: CertificateException) {
                        // System doesn't trust it — classify the issue
                        DebugLogger.d(TAG, "System validation failed: ${e.message}")
                    }
                }

                // System validation failed — classify and accept
                val cert = chain[0]
                val issuer = cert.issuerDN.name
                val subject = cert.subjectDN.name

                certificateIssue = if (issuer == subject) {
                    DebugLogger.d(TAG, "Self-signed certificate detected")
                    CertificateIssue.SELF_SIGNED
                } else {
                    DebugLogger.d(TAG, "Unknown CA certificate: issuer=$issuer")
                    CertificateIssue.UNKNOWN_CA
                }
                // Accept the certificate regardless
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                // Return system accepted issuers so TLS handshake works properly
                return systemTrustManager?.acceptedIssuers ?: arrayOf()
            }
        }
    }

    /**
     * Configure an OkHttpClient.Builder to handle certificate issues gracefully.
     * Uses system trust store first, falls back to accept-all for self-signed/unknown CA.
     */
    fun configureToAcceptSelfSigned(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            certificateIssue = CertificateIssue.NONE

            val trustManager = createSmartTrustManager()
            val trustManagers = arrayOf<TrustManager>(trustManager)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, java.security.SecureRandom())

            builder.sslSocketFactory(sslContext.socketFactory, trustManager)

            // Still need permissive hostname verification for IP-based connections
            // (e.g., 192.168.1.100 won't match any cert CN/SAN)
            builder.hostnameVerifier { _, _ -> true }

        } catch (e: Exception) {
            DebugLogger.w(TAG, "SSL configuration failed: ${e.message}")
        }

        return builder
    }

    /**
     * Reset the certificate issue detection.
     * Should be called before each new connection attempt.
     */
    fun reset() {
        certificateIssue = CertificateIssue.NONE
    }
}
