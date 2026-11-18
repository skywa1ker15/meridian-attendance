// Live contract round-trip: drives a running backend using the Android app's OWN request/response
// DTOs and JSON configuration (data/remote/dto + ApiJson). Every request body is encoded from an app
// DTO and every response is decoded into the DTO the app would use, so a field-name or type mismatch
// between the app and the server fails here instead of on a phone.
//
// Usage: tools/contract-roundtrip/run <base-url> (see the script for required env vars).
// Must only be pointed at a development database: it creates attendance records.

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import za.co.meridiangroup.attendance.data.remote.ApiJson
import za.co.meridiangroup.attendance.data.remote.dto.*

private val json = ApiJson.json
private val http: HttpClient = HttpClient.newHttpClient()
private lateinit var base: String
private var token: String? = null
private var passed = 0
private val failures = mutableListOf<String>()

/** The auth endpoints are rate limited per IP; a throttled call waits out Retry-After and tries again. */
private fun call(method: String, path: String, body: String?): Pair<Int, String> {
    repeat(3) { attempt ->
        val b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() != 429 || attempt == 2) return r.statusCode() to r.body()
        val wait = r.headers().firstValue("Retry-After").map { it.toLongOrNull() ?: 5L }.orElse(5L).coerceIn(1L, 60L)
        println("       (rate limited on $path, waiting ${wait}s)")
        Thread.sleep((wait + 1) * 1000)
    }
    error("unreachable")
}

private fun <T> step(name: String, method: String, path: String, body: String?, ser: KSerializer<T>, expect: Set<Int> = setOf(200, 201)): T? {
    return try {
        val (code, text) = call(method, path, body)
        check(code in expect) { "HTTP $code: ${text.take(300)}" }
        val v = json.decodeFromString(ser, text)
        passed++; println("  ok   $name ($method $path -> $code)"); v
    } catch (e: Throwable) {
        failures += "$name: ${e.message?.lineSequence()?.first()}"; println("  FAIL $name: ${e.message?.take(400)}"); null
    }
}

private fun noContent(name: String, method: String, path: String, body: String?) {
    val (code, text) = call(method, path, body)
    if (code == 204 || code == 202) { passed++; println("  ok   $name ($method $path -> $code)") }
    else { failures += "$name: HTTP $code"; println("  FAIL $name: HTTP $code ${text.take(200)}") }
}

private inline fun <reified T> enc(v: T): String = json.encodeToString(kotlinx.serialization.serializer<T>(), v)

fun main(args: Array<String>) {
    base = args.getOrElse(0) { "http://127.0.0.1:8000" }.trimEnd('/') + "/api/v1"
    val id = System.getenv("RT_ID") ?: error("RT_ID required"); val pw = System.getenv("RT_PW") ?: error("RT_PW required")
    val device = DeviceInfoDto(UUID.randomUUID().toString(), "Pixel 8", "Google", "15", "1.0.0 (1)")
    val now = { Instant.now().toString() }

    println("Employee flow as the app performs it:")
    val auth = step("login with device", "POST", "/auth/login", enc(LoginRequestDto(id, pw, device)), AuthResponseDto.serializer()) ?: return finish()
    token = auth.accessToken
    step("me", "GET", "/auth/me", null, MeResponseDto.serializer())
    val wps = step("workplaces", "GET", "/config/workplaces", null, WorkplacesResponseDto.serializer())
    step("policy", "GET", "/config/policy", null, PolicyDto.serializer())
    step("today (before)", "GET", "/attendance/today", null, TodaySummaryDto.serializer())
    val wp = wps?.items?.firstOrNull() ?: return finish()
    val here = LocationDto(wp.latitude, wp.longitude, 12.0)

    val arrival = step("submit arrival", "POST", "/attendance/events",
        enc(SubmitEventRequestDto(UUID.randomUUID().toString(), wp.id, "arrival", "geofence", now(), "Africa/Johannesburg", "dwell", here, null, false, 80)),
        SubmitEventResponseDto.serializer())
    step("pending list", "GET", "/attendance/pending", null, ItemsDto.serializer(EventDto.serializer()))
    val conf = arrival?.let {
        step("confirm arrival", "POST", "/attendance/events/${it.event.id}/confirm",
            enc(ConfirmEventRequestDto(UUID.randomUUID().toString(), now(), here)), ConfirmEventResponseDto.serializer())
    }
    println("       -> arrival status=${conf?.event?.status} session=${conf?.session?.status}")
    if (conf?.event?.status != "confirmed") failures += "arrival should be confirmed at the workplace, got ${conf?.event?.status}"

    val dep = step("submit departure", "POST", "/attendance/events",
        enc(SubmitEventRequestDto(UUID.randomUUID().toString(), wp.id, "departure", "geofence", now(), "Africa/Johannesburg", "exit", LocationDto(wp.latitude + 0.01, wp.longitude, 15.0), null, false, 70)),
        SubmitEventResponseDto.serializer())
    val depConf = dep?.let {
        step("confirm departure", "POST", "/attendance/events/${it.event.id}/confirm",
            enc(ConfirmEventRequestDto(UUID.randomUUID().toString(), now())), ConfirmEventResponseDto.serializer())
    }
    println("       -> departure status=${depConf?.event?.status} session=${depConf?.session?.status}")

    // Offline replay: a detection plus a dismissal of it, referenced by client_event_id (server id never seen).
    val offlineClient = UUID.randomUUID().toString()
    val offlineEvent = SubmitEventRequestDto(offlineClient, wp.id, "arrival", "geofence", now(), "Africa/Johannesburg", "dwell", here, null, true, 40)
    val syncBody = SyncRequestDto(listOf(
        SyncItemDto("event", null, json.encodeToJsonElement(SubmitEventRequestDto.serializer(), offlineEvent).jsonObject),
        SyncItemDto("dismissal", offlineClient, json.encodeToJsonElement(DismissEventRequestDto.serializer(), DismissEventRequestDto("wrong_detection")).jsonObject),
    ))
    val sync = step("offline sync batch", "POST", "/attendance/sync", enc(syncBody), SyncResponseDto.serializer())
    println("       -> sync results ok=${sync?.results?.map { it.ok }}")
    if (sync != null && !sync.results.all { it.ok }) failures += "sync items not all ok: ${sync.results.map { it.error?.code }}"
    val replay = step("offline sync replay (idempotent)", "POST", "/attendance/sync", enc(syncBody), SyncResponseDto.serializer())
    if (replay != null && replay.results.firstOrNull()?.event?.id != sync?.results?.firstOrNull()?.event?.id) failures += "replay created a different event"

    val sessions = step("history page", "GET", "/attendance/sessions?page=1&page_size=20", null, PageDto.serializer(SessionDto.serializer()))
    sessions?.items?.firstOrNull()?.let { step("session detail", "GET", "/attendance/sessions/${it.id}", null, SessionDetailDto.serializer()) }
    step("own events page", "GET", "/attendance/events?page=1", null, PageDto.serializer(EventDto.serializer()))
    step("today (after)", "GET", "/attendance/today", null, TodaySummaryDto.serializer())

    step("create manual request", "POST", "/attendance/manual-requests",
        enc(ManualRequestCreateDto(UUID.randomUUID().toString(), "missed_clock_out", java.time.LocalDate.now().minusDays(1).toString(),
            null, Instant.now().minusSeconds(86_400).toString(), "Phone battery died (round-trip test)")), ManualRequestDto.serializer())
    step("manual requests page", "GET", "/attendance/manual-requests?page=1", null, PageDto.serializer(ManualRequestDto.serializer()))
    val notes = step("notifications page", "GET", "/notifications?page=1", null, PageDto.serializer(NotificationDto.serializer()))
    step("unread count", "GET", "/notifications/unread-count", null, CountDto.serializer())
    notes?.items?.firstOrNull()?.let { noContent("mark notification read", "POST", "/notifications/${it.id}/read", null) }
    noContent("upload FCM token", "POST", "/devices/me/fcm-token", enc(FcmTokenRequestDto("roundtrip-test-token")))
    step("device", "GET", "/devices/me", null, DeviceDto.serializer())

    val refreshed = step("refresh rotation", "POST", "/auth/refresh", enc(RefreshRequestDto(auth.refreshToken)), AuthResponseDto.serializer())
    token = refreshed?.accessToken ?: token
    val (reuseCode, reuseBody) = call("POST", "/auth/refresh", enc(RefreshRequestDto(auth.refreshToken)))
    val reuse = runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), reuseBody).error.code }.getOrNull()
    if (reuseCode == 401 && reuse == "token_reused") { passed++; println("  ok   refresh-token reuse detected ($reuse)") } else failures += "reuse: $reuseCode $reuse"

    System.getenv("RT_MANAGER_ID")?.let { mid ->
        println("Manage section (administrator screens as the app performs them):")
        token = null
        val m = step("manager login", "POST", "/auth/login", enc(LoginRequestDto(mid, System.getenv("RT_MANAGER_PW")!!, null)), AuthResponseDto.serializer())
        token = m?.accessToken
        val today = java.time.LocalDate.now().toString()
        val weekAgo = java.time.LocalDate.now().minusDays(6).toString()

        step("overview", "GET", "/admin/overview?date=$today", null, OverviewDto.serializer())
        step("live attendance", "GET", "/admin/attendance/live", null, LiveAttendanceResponseDto.serializer())
        step("review queue", "GET", "/admin/attendance/events?status=under_review&page=1", null, PageDto.serializer(EventDto.serializer()))
        step("exceptions", "GET", "/admin/attendance/exceptions?from=$weekAgo&to=$today&page=1", null, PageDto.serializer(AttendanceExceptionDto.serializer()))
        step("departments", "GET", "/admin/departments", null, ItemsDto.serializer(AdminDepartmentDto.serializer()))
        step("devices", "GET", "/admin/devices?page=1", null, PageDto.serializer(DeviceDto.serializer()))
        step("roles", "GET", "/admin/roles", null, RolesResponseDto.serializer())
        step("audit logs", "GET", "/admin/audit-logs?page=1", null, PageDto.serializer(AuditLogDto.serializer()))
        step("admin sessions", "GET", "/admin/attendance/sessions?from=$weekAgo&to=$today&page=1", null, PageDto.serializer(SessionDto.serializer()))
            ?.items?.firstOrNull()?.let { session ->
                step("admin session detail", "GET", "/admin/attendance/sessions/${session.id}", null, SessionDetailDto.serializer())
            }
        step("admin manual requests", "GET", "/admin/manual-requests?status=pending&page=1", null, PageDto.serializer(ManualRequestDto.serializer()))
        step("report preview (daily)", "GET", "/admin/reports/daily?from=$weekAgo&to=$today&format=json", null, ReportDto.serializer())

        // Employees: directory, one-time password on create, detail, edit, role, reset, export.
        val directory = step("employees page", "GET", "/admin/employees?page=1&page_size=5", null, PageDto.serializer(UserDto.serializer()))
        directory?.items?.firstOrNull()?.let { user ->
            step("employee detail", "GET", "/admin/employees/${user.id}", null, EmployeeDetailDto.serializer())
        }
        val suffix = UUID.randomUUID().toString().take(6).uppercase()
        val created = step("create employee", "POST", "/admin/employees",
            enc(EmployeeCreateRequestDto("RT-$suffix", "Roundtrip", "Tester", "roundtrip.$suffix@example.co.za", role = "employee")),
            EmployeeCreatedDto.serializer(), expect = setOf(201))
        if (created != null && created.temporaryPassword.isNullOrBlank() && created.invitation == null) {
            failures += "create employee returned neither temporary_password nor invitation"
        }
        created?.user?.id?.let { newId ->
            step("edit employee", "PATCH", "/admin/employees/$newId", enc(EmployeeUpdateRequestDto(phone = "+27 31 000 0000")), UserDto.serializer())
            step("change role", "POST", "/admin/employees/$newId/role", enc(RoleRequestDto("manager")), UserDto.serializer())
            step("reset password", "POST", "/admin/employees/$newId/reset-password", null, TemporaryPasswordDto.serializer())
            step("deactivate", "POST", "/admin/employees/$newId/deactivate", enc(ReasonRequestDto("contract round-trip test")), UserDto.serializer())
            step("reactivate", "POST", "/admin/employees/$newId/reactivate", null, UserDto.serializer())
        }

        invitationFlow(suffix)

        // Workplaces and policy: the app sends a full body on create and only changed keys on edit.
        val workplaces = step("admin workplaces", "GET", "/admin/workplaces", null, ItemsDto.serializer(WorkplaceDto.serializer()))
        workplaces?.items?.firstOrNull()?.let { existing ->
            val edited = existing.copy(radiusM = existing.radiusM + 1.0)
            val diff = WorkplacePayload.diff(existing, edited)
            step("patch workplace (diff body)", "PATCH", "/admin/workplaces/${existing.id}", diff.toString(), WorkplaceDto.serializer())
            step("patch workplace back", "PATCH", "/admin/workplaces/${existing.id}", WorkplacePayload.diff(edited, existing).toString(), WorkplaceDto.serializer())
        }
        val policy = step("admin policy", "GET", "/admin/policy", null, PolicyDto.serializer())
        policy?.let {
            val diff = PolicyPayload.diff(it, it.copy(graceMinutes = it.graceMinutes))
            if (diff.isNotEmpty()) failures += "policy diff of an unchanged policy is not empty: ${diff.keys}"
            step("patch policy (one key)", "PATCH", "/admin/policy", PolicyPayload.diff(it, it.copy(graceMinutes = it.graceMinutes + 1)).toString(), PolicyDto.serializer())
            step("patch policy back", "PATCH", "/admin/policy", PolicyPayload.diff(it.copy(graceMinutes = it.graceMinutes + 1), it).toString(), PolicyDto.serializer())
        }
    }

    finish()
}


/**
 * Invitation flow as the app performs it: HR creates an invited employee, that employee cannot sign in
 * yet (401 invitation_pending), the emailed link is read (GET /auth/invitation), accepted
 * (POST /auth/invitation/accept, same body as login) and the new password then works on /auth/login.
 *
 * The raw token only ever exists inside the invitation email, so for the accept/login half this needs
 * RT_DB (a psql connection string for the SAME development database) to plant a token the way the
 * server would. Without it the token-less half still runs.
 */
private fun invitationFlow(suffix: String) {
    val adminToken = token
    println("Invitation flow (invite -> accept -> login):")
    val email = "roundtrip.invite.$suffix@example.co.za"
    val number = "RTI-$suffix"
    val created = step(
        "create invited employee", "POST", "/admin/employees",
        enc(EmployeeCreateRequestDto(number, "Roundtrip", "Invitee", email, role = "employee", sendInvitation = true)),
        EmployeeCreatedDto.serializer(), expect = setOf(201),
    ) ?: return
    if (created.invitation == null) failures += "create with send_invitation=true returned no invitation object"
    if (!created.temporaryPassword.isNullOrBlank()) failures += "an invited employee must not get a temporary password"
    if (created.invitation?.expiresAt.isNullOrBlank()) failures += "invitation has no expires_at"
    // When the email could not be sent the admin must be handed the link, otherwise the employee is stranded.
    created.invitation?.let { inv ->
        if (inv.sent != true && inv.link.isNullOrBlank()) failures += "invitation was not emailed and carries no link"
        if (inv.sent == true && !inv.link.isNullOrBlank()) failures += "an emailed invitation must not expose its link"
        println("       -> invitation sent=${inv.sent} link=${if (inv.link.isNullOrBlank()) "none" else "present"}")
    }

    // The app's login screen maps this code onto "finish setting up your account".
    token = null
    val (pendingCode, pendingBody) = call("POST", "/auth/login", enc(LoginRequestDto(number, "Whatever-Pass-2026", null)))
    val pendingError = runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), pendingBody).error.code }.getOrNull()
    if (pendingCode == 401 && pendingError == "invitation_pending") {
        passed++; println("  ok   login before activation -> 401 invitation_pending")
    } else {
        failures += "login before activation: $pendingCode $pendingError"
    }

    // A bad link must answer 400 token_invalid (the app shows "ask your manager for a new one").
    val (badCode, badBody) = call("GET", "/auth/invitation?token=not-a-real-token", null)
    val badError = runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), badBody).error.code }.getOrNull()
    if (badCode == 400 && badError == "token_invalid") { passed++; println("  ok   invalid invitation link -> 400 token_invalid") }
    else failures += "invalid invitation link: $badCode $badError"

    val raw = plantInvitation(created.user.id)
    if (raw == null) {
        println("  skip accept/login: set RT_DB=<psql connection string> to exercise the whole invitation flow")
    } else {
        val details = step("read invitation", "GET", "/auth/invitation?token=$raw", null, InvitationDto.serializer())
        if (details != null && details.email != email) failures += "invitation is for ${details.email}, expected $email"
        if (details != null && details.phone != null) failures += "an invited employee should not have a phone yet"
        // Must not contain the employee number, email, first or last name: the server checks all four.
        val password = "Umgeni-Ridge-$suffix"
        val accepted = step(
            "accept invitation", "POST", "/auth/invitation/accept",
            enc(AcceptInvitationRequestDto(raw, "082 123 4567", password, DeviceInfoDto(java.util.UUID.randomUUID().toString(), "Pixel 8", "Google", "15", "1.0.0 (1)"))),
            AuthResponseDto.serializer(), expect = setOf(201),
        )
        if (accepted != null && accepted.user.phone != "+27821234567") failures += "phone stored as ${accepted?.user?.phone}, expected +27821234567"
        if (accepted != null && accepted.mustChangePassword) failures += "an activated employee must not be asked to change their password"
        val (reuseCode, reuseBody) = call("POST", "/auth/invitation/accept", enc(AcceptInvitationRequestDto(raw, "0821234567", "Another-Pass-2026", null)))
        val reuseError = runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), reuseBody).error.code }.getOrNull()
        if (reuseCode == 400 && reuseError == "token_invalid") { passed++; println("  ok   an accepted invitation cannot be reused") }
        else failures += "invitation reuse: $reuseCode $reuseError"
        step("login with the chosen password", "POST", "/auth/login", enc(LoginRequestDto(number, password, null)), AuthResponseDto.serializer())
    }

    // Password reset: a bad reset token is rejected, and forgot-password always answers 202.
    val (resetCode, resetBody) = call("POST", "/auth/password/reset", enc(ResetPasswordRequestDto("not-a-real-token", "Umgeni-Ridge-Reset-2026")))
    val resetError = runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), resetBody).error.code }.getOrNull()
    when {
        resetCode == 400 && resetError == "token_invalid" -> { passed++; println("  ok   invalid reset link -> 400 token_invalid") }
        // The reset endpoints share a tight rate limit; being throttled is not a contract failure.
        resetCode == 429 -> println("  skip invalid reset link: rate limited by the server")
        else -> failures += "invalid reset link: $resetCode $resetError"
    }
    noContent("forgot password", "POST", "/auth/password/forgot", enc(ForgotPasswordRequestDto(email)))

    // Resend invitation (the "Resend invitation" action on an employee's profile).
    token = adminToken
    val second = step(
        "create a second invited employee", "POST", "/admin/employees",
        enc(EmployeeCreateRequestDto("RTI2-$suffix", "Roundtrip", "Resend", "roundtrip.resend.$suffix@example.co.za", role = "employee", sendInvitation = true)),
        EmployeeCreatedDto.serializer(), expect = setOf(201),
    )
    second?.user?.id?.let { id ->
        val resent = step("resend invitation", "POST", "/admin/employees/$id/invite", null, InvitationSummaryDto.serializer())
        if (resent != null && resent.sent != true && resent.link.isNullOrBlank()) failures += "resent invitation was not emailed and carries no link"
    }
}

/**
 * Plants an invitation token for [userId] directly in the development database, exactly as
 * app/services/invitations.py does (SHA-256 of the raw token). Returns null when RT_DB is unset.
 */
private fun plantInvitation(userId: String): String? {
    val db = System.getenv("RT_DB")?.takeIf { it.isNotBlank() } ?: return null
    val raw = "rt-" + java.util.UUID.randomUUID().toString().replace("-", "")
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val sql = "insert into invitation_tokens (id, user_id, token_hash, expires_at) values " +
        "('${java.util.UUID.randomUUID()}', '$userId', '$hash', now() + interval '7 days')"
    val process = ProcessBuilder("psql", db, "-v", "ON_ERROR_STOP=1", "-c", sql).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    return if (process.waitFor() == 0) raw else { failures += "could not plant an invitation token: $output"; null }
}

private fun finish() {
    println("\ncontract-roundtrip: $passed checks passed, ${failures.size} failed")
    failures.forEach { println("  - $it") }
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
