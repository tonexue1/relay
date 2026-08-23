package relay.uikit

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ChartRenderModelTest {
    @Test
    fun `legacy points normalize to one render series`() {
        val model = ChartSpec(
            kind = ChartKind.LINE,
            points = listOf(ChartPoint("一", 12.0), ChartPoint("二", 18.0)),
        ).toRenderModel()

        assertEquals(listOf("一", "二"), model.categories)
        assertEquals(ChartKind.LINE, model.series.single().kind)
        assertEquals(listOf(12.0, 18.0), model.series.single().values)
    }

    @Test
    fun `combo chart separates layers axes and annotations`() {
        val points = listOf(ChartPoint("Q1", 25.0), ChartPoint("Q2", 33.0))
        val model = ChartSpec(
            series = listOf(
                ChartSeries("收入", ChartKind.BAR, points),
                ChartSeries(
                    "转化率",
                    ChartKind.LINE,
                    listOf(ChartPoint("Q1", 12.5), ChartPoint("Q2", 16.2)),
                    ChartAxis.END,
                ),
            ),
            annotations = listOf(ChartAnnotation("活动上线", "Q2", 16.2, ChartAxis.END)),
        ).toRenderModel()

        assertEquals(listOf("Q1", "Q2"), model.categories)
        assertEquals(listOf("收入"), model.columnSeries.map { it.name })
        assertEquals(listOf("转化率"), model.lineSeries.map { it.name })
        assertTrue(model.usesEndAxis)
        assertEquals(1, model.annotations.single().categoryIndex)
    }
}
