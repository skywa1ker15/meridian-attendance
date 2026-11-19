package za.co.meridiangroup.attendance.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkplaceDao {
    @Query("SELECT * FROM workplaces ORDER BY name")
    fun observeAll(): Flow<List<WorkplaceEntity>>

    @Query("SELECT * FROM workplaces")
    suspend fun all(): List<WorkplaceEntity>

    @Query("SELECT * FROM workplaces WHERE id = :id")
    suspend fun byId(id: String): WorkplaceEntity?

    @Query("DELETE FROM workplaces")
    suspend fun clear()

    @Upsert
    suspend fun upsertAll(items: List<WorkplaceEntity>)

    @Transaction
    suspend fun replaceAll(items: List<WorkplaceEntity>) {
        clear()
        upsertAll(items)
    }
}

@Dao
interface TodayDao {
    @Query("SELECT * FROM today_summary WHERE id = 0")
    fun observe(): Flow<TodaySummaryEntity?>

    @Query("SELECT * FROM today_summary WHERE id = 0")
    suspend fun get(): TodaySummaryEntity?

    @Upsert
    suspend fun upsert(entity: TodaySummaryEntity)

    @Query("DELETE FROM today_summary")
    suspend fun clear()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY work_date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Upsert
    suspend fun upsertAll(items: List<SessionEntity>)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface EventDao {
    /** Events the employee still has to act on (server `pending`, not being confirmed/dismissed right now). */
    @Query(
        "SELECT * FROM events WHERE status = 'pending' AND local_status NOT IN ('confirming','dismissing') " +
            "ORDER BY detected_at DESC",
    )
    fun observeAwaitingConfirmation(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE status = 'pending' AND local_status NOT IN ('confirming','dismissing')")
    suspend fun awaitingConfirmation(): List<EventEntity>

    @Query("SELECT * FROM events WHERE client_event_id = :ref OR server_id = :ref LIMIT 1")
    suspend fun byRef(ref: String): EventEntity?

    @Query("SELECT * FROM events WHERE client_event_id = :ref OR server_id = :ref LIMIT 1")
    fun observeByRef(ref: String): Flow<EventEntity?>

    @Query(
        "SELECT * FROM events WHERE event_type = :type AND status = 'pending' AND created_at >= :sinceMillis " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun recentPendingOfType(type: String, sinceMillis: Long): EventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EventEntity): Long

    @Upsert
    suspend fun upsert(entity: EventEntity)

    @Query("UPDATE events SET local_status = :localStatus, updated_at = :now WHERE client_event_id = :clientEventId")
    suspend fun setLocalStatus(clientEventId: String, localStatus: String, now: Long)

    @Query("UPDATE events SET last_notified_at = :now WHERE client_event_id = :clientEventId")
    suspend fun markNotified(clientEventId: String, now: Long)

    /** Removes terminal rows older than the cut-off (local cache only; the server keeps history). */
    @Query("DELETE FROM events WHERE status NOT IN ('pending') AND updated_at < :before")
    suspend fun pruneTerminal(before: Long)

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY created_at DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(items: List<NotificationEntity>)

    @Query("UPDATE notifications SET read_at = :readAt WHERE id = :id")
    suspend fun markRead(id: String, readAt: String)

    @Query("UPDATE notifications SET read_at = :readAt WHERE read_at IS NULL")
    suspend fun markAllRead(readAt: String)

    @Query("DELETE FROM notifications")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM pending_outbox WHERE dead = 0 AND next_attempt_at <= :now ORDER BY created_at, id")
    suspend fun due(now: Long): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM pending_outbox WHERE dead = 0")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_outbox WHERE dead = 1")
    fun observeDeadCount(): Flow<Int>

    @Query("SELECT * FROM pending_outbox WHERE dead = 1 ORDER BY created_at DESC")
    fun observeDead(): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM pending_outbox WHERE dead = 0")
    suspend fun queuedCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: OutboxEntity): Long

    @Query("DELETE FROM pending_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE pending_outbox SET attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAt WHERE id = :id")
    suspend fun markRetry(id: Long, error: String, nextAt: Long)

    @Query("UPDATE pending_outbox SET attempts = attempts + 1, last_error = :error, dead = 1 WHERE id = :id")
    suspend fun markDead(id: Long, error: String)

    /** Rewrites references from a client_event_id to the server id once it is known. */
    @Query("UPDATE pending_outbox SET event_ref = :serverId WHERE event_ref = :clientEventId")
    suspend fun rewriteEventRef(clientEventId: String, serverId: String)

    @Query("DELETE FROM pending_outbox WHERE dead = 1")
    suspend fun clearDead()

    @Query("SELECT COUNT(*) FROM pending_outbox")
    suspend fun totalCount(): Int

    /** Delete the oldest dead rows, then the oldest live rows, to bring total under [limit]. */
    @Query("DELETE FROM pending_outbox WHERE id IN (SELECT id FROM pending_outbox ORDER BY dead DESC, created_at ASC LIMIT :excess)")
    suspend fun trimOldest(excess: Int)

    @Query("DELETE FROM pending_outbox")
    suspend fun clear()
}
