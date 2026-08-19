package relay.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import relay.llm.Provider
import relay.llm.model.ChatRequest
import relay.llm.model.Message
import relay.llm.provider.DeepSeek

/**
 * Cloud extract plugin. Not part of [MemoryStore].
 */
class CloudTripleExtractor(
    private val provider: Provider,
    private val model: String = DeepSeek.CHAT,
) {
    suspend fun extract(
        graphId: String,
        dialogue: String,
        rawEventIds: List<String>,
    ): List<TripleDraft> {
        if (dialogue.isBlank()) return emptyList()
        val messages = extractMessages(dialogue)
        val response = provider.chat(
            ChatRequest(
                model = model,
                messages = messages,
                temperature = 0.0,
                maxTokens = 512,
                extra = mapOf(
                    "response_format" to buildJsonObject { put("type", "json_object") },
                ),
            ),
        )
        val raw = response.message.content.orEmpty()
        val parsed = parseTriplesJson(raw)
        val cleaned = cleanTriples(
            parsed.map {
                TripleDraft(graphId = graphId, s = it.s, p = it.p, o = it.o, rawEventIds = rawEventIds)
            },
            chunk = dialogue,
        )
        return cleaned.map {
            TripleDraft(graphId = graphId, s = it.s, p = it.p, o = it.o, rawEventIds = rawEventIds)
        }
    }

    companion object {
        fun formatTurns(events: List<RawEvent>): String = events.joinToString("\n") { event ->
            val label = if (event.role == "user") "用户" else "助理"
            "$label: ${event.text.trim()}"
        }
    }
}

internal fun extractMessages(chunk: String): List<Message> {
    val rels = PREDICATES.joinToString("、")
    val messages = mutableListOf(
        Message.system("$EXTRACT_SYSTEM\n关系词表：$rels"),
    )
    for (ex in EXTRACT_FEWSHOT) {
        messages += Message.user("对话：\n${ex.first}")
        messages += Message.assistant(ex.second)
    }
    messages += Message.user("对话：\n$chunk\n\n只输出 JSON。")
    return messages
}

internal fun parseTriplesJson(raw: String): List<ParsedTriple> {
    val body = stripFence(raw)
    if (body.isBlank()) return emptyList()
    return try {
        val parsed = EXTRACT_JSON.decodeFromString(ExtractBatch.serializer(), body)
        parsed.triples.orEmpty().mapNotNull { item ->
            val s = item.s?.trim().orEmpty()
            val p = item.p?.trim().orEmpty()
            val o = item.o?.trim().orEmpty()
            if (s.isEmpty() || p.isEmpty() || o.isEmpty()) null else ParsedTriple(s, p, o)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal data class ParsedTriple(val s: String, val p: String, val o: String)

private fun stripFence(text: String): String {
    var body = text.trim()
    if (body.startsWith("```")) {
        body = body.removePrefix("```json").removePrefix("```").trim()
        body = body.removeSuffix("```").trim()
    }
    return body
}

@Serializable
private data class ExtractBatch(val triples: List<ExtractTriple>? = emptyList())

@Serializable
private data class ExtractTriple(
    val s: String? = null,
    val p: String? = null,
    val o: String? = null,
)

private val EXTRACT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val EXTRACT_SYSTEM = """你是知识抽取器，不是助理。只抽已经说出口的个人事实。
主语：说话的人用「用户」，其他人用姓名或称呼。不要抽「助理」实体。
named 只用于宠物名。过敏史「要报 X」= allergic_to X，不要写成 likes/dislikes。
宾语用短名称：地铁不是坐地铁，美式不是美式咖啡。
不要抽：机票、提醒、周末、过年、设备型号。技术能力用 skilled_in，不要 likes。
plans 可以是地点，也可以是意向：跳槽、休息。不要把下周三/今晚当宾语。
located_in 只用于地点→地点。work_location 是办公地，lives_in 是住地，born_in 是出生地。
家人优先 child_of/parent_of/spouse_of/sibling_of；说不清再用 family_of。
「我妈爱吃X」主语是妈妈，不要写成用户 likes X。同时抽 用户 child_of 妈妈。
朋友用 friend_of，同事用 colleague_of。学校 alumni_of，组织 member_of。
素食/清真 = diet。拥有物 owns。参加的活动 attends。会的语言 knows_language。
想换工作、离职、说拜拜 = plans 跳槽。想歇一阵 = plans 休息。
作业/功课没做完、还要交X = has_task X，不要写成 likes 或 plans。
工作N年了、工龄N年 = work_years N年（两年、三年），不要写成 works_at。
吃素/素食/清真 = diet；英语/日语 = knows_language，不要写成 skilled_in。
有个人事实就必须抽；空列表只用于纯闲聊。"""

private val EXTRACT_FEWSHOT: List<Pair<String, String>> = listOf(
    "用户: 今天雨好大，随便聊聊，没什么要记的。\n助理: 那就聊。" to
        """{"triples":[]}""",
    "用户: 体检表过敏史把头孢报上去，小时候起过疹。\n助理: 记下头孢。" to
        """{"triples":[{"s":"用户","p":"allergic_to","o":"头孢"}]}""",
    "用户: 我打算离职，先歇两个月，别的先不谈。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"plans","o":"跳槽"},{"s":"用户","p":"plans","o":"休息"}]}""",
    "用户: 我妈爱吃花生。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"child_of","o":"妈妈"},{"s":"妈妈","p":"likes","o":"花生"}]}""",
    "用户: 我作业没做完。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"has_task","o":"作业"}]}""",
    "用户: 我工作两年了。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"work_years","o":"两年"}]}""",
    AssistantPlay.DIALOGUE to AssistantPlay.goldJson(),
)
