package relay.uikit

internal data class ChartRenderSeries(
    val name: String,
    val kind: ChartKind,
    val axis: ChartAxis,
    val values: List<Double>,
)

internal data class ChartRenderAnnotation(
    val label: String,
    val categoryIndex: Int,
    val value: Double?,
    val axis: ChartAxis,
)

internal data class ChartRenderModel(
    val categories: List<String>,
    val series: List<ChartRenderSeries>,
    val stacked: Boolean,
    val annotations: List<ChartRenderAnnotation>,
) {
    val columnSeries: List<ChartRenderSeries> get() = series.filter { it.kind == ChartKind.BAR }
    val lineSeries: List<ChartRenderSeries> get() = series.filter { it.kind == ChartKind.LINE }
    val usesEndAxis: Boolean get() = series.any { it.axis == ChartAxis.END }
}

internal fun ChartSpec.toRenderModel(): ChartRenderModel {
    val effectiveSeries = series.ifEmpty {
        listOf(ChartSeries(title.ifBlank { kind.name.lowercase() }, kind, points))
    }
    val categories = effectiveSeries.firstOrNull()?.points?.map(ChartPoint::label).orEmpty()
    return ChartRenderModel(
        categories = categories,
        series = effectiveSeries.map { item ->
            ChartRenderSeries(item.name, item.kind, item.axis, item.points.map(ChartPoint::value))
        },
        stacked = stacked,
        annotations = annotations.map { annotation ->
            ChartRenderAnnotation(
                label = annotation.label,
                categoryIndex = categories.indexOf(annotation.pointLabel),
                value = annotation.value,
                axis = annotation.axis,
            )
        },
    )
}
