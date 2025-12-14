package sh.hnet.comfychair

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
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
 * SelfSignedCertHelper - Helps handle SSL certificate issues
 *
 * In production environments, SSL certificates are issued by trusted Certificate Authorities (CAs).
 * However, many local ComfyUI servers use:
 * 1. Self-signed certificates (not signed by any CA)
 * 2. Certificates from custom/private CAs (not in device's trusted CA store)
 *
 * This helper allows the app to connect to such servers while
 * tracking what type of certificate issue was detected.
 */
object SelfSignedCertHelper {

    // Track what type of certificate issue was detected
    var certificateIssue = CertificateIssue.NONE
        private set

    /**
     * Create a custom TrustManager that accepts all certificates
     * This is necessary for self-signed and unknown CA certificates
     *
     * SECURITY NOTE: This trusts ALL certificates, including invalid ones.
     * Only use this for connections to known, trusted local servers.
     */
    private fun createTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            /**
             * Check if client certificates are trusted
             * For our use case, we accept all client certificates
             */
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Accept all client certificates
            }

            /**
             * Check if server certificates are trusted
             * This is where we detect certificate issues
             *
             * @param chain The certificate chain from the server
             * @param authType The authentication type (e.g., "RSA", "ECDSA")
             */
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // If we receive a certificate chain, analyze it
                if (chain != null && chain.isNotEmpty()) {
                    val cert = chain[0] // Get the server's certificate

                    // Get issuer and subject Distinguished Names
                    // Issuer: Who created/signed the certificate
                    // Subject: Who the certificate is for
                    val issuer = cert.issuerDN.name
                    val subject = cert.subjectDN.name

                    if (issuer == subject) {
                        // Self-signed certificate: the certificate signed itself
                        // Example: A ComfyUI server that generated its own certificate
                        certificateIssue = CertificateIssue.SELF_SIGNED
                    } else {
                        // Certificate is signed by someone else (a CA)
                        // But since we're in this custom trust manager, it means
                        // the default validation failed - so it's an unknown CA
                        // Example: A private CA that's not in Android's trusted CA store
                        certificateIssue = CertificateIssue.UNKNOWN_CA
                    }
                }
                // Accept the certificate regardless of the issue
            }

            /**
             * Return the list of accepted certificate issuers
             * We accept all, so return empty array
             */
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }
    }

    /**
     * Configure an OkHttpClient.Builder to accept certificates with issues
     * (self-signed certificates and unknown CA certificates)
     *
     * @param builder The OkHttpClient.Builder to configure
     * @return The configured builder (for method chaining)
     */
    fun configureToAcceptSelfSigned(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            // Reset the certificate issue before each connection attempt
            certificateIssue = CertificateIssue.NONE

            // Create our custom trust manager
            val trustManager = createTrustManager()
            val trustManagers = arrayOf<TrustManager>(trustManager)

            // Create an SSL context with our trust manager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, java.security.SecureRandom())

            // Configure the builder to use our SSL context
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)

            // Also disable hostname verification
            // Normally, the SSL certificate must match the hostname
            // For local servers (e.g., 192.168.1.100), this often doesn't match
            builder.hostnameVerifier { _, _ -> true }

        } catch (e: Exception) {
            // If SSL configuration fails, the builder will use default SSL settings
        }

        return builder
    }

    /**
     * Reset the certificate issue detection
     * Should be called before each new connection attempt
     */
    fun reset() {
        certificateIssue = CertificateIssue.NONE
    }
}
