package com.meatsuitdiagnostics.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class Option(val key: String, val label: String)

@Serializable
data class NumericField(
    val key: String,
    val label: String,
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val decimals: Int = 0,
)

/** A question's answer type and settings. Mirrors server/app/question_types.py. */
sealed interface QuestionSpec {

    @Serializable
    data class Scale(
        val min: Int,
        val max: Int,
        val step: Int = 1,
        @SerialName("min_label") val minLabel: String? = null,
        @SerialName("max_label") val maxLabel: String? = null,
    ) : QuestionSpec {
        val values: List<Int> get() = (min..max step step).toList()
    }

    @Serializable
    data class YesNo(
        @SerialName("true_label") val trueLabel: String = "Yes",
        @SerialName("false_label") val falseLabel: String = "No",
    ) : QuestionSpec

    @Serializable
    data class Text(
        val multiline: Boolean = false,
        @SerialName("max_length") val maxLength: Int = 1000,
    ) : QuestionSpec

    data object Time : QuestionSpec

    @Serializable
    data class Numeric(val fields: List<NumericField>) : QuestionSpec

    @Serializable
    data class SingleSelect(val options: List<Option>) : QuestionSpec

    @Serializable
    data class MultiSelect(val options: List<Option>, val min: Int = 0, val max: Int? = null) : QuestionSpec

    /** A type this version of the app doesn't understand (the server is newer). It can only be skipped. */
    data class Unsupported(val type: String) : QuestionSpec
}

data class Question(val id: Int, val version: Int, val text: String, val spec: QuestionSpec)

private val configJson = Json { ignoreUnknownKeys = true }

fun parseSpec(type: String, config: JsonObject): QuestionSpec = try {
    when (type) {
        "scale" -> configJson.decodeFromJsonElement(QuestionSpec.Scale.serializer(), config)
        "boolean" -> configJson.decodeFromJsonElement(QuestionSpec.YesNo.serializer(), config)
        "text" -> configJson.decodeFromJsonElement(QuestionSpec.Text.serializer(), config)
        "time" -> QuestionSpec.Time
        "numeric" -> configJson.decodeFromJsonElement(QuestionSpec.Numeric.serializer(), config)
        "single_select" -> configJson.decodeFromJsonElement(QuestionSpec.SingleSelect.serializer(), config)
        "multi_select" -> configJson.decodeFromJsonElement(QuestionSpec.MultiSelect.serializer(), config)
        else -> QuestionSpec.Unsupported(type)
    }
} catch (e: IllegalArgumentException) { // includes SerializationException
    QuestionSpec.Unsupported(type)
}
