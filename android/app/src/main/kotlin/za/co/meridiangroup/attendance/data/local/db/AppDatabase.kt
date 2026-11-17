package za.co.meridiangroup.attendance.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkplaceEntity::class,
        TodaySummaryEntity::class,
        SessionEntity::class,
        EventEntity::class,
        NotificationEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workplaceDao(): WorkplaceDao
    abstract fun todayDao(): TodayDao
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun notificationDao(): NotificationDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "meridian_attendance.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // Version 1 is the first released schema. Future versions must add explicit Migrations
                // (schemas are exported to app/schemas); the outbox must never be wiped by a destructive migration.
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
