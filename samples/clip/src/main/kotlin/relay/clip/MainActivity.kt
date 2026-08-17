package relay.clip

import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import relay.clip.search.SearchActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<TextView>(R.id.handlers).text = processTextSummary()
        findViewById<Button>(R.id.read_clipboard).setOnClickListener { openClipboard() }
        findViewById<Button>(R.id.open_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<Button>(R.id.use_typed).setOnClickListener {
            openText(
                findViewById<EditText>(R.id.editable).text?.toString().orEmpty(),
                InboundText.Source.Typed,
            )
        }
    }

    private fun openClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        openText(
            item?.coerceToText(this)?.toString().orEmpty(),
            InboundText.Source.Clipboard,
        )
    }

    private fun openText(raw: String, source: InboundText.Source) {
        val text = raw.trim()
        if (text.isEmpty()) {
            val msg = if (source == InboundText.Source.Clipboard) {
                "剪贴板是空的。直接在输入框里打也行。"
            } else {
                "先打一段课题。"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            InboundText.launch(text, source).setClass(this, ProcessTextActivity::class.java),
        )
    }

    private fun processTextSummary(): String {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val found = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val labels = found.map { it.loadLabel(packageManager).toString() }
        val self = labels.any { it.contains("Relay") }
        return buildString {
            append("系统解析到 ${labels.size} 个 PROCESS_TEXT：")
            append(if (labels.isEmpty()) "无" else labels.joinToString("、"))
            append('\n')
            append(if (self) "本包已注册，选中后看 ▸ / ⋮。" else "本包没注册上，先卸了重装 Relay Clip。")
        }
    }
}
