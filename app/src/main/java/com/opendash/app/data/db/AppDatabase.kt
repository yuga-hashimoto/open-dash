package com.opendash.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        RoutineEntity::class,
        DocumentChunkEntity::class,
        ToolUsageEntity::class,
        SpeakerGroupEntity::class,
        MultiroomTrafficEntity::class,
        MultiroomRejectionEntity::class,
        ShoppingListItemEntity::class,
        ReminderEntity::class,
        AlarmEntity::class,
        KnowledgeEntity::class
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun routineDao(): RoutineDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun toolUsageDao(): ToolUsageDao
    abstract fun speakerGroupDao(): SpeakerGroupDao
    abstract fun multiroomTrafficDao(): MultiroomTrafficDao
    abstract fun multiroomRejectionDao(): MultiroomRejectionDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun reminderDao(): ReminderDao
    abstract fun alarmDao(): AlarmDao
    abstract fun knowledgeDao(): KnowledgeDao
}
