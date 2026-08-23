package relay.artifacts

object ArtifactValidator {
    fun validate(mime: String, body: String): ArtifactValidationReport {
        val errors = buildList {
            if (body.isBlank()) add("产物内容为空")
            if ('\u0000' in body) add("产物包含 NUL 字符")
            if (mime == "text/html" && body.count { it == '<' } != body.count { it == '>' }) {
                add("HTML 尖括号数量不匹配")
            }
        }
        val warnings = buildList {
            if (mime == "text/html" && EXTERNAL_RESOURCE.containsMatchIn(body)) {
                add("外部网络资源会被沙箱拦截，请改用内置或 data/blob 资源")
            }
            if (mime == "text/html" && Regex("<base\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                add("base 元素会在预览前被移除")
            }
            if (mime == "text/markdown" && body.length > 200_000) {
                add("Markdown 很长，端上预览可能需要分段")
            }
        }
        return ArtifactValidationReport(errors.isEmpty(), errors, warnings)
    }

    private val EXTERNAL_RESOURCE = Regex(
        """(?:src|href)\s*=\s*["']https?://""",
        setOf(RegexOption.IGNORE_CASE),
    )
}
