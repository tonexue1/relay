package relay.uikit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownAst.render(markdown) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            if (block.tableRows != null) {
                MarkdownTable(block.tableRows)
            } else {
                Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    fontFamily = if (block.code) FontFamily.Monospace else null,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<AnnotatedString>>) {
    Column(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        rows.forEachIndexed { rowIndex, cells ->
            Row {
                cells.forEach { cell ->
                    Surface(
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else null,
                            modifier = Modifier.widthIn(min = 96.dp, max = 180.dp).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

internal data class MarkdownBlock(
    val text: AnnotatedString,
    val level: Int = 0,
    val code: Boolean = false,
    val tableRows: List<List<AnnotatedString>>? = null,
)

internal object MarkdownAst {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()

    fun render(markdown: String): List<MarkdownBlock> {
        val document = parser.parse(markdown)
        val output = mutableListOf<MarkdownBlock>()
        var child = document.firstChild
        while (child != null) {
            when (child) {
                is Heading -> output += MarkdownBlock(inline(child), level = child.level)
                is FencedCodeBlock -> output += MarkdownBlock(
                    AnnotatedString(child.literal.trimEnd()),
                    code = true,
                )
                is BulletList -> {
                    var item = child.firstChild
                    while (item != null) {
                        output += MarkdownBlock(buildAnnotatedString {
                            append("• ")
                            append(inline(item))
                        })
                        item = item.next
                    }
                }
                is TableBlock -> output += MarkdownBlock(
                    text = AnnotatedString(""),
                    tableRows = tableRows(child),
                )
                is Paragraph -> output += MarkdownBlock(inline(child))
                else -> {
                    val text = inline(child)
                    if (text.isNotBlank()) output += MarkdownBlock(text)
                }
            }
            child = child.next
        }
        return output.ifEmpty { listOf(MarkdownBlock(AnnotatedString(""))) }
    }

    private fun tableRows(table: TableBlock): List<List<AnnotatedString>> {
        val rows = mutableListOf<List<AnnotatedString>>()
        fun collect(node: Node) {
            if (node is TableRow) {
                val cells = mutableListOf<AnnotatedString>()
                var cell = node.firstChild
                while (cell != null) {
                    if (cell is TableCell) cells += inline(cell)
                    cell = cell.next
                }
                rows += cells
                return
            }
            var child = node.firstChild
            while (child != null) {
                collect(child)
                child = child.next
            }
        }
        collect(table)
        return rows
    }

    private fun inline(node: Node): AnnotatedString = buildAnnotatedString {
        node.accept(object : AbstractVisitor() {
            override fun visit(text: MarkdownText) {
                append(text.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                append('\n')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                append('\n')
            }

            override fun visit(code: Code) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(code.literal)
                pop()
            }

            override fun visit(emphasis: Emphasis) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                visitChildren(emphasis)
                pop()
            }

            override fun visit(strongEmphasis: StrongEmphasis) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                visitChildren(strongEmphasis)
                pop()
            }

            override fun visit(link: Link) {
                pushStringAnnotation("URL", link.destination)
                pushStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xff3f51b5)))
                visitChildren(link)
                pop()
                pop()
            }

            override fun visit(listItem: ListItem) {
                visitChildren(listItem)
            }

            override fun visit(paragraph: Paragraph) {
                visitChildren(paragraph)
            }
        })
    }
}
