package za.co.meridiangroup.attendance.data.repository

import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.LiveAttendanceResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.ReviewNoteRequestDto

/** Manager/admin endpoints. The server enforces `attendance:review` / `admin:read`; the UI only hides entries. */
class AdminRepository(private val api: AttendanceApi, private val caller: ApiCaller) {
    suspend fun live(): ApiResult<LiveAttendanceResponseDto> = caller.call { api.adminLive() }

    /** Events awaiting a manager: `under_review` (and still-`pending` ones for visibility). */
    suspend fun reviewQueue(status: String, page: Int): ApiResult<PageDto<EventDto>> =
        caller.call { api.adminEvents(status = status, page = page, pageSize = 30) }

    suspend fun approve(id: String, note: String): ApiResult<EventDto> = caller.call { api.adminApproveEvent(id, ReviewNoteRequestDto(note.trim())) }
    suspend fun reject(id: String, note: String): ApiResult<EventDto> = caller.call { api.adminRejectEvent(id, ReviewNoteRequestDto(note.trim())) }
}
