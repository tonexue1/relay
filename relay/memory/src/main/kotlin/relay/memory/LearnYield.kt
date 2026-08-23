package relay.memory

/**
 * Distinguishes idle empty extracts from user turns that almost certainly
 * contained durable facts. Idle SUCCESS_EMPTY may consume; low-yield empties must not.
 */
internal object LearnYield {
    private val idleOnly = Regex(
        "^(你好|hello|hi|在吗|谢谢|好的|嗯+|哦+|哈+|天气|随便聊聊|没什么).*$",
        RegexOption.IGNORE_CASE,
    )
    private val factualCue = Regex(
        "过敏|住|毕业|做过|项目|擅长|开发|模块|引擎|架构|分层|打算|计划|喜欢|不喜欢|" +
            "工作|同事|学校|宠物|孩子|硕士|工龄|客户端|卡片|鸿蒙|安卓|android|binder",
        RegexOption.IGNORE_CASE,
    )

    fun shouldRetryEmpty(events: List<RawEvent>): Boolean {
        val user = events.filter { it.role.equals("user", ignoreCase = true) }
            .joinToString("\n") { it.text }
            .trim()
        if (user.isBlank()) return false
        val compact = user.replace(Regex("\\s+"), "")
        if (compact.length < 4) return false
        if (idleOnly.matches(compact)) return false
        return factualCue.containsMatchIn(user) || compact.length >= 24
    }
}

/**
 * Last-resort claim when the model returned nothing but the user stated
 * project / architecture facts the closed predicate set cannot hold faithfully.
 */
internal object HardFactClaims {
    private val projectCue = Regex(
        "做过|项目|模块|引擎|架构|分层|repository|策略模式|JSBridge|卡片引擎",
        RegexOption.IGNORE_CASE,
    )

    fun from(graphId: String, events: List<RawEvent>): List<ClaimDraft> =
        events.filter { it.role.equals("user", ignoreCase = true) }.mapNotNull { event ->
            val text = event.text.trim().replace(Regex("\\s+"), " ")
            if (text.length < 8 || !projectCue.containsMatchIn(text)) return@mapNotNull null
            ClaimDraft(
                graphId = graphId,
                subject = "用户",
                text = text.take(200),
                rawEventIds = listOf(event.id),
                scope = MemoryScope.SESSION,
                state = MemoryState.CANDIDATE,
            )
        }
}
