package relay.clip

import android.content.Intent

/**
 * Sample-local inbound ports. Not part of relay-*.
 * Typed / share / clipboard / PROCESS_TEXT all collapse to one CharSequence.
 * Write-back exists only for PROCESS_TEXT.
 */
internal data class InboundText(
    val text: String,
    val writable: Boolean,
    val source: Source,
) {
    enum class Source(val label: String) {
        ProcessText("选区"),
        Share("分享"),
        Clipboard("剪贴板"),
        Typed("手动"),
    }

    companion object {
        const val EXTRA_SOURCE = "relay.clip.EXTRA_SOURCE"

        fun fromIntent(intent: Intent): InboundText {
            val tagged = intent.getStringExtra(EXTRA_SOURCE)
                ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            if (tagged != null && tagged != Source.ProcessText) {
                return InboundText(intent.sharedText(), writable = false, source = tagged)
            }
            return when (intent.action) {
                Intent.ACTION_SEND -> InboundText(
                    text = intent.sharedText(),
                    writable = false,
                    source = Source.Share,
                )
                else -> InboundText(
                    text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
                    writable = !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
                    source = Source.ProcessText,
                )
            }
        }

        fun launch(text: String, source: Source): Intent =
            Intent()
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(EXTRA_SOURCE, source.name)
    }
}

private fun Intent.sharedText(): String =
    getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
