package sh.hnet.comfychair.ui.screens

import android.annotation.SuppressLint
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

/**
 * Full-screen WebView for browser-based SSO authentication.
 *
 * Loads [serverUrl] in a WebView so the user can complete any required
 * sign-in flow (Authentik, Authelia, Cloudflare Access, OAuth2-proxy, etc.).
 * When the user taps "Done", the session cookies for [serverUrl] are captured
 * and returned via [onAuthComplete].
 *
 * SSL errors cancel the affected WebView request rather than bypassing
 * certificate validation.
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

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError
                        ) {
                            // Do not bypass certificate validation in the WebView.
                            handler.cancel()
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
