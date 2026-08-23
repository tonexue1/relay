package relay.uikit

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class HtmlPreviewDiagnostic(
    val kind: String,
    val message: String = "",
    val line: Int? = null,
    val column: Int? = null,
    val selector: String? = null,
    val path: String? = null,
    val textSnippet: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
)

@Composable
fun HtmlArtifactPreview(
    html: String,
    modifier: Modifier = Modifier,
    annotationMode: Boolean = false,
    onDiagnostic: (HtmlPreviewDiagnostic) -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember(html) { mutableStateOf(false) }
    if (failed) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("预览进程不可用，请查看源码或重载。")
        }
    } else {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                createSecureArtifactWebView(context, onDiagnostic) { failed = true }.also { webView = it }
            },
            update = { view -> view.loadArtifact(html, annotationMode) },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun createSecureArtifactWebView(
    context: android.content.Context,
    onDiagnostic: (HtmlPreviewDiagnostic) -> Unit,
    onRendererGone: () -> Unit,
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    return WebView(context).apply {
        setBackgroundColor(Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING ||
                    consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                ) {
                    onDiagnostic(
                        HtmlPreviewDiagnostic(
                            "console",
                            consoleMessage.message().take(2000),
                            consoleMessage.lineNumber(),
                        ),
                    )
                }
                return true
            }
        }
        setDownloadListener { _, _, _, _, _ ->
            onDiagnostic(HtmlPreviewDiagnostic("blocked_download", "downloads are disabled"))
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = request.url.host != APP_HOST || request.url.path != SHELL_PATH
                if (blocked) onDiagnostic(HtmlPreviewDiagnostic("blocked_navigation", request.url.toString()))
                return blocked
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onDiagnostic(HtmlPreviewDiagnostic("renderer_process_gone", "didCrash=${detail.didCrash()}"))
                onRendererGone()
                view.destroy()
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                this,
                DIAGNOSTICS_OBJECT,
                setOf(APP_ORIGIN),
            ) { _, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, _ ->
                if (isMainFrame && sourceOrigin.toString().startsWith(APP_ORIGIN)) {
                    val raw = message.data.orEmpty().take(MAX_DIAGNOSTIC_CHARS)
                    runCatching {
                        DIAGNOSTIC_JSON.decodeFromString<HtmlPreviewDiagnostic>(raw)
                    }.getOrNull()?.let(onDiagnostic)
                }
            }
        }
    }
}

internal fun WebView.loadArtifact(source: String, annotationMode: Boolean) {
    val instrumented = buildArtifactDocument(source, annotationMode)
    val encoded = Base64.encodeToString(instrumented.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val url = "$APP_ORIGIN$SHELL_PATH#$encoded"
    if (this.url != url) loadUrl(url)
}

internal fun buildArtifactDocument(source: String, annotationMode: Boolean): String {
    val csp = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob: https://appassets.androidplatform.net; font-src data: https://appassets.androidplatform.net; media-src data: blob:; connect-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none';">"""
    val diagnostics = """
<script>
(() => {
  const send = (kind, message, extra = {}) => {
    try { parent.postMessage({relayArtifact:true,kind,message:String(message || '').slice(0,2000),...extra}, '*'); } catch (_) {}
  };
  addEventListener('error', e => send('error', e.message, {line:e.lineno,column:e.colno}));
  addEventListener('unhandledrejection', e => send('unhandledrejection', e.reason));
  addEventListener('securitypolicyviolation', e => send('blocked_resource', e.blockedURI || e.violatedDirective));
  addEventListener('DOMContentLoaded', () => send('dom_ready', '', {width:innerWidth,height:innerHeight}));
  ${if (annotationMode) """
  addEventListener('click', e => {
    e.preventDefault(); e.stopPropagation();
    const el=e.target, r=el.getBoundingClientRect();
    const path=[]; let n=el;
    while(n && n.nodeType===1 && path.length<8){path.unshift(n.tagName.toLowerCase()+(n.id?'#'+n.id:''));n=n.parentElement;}
    send('annotation','',{selector:el.id?'#'+el.id:el.tagName.toLowerCase(),textSnippet:(el.textContent||'').trim().slice(0,240),x:r.x,y:r.y,width:r.width,height:r.height,path:path.join('>')});
  }, true);
  """ else ""}
})();
</script>
""".trimIndent()
    val withoutDangerousBase = source.replace(Regex("<base\\b[^>]*>", RegexOption.IGNORE_CASE), "")
    val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    val html = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    return if (head.containsMatchIn(withoutDangerousBase)) {
        val match = requireNotNull(head.find(withoutDangerousBase))
        withoutDangerousBase.replaceRange(match.range, "${match.value}$csp$diagnostics")
    } else if (html.containsMatchIn(withoutDangerousBase)) {
        val match = requireNotNull(html.find(withoutDangerousBase))
        withoutDangerousBase.replaceRange(match.range, "${match.value}<head>$csp$diagnostics</head>")
    } else {
        "<!doctype html><html><head>$csp$diagnostics</head><body>$withoutDangerousBase</body></html>"
    }
}

private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), byteArrayOf().inputStream())

private const val APP_HOST = "appassets.androidplatform.net"
private const val APP_ORIGIN = "https://$APP_HOST"
private const val SHELL_PATH = "/assets/artifact-shell.html"
private const val DIAGNOSTICS_OBJECT = "relayDiagnostics"
private const val MAX_DIAGNOSTIC_CHARS = 8192
private val DIAGNOSTIC_JSON = Json { ignoreUnknownKeys = true }
