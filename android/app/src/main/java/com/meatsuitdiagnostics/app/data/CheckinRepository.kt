package com.meatsuitdiagnostics.app.data

import androidx.room.withTransaction
import com.meatsuitdiagnostics.app.data.api.QuestionDto
import com.meatsuitdiagnostics.app.data.api.ResponsePayload
import com.meatsuitdiagnostics.app.data.api.ResponseStatus
import com.meatsuitdiagnostics.app.data.db.AppDatabase
import com.meatsuitdiagnostics.app.data.db.DraftEntity
import com.meatsuitdiagnostics.app.data.db.InstanceEntity
import com.meatsuitdiagnostics.app.data.db.OutboxEntity
import com.meatsuitdiagnostics.app.data.db.isOpen
import com.meatsuitdiagnostics.app.domain.Answer
import com.meatsuitdiagnostics.app.domain.Question
import com.meatsuitdiagnostics.app.domain.Schedule
import com.meatsuitdiagnostics.app.domain.answerFromJson
import com.meatsuitdiagnostics.app.domain.toJson
import com.meatsuitdiagnostics.app.scheduling.AlarmScheduler
import com.meatsuitdiagnostics.app.scheduling.Notifier
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** How the user left a question: answered, or deliberately skipped. */
sealed interface AnswerState {
    data class Answered(val answer: Answer) : AnswerState
    data object Skipped : AnswerState
}

data class OpenInstance(
    val instance: InstanceEntity,
    val questions: List<Question>,
    val drafts: Map<Int, AnswerState>,
)

/** The life of a check-in on the phone: fired by an alarm, snoozed, answered, or expired. */
class CheckinRepository(
    private val db: AppDatabase,
    private val scheduler: AlarmScheduler,
    private val notifier: Notifier,
    private val json: Json,
    private val requestSync: () -> Unit,
) {
    private val configDao = db.configDao()
    private val instanceDao = db.instanceDao()
    private val draftDao = db.draftDao()
    private val outboxDao = db.outboxDao()

    val openInstances: Flow<List<InstanceEntity>> = instanceDao.observeOpen()
    val pendingUploads: Flow<Int> = outboxDao.observePendingCount()
    val failedUploads: Flow<List<OutboxEntity>> = outboxDao.observeFailed()

    /** Creates the instance for an alarm. Returns null if the check-in no longer exists or this alarm already fired. */
    suspend fun fire(checkinId: Int, scheduledFor: Instant): InstanceEntity? {
        val checkin = configDao.checkin(checkinId) ?: return null
        val questions = checkin.questionIds.mapNotNull { configDao.question(it)?.toDto() }
        if (questions.isEmpty()) return null
        val expiresAt = Schedule.expiresAt(
            scheduledFor.atZone(ZoneId.systemDefault()),
            checkin.expiresAfterMinutes,
            checkin.timeLocal,
            checkin.daysOfWeek.toSet(),
        )
        val instance = InstanceEntity(
            id = UUID.randomUUID().toString(),
            checkinId = checkin.id,
            checkinName = checkin.name,
            scheduledFor = scheduledFor.toEpochMilli(),
            expiresAt = expiresAt.toEpochMilli(),
            status = InstanceEntity.STATUS_PENDING,
            questionCount = questions.size,
            questionsJson = json.encodeToString(QUESTIONS_SERIALIZER, questions),
        )
        return if (instanceDao.insert(instance) == -1L) null else instance
    }

    /** Snoozes once. Returns when the reminder should come back, or null if snoozing isn't possible. */
    suspend fun snooze(instanceId: String, minutes: Long, now: Instant = Instant.now()): Instant? {
        val instance = instanceDao.get(instanceId) ?: return null
        if (instance.status != InstanceEntity.STATUS_PENDING || instance.snoozed) return null
        val until = now.plus(Duration.ofMinutes(minutes))
        instanceDao.update(
            instance.copy(status = InstanceEntity.STATUS_SNOOZED, snoozed = true, snoozedUntil = until.toEpochMilli())
        )
        notifier.cancel(instance)
        scheduler.scheduleSnoozeEnd(instance.id, until)
        return until
    }

    /** Called when a snooze runs out: the check-in becomes due again and the reminder reappears. */
    suspend fun endSnooze(instanceId: String) {
        val instance = instanceDao.get(instanceId) ?: return
        if (instance.status != InstanceEntity.STATUS_SNOOZED) return
        val reopened = instance.copy(status = InstanceEntity.STATUS_PENDING)
        instanceDao.update(reopened)
        notifier.show(reopened)
    }

    suspend fun instance(instanceId: String): InstanceEntity? = instanceDao.get(instanceId)

    /** Loads an open check-in for answering, with any answers saved so far. Null if it's closed or unknown. */
    suspend fun openForAnswering(instanceId: String): OpenInstance? {
        val instance = instanceDao.get(instanceId)?.takeIf { it.isOpen } ?: return null
        val questions = questionsOf(instance).map { it.toQuestion() }
        val byId = questions.associateBy { it.id }
        val drafts = draftDao.forInstance(instanceId).mapNotNull { draft ->
            val question = byId[draft.questionId] ?: return@mapNotNull null
            val state = when {
                draft.skipped -> AnswerState.Skipped
                draft.valueJson != null ->
                    answerFromJson(question.spec, json.parseToJsonElement(draft.valueJson))?.let(AnswerState::Answered)
                else -> null
            }
            state?.let { draft.questionId to it }
        }.toMap()
        return OpenInstance(instance, questions, drafts)
    }

    suspend fun saveDraft(instanceId: String, questionId: Int, state: AnswerState) {
        draftDao.upsert(
            DraftEntity(
                instanceId = instanceId,
                questionId = questionId,
                valueJson = (state as? AnswerState.Answered)?.answer?.toJson()?.toString(),
                skipped = state is AnswerState.Skipped,
            )
        )
    }

    /** Records every question of the check-in (unanswered ones as skipped) and queues them for upload. */
    suspend fun submit(instanceId: String, answers: Map<Int, AnswerState>, now: Instant = Instant.now()): Boolean {
        val instance = instanceDao.get(instanceId)?.takeIf { it.isOpen } ?: return false
        val responses = questionsOf(instance).map { question ->
            when (val state = answers[question.id]) {
                is AnswerState.Answered -> payload(question, instance, ResponseStatus.ANSWERED, state.answer.toJson(), now)
                AnswerState.Skipped, null -> payload(question, instance, ResponseStatus.SKIPPED, null, now)
            }
        }
        close(instance, InstanceEntity.STATUS_COMPLETED, responses, now)
        requestSync()
        return true
    }

    /**
     * Closes every open check-in whose time is up. Answers saved as drafts are kept; questions never
     * reached are recorded as missed.
     */
    suspend fun expireDue(now: Instant = Instant.now()) {
        val due = instanceDao.dueForExpiry(now.toEpochMilli())
        if (due.isEmpty()) return
        for (instance in due) {
            val expiredAt = Instant.ofEpochMilli(instance.expiresAt)
            val drafts = draftDao.forInstance(instance.id).associateBy { it.questionId }
            val responses = questionsOf(instance).map { question ->
                val draft = drafts[question.id]
                when {
                    draft?.valueJson != null ->
                        payload(question, instance, ResponseStatus.ANSWERED, json.parseToJsonElement(draft.valueJson), expiredAt)
                    draft?.skipped == true -> payload(question, instance, ResponseStatus.SKIPPED, null, expiredAt)
                    else -> payload(question, instance, ResponseStatus.MISSED, null, expiredAt)
                }
            }
            close(instance, InstanceEntity.STATUS_EXPIRED, responses, now)
        }
        requestSync()
    }

    /** An answer given outside any check-in, from "Answer now". */
    suspend fun submitAdHoc(question: Question, answer: Answer, now: Instant = Instant.now()) {
        val dto = configDao.question(question.id)?.toDto()?.takeIf { it.version == question.version }
            ?: error("question ${question.id} v${question.version} is no longer current")
        val response = payload(dto, null, ResponseStatus.ANSWERED, answer.toJson(), now)
        outboxDao.insertAll(listOf(outboxEntry(response, now)))
        requestSync()
    }

    /** After a reboot the system forgets all alarms: put back expiry and snooze alarms for open check-ins. */
    suspend fun restoreAlarms() {
        for (instance in instanceDao.open()) {
            scheduler.scheduleExpiry(instance.id, Instant.ofEpochMilli(instance.expiresAt))
            if (instance.status == InstanceEntity.STATUS_SNOOZED && instance.snoozedUntil != null) {
                scheduler.scheduleSnoozeEnd(instance.id, Instant.ofEpochMilli(instance.snoozedUntil))
            }
        }
    }

    suspend fun retryFailedUploads() {
        outboxDao.retryFailed()
        requestSync()
    }

    suspend fun discardFailedUploads() = outboxDao.discardFailed()

    private suspend fun close(instance: InstanceEntity, status: String, responses: List<ResponsePayload>, now: Instant) {
        db.withTransaction {
            outboxDao.insertAll(responses.map { outboxEntry(it, now) })
            instanceDao.update(instance.copy(status = status))
            draftDao.deleteForInstance(instance.id)
            instanceDao.deleteClosedBefore(now.minus(Duration.ofDays(RETAIN_CLOSED_DAYS)).toEpochMilli())
        }
        notifier.cancel(instance)
        scheduler.cancelInstance(instance.id)
    }

    private fun questionsOf(instance: InstanceEntity): List<QuestionDto> =
        json.decodeFromString(QUESTIONS_SERIALIZER, instance.questionsJson)

    private fun payload(
        question: QuestionDto,
        instance: InstanceEntity?,
        status: String,
        value: JsonElement?,
        answeredAt: Instant,
    ) = ResponsePayload(
        id = UUID.randomUUID().toString(),
        questionId = question.id,
        questionVersion = question.version,
        checkinId = instance?.checkinId,
        instanceId = instance?.id,
        status = status,
        value = value,
        scheduledFor = instance?.let { utc(Instant.ofEpochMilli(it.scheduledFor)) },
        answeredAt = utc(answeredAt),
    )

    private fun outboxEntry(payload: ResponsePayload, now: Instant) = OutboxEntity(
        id = payload.id,
        payloadJson = json.encodeToString(ResponsePayload.serializer(), payload),
        createdAt = now.toEpochMilli(),
    )

    private companion object {
        val QUESTIONS_SERIALIZER = ListSerializer(QuestionDto.serializer())
        const val RETAIN_CLOSED_DAYS = 7L

        /** Millisecond precision with a Z suffix, e.g. 2026-10-03T01:04:12.123Z. */
        fun utc(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).toString()
    }
}
