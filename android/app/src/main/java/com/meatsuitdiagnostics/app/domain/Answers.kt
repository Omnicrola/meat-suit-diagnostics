package com.meatsuitdiagnostics.app.domain

import java.math.BigDecimal
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A complete answer to one question. JSON forms match what the server validates. */
sealed interface Answer {
    data class ScaleValue(val value: Int) : Answer
    data class YesNoValue(val value: Boolean) : Answer
    data class TextValue(val value: String) : Answer
    data class TimeValue(val hour: Int, val minute: Int) : Answer
    data class NumericValue(val values: Map<String, BigDecimal>) : Answer
    data class SingleValue(val key: String) : Answer
    data class MultiValue(val keys: List<String>) : Answer
}

fun Answer.toJson(): JsonElement = when (this) {
    is Answer.ScaleValue -> JsonPrimitive(value)
    is Answer.YesNoValue -> JsonPrimitive(value)
    is Answer.TextValue -> JsonPrimitive(value)
    is Answer.TimeValue -> JsonPrimitive(String.format(Locale.ROOT, "%02d:%02d", hour, minute))
    is Answer.NumericValue -> JsonObject(values.mapValues { (_, v) -> numberJson(v) })
    is Answer.SingleValue -> JsonPrimitive(key)
    is Answer.MultiValue -> JsonArray(keys.map { JsonPrimitive(it) })
}

/** Whole numbers as integers, others as decimals (never scientific notation). */
private fun numberJson(value: BigDecimal): JsonPrimitive {
    val stripped = value.stripTrailingZeros()
    return if (stripped.scale() <= 0) JsonPrimitive(stripped.toLong()) else JsonPrimitive(stripped.toDouble())
}

/** Reads an answer back from its JSON form (used for saved drafts). Returns null if it doesn't fit the spec. */
fun answerFromJson(spec: QuestionSpec, json: JsonElement): Answer? = runCatching {
    when (spec) {
        is QuestionSpec.Scale -> Answer.ScaleValue(json.jsonPrimitive.int)
        is QuestionSpec.YesNo -> Answer.YesNoValue(json.jsonPrimitive.boolean)
        is QuestionSpec.Text -> Answer.TextValue(json.jsonPrimitive.content)
        QuestionSpec.Time -> json.jsonPrimitive.content.split(":").let { Answer.TimeValue(it[0].toInt(), it[1].toInt()) }
        is QuestionSpec.Numeric -> Answer.NumericValue(json.jsonObject.mapValues { BigDecimal(it.value.jsonPrimitive.content) })
        is QuestionSpec.SingleSelect -> Answer.SingleValue(json.jsonPrimitive.content)
        is QuestionSpec.MultiSelect -> Answer.MultiValue(json.jsonArray.map { it.jsonPrimitive.content })
        is QuestionSpec.Unsupported -> null
    }
}.getOrNull()?.takeIf { spec.validate(it) == null }

/** Returns a message if the answer isn't valid for this question, mirroring the server's checks. */
fun QuestionSpec.validate(answer: Answer): String? = when {
    this is QuestionSpec.Scale && answer is Answer.ScaleValue -> when {
        answer.value !in min..max -> "Choose a value from $min to $max"
        (answer.value - min) % step != 0 -> "Choose a value in steps of $step"
        else -> null
    }
    this is QuestionSpec.YesNo && answer is Answer.YesNoValue -> null
    this is QuestionSpec.Text && answer is Answer.TextValue -> when {
        answer.value.isBlank() -> "Enter some text"
        answer.value.length > maxLength -> "At most $maxLength characters"
        else -> null
    }
    this is QuestionSpec.Time && answer is Answer.TimeValue ->
        if (answer.hour in 0..23 && answer.minute in 0..59) null else "Not a valid time"
    this is QuestionSpec.Numeric && answer is Answer.NumericValue -> when {
        answer.values.keys != fields.map { it.key }.toSet() -> "Fill in every field"
        else -> fields.firstNotNullOfOrNull { f -> numericFieldError(f, answer.values.getValue(f.key))?.let { "${f.label}: $it" } }
    }
    this is QuestionSpec.SingleSelect && answer is Answer.SingleValue ->
        if (options.any { it.key == answer.key }) null else "Choose one of the options"
    this is QuestionSpec.MultiSelect && answer is Answer.MultiValue -> when {
        answer.keys.toSet().size != answer.keys.size -> "Options must not repeat"
        !options.map { it.key }.containsAll(answer.keys) -> "Choose from the options"
        answer.keys.size < min -> "Choose at least $min"
        max != null && answer.keys.size > max -> "Choose at most $max"
        else -> null
    }
    else -> "Wrong kind of answer"
}

fun numericFieldError(field: NumericField, value: BigDecimal): String? = when {
    field.min != null && value < field.min.toBigDecimal() -> "at least ${formatNumber(field.min)}"
    field.max != null && value > field.max.toBigDecimal() -> "at most ${formatNumber(field.max)}"
    value.stripTrailingZeros().scale() > field.decimals ->
        if (field.decimals == 0) "whole numbers only" else "at most ${field.decimals} decimal places"
    else -> null
}

/** Parses what was typed into a numeric field: a value, an error, or neither (empty). */
data class NumericInput(val value: BigDecimal?, val error: String?)

fun parseNumericInput(field: NumericField, raw: String): NumericInput {
    val text = raw.trim().replace(',', '.')
    if (text.isEmpty()) return NumericInput(null, null)
    val value = text.toBigDecimalOrNull() ?: return NumericInput(null, "Not a number")
    numericFieldError(field, value)?.let { return NumericInput(null, it.replaceFirstChar(Char::uppercase)) }
    return NumericInput(value, null)
}

fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
