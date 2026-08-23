package relay.uikit

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart

@Composable
fun WidgetHost(
    spec: WidgetSpec,
    modifier: Modifier = Modifier,
    onOpenArtifact: (FileSpec) -> Unit = {},
    onGraphFocus: (String) -> Unit = {},
    onChoiceFormSubmit: (ChoiceFormSpec, Map<String, List<String>>) -> Unit = { _, _ -> },
    choiceFormEnabled: Boolean = true,
) {
    var expanded by remember(spec.sourceId, spec.summary()) { mutableStateOf(false) }
    Column(modifier) {
        WidgetBody(spec, Modifier, onOpenArtifact, onGraphFocus, onChoiceFormSubmit, choiceFormEnabled)
        if (spec.display == DisplayMode.CANVAS) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.End)) {
                Text("展开")
            }
        }
    }
    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = { expanded = false }) { Text("关闭") }
                    WidgetBody(
                        spec,
                        Modifier.weight(1f),
                        onOpenArtifact,
                        onGraphFocus,
                        onChoiceFormSubmit,
                        choiceFormEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetBody(
    spec: WidgetSpec,
    modifier: Modifier,
    onOpenArtifact: (FileSpec) -> Unit,
    onGraphFocus: (String) -> Unit,
    onChoiceFormSubmit: (ChoiceFormSpec, Map<String, List<String>>) -> Unit,
    choiceFormEnabled: Boolean,
) {
    when (spec) {
        is MarkdownSpec -> MarkdownRenderer(spec.markdown, modifier)
        is KvSpec -> KvWidget(spec, modifier)
        is TableSpec -> TableWidget(spec, modifier)
        is CardSpec -> ContentCard(spec, modifier)
        is ChoiceFormSpec -> ChoiceFormWidget(
            spec,
            modifier,
            enabled = choiceFormEnabled,
            onSubmit = { onChoiceFormSubmit(spec, it) },
        )
        is ChartSpec -> ChartWidget(spec, modifier)
        is GraphSpec -> GraphWidget(spec, modifier, onGraphFocus)
        is FileSpec -> FileWidget(spec, modifier, onOpenArtifact)
        is ListSpec -> ListWidget(spec, modifier)
        is ImageSpec -> ImageWidget(spec, modifier)
        is FallbackSpec -> FallbackWidget(spec, modifier)
    }
}

@Composable
private fun KvWidget(spec: KvSpec, modifier: Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (spec.title.isNotBlank()) Text(spec.title, style = MaterialTheme.typography.titleSmall)
            spec.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.key, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.value)
                }
            }
        }
    }
}

@Composable
private fun TableWidget(spec: TableSpec, modifier: Modifier) {
    var selected by remember(spec) { mutableStateOf<List<String>?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (spec.title.isNotBlank()) Text(spec.title, style = MaterialTheme.typography.titleSmall)
        Column(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(8.dp),
        ) {
            TableRow(spec.columns, header = true)
            spec.rows.forEach { row -> TableRow(row, onClick = { selected = row }) }
        }
        selected?.let { row ->
            Text(
                spec.columns.zip(row).joinToString(" · ") { (column, value) -> "$column: $value" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        cells.forEach { value ->
            Text(
                value,
                modifier = Modifier.size(width = 132.dp, height = 40.dp).padding(6.dp),
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ContentCard(spec: CardSpec, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(spec.title, style = MaterialTheme.typography.titleMedium)
            if (spec.subtitle.isNotBlank()) {
                Text(spec.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (spec.body.isNotBlank()) MarkdownRenderer(spec.body)
        }
    }
}

@Composable
private fun ChartWidget(spec: ChartSpec, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (spec.title.isNotBlank()) Text(spec.title, style = MaterialTheme.typography.titleSmall)
        if (spec.kind == ChartKind.PIE) {
            PieChart(spec, Modifier.fillMaxWidth().height(220.dp))
        } else {
            val model = remember(spec) { spec.toRenderModel() }
            val producer = remember { CartesianChartModelProducer() }
            LaunchedEffect(model) {
                producer.runTransaction {
                    if (model.columnSeries.isNotEmpty()) {
                        columnModel {
                            model.columnSeries.forEach { series(it.values) }
                        }
                    }
                    if (model.lineSeries.isNotEmpty()) {
                        lineModel {
                            model.lineSeries.forEach { series(it.values) }
                        }
                    }
                }
            }
            val columnLayer = if (model.columnSeries.isNotEmpty()) {
                val axis = model.columnSeries.first().axis
                rememberColumnCartesianLayer(
                    mergeMode = {
                        if (model.stacked) ColumnCartesianLayer.MergeMode.Stacked
                        else ColumnCartesianLayer.MergeMode.Grouped()
                    },
                    verticalAxisPosition = axis.toVicoPosition(),
                )
            } else {
                null
            }
            val lineLayer = if (model.lineSeries.isNotEmpty()) {
                val axis = model.lineSeries.first().axis
                rememberLineCartesianLayer(verticalAxisPosition = axis.toVicoPosition())
            } else {
                null
            }
            val startAxis = VerticalAxis.rememberStart()
            val endAxis = if (model.usesEndAxis) VerticalAxis.rememberEnd() else null
            val bottomAxis = HorizontalAxis.rememberBottom()
            val chart = when {
                columnLayer != null && lineLayer != null -> rememberCartesianChart(
                    columnLayer,
                    lineLayer,
                    startAxis = startAxis,
                    endAxis = endAxis,
                    bottomAxis = bottomAxis,
                )
                columnLayer != null -> rememberCartesianChart(
                    columnLayer,
                    startAxis = startAxis,
                    endAxis = endAxis,
                    bottomAxis = bottomAxis,
                )
                else -> rememberCartesianChart(
                    requireNotNull(lineLayer),
                    startAxis = startAxis,
                    endAxis = endAxis,
                    bottomAxis = bottomAxis,
                )
            }
            CartesianChartHost(chart, producer, Modifier.fillMaxWidth().height(220.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                model.categories.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                model.series.forEach { item ->
                    Text(
                        "${item.name} · ${item.kind.name.lowercase()} · ${item.axis.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (spec.startAxisTitle.isNotBlank() || spec.endAxisTitle.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(spec.startAxisTitle, style = MaterialTheme.typography.labelSmall)
                    Text(spec.endAxisTitle, style = MaterialTheme.typography.labelSmall)
                }
            }
            spec.annotations.forEach { annotation ->
                Text(
                    "• ${annotation.label} · ${annotation.pointLabel}" +
                        annotation.value?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (spec.series.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                spec.points.forEach { Text("${it.label} ${it.value}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

private fun ChartAxis.toVicoPosition(): Axis.Position.Vertical =
    when (this) {
        ChartAxis.START -> Axis.Position.Vertical.Start
        ChartAxis.END -> Axis.Position.Vertical.End
    }

@Composable
private fun PieChart(spec: ChartSpec, modifier: Modifier) {
    val colors = listOf(Color(0xff735751), Color(0xff638475), Color(0xffc68b59), Color(0xff716b9e))
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val total = spec.points.sumOf { it.value.coerceAtLeast(0.0) }.takeIf { it > 0 } ?: 1.0
        var start = -90f
        spec.points.forEachIndexed { index, point ->
            val sweep = (point.value.coerceAtLeast(0.0) / total * 360).toFloat()
            drawArc(colors[index % colors.size], start, sweep, useCenter = true)
            start += sweep
        }
        drawCircle(surface, radius = size.minDimension * 0.18f, center = center)
        drawCircle(outline, radius = size.minDimension * 0.42f, center = center, style = Stroke(1f))
    }
}

@Composable
private fun FileWidget(spec: FileSpec, modifier: Modifier, onOpen: (FileSpec) -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(spec.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${spec.mime} · v${spec.artifactVersion} · ${spec.status}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (spec.summaryText.isNotBlank()) Text(spec.summaryText, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onOpen(spec) }, enabled = spec.status == "ready") { Text("查看") }
        }
    }
}

@Composable
private fun ListWidget(spec: ListSpec, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (spec.title.isNotBlank()) Text(spec.title, style = MaterialTheme.typography.titleSmall)
        spec.items.forEach { Text("• $it") }
    }
}

@Composable
private fun ImageWidget(spec: ImageSpec, modifier: Modifier) {
    val bitmap = remember(spec.uri) {
        runCatching {
            if (!spec.uri.startsWith("data:image/")) return@runCatching null
            val bytes = Base64.decode(spec.uri.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Column(modifier.fillMaxWidth()) {
        if (bitmap != null) {
            Image(bitmap, spec.alt, Modifier.fillMaxWidth())
        } else {
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(spec.alt) }
        }
        if (spec.caption.isNotBlank()) Text(spec.caption, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FallbackWidget(spec: FallbackSpec, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(spec.reason, color = MaterialTheme.colorScheme.error)
            if (spec.rawSummary.isNotBlank()) Text(spec.rawSummary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
