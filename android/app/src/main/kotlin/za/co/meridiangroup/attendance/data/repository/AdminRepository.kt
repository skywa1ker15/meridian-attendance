package za.co.meridiangroup.attendance.data.repository

import java.io.File
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.data.files.ExportStore
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.AttendanceExceptionDto
import za.co.meridiangroup.attendance.data.remote.dto.AuditLogDto
import za.co.meridiangroup.attendance.data.remote.dto.BroadcastRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.BroadcastResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.DepartmentCreateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.DepartmentUpdateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeCreateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeCreatedDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.InvitationSummaryDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeUpdateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EraseRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EraseResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.ItemsDto
import za.co.meridiangroup.attendance.data.remote.dto.LiveAttendanceResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.OptionalNoteRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.OverviewDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.remote.dto.ReasonRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ReportDto
import za.co.meridiangroup.attendance.data.remote.dto.RequestApproveRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ReviewNoteRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.RoleRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.RolesResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionCreateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionCorrectionRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.TemporaryPasswordDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplaceDto

/**
 * Every docs/API.md §7 administration endpoint, plus the file downloads (reports, POPIA export).
 * The server enforces the permission matrix; the UI only hides what the caller may not do.
 */
class AdminRepository(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val exports: ExportStore? = null,
) {
    // ---- Overview / live / exceptions ----
    suspend fun overview(date: String? = null): ApiResult<OverviewDto> = caller.call { api.adminOverview(date) }

    suspend fun live(departmentId: String? = null): ApiResult<LiveAttendanceResponseDto> =
        caller.call { api.adminLive(departmentId?.ifBlank { null }) }

    suspend fun exceptions(from: String?, to: String?, departmentId: String?, kind: String?, page: Int): ApiResult<PageDto<AttendanceExceptionDto>> =
        caller.call { api.adminExceptions(from, to, departmentId?.ifBlank { null }, kind?.ifBlank { null }, page, 30) }

    // ---- Review queue ----
    /** Events awaiting a manager: `under_review` and still-`pending` ones. */
    suspend fun reviewQueue(status: String, page: Int): ApiResult<PageDto<EventDto>> =
        caller.call { api.adminEvents(status = status, page = page, pageSize = 30) }

    suspend fun events(status: String?, from: String?, to: String?, employeeId: String?, eventType: String?, page: Int): ApiResult<PageDto<EventDto>> =
        caller.call { api.adminEvents(status?.ifBlank { null }, from, to, employeeId?.ifBlank { null }, eventType?.ifBlank { null }, page, 30) }

    suspend fun approve(id: String, note: String): ApiResult<EventDto> =
        caller.call { api.adminApproveEventOptionalNote(id, OptionalNoteRequestDto(note.trim().ifBlank { null })) }

    suspend fun reject(id: String, note: String): ApiResult<EventDto> = caller.call { api.adminRejectEvent(id, ReviewNoteRequestDto(note.trim())) }

    // ---- Manual requests ----
    suspend fun manualRequests(status: String?, type: String?, employeeId: String?, page: Int): ApiResult<PageDto<ManualRequestDto>> =
        caller.call { api.adminManualRequests(status?.ifBlank { null }, type?.ifBlank { null }, employeeId?.ifBlank { null }, page = page) }

    suspend fun approveRequest(id: String, note: String?, clockInAt: String?, clockOutAt: String?): ApiResult<ManualRequestDto> =
        caller.call { api.adminApproveRequest(id, RequestApproveRequestDto(note?.trim()?.ifBlank { null }, clockInAt, clockOutAt)) }

    suspend fun rejectRequest(id: String, note: String): ApiResult<ManualRequestDto> =
        caller.call { api.adminRejectRequest(id, ReviewNoteRequestDto(note.trim())) }

    // ---- Employees ----
    suspend fun employees(search: String?, departmentId: String?, status: String?, role: String?, page: Int): ApiResult<PageDto<UserDto>> =
        caller.call {
            api.adminEmployees(search?.trim()?.ifBlank { null }, departmentId?.ifBlank { null }, status?.ifBlank { null }, role?.ifBlank { null }, page, 30)
        }

    suspend fun employee(id: String): ApiResult<EmployeeDetailDto> = caller.call { api.adminEmployee(id) }

    suspend fun createEmployee(body: EmployeeCreateRequestDto): ApiResult<EmployeeCreatedDto> = caller.call { api.adminCreateEmployee(body) }

    suspend fun updateEmployee(id: String, body: EmployeeUpdateRequestDto): ApiResult<UserDto> = caller.call { api.adminUpdateEmployee(id, body) }

    suspend fun deactivateEmployee(id: String, reason: String): ApiResult<UserDto> = caller.call { api.adminDeactivateEmployee(id, ReasonRequestDto(reason.trim())) }

    suspend fun reactivateEmployee(id: String): ApiResult<UserDto> = caller.call { api.adminReactivateEmployee(id) }

    suspend fun resetPassword(id: String): ApiResult<TemporaryPasswordDto> = caller.call { api.adminResetPassword(id) }

    /** Emails the employee a fresh invitation link (POST /admin/employees/{id}/invite). */
    suspend fun resendInvitation(id: String): ApiResult<InvitationSummaryDto> = caller.call { api.adminResendInvitation(id) }

    suspend fun changeRole(id: String, role: String): ApiResult<UserDto> = caller.call { api.adminChangeRole(id, RoleRequestDto(role)) }

    suspend fun eraseEmployee(id: String, employeeNumber: String, reason: String): ApiResult<EraseResponseDto> =
        caller.call { api.adminEraseEmployee(id, EraseRequestDto(employeeNumber.trim(), reason.trim())) }

    /** POPIA access request: downloads the JSON export to the app cache and returns the file. */
    suspend fun exportEmployee(id: String, employeeNumber: String): ApiResult<File> =
        caller.call { api.adminExportEmployee(id) }.toFile("${employeeNumber.ifBlank { id }}-personal-data.json")

    // ---- Departments ----
    suspend fun departments(): ApiResult<ItemsDto<AdminDepartmentDto>> = caller.call { api.adminDepartments() }

    suspend fun createDepartment(name: String, code: String): ApiResult<AdminDepartmentDto> =
        caller.call { api.adminCreateDepartment(DepartmentCreateRequestDto(name.trim(), code.trim().uppercase())) }

    suspend fun updateDepartment(id: String, name: String?, code: String?, active: Boolean?): ApiResult<AdminDepartmentDto> =
        caller.call { api.adminUpdateDepartment(id, DepartmentUpdateRequestDto(name?.trim(), code?.trim()?.uppercase(), active)) }

    // ---- Devices ----
    suspend fun devices(status: String?, userId: String?, search: String?, page: Int): ApiResult<PageDto<DeviceDto>> =
        caller.call { api.adminDevices(status?.ifBlank { null }, userId?.ifBlank { null }, search?.trim()?.ifBlank { null }, page, 30) }

    suspend fun approveDevice(id: String): ApiResult<DeviceDto> = caller.call { api.adminApproveDevice(id) }

    suspend fun revokeDevice(id: String, reason: String): ApiResult<DeviceDto> = caller.call { api.adminRevokeDevice(id, ReasonRequestDto(reason.trim())) }

    // ---- Attendance records ----
    suspend fun sessions(
        from: String?,
        to: String?,
        employeeId: String?,
        departmentId: String?,
        status: String?,
        late: Boolean?,
        manual: Boolean?,
        page: Int,
    ): ApiResult<PageDto<SessionDto>> = caller.call {
        api.adminSessions(from, to, employeeId?.ifBlank { null }, departmentId?.ifBlank { null }, status?.ifBlank { null }, late, manual, page, 30)
    }

    suspend fun session(id: String): ApiResult<SessionDetailDto> = caller.call { api.adminSession(id) }

    suspend fun correctSession(id: String, clockInAt: String?, clockOutAt: String?, note: String): ApiResult<SessionDto> =
        caller.call { api.adminCorrectSession(id, SessionCorrectionRequestDto(clockInAt, clockOutAt, note.trim())) }

    suspend fun createSession(employeeId: String, workDate: String, clockInAt: String, clockOutAt: String?, note: String): ApiResult<SessionDto> =
        caller.call { api.adminCreateSession(SessionCreateRequestDto(employeeId, workDate, clockInAt, clockOutAt, note.trim())) }

    // ---- Reports ----
    suspend fun report(
        report: String,
        from: String?,
        to: String?,
        employeeId: String?,
        departmentId: String?,
        status: String?,
    ): ApiResult<ReportDto> = caller.call {
        api.adminReport(report, from, to, employeeId?.ifBlank { null }, departmentId?.ifBlank { null }, status?.ifBlank { null }, "json")
    }

    /** csv / xlsx / pdf: streamed with the bearer token into the app cache, ready for the share sheet. */
    suspend fun reportFile(
        report: String,
        format: String,
        from: String?,
        to: String?,
        employeeId: String?,
        departmentId: String?,
        status: String?,
    ): ApiResult<File> = caller.call {
        api.adminReportFile(report, format, from, to, employeeId?.ifBlank { null }, departmentId?.ifBlank { null }, status?.ifBlank { null })
    }.toFile("$report-${from ?: ""}-${to ?: ""}.$format")

    // ---- Workplaces & policy ----
    suspend fun workplaces(): ApiResult<ItemsDto<WorkplaceDto>> = caller.call { api.adminWorkplaces() }

    suspend fun createWorkplace(body: JsonObject): ApiResult<WorkplaceDto> = caller.call { api.adminCreateWorkplace(body) }

    suspend fun updateWorkplace(id: String, body: JsonObject): ApiResult<WorkplaceDto> = caller.call { api.adminUpdateWorkplace(id, body) }

    suspend fun policy(): ApiResult<PolicyDto> = caller.call { api.adminPolicy() }

    suspend fun updatePolicy(body: JsonObject): ApiResult<PolicyDto> = caller.call { api.adminUpdatePolicy(body) }

    // ---- Roles, audit, broadcast ----
    suspend fun roles(): ApiResult<RolesResponseDto> = caller.call { api.adminRoles() }

    suspend fun auditLogs(
        actorId: String?,
        action: String?,
        entityType: String?,
        from: String?,
        to: String?,
        search: String?,
        page: Int,
    ): ApiResult<PageDto<AuditLogDto>> = caller.call {
        api.adminAuditLogs(
            actorId?.ifBlank { null }, action?.trim()?.ifBlank { null }, entityType?.ifBlank { null }, null,
            from, to, search?.trim()?.ifBlank { null }, page, 30,
        )
    }

    suspend fun broadcast(title: String, body: String, departmentId: String?): ApiResult<BroadcastResponseDto> =
        caller.call { api.adminBroadcast(BroadcastRequestDto(title.trim(), body.trim(), departmentId?.ifBlank { null })) }

    private suspend fun ApiResult<ResponseBody>.toFile(fileName: String): ApiResult<File> = when (this) {
        is ApiResult.Success -> {
            val store = exports
            if (store == null) {
                value.close()
                ApiResult.Failure(za.co.meridiangroup.attendance.core.ApiFailure(0, "no_storage", "Downloads are not available."))
            } else {
                ApiResult.Success(store.save(value, fileName))
            }
        }
        is ApiResult.Failure -> this
        is ApiResult.Offline -> this
    }
}
