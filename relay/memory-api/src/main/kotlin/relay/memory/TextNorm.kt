package relay.memory

import java.text.Normalizer

internal fun nfkcCompact(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), "")

internal fun normalizeText(text: String): String = nfkcCompact(text)

internal fun Char.isCjk(): Boolean {
    val type = Character.UnicodeBlock.of(this) ?: return false
    return type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        type == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}

internal fun queryTokens(text: String): Set<String> {
    val n = normalizeText(text)
    if (n.isEmpty()) return emptySet()
    val tokens = mutableSetOf(n)
    Regex("[a-z0-9]+").findAll(n).forEach { tokens += it.value }
    val cjk = n.filter { it.isCjk() }
    val maxLen = minOf(3, cjk.length)
    for (len in 2..maxLen) {
        for (i in 0..cjk.length - len) {
            tokens += cjk.substring(i, i + len)
        }
    }
    return tokens
}

/** FTS5 document: keep the whole string plus CJK unigrams so MATCH still hits if the tokenizer splits. */
internal fun ftsIndexText(text: String): String {
    val chunks = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (chunks.isEmpty()) return ""
    return buildString {
        for (chunk in chunks) {
            if (isNotEmpty()) append(' ')
            append(chunk)
            val cjk = chunk.filter { it.isCjk() }
            if (cjk.length >= 2) {
                append(' ')
                append(cjk.toList().joinToString(" "))
            }
        }
    }
}
