package relay.uikit

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GraphWidget(
    spec: GraphSpec,
    modifier: Modifier = Modifier,
    onFocus: (String) -> Unit = {},
    onResearch: (String) -> Unit = {},
) {
    var query by remember(spec.sourceId) { mutableStateOf("") }
    var labels by remember(spec.sourceId) { mutableStateOf(spec.showPredicates) }
    var focusId by remember(spec.sourceId) { mutableStateOf(spec.focusId ?: spec.nodes.firstOrNull()?.id) }
    val chapters = remember(spec.edges) { spec.edges.mapNotNull { it.chapter }.distinct().sorted() }
    var chapter by remember(spec.sourceId) { mutableStateOf(spec.chapter ?: chapters.lastOrNull()) }
    var scale by remember(spec.sourceId) { mutableFloatStateOf(1f) }
    var pan by remember(spec.sourceId) { mutableStateOf(Offset.Zero) }
    val chapterEdges = remember(spec.edges, chapter) {
        chapter?.let { selected -> spec.edges.filter { it.chapter == null || it.chapter <= selected } } ?: spec.edges
    }
    val filtered = remember(spec, chapterEdges, query, focusId) {
        val matchingIds = if (query.isBlank()) {
            spec.nodes.map { it.id }.toSet()
        } else {
            spec.nodes.filter { it.label.contains(query, ignoreCase = true) || it.kind.contains(query, ignoreCase = true) }
                .map { it.id }.toSet()
        }
        val neighborhood = focusId?.let { focus ->
            chapterEdges.flatMap { edge ->
                when (focus) {
                    edge.source -> listOf(edge.source, edge.target)
                    edge.target -> listOf(edge.source, edge.target)
                    else -> emptyList()
                }
            }.toSet() + focus
        } ?: matchingIds
        spec.nodes.filter { it.id in matchingIds && (query.isNotBlank() || it.id in neighborhood) }
    }
    val nodeIds = filtered.map { it.id }.toSet()
    val edges = chapterEdges.filter { it.source in nodeIds && it.target in nodeIds }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (spec.title.isNotBlank()) Text(spec.title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = labels, onClick = { labels = !labels }, label = { Text("关系标签") })
            TextButton(onClick = {
                scale = 1f
                pan = Offset.Zero
            }) { Text("复位") }
        }
        if (chapters.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chapters.forEach { value ->
                    FilterChip(
                        selected = chapter == value,
                        onClick = { chapter = value },
                        label = { Text("第${value}回") },
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("筛选节点") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val nodeColor = MaterialTheme.colorScheme.primary
        val focusColor = MaterialTheme.colorScheme.tertiary
        val edgeColor = MaterialTheme.colorScheme.outline
        val textColor = MaterialTheme.colorScheme.onSurface
        Canvas(
            Modifier.fillMaxWidth().height(320.dp)
                .pointerInput(spec.sourceId) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                scale = (scale * event.calculateZoom()).coerceIn(0.6f, 3.5f)
                                pan += event.calculatePan()
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(filtered, scale, pan) {
                    fun hitNode(tap: Offset): String? {
                        val layout = graphLayout(filtered, focusId, size.width.toFloat(), size.height.toFloat())
                        val hit = layout.minByOrNull { (_, point) ->
                            val shown = (point - Offset(size.width / 2f, size.height / 2f)) * scale +
                                Offset(size.width / 2f, size.height / 2f) + pan
                            (shown - tap).getDistance()
                        }
                        if (hit != null) {
                            val point = (hit.value - Offset(size.width / 2f, size.height / 2f)) * scale +
                                Offset(size.width / 2f, size.height / 2f) + pan
                            if ((point - tap).getDistance() <= 36.dp.toPx()) {
                                return hit.key
                            }
                        }
                        return null
                    }
                    detectTapGestures(
                        onLongPress = { tap ->
                            hitNode(tap)?.let(onResearch)
                        },
                        onTap = { tap ->
                            hitNode(tap)?.let {
                                focusId = it
                                onFocus(it)
                            }
                        },
                    )
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val layout = graphLayout(filtered, focusId, size.width, size.height)
            fun project(point: Offset): Offset = (point - center) * scale + center + pan
            edges.forEach { edge ->
                val start = layout[edge.source]?.let(::project) ?: return@forEach
                val end = layout[edge.target]?.let(::project) ?: return@forEach
                drawLine(edgeColor, start, end, strokeWidth = 2f)
                if (labels) {
                    drawIntoCanvas { canvas ->
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = textColor.toArgb()
                            textSize = 11.dp.toPx()
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.nativeCanvas.drawText(
                            edge.predicate,
                            (start.x + end.x) / 2,
                            (start.y + end.y) / 2 - 5.dp.toPx(),
                            paint,
                        )
                    }
                }
            }
            filtered.forEach { node ->
                val point = layout[node.id]?.let(::project) ?: return@forEach
                drawCircle(if (node.id == focusId) focusColor else nodeColor, 18.dp.toPx() * scale.coerceAtMost(1.4f), point)
                drawIntoCanvas { canvas ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = textColor.toArgb()
                        textSize = 12.dp.toPx()
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(node.label, point.x, point.y + 34.dp.toPx(), paint)
                }
            }
        }
        if (spec.claims.isNotEmpty()) {
            Text("Claim", style = MaterialTheme.typography.labelLarge)
            spec.claims.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (filtered.isEmpty()) Text("没有匹配节点", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun graphLayout(
    nodes: List<GraphNode>,
    focusId: String?,
    width: Float,
    height: Float,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    val center = Offset(width / 2f, height / 2f)
    val ordered = nodes.sortedWith(
        compareBy<GraphNode> { it.id != focusId }
            .thenBy { it.kind }
            .thenBy { it.id },
    )
    val focus = ordered.first()
    val others = ordered.drop(1)
    val radius = minOf(width, height) * 0.34f
    return buildMap {
        put(focus.id, center)
        others.forEachIndexed { index, node ->
            val angle = 2.0 * PI * index / others.size.coerceAtLeast(1) - PI / 2
            put(node.id, center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat()))
        }
    }
}

private fun Color.toArgb(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
