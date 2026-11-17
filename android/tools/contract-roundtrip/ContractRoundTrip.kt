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

private fun call(method: String, path: String, body: String?): Pair<Int, String> {
    val b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
    token?.let { b.header("Authorization", "Bearer $it") }
    b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    return r.statusCode() to r.body()
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
        println("Manager screens:")
        token = null
        val m = step("manager login", "POST", "/auth/login", enc(LoginRequestDto(mid, System.getenv("RT_MANAGER_PW")!!, null)), AuthResponseDto.serializer())
        token = m?.accessToken
        step("live attendance", "GET", "/admin/attendance/live", null, LiveAttendanceResponseDto.serializer())
        step("review queue", "GET", "/admin/attendance/events?status=under_review&page=1", null, PageDto.serializer(EventDto.serializer()))
    }
    finish()
}

private fun finish() {
    println("\ncontract-roundtrip: $passed checks passed, ${failures.size} failed")
    failures.forEach { println("  - $it") }
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
