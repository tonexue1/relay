package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

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
    fun extractCleansAliasesAndAttachesRawEventIds() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"allergic_to","o":"花生酱"}]}""",
        )
        val extractor = CloudTripleExtractor(provider, model = "fake-model")
        val drafts = extractor.extract(GRAPH_ASSISTANT, "用户: 我花生过敏，别推荐花生酱。", listOf("evt-1"))
        assertEquals("花生", drafts.single().o)
        assertEquals(listOf("evt-1"), drafts.single().rawEventIds)
        val request = provider.chats.single()
        assertEquals("json_object", request.extra.getValue("response_format").jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(request.messages.last().content.orEmpty().contains("只输出 JSON"))
    }

    @Test
    fun extractKeepsUnfinishedHomework() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"has_task","o":"作业"}]}""",
        )
        val drafts = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我作业没做完。", listOf("e"))
        assertEquals("作业", drafts.single { it.p == "has_task" }.o)
    }

    @Test
    fun extractBlankDialogueSkipsProvider() = runTest {
        val provider = RecordingProvider("""{"triples":[]}""")
        val drafts = CloudTripleExtractor(provider, model = "fake-model").extract(GRAPH_ASSISTANT, "  ", listOf("e"))
        assertTrue(drafts.isEmpty())
        assertTrue(provider.chats.isEmpty())
    }

    @Test
    fun extractKeepsMomLikesPeanuts() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"妈妈","p":"likes","o":"花生"}]}""",
        )
        val drafts = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我妈爱吃花生。", listOf("e"))
        assertTrue(drafts.any { it.s == "妈妈" && it.p == "likes" && it.o == "花生" })
        assertTrue(drafts.any { it.s == "用户" && it.p == "child_of" && it.o == "妈妈" })
    }

    @Test
    fun extractDropsHallucinationNotInDialogue() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"allergic_to","o":"青霉素"}]}""",
        )
        val drafts = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 今天雨好大，随便聊聊。", listOf("e"))
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun extractKeepsYearsWorking() = runTest {
        val provider = RecordingProvider(
            """{"triples":[{"s":"用户","p":"work_years","o":"两年了"}]}""",
        )
        val drafts = CloudTripleExtractor(provider, model = "fake-model")
            .extract(GRAPH_ASSISTANT, "用户: 我工作两年了。", listOf("e"))
        assertEquals("两年", drafts.single { it.p == "work_years" }.o)
    }
}
