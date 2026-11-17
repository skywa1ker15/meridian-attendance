package za.co.meridiangroup.attendance.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
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

/**
 * Retrofit mirror of docs/API.md (base path `/api/v1/` is part of the Retrofit base URL).
 * Every call returns [Response] so that [ApiCaller] can map the error envelope uniformly.
 */
interface AttendanceApi {

    // ---- 1. Authentication ----
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): Response<AuthResponseDto>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<AuthResponseDto>

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto): Response<Unit>

    @GET("auth/me")
    suspend fun me(): Response<MeResponseDto>

    @PATCH("auth/me")
    suspend fun patchMe(@Body body: PatchMeRequestDto): Response<UserDto>

    @POST("auth/password/change")
    suspend fun changePassword(@Body body: ChangePasswordRequestDto): Response<Unit>

    @POST("auth/password/forgot")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequestDto): Response<Unit>

    // ---- 2. Devices ----
    @POST("devices/register")
    suspend fun registerDevice(@Body body: DeviceInfoDto): Response<DeviceDto>

    @GET("devices/me")
    suspend fun myDevice(): Response<DeviceDto>

    @POST("devices/me/fcm-token")
    suspend fun uploadFcmToken(@Body body: FcmTokenRequestDto): Response<Unit>

    @POST("devices/me/integrity")
    suspend fun submitIntegrity(@Body body: IntegrityRequestDto): Response<IntegrityResponseDto>

    // ---- 3. Configuration ----
    @GET("config/workplaces")
    suspend fun workplaces(): Response<WorkplacesResponseDto>

    @GET("config/policy")
    suspend fun policy(): Response<PolicyDto>

    // ---- 4. Attendance ----
    @POST("attendance/events")
    suspend fun submitEvent(@Header("Idempotency-Key") idempotencyKey: String, @Body body: SubmitEventRequestDto): Response<SubmitEventResponseDto>

    @POST("attendance/events/{event_id}/confirm")
    suspend fun confirmEvent(
        @Path("event_id") eventId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ConfirmEventRequestDto,
    ): Response<ConfirmEventResponseDto>

    @POST("attendance/events/{event_id}/dismiss")
    suspend fun dismissEvent(@Path("event_id") eventId: String, @Body body: DismissEventRequestDto): Response<EventDto>

    @GET("attendance/pending")
    suspend fun pendingEvents(): Response<ItemsDto<EventDto>>

    @GET("attendance/today")
    suspend fun today(): Response<TodaySummaryDto>

    @GET("attendance/sessions")
    suspend fun sessions(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): Response<PageDto<SessionDto>>

    @GET("attendance/sessions/{id}")
    suspend fun session(@Path("id") id: String): Response<SessionDetailDto>

    @GET("attendance/events")
    suspend fun events(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): Response<PageDto<EventDto>>

    @POST("attendance/sync")
    suspend fun sync(@Body body: SyncRequestDto): Response<SyncResponseDto>

    // ---- 5. Manual requests ----
    @POST("attendance/manual-requests")
    suspend fun createManualRequest(@Header("Idempotency-Key") idempotencyKey: String, @Body body: ManualRequestCreateDto): Response<ManualRequestDto>

    @GET("attendance/manual-requests")
    suspend fun manualRequests(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): Response<PageDto<ManualRequestDto>>

    @POST("attendance/manual-requests/{id}/cancel")
    suspend fun cancelManualRequest(@Path("id") id: String): Response<ManualRequestDto>

    // ---- 6. Notifications ----
    @GET("notifications")
    suspend fun notifications(
        @Query("unread_only") unreadOnly: Boolean = false,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): Response<PageDto<NotificationDto>>

    @GET("notifications/unread-count")
    suspend fun unreadCount(): Response<CountDto>

    @POST("notifications/{id}/read")
    suspend fun markRead(@Path("id") id: String): Response<Unit>

    @POST("notifications/read-all")
    suspend fun markAllRead(): Response<Unit>

    // ---- 7. Administration (manager / admin) ----
    @GET("admin/attendance/live")
    suspend fun adminLive(@Query("department_id") departmentId: String? = null): Response<LiveAttendanceResponseDto>

    @GET("admin/attendance/events")
    suspend fun adminEvents(
        @Query("status") status: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("employee_id") employeeId: String? = null,
        @Query("event_type") eventType: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): Response<PageDto<EventDto>>

    @POST("admin/attendance/events/{id}/approve")
    suspend fun adminApproveEvent(@Path("id") id: String, @Body body: ReviewNoteRequestDto): Response<EventDto>

    @POST("admin/attendance/events/{id}/reject")
    suspend fun adminRejectEvent(@Path("id") id: String, @Body body: ReviewNoteRequestDto): Response<EventDto>
}
