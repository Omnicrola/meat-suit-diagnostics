package com.meatsuitdiagnostics.app.data.api

import com.meatsuitdiagnostics.app.domain.Question
import com.meatsuitdiagnostics.app.domain.parseSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// JSON shapes of the server API (docs/DESIGN.md section 6).

@Serializable
data class QuestionDto(
    val id: Int,
    val version: Int,
    val text: String,
    val type: String,
    val config: JsonObject,
) {
    fun toQuestion() = Question(id, version, text, parseSpec(type, config))
}

@Serializable
data class CheckinDto(
    val id: Int,
    val name: String,
    @SerialName("time_local") val timeLocal: String,
    @SerialName("days_of_week") val daysOfWeek: List<Int>,
    @SerialName("expires_after_minutes") val expiresAfterMinutes: Int,
    @SerialName("question_ids") val questionIds: List<Int>,
)

@Serializable
data class ConfigDto(
    val version: Int,
    val questions: List<QuestionDto>,
    val checkins: List<CheckinDto>,
)

/** One response as uploaded. Built when an answer is saved and stored in the outbox until the server has it. */
@Serializable
data class ResponsePayload(
    val id: String,
    @SerialName("question_id") val questionId: Int,
    @SerialName("question_version") val questionVersion: Int,
    @SerialName("checkin_id") val checkinId: Int? = null,
    @SerialName("instance_id") val instanceId: String? = null,
    val status: String,
    val value: JsonElement? = null,
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("answered_at") val answeredAt: String,
)

@Serializable
data class RejectedDto(val id: String? = null, val error: String)

@Serializable
data class UploadResultDto(
    val accepted: List<String>,
    val duplicates: List<String>,
    val rejected: List<RejectedDto>,
)

object ResponseStatus {
    const val ANSWERED = "answered"
    const val SKIPPED = "skipped"
    const val MISSED = "missed"
}
