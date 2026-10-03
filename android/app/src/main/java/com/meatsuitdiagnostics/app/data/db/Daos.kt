package com.meatsuitdiagnostics.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ConfigDao {
    @Query("SELECT * FROM questions ORDER BY id")
    abstract fun observeQuestions(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE id = :id")
    abstract suspend fun question(id: Int): QuestionEntity?

    @Query("SELECT * FROM checkins ORDER BY timeLocal, id")
    abstract fun observeCheckins(): Flow<List<CheckinEntity>>

    @Query("SELECT * FROM checkins")
    abstract suspend fun checkins(): List<CheckinEntity>

    @Query("SELECT * FROM checkins WHERE id = :id")
    abstract suspend fun checkin(id: Int): CheckinEntity?

    @Transaction
    open suspend fun replaceAll(questions: List<QuestionEntity>, checkins: List<CheckinEntity>) {
        deleteQuestions()
        deleteCheckins()
        insertQuestions(questions)
        insertCheckins(checkins)
    }

    @Query("DELETE FROM questions")
    abstract suspend fun deleteQuestions()

    @Query("DELETE FROM checkins")
    abstract suspend fun deleteCheckins()

    @Insert
    abstract suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Insert
    abstract suspend fun insertCheckins(checkins: List<CheckinEntity>)
}

@Dao
interface InstanceDao {
    /** Returns -1 if an instance for the same check-in and time already exists (a repeated alarm). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(instance: InstanceEntity): Long

    @Update
    suspend fun update(instance: InstanceEntity)

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun get(id: String): InstanceEntity?

    @Query("SELECT * FROM instances WHERE status IN ('pending', 'snoozed') ORDER BY scheduledFor")
    fun observeOpen(): Flow<List<InstanceEntity>>

    @Query("SELECT * FROM instances WHERE status IN ('pending', 'snoozed')")
    suspend fun open(): List<InstanceEntity>

    @Query("SELECT * FROM instances WHERE status IN ('pending', 'snoozed') AND expiresAt <= :now")
    suspend fun dueForExpiry(now: Long): List<InstanceEntity>

    @Query("DELETE FROM instances WHERE status IN ('completed', 'expired') AND scheduledFor < :before")
    suspend fun deleteClosedBefore(before: Long)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE instanceId = :instanceId")
    suspend fun forInstance(instanceId: String): List<DraftEntity>

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE instanceId = :instanceId")
    suspend fun deleteForInstance(instanceId: String)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insertAll(items: List<OutboxEntity>)

    @Query("SELECT * FROM outbox WHERE error IS NULL ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE error IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM outbox WHERE error IS NOT NULL ORDER BY createdAt")
    fun observeFailed(): Flow<List<OutboxEntity>>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("UPDATE outbox SET error = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    @Query("UPDATE outbox SET error = NULL WHERE error IS NOT NULL")
    suspend fun retryFailed()

    @Query("DELETE FROM outbox WHERE error IS NOT NULL")
    suspend fun discardFailed()
}
