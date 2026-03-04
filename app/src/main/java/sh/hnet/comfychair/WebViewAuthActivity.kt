package sh.hnet.comfychair

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import sh.hnet.comfychair.ui.theme.ComfyChairTheme

/**
 * Activity that presents a WebView for browser-based authentication.
 *
 * Used with reverse proxies that have SSO/OAuth in front of ComfyUI (e.g., Authentik forward-auth).
 * After the user authenticates, this activity captures all session cookies and returns them
 * to the caller via the Activity result.
 *
 * Extras (input):
 *   EXTRA_URL  — Full URL to load (e.g., "https://comfy.example.com")
 *   EXTRA_HOST — ComfyUI hostname used to detect when auth is complete
 *
 * Result extras:
 *   EXTRA_COOKIES             — Raw Cookie header string for the ComfyUI server
 *   EXTRA_AUTH_DOMAIN         — Hostname of the SSO/auth domain (e.g. "auth.bun.cafe")
 *   EXTRA_AUTH_DOMAIN_COOKIES — Raw Cookie header string for the auth domain
 */
class WebViewAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_HOST = "host"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_AUTH_DOMAIN = "auth_domain"
        const val EXTRA_AUTH_DOMAIN_COOKIES = "auth_domain_cookies"

        /** Launch this activity and expect a result via ActivityResultLauncher. */
        fun createIntent(context: Context, url: String, host: String): Intent {
            return Intent(context, WebViewAuthActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HOST, host)
            }
        }

        /**
         * Collect all cookies from Android's CookieManager for a given URL and any
         * auth-domain cookies that were set during the session, merging them into a
         * single "Cookie: name=value; name2=value2" header string.
         *
         * We collect from the target URL and trust the system CookieManager to have
         * accumulated cookies across all redirect domains visited in the WebView.
         */
        fun extractCookies(targetUrl: String): String {
            val cookieManager = CookieManager.getInstance()
            // CookieManager.getCookie returns the cookies applicable to the given URL
            // (respects domain/path scoping). This is exactly what OkHttp will need
            // to pass when talking to the ComfyUI server.
            return cookieManager.getCookie(targetUrl)?.trim() ?: ""
        }

        /**
         * Find the first non-ComfyUI domain visited during auth and return its cookies.
         * This is the SSO/auth domain whose session cookie enables silent token refresh.
         *
         * @param visitedDomains All hostnames visited during the WebView session
         * @param comfyHost The ComfyUI hostname to exclude
         * @return Pair of (authDomain, authDomainCookies), both empty if not found
         */
        fun extractAuthDomainCookies(visitedDomains: Collection<String>, comfyHost: String): Pair<String, String> {
            val cookieManager = CookieManager.getInstance()
            for (domain in visitedDomains) {
                if (domain.equals(comfyHost, ignoreCase = true)) continue
                // Try https first (most auth servers use HTTPS), then http
                val cookies = (cookieManager.getCookie("https://$domain")?.trim()
                    ?: cookieManager.getCookie("http://$domain")?.trim())
                    ?.takeIf { it.isNotEmpty() }
                if (!cookies.isNullOrEmpty()) {
                    return Pair(domain, cookies)
                }
            }
            return Pair("", "")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""

        // Clear cookies for the target domain so the user hits the auth flow fresh.
        // We only clear the target domain's cookies — not removeAllCookies() which
        // would nuke cookies for every site the system WebView has visited.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            clearCookiesForUrl(url)
            flush()
        }

        setContent {
            ComfyChairTheme {
                WebViewAuthScreen(
                    url = url,
                    host = host,
                    onDone = { cookies, authDomain, authDomainCookies ->
                        val result = Intent().apply {
                            putExtra(EXTRA_COOKIES, cookies)
                            putExtra(EXTRA_AUTH_DOMAIN, authDomain)
                            putExtra(EXTRA_AUTH_DOMAIN_COOKIES, authDomainCookies)
                        }
                        setResult(Activity.RESULT_OK, result)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewAuthScreen(
    url: String,
    host: String,
    onDone: (cookies: String, authDomain: String, authDomainCookies: String) -> Unit,
    onCancel: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }
    // Whether we're back on the ComfyUI server (auth chain likely complete)
    var authAppearsComplete by remember { mutableStateOf(false) }
    var webViewRef: WebView? by remember { mutableStateOf(null) }

    // Track all non-ComfyUI hostnames visited during the auth redirect chain.
    // The first one is typically the SSO/auth domain whose session cookie enables
    // silent OkHttp token refresh without needing to show the WebView again.
    val visitedAuthDomains = remember { LinkedHashSet<String>() }

    fun collectAndReturn() {
        val cookies = WebViewAuthActivity.extractCookies(url)
        val (authDomain, authDomainCookies) = WebViewAuthActivity.extractAuthDomainCookies(visitedAuthDomains, host)
        onDone(cookies, authDomain, authDomainCookies)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_browser_auth),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (currentUrl.isNotEmpty()) {
                            Text(
                                text = currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.button_cancel)
                        )
                    }
                },
                actions = {
                    // Done button - enabled when we detect auth is complete OR always available
                    // so the user can manually signal "I'm done"
                    IconButton(onClick = { collectAndReturn() }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.button_browser_auth_done),
                            tint = if (authAppearsComplete) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (authAppearsComplete) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
            )

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                // Allow mixed content for servers that may redirect between
                                // http/https during the auth flow
                                @SuppressLint("SetJavaScriptEnabled")
                                setSupportMultipleWindows(false)
                                userAgentString = "ComfyChair/1.0 (Android)"
                            }

                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    pageUrl: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    isLoading = true
                                    currentUrl = pageUrl ?: ""
                                    // Check if we're back on the ComfyUI host
                                    authAppearsComplete = isOnTargetHost(pageUrl, host)
                                    // Record any non-ComfyUI domain we land on — this is
                                    // the auth/SSO domain whose cookies enable silent refresh.
                                    val pageHost = try {
                                        Uri.parse(pageUrl ?: "").host ?: ""
                                    } catch (e: Exception) { "" }
                                    if (pageHost.isNotEmpty() && !pageHost.equals(host, ignoreCase = true)) {
                                        visitedAuthDomains.add(pageHost)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    isLoading = false
                                    currentUrl = pageUrl ?: ""
                                    authAppearsComplete = isOnTargetHost(pageUrl, host)
                                    // Flush cookies to disk so getCookie() is up to date
                                    CookieManager.getInstance().flush()
                                    // Auto-finish if we're back on the ComfyUI host AND cookies exist
                                    if (authAppearsComplete) {
                                        val cookies = WebViewAuthActivity.extractCookies(url)
                                        if (cookies.isNotEmpty()) {
                                            collectAndReturn()
                                        }
                                        // If no cookies yet, don't auto-finish — user can
                                        // manually tap Done or the redirect chain will continue
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    // Let the WebView handle all redirects internally
                                    return false
                                }
                            }

                            loadUrl(url)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Clear cookies for a specific URL by setting each cookie's value to empty with
 * an expired date. CookieManager has no per-URL delete API, so this is the
 * standard workaround.
 */
private fun CookieManager.clearCookiesForUrl(url: String) {
    val existing = getCookie(url) ?: return
    existing.split(";").forEach { cookie ->
        val name = cookie.trim().split("=").firstOrNull()?.trim() ?: return@forEach
        setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
    }
}

/**
 * Returns true if [pageUrl] is on the same host as [targetHost].
 * Used to detect when the auth redirect chain has returned us to the ComfyUI server.
 */
private fun isOnTargetHost(pageUrl: String?, targetHost: String): Boolean {
    if (pageUrl.isNullOrEmpty() || targetHost.isEmpty()) return false
    return try {
        val uri = Uri.parse(pageUrl)
        uri.host?.equals(targetHost, ignoreCase = true) == true
    } catch (e: Exception) {
        false
    }
}
