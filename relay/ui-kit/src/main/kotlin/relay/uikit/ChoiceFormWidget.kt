package relay.uikit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ChoiceFormWidget(
    spec: ChoiceFormSpec,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSubmit: (Map<String, List<String>>) -> Unit = {},
) {
    var questionIndex by remember(spec.sourceId, spec.questions) { mutableIntStateOf(0) }
    var answers by remember(spec.sourceId, spec.questions) {
        mutableStateOf(spec.questions.associate { it.id to emptyList<String>() })
    }
    var localSubmission by remember(spec.sourceId, spec.submittedAnswers) {
        mutableStateOf<Map<String, List<String>>?>(null)
    }
    var error by remember(spec.sourceId) { mutableStateOf("") }
    val submitted = spec.submittedAnswers ?: localSubmission

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        if (submitted != null) {
            SubmittedChoiceForm(spec, submitted)
        } else {
            val question = spec.questions[questionIndex]
            Column {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${questionIndex + 1} / ${spec.questions.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (questionIndex + 1f) / spec.questions.size },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        question.title,
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        question.hint.ifBlank {
                            if (question.kind == ChoiceKind.MULTI) "多选" else "单选"
                        },
                        modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        question.options.forEach { option ->
                            val selected = option.id in answers.getValue(question.id)
                            ChoiceOptionRow(
                                option = option,
                                kind = question.kind,
                                selected = selected,
                                enabled = enabled,
                                onClick = {
                                    error = ""
                                    answers = answers + (
                                        question.id to when (question.kind) {
                                            ChoiceKind.SINGLE -> listOf(option.id)
                                            ChoiceKind.MULTI -> if (selected) {
                                                answers.getValue(question.id) - option.id
                                            } else {
                                                answers.getValue(question.id) + option.id
                                            }
                                        }
                                    )
                                },
                            )
                        }
                    }
                    if (error.isNotBlank()) {
                        Text(
                            error,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { questionIndex--; error = "" },
                        enabled = enabled && questionIndex > 0,
                    ) { Text("上一步") }
                    Box(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (question.required && answers.getValue(question.id).isEmpty()) {
                                error = if (question.kind == ChoiceKind.MULTI) "请至少选择一项" else "请选择一项后继续"
                            } else if (questionIndex < spec.questions.lastIndex) {
                                questionIndex++
                                error = ""
                            } else {
                                val submission = answers.filterValues { it.isNotEmpty() }
                                localSubmission = submission
                                onSubmit(submission)
                            }
                        },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text(if (questionIndex == spec.questions.lastIndex) spec.submitLabel else "下一步")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceOptionRow(
    option: ChoiceOption,
    kind: ChoiceKind,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = if (kind == ChoiceKind.SINGLE) CircleShape else RoundedCornerShape(6.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selected) Text("✓", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (option.description.isNotBlank()) {
                    Text(
                        option.description,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (option.recommended) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(99.dp)) {
                    Text(
                        "推荐",
                        Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubmittedChoiceForm(
    spec: ChoiceFormSpec,
    answers: Map<String, List<String>>,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(34.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✓", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
                }
            }
            Column {
                Text("选择已提交", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(spec.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.questions.forEach { question ->
                    val labels = answers[question.id].orEmpty().mapNotNull { answerId ->
                        question.options.firstOrNull { it.id == answerId }?.label
                    }
                    if (labels.isNotEmpty()) {
                        Column {
                            Text(
                                question.title,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(labels.joinToString("、"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
