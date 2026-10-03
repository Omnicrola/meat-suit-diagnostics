package com.meatsuitdiagnostics.app.data

import com.meatsuitdiagnostics.app.data.api.ConfigDto
import com.meatsuitdiagnostics.app.data.db.CheckinEntity
import com.meatsuitdiagnostics.app.data.db.ConfigDao
import com.meatsuitdiagnostics.app.data.db.QuestionEntity
import com.meatsuitdiagnostics.app.domain.Question
import com.meatsuitdiagnostics.app.scheduling.AlarmScheduler
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The questions and check-ins from the server, cached locally, and the alarms that follow from them. */
class ConfigRepository(private val dao: ConfigDao, private val scheduler: AlarmScheduler) {

    val questions: Flow<List<Question>> = dao.observeQuestions().map { list -> list.map { it.toQuestion() } }

    val checkins: Flow<List<CheckinEntity>> = dao.observeCheckins()

    suspend fun question(id: Int): Question? = dao.question(id)?.toQuestion()

    suspend fun apply(config: ConfigDto) {
        val previous = dao.checkins()
        dao.replaceAll(
            questions = config.questions.map(QuestionEntity::from),
            checkins = config.checkins.map {
                CheckinEntity(it.id, it.name, it.timeLocal, it.daysOfWeek, it.expiresAfterMinutes, it.questionIds)
            },
        )
        val currentIds = config.checkins.map { it.id }.toSet()
        previous.filter { it.id !in currentIds }.forEach { scheduler.cancelCheckin(it.id) }
        rescheduleAll()
    }

    /** Sets the next alarm for every check-in. Safe to repeat: an existing alarm is replaced. */
    suspend fun rescheduleAll(now: ZonedDateTime = ZonedDateTime.now()) {
        dao.checkins().forEach { scheduler.scheduleNext(it, now) }
    }
}
