package sh.hnet.comfychair.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ServerTrustStore

/**
 * Full-screen WebView for browser-based SSO authentication.
 *
 * Loads [serverUrl] in a WebView so the user can complete any required
 * sign-in flow (Authentik, Authelia, Cloudflare Access, OAuth2-proxy, etc.).
 * When the user taps "Done", the session cookies for [serverUrl] are captured
 * and returned via [onAuthComplete].
 *
 * SSL errors are treated the same as in the OkHttp configuration — they are
 * accepted so that self-signed certificate servers work correctly.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAuthScreen(
    serverUrl: String,
    onAuthComplete: (cookies: String) -> Unit,
    onCancel: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_browser_auth)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.content_description_close)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val cookies = CookieManager.getInstance()
                                .getCookie(serverUrl)
                                .orEmpty()
                            onAuthComplete(cookies)
                        }
                    ) {
                        Text(stringResource(R.string.button_browser_auth_done))
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    with(CookieManager.getInstance()) {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = false  // Let WebView handle all navigation

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError
                        ) {
                            // Accept only the certificate the user trusted for this server on the login screen
                            // (the connection test always runs before this screen opens); reject anything else.
                            val uri = Uri.parse(error.url)
                            val host = uri.host
                            val port = if (uri.port != -1) uri.port else 443
                            val certificate = error.certificate.x509Certificate
                            if (host != null && certificate != null &&
                                ServerTrustStore(context).isPinned(host, port, certificate)
                            ) {
                                handler.proceed()
                            } else {
                                handler.cancel()
                            }
                        }
                    }

                    loadUrl(serverUrl)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}
