package relay.uikit

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.artifacts.FileArtifactRepository

class UiArtifactToolsTest {
    @Test
    fun `product capability set excludes html`() {
        val repository = FileArtifactRepository(Files.createTempDirectory("ui-kit-test").toFile())
        val names = uiArtifactTools(repository, includeHtml = false).map { it.def.name }

        assertTrue(UiToolNames.WRITE_MARKDOWN in names)
        assertFalse(UiToolNames.WRITE_HTML in names)
        assertTrue(UiToolNames.KV in names)
        assertTrue(UiToolNames.CHOICE_FORM in names)
    }

    @Test
    fun `chart tool accepts combo chart contract`() {
        val spec = widgetFromToolCall(
            UiToolNames.CHART,
            """
            {
              "title":"收入与转化率",
              "startAxisTitle":"万元",
              "endAxisTitle":"%",
              "series":[
                {"name":"收入","kind":"BAR","points":[{"label":"Q1","value":25},{"label":"Q2","value":33}]},
                {"name":"转化率","kind":"LINE","axis":"END","points":[{"label":"Q1","value":12.5},{"label":"Q2","value":16.2}]}
              ],
              "annotations":[{"label":"活动上线","pointLabel":"Q2","value":16.2,"axis":"END"}]
            }
            """.trimIndent(),
        ) as ChartSpec

        assertEquals(2, spec.series.size)
        assertEquals(ChartKind.LINE, spec.series.last().kind)
        assertEquals(ChartAxis.END, spec.series.last().axis)
        assertEquals("活动上线", spec.annotations.single().label)
    }

    @Test
    fun `choice form tool parses single and multi questions`() {
        val spec = widgetFromToolCall(
            UiToolNames.CHOICE_FORM,
            """
            {
              "title":"确定研究方式",
              "taskAnchor":"根据用户当前目标确定研究路径",
              "submitLabel":"提交选择",
              "questions":[
                {"id":"path","kind":"SINGLE","title":"从哪里开始？","options":[{"id":"a","label":"架构总览"}]},
                {"id":"topics","kind":"MULTI","title":"关注什么？","options":[{"id":"ipc","label":"Binder"}]}
              ]
            }
            """.trimIndent(),
        ) as ChoiceFormSpec

        assertEquals(2, spec.questions.size)
        assertEquals(ChoiceKind.MULTI, spec.questions.last().kind)
        assertEquals("根据用户当前目标确定研究路径", spec.taskAnchor)
        assertEquals(null, spec.submittedAnswers)
    }
}
