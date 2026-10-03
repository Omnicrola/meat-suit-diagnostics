package com.meatsuitdiagnostics.app.ui.common

import com.meatsuitdiagnostics.app.domain.Answer
import com.meatsuitdiagnostics.app.domain.QuestionSpec
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val shortTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val shortDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatTime(instant: Instant): String = shortTime.format(instant.atZone(ZoneId.systemDefault()))

fun formatTime(epochMillis: Long): String = formatTime(Instant.ofEpochMilli(epochMillis))

/** "Today 08:00", "Tomorrow 08:00", "Wed 08:00", or a date for anything further away. */
fun formatDayAndTime(instant: Instant, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val date = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val day = when {
        date == today -> "Today"
        date == today.plusDays(1) -> "Tomorrow"
        date.isAfter(today) && date.isBefore(today.plusDays(7)) ->
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> shortDate.format(date)
    }
    return "$day ${formatTime(instant)}"
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun formatAgo(epochMillis: Long, now: Instant = Instant.now()): String {
    val elapsed = Duration.between(Instant.ofEpochMilli(epochMillis), now)
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} min ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()} h ago"
        elapsed.toDays() == 1L -> "yesterday"
        else -> "${elapsed.toDays()} days ago"
    }
}

/** A one-line description of an answer, for the review page. */
fun answerSummary(spec: QuestionSpec, answer: Answer): String = when (answer) {
    is Answer.ScaleValue -> answer.value.toString()
    is Answer.YesNoValue -> (spec as? QuestionSpec.YesNo)
        ?.let { if (answer.value) it.trueLabel else it.falseLabel }
        ?: if (answer.value) "Yes" else "No"
    is Answer.TextValue -> answer.value
    is Answer.TimeValue -> shortTime.format(LocalTime.of(answer.hour, answer.minute))
    is Answer.NumericValue -> (spec as? QuestionSpec.Numeric)?.fields.orEmpty().joinToString(", ") { field ->
        val number = answer.values[field.key]?.stripTrailingZeros()?.toPlainString() ?: "?"
        "${field.label} $number${field.unit?.let { " $it" }.orEmpty()}"
    }
    is Answer.SingleValue -> optionLabel(spec, answer.key)
    is Answer.MultiValue ->
        if (answer.keys.isEmpty()) "None" else answer.keys.joinToString(", ") { optionLabel(spec, it) }
}

private fun optionLabel(spec: QuestionSpec, key: String): String {
    val options = when (spec) {
        is QuestionSpec.SingleSelect -> spec.options
        is QuestionSpec.MultiSelect -> spec.options
        else -> emptyList()
    }
    return options.firstOrNull { it.key == key }?.label ?: key
}
