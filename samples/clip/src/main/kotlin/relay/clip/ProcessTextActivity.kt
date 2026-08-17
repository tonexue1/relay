package relay.clip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Inbound text → on-device rewrite (S1) and cloud Supervisor research (S2). */
class ProcessTextActivity : ComponentActivity() {

    companion object {
        const val AUTO_RESEARCH = "relay.clip.AUTO_RESEARCH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val inbound = InboundText.fromIntent(intent)
        val autoResearch = intent.getBooleanExtra(AUTO_RESEARCH, false)
        setContent {
            ClipTheme {
                ProcessTextScreen(
                    selected = inbound.text,
                    writable = inbound.writable,
                    sourceLabel = inbound.source.label,
                    autoResearch = autoResearch,
                    onWriteBack = { replacement ->
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, replacement),
                        )
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}
