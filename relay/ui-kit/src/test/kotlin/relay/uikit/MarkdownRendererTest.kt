package relay.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun `gfm table is parsed as structured rows`() {
        val blocks = MarkdownAst.render(
            """
            | 月份 | 投入 | 评分 |
            | :--: | --: | --: |
            | 3 月 | 28 | 62 |
            | 4 月 | 31 | 66 |
            """.trimIndent(),
        )

        val table = blocks.single().tableRows
        assertNotNull(table)
        assertEquals(listOf("月份", "投入", "评分"), table?.first()?.map { it.text })
        assertEquals(listOf("4 月", "31", "66"), table?.last()?.map { it.text })
    }
}
