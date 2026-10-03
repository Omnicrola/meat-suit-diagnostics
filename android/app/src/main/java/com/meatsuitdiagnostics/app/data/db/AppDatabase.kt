package com.meatsuitdiagnostics.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Schema changes need a Migration: a destructive fallback would throw away answers that haven't been
// uploaded yet. Exported schemas live in app/schemas/ for writing and testing migrations.
@Database(
    entities = [QuestionEntity::class, CheckinEntity::class, InstanceEntity::class, DraftEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun instanceDao(): InstanceDao
    abstract fun draftDao(): DraftDao
    abstract fun outboxDao(): OutboxDao
}
