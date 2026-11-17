package za.co.meridiangroup.attendance.data.remote

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import za.co.meridiangroup.attendance.core.ApiFailure
import za.co.meridiangroup.attendance.core.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApiCallerTest {
    private val logouts = mutableListOf<ApiFailure>()
    private val caller = ApiCaller { logouts += it }
    private val json = "application/json".toMediaType()

    @Test
    fun successBodyIsReturned() = runTest {
        val r = caller.call<String> { Response.success("hello") }
        assertEquals(ApiResult.Success("hello"), r)
    }

    @Test
    fun emptyBodyMapsToUnit() = runTest {
        val r = caller.call<Unit> { Response.success(204, null) }
        assertIs<ApiResult.Success<Unit>>(r)
    }

    @Test
    fun ioExceptionIsOffline() = runTest {
        val r = caller.call<String> { throw IOException("no route") }
        assertIs<ApiResult.Offline>(r)
    }

    @Test
    fun errorEnvelopeIsParsedAndRateLimitHeaderRead() = runTest {
        val body = """{"error":{"code":"rate_limited","message":"slow"}}""".toResponseBody(json)
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.invalid/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Too Many")
            .headers(Headers.headersOf("Retry-After", "12")).body(body).build()
        val r = caller.call<String> { Response.error(body, raw) }
        val f = assertIs<ApiResult.Failure>(r).failure
        assertEquals("rate_limited", f.code)
        assertEquals(12, f.retryAfterSeconds)
        assertTrue(logouts.isEmpty())
    }

    @Test
    fun deviceRevokedTriggersForcedLogout() = runTest {
        val r = caller.call<String> { Response.error(403, """{"error":{"code":"device_revoked","message":"revoked"}}""".toResponseBody(json)) }
        assertIs<ApiResult.Failure>(r)
        assertEquals("device_revoked", logouts.single().code)
    }
}
