package relay.memory.extract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import relay.llm.Provider
import relay.llm.model.ChatRequest
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.provider.DeepSeek
import relay.memory.ClaimDraft
import relay.memory.ExtractOutcome
import relay.memory.ExtractResult
import relay.memory.Fact
import relay.memory.MemoryClassifier
import relay.memory.MemoryScope
import relay.memory.MemoryState
import relay.memory.PREDICATES
import relay.memory.RawEvent
import relay.memory.TripleDraft
import relay.memory.predicateLabel

/** Cloud extract plugin. Talks to a [Provider]; the graph store never does. */
class CloudTripleExtractor(
    private val provider: Provider,
    private val model: String = DeepSeek.CHAT,
) : MemoryExtractor {
    override suspend fun extract(
        graphId: String,
        dialogue: String,
        rawEventIds: List<String>,
        priorFacts: List<Fact>,
    ): ExtractResult {
        if (dialogue.isBlank()) {
            return ExtractResult(
                outcome = ExtractOutcome.SUCCESS_EMPTY,
                finishReason = FinishReason.STOP,
            )
        }
        val messages = extractMessages(dialogue, priorFacts)
        val response = provider.chat(
            ChatRequest(
                model = model,
                messages = messages,
                temperature = 0.0,
                maxTokens = 1024,
                extra = mapOf(
                    "response_format" to buildJsonObject { put("type", "json_object") },
                ),
            ),
        )
        val raw = response.message.content.orEmpty()
        if (response.finishReason == FinishReason.LENGTH) {
            return ExtractResult(
                outcome = ExtractOutcome.TRUNCATED,
                raw = raw,
                finishReason = response.finishReason,
                errors = listOf("model output truncated"),
            )
        }
        if (response.finishReason in setOf(
                FinishReason.CONTENT_FILTER,
                FinishReason.ERROR,
                FinishReason.TOOL_CALLS,
            )
        ) {
            return ExtractResult(
                outcome = ExtractOutcome.REJECTED,
                raw = raw,
                finishReason = response.finishReason,
                errors = listOf("model finish reason ${response.finishReason}"),
            )
        }
        if (raw.isBlank()) {
            return ExtractResult(
                outcome = ExtractOutcome.REJECTED,
                raw = raw,
                finishReason = response.finishReason,
                errors = listOf("empty model response"),
            )
        }
        return when (val parsed = parseExtractJson(raw)) {
            is ParseExtractResult.Failure -> ExtractResult(
                outcome = ExtractOutcome.PARSE_FAILED,
                raw = raw,
                finishReason = response.finishReason,
                errors = parsed.errors,
            )
            is ParseExtractResult.Success -> {
                val claims = parsed.claims.map {
                    ClaimDraft(
                        graphId = graphId,
                        subject = it.subject,
                        text = it.text,
                        rawEventIds = rawEventIds,
                        scope = MemoryScope.SESSION,
                        state = MemoryState.CANDIDATE,
                    )
                }
                val drafts = parsed.triples.map {
                    val draft = TripleDraft(
                        graphId = graphId,
                        s = it.s,
                        p = it.p,
                        o = it.o,
                        rawEventIds = rawEventIds,
                        retract = it.retract,
                    )
                    val classification = MemoryClassifier.triple(draft)
                    draft.copy(
                        scope = classification.scope,
                        state = classification.state,
                        scopeId = classification.scopeId,
                    )
                }
                ExtractResult(
                    outcome = if (claims.isEmpty() && drafts.isEmpty()) {
                        ExtractOutcome.SUCCESS_EMPTY
                    } else {
                        ExtractOutcome.SUCCESS
                    },
                    claims = claims,
                    drafts = drafts,
                    raw = raw,
                    finishReason = response.finishReason,
                )
            }
        }
    }

    companion object {
        fun formatTurns(events: List<RawEvent>): String = events.joinToString("\n") { event ->
            val label = if (event.role == "user") "用户" else "助理"
            "$label: ${event.text.trim()}"
        }
    }
}

internal fun extractMessages(chunk: String, priorFacts: List<Fact> = emptyList()): List<Message> {
    val rels = PREDICATES.joinToString("、")
    val messages = mutableListOf(
        Message.system("$EXTRACT_SYSTEM\n关系词表：$rels"),
    )
    for (ex in EXTRACT_FEWSHOT) {
        messages += Message.user("对话：\n${ex.first}")
        messages += Message.assistant(ex.second)
    }
    messages += Message.user(extractUserPrompt(chunk, priorFacts))
    return messages
}

internal fun extractUserPrompt(chunk: String, priorFacts: List<Fact> = emptyList()): String = buildString {
    if (priorFacts.isNotEmpty()) {
        append("已有事实：\n")
        for (fact in priorFacts) {
            append("- ${fact.s} ${predicateLabel(fact.p)} ${fact.o}\n")
        }
        append('\n')
    }
    append("对话：\n")
    append(chunk)
    if (priorFacts.count { it.p == "plans" } > 1) {
        append("\n\n已有多条打算时，对话没点名不要 retract，输出空 triples。")
    }
    append("\n\n只输出 JSON。")
}

internal fun parseTriplesJson(raw: String): List<ParsedTriple> {
    val parsed = parseExtractJson(raw)
    return if (parsed is ParseExtractResult.Success) parsed.triples else emptyList()
}

internal data class ParsedTriple(val s: String, val p: String, val o: String, val retract: Boolean = false)
internal data class ParsedClaim(val subject: String, val text: String)

internal sealed interface ParseExtractResult {
    data class Success(
        val claims: List<ParsedClaim>,
        val triples: List<ParsedTriple>,
    ) : ParseExtractResult

    data class Failure(val errors: List<String>) : ParseExtractResult
}

internal fun parseExtractJson(raw: String): ParseExtractResult {
    val body = stripFence(raw)
    if (body.isBlank()) return ParseExtractResult.Failure(listOf("empty JSON body"))
    return try {
        val parsed = EXTRACT_JSON.decodeFromString(ExtractBatch.serializer(), body)
        val errors = mutableListOf<String>()
        val claims = parsed.claims.orEmpty().mapIndexedNotNull { index, item ->
            val subject = item.subject?.trim().orEmpty()
            val text = item.text?.trim().orEmpty()
            if (subject.isEmpty() || text.isEmpty()) {
                errors += "claims[$index] requires subject and text"
                null
            } else {
                ParsedClaim(subject, text)
            }
        }
        val triples = parsed.triples.orEmpty().mapIndexedNotNull { index, item ->
            val s = item.s?.trim().orEmpty()
            val p = item.p?.trim().orEmpty()
            val o = item.o?.trim().orEmpty()
            if (s.isEmpty() || p.isEmpty() || o.isEmpty()) {
                errors += "triples[$index] requires s, p and o"
                null
            } else {
                ParsedTriple(s, p, o, item.retract == true)
            }
        }
        if (errors.isEmpty()) ParseExtractResult.Success(claims, triples) else ParseExtractResult.Failure(errors)
    } catch (e: Exception) {
        ParseExtractResult.Failure(listOf(e.message ?: "invalid extract JSON"))
    }
}

private fun stripFence(text: String): String {
    var body = text.trim()
    if (body.startsWith("```")) {
        body = body.removePrefix("```json").removePrefix("```").trim()
        body = body.removeSuffix("```").trim()
    }
    return body
}

@Serializable
private data class ExtractBatch(
    val claims: List<ExtractClaim>? = emptyList(),
    val triples: List<ExtractTriple>? = emptyList(),
)

@Serializable
private data class ExtractClaim(
    val subject: String? = null,
    val text: String? = null,
)

@Serializable
private data class ExtractTriple(
    val s: String? = null,
    val p: String? = null,
    val o: String? = null,
    val retract: Boolean? = null,
)

private val EXTRACT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val EXTRACT_SYSTEM = """你是知识抽取器，不是助理。只抽已经说出口、跨会话仍有用的事实。
输出一个 JSON 对象：{"claims":[],"triples":[]}。
能被关系词表忠实表达的事实只写 triples；不能忠实表达的项目经历、架构、职责、条件策略写成自洽的短句 claim。
claims 每项格式 {"subject":"用户或实体","text":"一条原子事实"}。不要把同一事实同时写进 claims 和 triples。
不要为了进图硬凑关系；复杂事实进入 claims。纯闲聊、纯助理建议才允许两边都空。
主语：说话的人用「用户」，其他人用姓名或称呼。不要抽「助理」实体。
named 只用于宠物名。过敏史「要报 X」= allergic_to X，不要写成 likes/dislikes。
宾语用短名称：地铁不是坐地铁，美式不是美式咖啡。
不要抽：机票、提醒、周末、过年、设备型号。技术能力用 skilled_in，不要 likes。
参与过的项目用 worked_on；项目的明确模块用 has_component；明确采用的技术用 uses_technology。
求职目标或想投的岗位用 target_role，不要误写成当前 works_as。
plans 可以是地点，也可以是意向：跳槽、休息。不要把下周三/今晚当宾语。
located_in 只用于地点→地点。「去上海住静安」= plans 上海 + 上海 located_in 静安。
work_location 是办公地，lives_in 是住地，born_in 是出生地。
家人优先 child_of/parent_of/spouse_of/sibling_of；说不清再用 family_of。
「我妈/我爸」= 用户 child_of 妈妈/爸爸。「我女儿/儿子/孩子 X」= 用户 parent_of X，不要写成 child_of。
「我妈爱吃X」主语是妈妈，不要写成用户 likes X。
朋友用 friend_of，同事用 colleague_of。学校 alumni_of，组织 member_of。
素食/清真 = diet。拥有物 owns。参加的活动 attends。会的语言 knows_language。
想换工作、离职、说拜拜 = plans 跳槽。想歇一阵 = plans 休息。
作业/功课没做完、还要交报销/搬家 = has_task。工作吐槽里的需求、产品、改需求不是 has_task。
取消、不打算去了、签证没过 = 对已有 plans 输出同一主谓宾并加 "retract":true，不要再写一条正向 plans。没点名且只有一条打算时，retract 那一条。没点名且已有多条打算 → 空列表，不要猜。
「可能取消 / 先别划掉」= 仍抽那条 plans，不要空。
搬家到新城市只抽新 lives_in，不要对旧住址 retract。
「过年回X / 过去一趟」不是用户 plans X。说「我杭州的会」可抽 用户 lives_in 杭州。
别人点的菜不要 likes。「比我熟 X」skilled_in 写在同事身上，不要写成用户。
工作N年了、工龄N年 = work_years N年（两年、三年），不要写成 works_at。
吃素/素食/清真 = diet；英语/日语 = knows_language，不要写成 skilled_in。
有个人事实就必须抽；闭集表达不了就写 claim。两边空列表只用于纯闲聊。"""

private val EXTRACT_FEWSHOT: List<Pair<String, String>> = listOf(
    "用户: 我做过车管家，主界面有卡片引擎，云端下发卡片，车端渲染；详情页是 H5，车端提供 JSBridge。\n助理: 这个动态化架构很有价值。" to
        """{"claims":[{"subject":"车管家","text":"车管家通过云端下发卡片并由车端动态渲染主界面"}],"triples":[{"s":"用户","p":"worked_on","o":"车管家"},{"s":"车管家","p":"has_component","o":"卡片引擎"},{"s":"车管家","p":"uses_technology","o":"H5"},{"s":"车管家","p":"uses_technology","o":"JSBridge"}]}""",
    "用户: 鸿蒙项目里 repository 用策略模式，有的先展示本地再刷新，有的云端失败后回退本地。\n助理: 明白。" to
        """{"claims":[{"subject":"用户","text":"用户在鸿蒙项目中用策略模式实现 Repository 的本地与网络数据加载策略"}],"triples":[]}""",
    "用户: 我这次想投客户端开发。\n助理: 那车管家可以排最前面。" to
        """{"claims":[],"triples":[{"s":"用户","p":"target_role","o":"客户端开发"}]}""",
    "用户: 今天雨好大，随便聊聊，没什么要记的。\n助理: 那就聊。" to
        """{"claims":[],"triples":[]}""",
    "用户: 体检表过敏史把头孢报上去，小时候起过疹。\n助理: 记下头孢。" to
        """{"triples":[{"s":"用户","p":"allergic_to","o":"头孢"}]}""",
    "用户: 我打算离职，先歇两个月，别的先不谈。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"plans","o":"跳槽"},{"s":"用户","p":"plans","o":"休息"}]}""",
    "用户: 我妈爱吃花生。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"child_of","o":"妈妈"},{"s":"妈妈","p":"likes","o":"花生"}]}""",
    "用户: 我女儿小宝今年上幼儿园。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"parent_of","o":"小宝"}]}""",
    "用户: 哎，今天那个需求又变了，下周三就要交了，这产品一直变，我是真服了。\n助理: 确实烦。" to
        """{"triples":[]}""",
    "用户: 我作业没做完。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"has_task","o":"作业"}]}""",
    "用户: 我工作两年了。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"work_years","o":"两年"}]}""",
    "用户: 明天评审找王磊，他是我同事，比我熟 JNI。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"colleague_of","o":"王磊"},{"s":"王磊","p":"skilled_in","o":"JNI"}]}""",
    "用户: 下周去上海两天，住静安。可能取消，但先别从计划里划掉。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"plans","o":"上海"},{"s":"上海","p":"located_in","o":"静安"}]}""",
    "用户: 我住杭州。不对，上周搬上海了。\n助理: 记下了。" to
        """{"triples":[{"s":"用户","p":"lives_in","o":"上海"}]}""",
    "已有事实：\n- 用户 打算 美国\n对话：\n用户: 我不打算去了，签证没过。\n助理: 好。" to
        """{"triples":[{"s":"用户","p":"plans","o":"美国","retract":true}]}""",
    "已有事实：\n- 用户 打算 美国\n- 用户 打算 上海\n对话：\n用户: 那趟取消了。\n助理: 好。" to
        """{"triples":[]}""",
    """
        |用户: 我妈爱吃花生，今晚她来杭州吃饭。我自己花生过敏，火锅底料得分开。家里猫叫芝麻。
        |助理: 妈妈花生、你过敏、芝麻。
        |用户: 我作业没做完。白天在阿里做客户端，工作两年了，办公还在西溪。浙大毕业的。同事王磊，明天评审 JNI 留给他。
        |助理: 作业、阿里、两年、西溪、王磊。
        |用户: 咖啡我喝美式，不爱香菜。通勤宁可坐地铁。最近吃素。医生让我吃钙片。下周去上海住静安。英语还能对付。朋友李娜。我打算离职。
        |助理: 记下了。
        """.trimMargin() to
        """{"triples":[{"s":"用户","p":"child_of","o":"妈妈"},{"s":"妈妈","p":"likes","o":"花生"},{"s":"用户","p":"allergic_to","o":"花生"},{"s":"用户","p":"lives_in","o":"杭州"},{"s":"用户","p":"has_pet","o":"猫"},{"s":"猫","p":"named","o":"芝麻"},{"s":"用户","p":"has_task","o":"作业"},{"s":"用户","p":"works_at","o":"阿里"},{"s":"用户","p":"works_as","o":"客户端"},{"s":"用户","p":"work_years","o":"两年"},{"s":"用户","p":"work_location","o":"西溪"},{"s":"用户","p":"alumni_of","o":"浙大"},{"s":"用户","p":"colleague_of","o":"王磊"},{"s":"用户","p":"likes","o":"美式"},{"s":"用户","p":"dislikes","o":"香菜"},{"s":"用户","p":"prefers","o":"地铁"},{"s":"用户","p":"diet","o":"素食"},{"s":"用户","p":"takes","o":"钙片"},{"s":"用户","p":"plans","o":"上海"},{"s":"上海","p":"located_in","o":"静安"},{"s":"用户","p":"knows_language","o":"英语"},{"s":"用户","p":"friend_of","o":"李娜"},{"s":"用户","p":"plans","o":"离职"}]}""",
)
