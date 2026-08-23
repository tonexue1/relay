package relay.memory.extract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import relay.llm.model.FinishReason
import relay.memory.ExtractOutcome
import relay.memory.Fact
import relay.memory.GRAPH_ASSISTANT
import relay.memory.RawEvent

class CloudTripleExtractorTest {

    @Test
    fun parseAcceptsBareJson() {
        val parsed = parseTriplesJson("""{"triples":[{"s":"用户","p":"allergic_to","o":"头孢"}]}""")
        assertEquals("头孢", parsed.single().o)
    }

    @Test
    fun parseStripsMarkdownFence() {
        val parsed = parseTriplesJson(
            """
            ```json
            {"triples":[{"s":"用户","p":"lives_in","o":"杭州"}]}
            ```
            """.trimIndent(),
        )
        assertEquals("杭州", parsed.single().o)
    }

    @Test
    fun parseEmptyAndGarbageAreEmpty() {
        assertTrue(parseTriplesJson("").isEmpty())
        assertTrue(parseTriplesJson("not json").isEmpty())
        assertTrue(parseTriplesJson("""{"triples":[]}""").isEmpty())
        assertTrue(parseTriplesJson("""{"triples":[{"s":"用户","p":"likes"}]}""").isEmpty())
    }

    @Test
    fun extractMessagesAskForJsonOnlyAndListPredicates() {
        val messages = extractMessages("用户: 我住杭州")
        assertTrue(messages.first().content.orEmpty().contains("allergic_to"))
        assertTrue(messages.first().content.orEmpty().contains("parent_of"))
        assertTrue(messages.last().content.orEmpty().contains("只输出 JSON"))
        assertTrue(messages.size > 2)
    }

    @Test
    fun formatTurnsLabelsRoles() {
        val text = CloudTripleExtractor.formatTurns(
            listOf(
                RawEvent("1", GRAPH_ASSISTANT, 1, "", "user", "我花生过敏", "chat", false, "private"),
                RawEvent("2", GRAPH_ASSISTANT, 2, "", "assistant", "记下了", "chat", false, "private"),
            ),
        )
        assertEquals("用户: 我花生过敏\n助理: 记下了", text)
    }

    @Test
    fun extractAttachesRawEventIds() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"allergic_to","o":"花生酱"}]}""",
        )
        val extractor = CloudTripleExtractor(provider, model = "fake-model")
        val result = extractor.extract(GRAPH_ASSISTANT, "用户: 我花生过敏，别推荐花生酱。", listOf("evt-1"))
        assertEquals(ExtractOutcome.SUCCESS, result.outcome)
        assertEquals("花生酱", result.drafts.single().o)
        assertEquals(listOf("evt-1"), result.drafts.single().rawEventIds)
        val request = provider.chats.single()
        assertEquals("json_object", request.extra.getValue("response_format").jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(request.messages.last().content.orEmpty().contains("只输出 JSON"))
    }

    @Test
    fun extractKeepsUnfinishedHomework() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"has_task","o":"作业"}]}""",
        )
        val result = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我作业没做完。", listOf("e"))
        assertEquals("作业", result.drafts.single { it.p == "has_task" }.o)
    }

    @Test
    fun extractBlankDialogueSkipsProvider() = runTest {
        val provider = RecordingProvider("""{"triples":[]}""")
        val result = CloudTripleExtractor(provider, model = "fake-model").extract(GRAPH_ASSISTANT, "  ", listOf("e"))
        assertEquals(ExtractOutcome.SUCCESS_EMPTY, result.outcome)
        assertTrue(provider.chats.isEmpty())
    }

    @Test
    fun extractKeepsMomLikesPeanuts() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"妈妈","p":"likes","o":"花生"}]}""",
        )
        val result = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我妈爱吃花生。", listOf("e"))
        assertEquals(listOf(Triple("妈妈", "likes", "花生")), result.drafts.map { Triple(it.s, it.p, it.o) })
    }

    @Test
    fun extractPassesThroughModelOutputEvenIfNotInDialogue() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"allergic_to","o":"青霉素"}]}""",
        )
        val result = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 今天雨好大，随便聊聊。", listOf("e"))
        assertEquals("青霉素", result.drafts.single().o)
    }

    @Test
    fun extractKeepsYearsWorking() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"work_years","o":"两年了"}]}""",
        )
        val result = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我工作两年了。", listOf("e"))
        assertEquals("两年了", result.drafts.single { it.p == "work_years" }.o)
    }

    @Test
    fun parseRetractFlag() {
        val parsed = parseTriplesJson(
            """{"triples":[{"s":"用户","p":"plans","o":"美国","retract":true}]}""",
        )
        assertTrue(parsed.single().retract)
        assertEquals("美国", parsed.single().o)
    }

    @Test
    fun extractKeepsRetractFlagFromModel() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"plans","o":"美国","retract":true}]}""",
        )
        val result = CloudTripleExtractor(provider, model = "fake-model").extract(
            GRAPH_ASSISTANT,
            "用户: 我不打算去了，签证没过。",
            listOf("e"),
            priorFacts = listOf(Fact("用户", "plans", "美国")),
        )
        assertTrue(result.drafts.single().retract)
        assertEquals("美国", result.drafts.single().o)
        assertTrue(provider.chats.single().messages.last().content.orEmpty().contains("已有事实"))
    }

    @Test
    fun extractUserPromptWarnsNotToGuessRetractWhenManyPriors() {
        val prompt = extractUserPrompt(
            "用户: 那趟取消了。",
            listOf(Fact("用户", "plans", "美国"), Fact("用户", "plans", "上海")),
        )
        assertTrue(prompt.contains("没点名不要 retract"))
    }

    @Test
    fun parseKeepsCarButlerArchitectureClaim() {
        val parsed = parseExtractJson(
            """{"claims":[{"subject":"车管家","text":"车管家通过云端下发卡片并由车端动态渲染主界面"}],"triples":[{"s":"用户","p":"worked_on","o":"车管家"},{"s":"车管家","p":"has_component","o":"卡片引擎"}]}""",
        )
        check(parsed is ParseExtractResult.Success)
        assertEquals("车管家通过云端下发卡片并由车端动态渲染主界面", parsed.claims.single().text)
        assertEquals("worked_on", parsed.triples.single { it.o == "车管家" }.p)
        assertEquals("卡片引擎", parsed.triples.single { it.p == "has_component" }.o)
    }

    @Test
    fun extractKeepsOpenClaims() = runTest {
        val provider = RecordingProvider(
            """{"claims":[{"subject":"用户","text":"用户参与的车管家应用使用云端卡片"}],"triples":[]}""",
        )
        val result = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 车管家是云端下发卡片。", listOf("e"))

        assertEquals(ExtractOutcome.SUCCESS, result.outcome)
        assertEquals("用户参与的车管家应用使用云端卡片", result.claims.single().text)
        assertEquals(listOf("e"), result.claims.single().rawEventIds)
    }

    @Test
    fun malformedAndTruncatedAreNotSuccessEmpty() = runTest {
        val malformed = CloudTripleExtractor(RecordingProvider("not json"), model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我有项目经验", listOf("e"))
        val truncated = CloudTripleExtractor(
            RecordingProvider("""{"claims":[""", FinishReason.LENGTH),
            model = "fake-model",
        ).extract(GRAPH_ASSISTANT, "用户: 我有项目经验", listOf("e"))

        assertEquals(ExtractOutcome.PARSE_FAILED, malformed.outcome)
        assertEquals(ExtractOutcome.TRUNCATED, truncated.outcome)
    }
}
