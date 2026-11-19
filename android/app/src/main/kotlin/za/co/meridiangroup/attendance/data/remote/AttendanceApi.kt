package za.co.meridiangroup.attendance.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody
import kotlinx.serialization.json.JsonObject
import za.co.meridiangroup.attendance.data.remote.dto.*

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

    @POST("auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequestDto): Response<Unit>

    /** Invitation links: no authentication, the token in the emailed link is the credential. */
    @GET("auth/invitation")
    suspend fun invitation(@Query("token") token: String): Response<InvitationDto>

    @POST("auth/invitation/accept")
    suspend fun acceptInvitation(@Body body: AcceptInvitationRequestDto): Response<AuthResponseDto>

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
    suspend fun adminApproveEvent(@Path("id") id: String, @Body body: OptionalNoteRequestDto): Response<EventDto>

    @POST("admin/attendance/events/{id}/reject")
    suspend fun adminRejectEvent(@Path("id") id: String, @Body body: ReviewNoteRequestDto): Response<EventDto>

    // ---- §0 First-run setup (no authentication) ----
    @GET("setup/status")
    suspend fun setupStatus(): Response<SetupStatusDto>

    @POST("setup/first-admin")
    suspend fun setupFirstAdmin(@Body body: FirstAdminRequestDto): Response<AuthResponseDto>

    // ---- §7 Administration: overview ----
    @GET("admin/overview")
    suspend fun adminOverview(@Query("date") date: String? = null): Response<OverviewDto>

    @GET("admin/attendance/exceptions")
    suspend fun adminExceptions(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("department_id") departmentId: String? = null,
        @Query("kind") kind: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Response<PageDto<AttendanceExceptionDto>>

    // ---- Employees ----
    @GET("admin/employees")
    suspend fun adminEmployees(
        @Query("search") search: String? = null,
        @Query("department_id") departmentId: String? = null,
        @Query("status") status: String? = null,
        @Query("role") role: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Response<PageDto<UserDto>>

    @POST("admin/employees")
    suspend fun adminCreateEmployee(@Body body: EmployeeCreateRequestDto): Response<EmployeeCreatedDto>

    @GET("admin/employees/{id}")
    suspend fun adminEmployee(@Path("id") id: String): Response<EmployeeDetailDto>

    @PATCH("admin/employees/{id}")
    suspend fun adminUpdateEmployee(@Path("id") id: String, @Body body: EmployeeUpdateRequestDto): Response<UserDto>

    @POST("admin/employees/{id}/deactivate")
    suspend fun adminDeactivateEmployee(@Path("id") id: String, @Body body: ReasonRequestDto): Response<UserDto>

    @POST("admin/employees/{id}/reactivate")
    suspend fun adminReactivateEmployee(@Path("id") id: String): Response<UserDto>

    @POST("admin/employees/{id}/invite")
    suspend fun adminResendInvitation(@Path("id") id: String): Response<InvitationSummaryDto>

    @POST("admin/employees/{id}/reset-password")
    suspend fun adminResetPassword(@Path("id") id: String): Response<TemporaryPasswordDto>

    @POST("admin/employees/{id}/role")
    suspend fun adminChangeRole(@Path("id") id: String, @Body body: RoleRequestDto): Response<UserDto>

    @Streaming
    @GET("admin/employees/{id}/export")
    suspend fun adminExportEmployee(@Path("id") id: String): Response<ResponseBody>

    @POST("admin/employees/{id}/erase")
    suspend fun adminEraseEmployee(@Path("id") id: String, @Body body: EraseRequestDto): Response<EraseResponseDto>

    // ---- Departments ----
    @GET("admin/departments")
    suspend fun adminDepartments(): Response<ItemsDto<AdminDepartmentDto>>

    @POST("admin/departments")
    suspend fun adminCreateDepartment(@Body body: DepartmentCreateRequestDto): Response<AdminDepartmentDto>

    @PATCH("admin/departments/{id}")
    suspend fun adminUpdateDepartment(@Path("id") id: String, @Body body: DepartmentUpdateRequestDto): Response<AdminDepartmentDto>

    // ---- Devices ----
    @GET("admin/devices")
    suspend fun adminDevices(
        @Query("status") status: String? = null,
        @Query("user_id") userId: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Response<PageDto<DeviceDto>>

    @POST("admin/devices/{id}/approve")
    suspend fun adminApproveDevice(@Path("id") id: String): Response<DeviceDto>

    @POST("admin/devices/{id}/revoke")
    suspend fun adminRevokeDevice(@Path("id") id: String, @Body body: ReasonRequestDto): Response<DeviceDto>

    // ---- Attendance records ----
    @GET("admin/attendance/sessions")
    suspend fun adminSessions(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("employee_id") employeeId: String? = null,
        @Query("department_id") departmentId: String? = null,
        @Query("status") status: String? = null,
        @Query("late") late: Boolean? = null,
        @Query("manual") manual: Boolean? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Response<PageDto<SessionDto>>

    @GET("admin/attendance/sessions/{id}")
    suspend fun adminSession(@Path("id") id: String): Response<SessionDetailDto>

    @PATCH("admin/attendance/sessions/{id}")
    suspend fun adminCorrectSession(@Path("id") id: String, @Body body: SessionCorrectionRequestDto): Response<SessionDto>

    @POST("admin/attendance/sessions")
    suspend fun adminCreateSession(@Body body: SessionCreateRequestDto): Response<SessionDto>

    // ---- Manual requests ----
    @GET("admin/manual-requests")
    suspend fun adminManualRequests(
        @Query("status") status: String? = null,
        @Query("type") type: String? = null,
        @Query("employee_id") employeeId: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): Response<PageDto<ManualRequestDto>>

    @POST("admin/manual-requests/{id}/approve")
    suspend fun adminApproveRequest(@Path("id") id: String, @Body body: RequestApproveRequestDto): Response<ManualRequestDto>

    @POST("admin/manual-requests/{id}/reject")
    suspend fun adminRejectRequest(@Path("id") id: String, @Body body: ReviewNoteRequestDto): Response<ManualRequestDto>

    // ---- Reports ----
    @GET("admin/reports/{report}")
    suspend fun adminReport(
        @Path("report") report: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("employee_id") employeeId: String? = null,
        @Query("department_id") departmentId: String? = null,
        @Query("status") status: String? = null,
        @Query("format") format: String = "json",
    ): Response<ReportDto>

    @Streaming
    @GET("admin/reports/{report}")
    suspend fun adminReportFile(
        @Path("report") report: String,
        @Query("format") format: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("employee_id") employeeId: String? = null,
        @Query("department_id") departmentId: String? = null,
        @Query("status") status: String? = null,
    ): Response<ResponseBody>

    // ---- Workplaces & policy ----
    @GET("admin/workplaces")
    suspend fun adminWorkplaces(): Response<ItemsDto<WorkplaceDto>>

    @POST("admin/workplaces")
    suspend fun adminCreateWorkplace(@Body body: JsonObject): Response<WorkplaceDto>

    @PATCH("admin/workplaces/{id}")
    suspend fun adminUpdateWorkplace(@Path("id") id: String, @Body body: JsonObject): Response<WorkplaceDto>

    @GET("admin/policy")
    suspend fun adminPolicy(): Response<PolicyDto>

    @PATCH("admin/policy")
    suspend fun adminUpdatePolicy(@Body body: JsonObject): Response<PolicyDto>

    // ---- Roles, audit, broadcast ----
    @GET("admin/roles")
    suspend fun adminRoles(): Response<RolesResponseDto>

    @GET("admin/audit-logs")
    suspend fun adminAuditLogs(
        @Query("actor_id") actorId: String? = null,
        @Query("action") action: String? = null,
        @Query("entity_type") entityType: String? = null,
        @Query("entity_id") entityId: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Response<PageDto<AuditLogDto>>

    @POST("admin/notifications/broadcast")
    suspend fun adminBroadcast(@Body body: BroadcastRequestDto): Response<BroadcastResponseDto>
}
