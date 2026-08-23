package relay.uikit

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Test

class WidgetParserTest {
    @Test
    fun `valid kv parses and has visible summary`() {
        val spec = WidgetParser.parse(
            """{"type":"kv","version":1,"items":[{"key":"状态","value":"正常"}]}""",
        )
        assertIs<KvSpec>(spec)
        assertEquals("状态: 正常", spec.summary())
    }

    @Test
    fun `unknown type degrades without throwing`() {
        val spec = WidgetParser.parse("""{"type":"future","version":1,"value":3}""")
        assertIs<FallbackSpec>(spec)
        assertTrue(spec.reason.contains("未知"))
    }

    @Test
    fun `malformed table degrades`() {
        val spec = WidgetParser.parse(
            """{"type":"table","version":1,"columns":["a","b"],"rows":[["only one"]]}""",
        )
        assertIs<FallbackSpec>(spec)
    }

    @Test
    fun `unsupported version degrades`() {
        assertIs<FallbackSpec>(WidgetParser.parse("""{"type":"kv","version":2,"items":[]}"""))
    }

    @Test
    fun `legacy chart contract remains valid`() {
        val spec = WidgetParser.parse(
            """{"type":"chart","version":1,"kind":"BAR","points":[{"label":"一","value":12}]}""",
        )

        assertIs<ChartSpec>(spec)
        assertEquals(ChartKind.BAR, spec.kind)
        assertEquals(12.0, spec.points.single().value)
        assertTrue(spec.series.isEmpty())
    }

    @Test
    fun `choice form supports sequential single and multi questions`() {
        val spec = WidgetParser.parse(
            """
            {
              "type":"choice_form","version":1,"title":"确定研究方式",
              "questions":[
                {
                  "id":"path","kind":"SINGLE","title":"从哪里开始？",
                  "options":[{"id":"a","label":"架构总览","recommended":true},{"id":"b","label":"自行指定"}]
                },
                {
                  "id":"topics","kind":"MULTI","title":"关注哪些主题？",
                  "options":[{"id":"ipc","label":"Binder"},{"id":"runtime","label":"运行时"}]
                }
              ]
            }
            """.trimIndent(),
        )

        assertIs<ChoiceFormSpec>(spec)
        assertEquals(2, spec.questions.size)
        assertEquals(ChoiceKind.MULTI, spec.questions.last().kind)
        assertTrue(spec.questions.first().options.first().recommended)
    }

    @Test
    fun `choice form rejects duplicate question ids`() {
        val spec = WidgetParser.parse(
            """
            {
              "type":"choice_form","version":1,"title":"重复问题",
              "questions":[
                {"id":"same","kind":"SINGLE","title":"第一题","options":[{"id":"a","label":"A"}]},
                {"id":"same","kind":"SINGLE","title":"第二题","options":[{"id":"b","label":"B"}]}
              ]
            }
            """.trimIndent(),
        )

        assertIs<FallbackSpec>(spec)
        assertTrue(spec.reason.contains("问题 ID"))
    }

    @Test
    fun `stacked multi-series chart parses`() {
        val spec = WidgetParser.parse(
            """
            {
              "type":"chart","version":1,"stacked":true,
              "series":[
                {"name":"订阅","kind":"BAR","points":[{"label":"Q1","value":18},{"label":"Q2","value":24}]},
                {"name":"服务","kind":"BAR","points":[{"label":"Q1","value":7},{"label":"Q2","value":9}]}
              ]
            }
            """.trimIndent(),
        )

        assertIs<ChartSpec>(spec)
        assertTrue(spec.stacked)
        assertEquals(listOf("订阅", "服务"), spec.series.map { it.name })
    }

    @Test
    fun `advanced chart rejects mismatched categories`() {
        val spec = WidgetParser.parse(
            """
            {
              "type":"chart","version":1,
              "series":[
                {"name":"收入","kind":"BAR","points":[{"label":"Q1","value":18}]},
                {"name":"转化","kind":"LINE","axis":"END","points":[{"label":"Q2","value":12}]}
              ]
            }
            """.trimIndent(),
        )

        assertIs<FallbackSpec>(spec)
        assertTrue(spec.reason.contains("类别"))
    }

    @Test
    fun `widget fixtures all parse`() {
        val raw = requireNotNull(javaClass.getResource("/fixtures/widgets.json")).readText()
        val specs = Json.parseToJsonElement(raw).jsonArray.map { WidgetParser.parse(it.toString()) }

        assertTrue(specs.none { it is FallbackSpec })
        assertEquals(3, specs.count { it is ChartSpec })
    }
}
