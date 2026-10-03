package com.meatsuitdiagnostics.app.ui.question

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meatsuitdiagnostics.app.domain.Answer
import com.meatsuitdiagnostics.app.domain.NumericField
import com.meatsuitdiagnostics.app.domain.Question
import com.meatsuitdiagnostics.app.domain.QuestionSpec
import com.meatsuitdiagnostics.app.domain.formatNumber
import com.meatsuitdiagnostics.app.domain.parseNumericInput
import com.meatsuitdiagnostics.app.domain.validate
import java.time.LocalTime
import kotlin.math.abs

/**
 * The input for one question. Reports a complete, valid answer through [onChange], or null while the input
 * is empty or invalid (which keeps "Next" disabled).
 */
@Composable
fun QuestionInput(question: Question, value: Answer?, onChange: (Answer?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        // key(): each question gets fresh local input state.
        key(question.id) {
            when (val spec = question.spec) {
                is QuestionSpec.Scale -> ScaleInput(spec, (value as? Answer.ScaleValue)?.value) { onChange(Answer.ScaleValue(it)) }
                is QuestionSpec.YesNo -> YesNoInput(spec, (value as? Answer.YesNoValue)?.value) { onChange(Answer.YesNoValue(it)) }
                is QuestionSpec.Text -> TextInput(spec, (value as? Answer.TextValue)?.value.orEmpty(), onChange)
                QuestionSpec.Time -> TimeInput(value as? Answer.TimeValue, onChange)
                is QuestionSpec.Numeric -> NumericInput(spec, value as? Answer.NumericValue, onChange)
                is QuestionSpec.SingleSelect -> SingleSelectInput(spec, (value as? Answer.SingleValue)?.key) { onChange(Answer.SingleValue(it)) }
                is QuestionSpec.MultiSelect -> MultiSelectInput(spec, value as? Answer.MultiValue, onChange)
                is QuestionSpec.Unsupported -> Text(
                    "This question needs a newer version of the app. Skip it for now.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleInput(spec: QuestionSpec.Scale, selected: Int?, onSelect: (Int) -> Unit) {
    val values = spec.values
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (values.size <= MAX_SCALE_BUTTONS) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                values.forEach { v ->
                    val modifier = Modifier.size(52.dp)
                    val padding = PaddingValues(0.dp)
                    if (v == selected) {
                        Button(onClick = { onSelect(v) }, modifier = modifier, contentPadding = padding) { Text("$v") }
                    } else {
                        OutlinedButton(onClick = { onSelect(v) }, modifier = modifier, contentPadding = padding) { Text("$v") }
                    }
                }
            }
        } else {
            var position by remember { mutableFloatStateOf((selected ?: values[values.size / 2]).toFloat()) }
            Text(selected?.toString() ?: "–", style = MaterialTheme.typography.displaySmall)
            Slider(
                value = position,
                onValueChange = { raw ->
                    position = raw
                    onSelect(values.minBy { abs(it - raw) })
                },
                valueRange = spec.min.toFloat()..spec.max.toFloat(),
                steps = values.size - 2,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(spec.minLabel ?: "${spec.min}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(spec.maxLabel ?: "${spec.max}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun YesNoInput(spec: QuestionSpec.YesNo, selected: Boolean?, onSelect: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(true to spec.trueLabel, false to spec.falseLabel).forEach { (value, label) ->
            val modifier = Modifier.weight(1f).height(64.dp)
            if (selected == value) {
                Button(onClick = { onSelect(value) }, modifier = modifier) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }, modifier = modifier) { Text(label) }
            }
        }
    }
}

@Composable
private fun TextInput(spec: QuestionSpec.Text, initial: String, onChange: (Answer?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            if (it.length <= spec.maxLength) {
                text = it
                onChange(if (it.isBlank()) null else Answer.TextValue(it))
            }
        },
        singleLine = !spec.multiline,
        minLines = if (spec.multiline) 4 else 1,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        supportingText = { Text("${text.length} / ${spec.maxLength}") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInput(value: Answer.TimeValue?, onChange: (Answer?) -> Unit) {
    val context = LocalContext.current
    val now = remember { LocalTime.now() }
    val state = rememberTimePickerState(
        initialHour = value?.hour ?: now.hour,
        initialMinute = value?.minute ?: now.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    // The picker always shows a time, so it always counts as answered.
    LaunchedEffect(state.hour, state.minute) { onChange(Answer.TimeValue(state.hour, state.minute)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TimePicker(state = state)
    }
}

@Composable
private fun NumericInput(spec: QuestionSpec.Numeric, value: Answer.NumericValue?, onChange: (Answer?) -> Unit) {
    val raw = remember {
        mutableStateMapOf<String, String>().apply {
            spec.fields.forEach { put(it.key, value?.values?.get(it.key)?.stripTrailingZeros()?.toPlainString().orEmpty()) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        spec.fields.forEachIndexed { index, field ->
            val text = raw[field.key].orEmpty()
            val parsed = parseNumericInput(field, text)
            val message = parsed.error ?: rangeHint(field)
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    raw[field.key] = input
                    val values = spec.fields.associate { it.key to parseNumericInput(it, raw[it.key].orEmpty()).value }
                    onChange(
                        if (values.values.all { it != null }) Answer.NumericValue(values.mapValues { it.value!! }) else null
                    )
                },
                label = { Text(field.label) },
                suffix = field.unit?.let { unit -> { Text(unit) } },
                isError = parsed.error != null,
                supportingText = message?.let { msg -> { Text(msg) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.decimals > 0) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = if (index == spec.fields.lastIndex) ImeAction.Done else ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun rangeHint(field: NumericField): String? {
    val range = when {
        field.min != null && field.max != null -> "${formatNumber(field.min)}–${formatNumber(field.max)}"
        field.min != null -> "At least ${formatNumber(field.min)}"
        field.max != null -> "At most ${formatNumber(field.max)}"
        else -> null
    }
    val decimals = if (field.decimals > 0) "up to ${field.decimals} decimal places" else null
    return listOfNotNull(range, decimals).joinToString(", ").ifEmpty { null }
}

@Composable
private fun SingleSelectInput(spec: QuestionSpec.SingleSelect, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        spec.options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = option.key == selected, onClick = { onSelect(option.key) }, role = Role.RadioButton)
                    .padding(vertical = 10.dp),
            ) {
                RadioButton(selected = option.key == selected, onClick = null)
                Text(option.label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MultiSelectInput(spec: QuestionSpec.MultiSelect, value: Answer.MultiValue?, onChange: (Answer?) -> Unit) {
    var selected by remember { mutableStateOf(value?.keys.orEmpty().toSet()) }
    // With no minimum, choosing nothing is a valid answer ("none of these").
    LaunchedEffect(Unit) { if (value == null && spec.min == 0) onChange(Answer.MultiValue(emptyList())) }

    Column {
        val hint = when {
            spec.min > 0 && spec.max != null -> "Choose ${spec.min} to ${spec.max}"
            spec.min > 0 -> "Choose at least ${spec.min}"
            spec.max != null -> "Choose up to ${spec.max}"
            else -> "Choose any that apply"
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        spec.options.forEach { option ->
            val checked = option.key in selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { isChecked ->
                        selected = if (isChecked) selected + option.key else selected - option.key
                        // Keep the options' own order.
                        val answer = Answer.MultiValue(spec.options.map { it.key }.filter { it in selected })
                        onChange(answer.takeIf { spec.validate(it) == null })
                    })
                    .padding(vertical = 10.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(option.label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private const val MAX_SCALE_BUTTONS = 12
