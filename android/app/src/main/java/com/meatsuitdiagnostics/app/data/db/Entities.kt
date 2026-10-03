package com.meatsuitdiagnostics.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.meatsuitdiagnostics.app.data.api.QuestionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The current version of a question, as last received from the server. */
@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: Int,
    val version: Int,
    val text: String,
    val type: String,
    val configJson: String,
) {
    fun toDto() = QuestionDto(id, version, text, type, Json.parseToJsonElement(configJson).jsonObject)
    fun toQuestion() = toDto().toQuestion()

    companion object {
        fun from(dto: QuestionDto) = QuestionEntity(dto.id, dto.version, dto.text, dto.type, dto.config.toString())
    }
}

@Entity(tableName = "checkins")
data class CheckinEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val timeLocal: String,
    val daysOfWeek: List<Int>,
    val expiresAfterMinutes: Int,
    val questionIds: List<Int>,
)

/**
 * One occurrence of a check-in on this phone. Holds a snapshot of the questions as they were when it fired,
 * so a config change while it's open doesn't change what's being answered.
 */
@Entity(tableName = "instances", indices = [Index(value = ["checkinId", "scheduledFor"], unique = true)])
data class InstanceEntity(
    @PrimaryKey val id: String,
    val checkinId: Int,
    val checkinName: String,
    val scheduledFor: Long, // epoch millis
    val expiresAt: Long,
    val status: String,
    val snoozed: Boolean = false,
    val snoozedUntil: Long? = null,
    val questionCount: Int,
    val questionsJson: String, // JSON array of QuestionDto
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SNOOZED = "snoozed"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_EXPIRED = "expired"
    }
}

// An extension rather than a member so Room doesn't mistake it for a column.
val InstanceEntity.isOpen: Boolean
    get() = status == InstanceEntity.STATUS_PENDING || status == InstanceEntity.STATUS_SNOOZED

/** An answer in progress, saved as the user moves through a check-in so it survives the app being closed. */
@Entity(tableName = "drafts", primaryKeys = ["instanceId", "questionId"])
data class DraftEntity(
    val instanceId: String,
    val questionId: Int,
    val valueJson: String?,
    val skipped: Boolean,
)

/** A response waiting to be uploaded. Rows with an error were rejected by the server and need attention. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAt: Long,
    val error: String? = null,
)

class Converters {
    @TypeConverter
    fun intListToString(values: List<Int>): String = values.joinToString(",")

    @TypeConverter
    fun stringToIntList(value: String): List<Int> =
        if (value.isEmpty()) emptyList() else value.split(",").map(String::toInt)
}
