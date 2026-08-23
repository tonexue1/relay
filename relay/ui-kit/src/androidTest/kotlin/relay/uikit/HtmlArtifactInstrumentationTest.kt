package relay.uikit

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HtmlArtifactInstrumentationTest {
    @Test
    fun inlineScriptRunsWhileNetworkIsBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val diagnostics = Collections.synchronizedList(mutableListOf<HtmlPreviewDiagnostic>())
        val latch = CountDownLatch(2)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = createSecureArtifactWebView(
                context,
                onDiagnostic = {
                    diagnostics += it
                    if (it.kind == "inline_js" || it.kind == "blocked_resource") latch.countDown()
                },
                onRendererGone = {},
            )
            webView.loadArtifact(
                """
                <!doctype html><html><body><p id="state">before</p>
                <script>
                  state.textContent='after';
                  parent.postMessage({relayArtifact:true,kind:'inline_js',message:state.textContent}, '*');
                  fetch('https://example.invalid/blocked').catch(() => {});
                </script></body></html>
                """.trimIndent(),
                annotationMode = false,
            )
        }

        assertTrue("expected inline-js and CSP diagnostics: $diagnostics", latch.await(10, TimeUnit.SECONDS))
        assertTrue(diagnostics.any { it.kind == "inline_js" && it.message == "after" })
        assertTrue(diagnostics.any { it.kind == "blocked_resource" })

        instrumentation.runOnMainSync { webView.destroy() }
    }

    @Test
    fun privilegedWebViewCapabilitiesStayDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = createSecureArtifactWebView(context, {}, {})
            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertFalse(webView.settings.domStorageEnabled)
            assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
            assertTrue(webView.settings.mixedContentMode == WebSettings.MIXED_CONTENT_NEVER_ALLOW)
            webView.destroy()
        }
    }
}
