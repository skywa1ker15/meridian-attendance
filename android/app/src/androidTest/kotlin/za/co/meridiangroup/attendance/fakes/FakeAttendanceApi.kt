package za.co.meridiangroup.attendance.fakes

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.AuthResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.ChangePasswordRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ConfirmEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ConfirmEventResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.CountDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceInfoDto
import za.co.meridiangroup.attendance.data.remote.dto.DismissEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.FcmTokenRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ForgotPasswordRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.IntegrityRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.IntegrityResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.ItemsDto
import za.co.meridiangroup.attendance.data.remote.dto.LiveAttendanceResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.LoginRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.LogoutRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestCreateDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.MeResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.NotificationDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.PatchMeRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.remote.dto.RefreshRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ReviewNoteRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.SubmitEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SubmitEventResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.TodaySummaryDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplacesResponseDto

/** In-memory fake of the REST contract used by instrumented UI tests. Behaviour is switched per test. */
class FakeAttendanceApi : AttendanceApi {
    var validPassword = "correct-password"
    var mustChangePassword = false
    /** Status returned by confirm: "confirmed" or "under_review". */
    var confirmStatus = "confirmed"
    var offline = false
    val calls = mutableListOf<String>()

    val user = UserDto(id = "u1", employeeNumber = "MG-0042", firstName = "Thandi", lastName = "Naidoo", email = "t.naidoo@example.co.za", permissions = listOf("attendance:self"))

    private fun <T> err(code: Int, errorCode: String, message: String = ""): Response<T> =
        Response.error(code, """{"error":{"code":"$errorCode","message":"$message","details":{}}}""".toResponseBody("application/json".toMediaType()))

    private fun <T> guard(name: String, block: () -> Response<T>): Response<T> {
        calls += name
        if (offline) throw java.io.IOException("offline (fake)")
        return block()
    }

    private fun event(id: String, status: String, type: String = "arrival") = EventDto(
        id = id, clientEventId = "c-$id", eventType = type, status = status, workplaceId = "w1", workplaceName = "Umhlanga",
        detectedAt = "2026-11-16T06:00:00Z", expiresAt = "2026-11-16T18:00:00Z",
    )

    override suspend fun login(body: LoginRequestDto): Response<AuthResponseDto> = guard("login") {
        if (body.password != validPassword) err(401, "invalid_credentials", "Email or password is incorrect.")
        else Response.success(AuthResponseDto("access", "refresh", "bearer", 900, mustChangePassword, user.copy(mustChangePassword = mustChangePassword), null))
    }
    override suspend fun refresh(body: RefreshRequestDto) = guard("refresh") { Response.success(AuthResponseDto("access2", "refresh2", user = user)) }
    override suspend fun logout(body: LogoutRequestDto) = guard("logout") { Response.success(Unit) }
    override suspend fun me() = guard("me") { Response.success(MeResponseDto(user, null)) }
    override suspend fun patchMe(body: PatchMeRequestDto) = guard("patchMe") { Response.success(user.copy(phone = body.phone ?: user.phone)) }
    override suspend fun changePassword(body: ChangePasswordRequestDto) = guard("changePassword") { Response.success(Unit) }
    override suspend fun forgotPassword(body: ForgotPasswordRequestDto) = guard("forgotPassword") { Response.success(Unit) }
    override suspend fun registerDevice(body: DeviceInfoDto) = guard("registerDevice") { Response.success(DeviceDto("d1", body.deviceId)) }
    override suspend fun myDevice() = guard("myDevice") { Response.success(DeviceDto("d1", "inst")) }
    override suspend fun uploadFcmToken(body: FcmTokenRequestDto) = guard("uploadFcmToken") { Response.success(Unit) }
    override suspend fun submitIntegrity(body: IntegrityRequestDto) = guard("submitIntegrity") { Response.success(IntegrityResponseDto("not_configured")) }
    override suspend fun workplaces() = guard("workplaces") { Response.success(WorkplacesResponseDto(emptyList(), 1)) }
    override suspend fun policy() = guard("policy") { Response.success(PolicyDto()) }
    override suspend fun submitEvent(idempotencyKey: String, body: SubmitEventRequestDto) =
        guard("submitEvent") { Response.success(SubmitEventResponseDto(event("e-${body.clientEventId}", "pending").copy(clientEventId = body.clientEventId))) }
    override suspend fun confirmEvent(eventId: String, idempotencyKey: String, body: ConfirmEventRequestDto) =
        guard("confirmEvent") { Response.success(ConfirmEventResponseDto(event(eventId, confirmStatus).copy(clientEventId = "c1"))) }
    override suspend fun dismissEvent(eventId: String, body: DismissEventRequestDto) =
        guard("dismissEvent") { Response.success(event(eventId, "dismissed").copy(clientEventId = "c1")) }
    override suspend fun pendingEvents() = guard("pendingEvents") { Response.success(ItemsDto<EventDto>(emptyList())) }
    override suspend fun today() = guard("today") { Response.success(TodaySummaryDto(workDate = "2026-11-16")) }
    override suspend fun sessions(from: String?, to: String?, page: Int, pageSize: Int) = guard("sessions") { Response.success(PageDto<SessionDto>()) }
    override suspend fun session(id: String) = guard("session") { Response.success(SessionDetailDto(SessionDto(id))) }
    override suspend fun events(from: String?, to: String?, status: String?, page: Int, pageSize: Int) = guard("events") { Response.success(PageDto<EventDto>()) }
    override suspend fun sync(body: SyncRequestDto) = guard("sync") { Response.success(SyncResponseDto()) }
    override suspend fun createManualRequest(idempotencyKey: String, body: ManualRequestCreateDto) =
        guard("createManualRequest") { Response.success(ManualRequestDto("r1", body.type, workDate = body.workDate, reason = body.reason)) }
    override suspend fun manualRequests(status: String?, page: Int, pageSize: Int) = guard("manualRequests") { Response.success(PageDto<ManualRequestDto>()) }
    override suspend fun cancelManualRequest(id: String) = guard("cancelManualRequest") { Response.success(ManualRequestDto(id, "missed_clock_out", status = "cancelled")) }
    override suspend fun notifications(unreadOnly: Boolean, page: Int, pageSize: Int) = guard("notifications") { Response.success(PageDto<NotificationDto>()) }
    override suspend fun unreadCount() = guard("unreadCount") { Response.success(CountDto(0)) }
    override suspend fun markRead(id: String) = guard("markRead") { Response.success(Unit) }
    override suspend fun markAllRead() = guard("markAllRead") { Response.success(Unit) }
    override suspend fun adminLive(departmentId: String?) = guard("adminLive") { Response.success(LiveAttendanceResponseDto("2026-11-16")) }
    override suspend fun adminEvents(status: String?, from: String?, to: String?, employeeId: String?, eventType: String?, page: Int, pageSize: Int) =
        guard("adminEvents") { Response.success(PageDto<EventDto>()) }
    override suspend fun adminApproveEvent(id: String, body: ReviewNoteRequestDto) = guard("adminApproveEvent") { Response.success(event(id, "confirmed")) }
    override suspend fun adminRejectEvent(id: String, body: ReviewNoteRequestDto) = guard("adminRejectEvent") { Response.success(event(id, "rejected")) }
}
