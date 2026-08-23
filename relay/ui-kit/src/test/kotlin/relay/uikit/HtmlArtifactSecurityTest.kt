package relay.uikit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HtmlArtifactSecurityTest {
    @Test
    fun `generated document injects restrictive csp before source`() {
        val document = buildArtifactDocument(
            "<html><head><title>X</title></head><body><script>fetch('https://bad.test')</script></body></html>",
            annotationMode = false,
        )
        assertTrue(document.indexOf("Content-Security-Policy") < document.indexOf("<title>"))
        assertTrue(document.contains("connect-src 'none'"))
        assertTrue(document.contains("form-action 'none'"))
        assertFalse(document.contains("allow-same-origin"))
    }

    @Test
    fun `base elements are removed and diagnostics installed`() {
        val document = buildArtifactDocument(
            "<base href=\"https://bad.test/\"><h1>Hi</h1>",
            annotationMode = true,
        )
        assertFalse(document.contains("<base", ignoreCase = true))
        assertTrue(document.contains("securitypolicyviolation"))
        assertTrue(document.contains("relayArtifact:true"))
        assertTrue(document.contains("getBoundingClientRect"))
    }

    @Test
    fun `webview disables privileged access`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = createSecureArtifactWebView(context, {}, {})
        assertTrue(webView.settings.javaScriptEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertFalse(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
        webView.destroy()
    }
}
