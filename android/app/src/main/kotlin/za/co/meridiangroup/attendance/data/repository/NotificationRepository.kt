package za.co.meridiangroup.attendance.data.repository

import kotlinx.coroutines.flow.Flow
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.data.local.db.NotificationDao
import za.co.meridiangroup.attendance.data.local.db.NotificationEntity
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.NotificationDto
import za.co.meridiangroup.attendance.data.toEntity

class NotificationRepository(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val dao: NotificationDao,
    private val now: () -> Long,
) {
    val inbox: Flow<List<NotificationEntity>> = dao.observeAll()
    val unreadCount: Flow<Int> = dao.observeUnreadCount()

    suspend fun refresh(page: Int = 1): ApiResult<PageDto<NotificationDto>> {
        val r = caller.call { api.notifications(unreadOnly = false, page = page, pageSize = 30) }
        if (r is ApiResult.Success) dao.upsertAll(r.value.items.map { it.toEntity() })
        return r
    }

    suspend fun markRead(id: String): ApiResult<Unit> {
        val r = caller.call { api.markRead(id) }
        if (r !is ApiResult.Failure) dao.markRead(id, JohannesburgTime.toIsoUtc(now()))
        return r
    }

    suspend fun markAllRead(): ApiResult<Unit> {
        val r = caller.call { api.markAllRead() }
        if (r is ApiResult.Success) dao.markAllRead(JohannesburgTime.toIsoUtc(now()))
        return r
    }

    /** Stores a notification that arrived via FCM so the inbox updates before the next refresh. */
    suspend fun storePushed(dto: NotificationDto) = dao.upsertAll(listOf(dto.toEntity()))
}
