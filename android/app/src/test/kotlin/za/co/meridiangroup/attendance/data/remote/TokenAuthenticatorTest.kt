package za.co.meridiangroup.attendance.data.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import retrofit2.Retrofit
import retrofit2.create
import za.co.meridiangroup.attendance.core.ApiFailure
import za.co.meridiangroup.attendance.data.local.TokenHolder
import za.co.meridiangroup.attendance.data.local.TokenPair
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Single-flight refresh, retry with the new token, logout on token_reused (docs/API.md §1). */
class TokenAuthenticatorTest {
    private lateinit var server: MockWebServer
    private val refreshCalls = AtomicInteger(0)
    private val logouts = mutableListOf<ApiFailure>()

    private class MemoryTokens(var pair: TokenPair?) : TokenHolder {
        override fun current() = pair
        override fun save(pair: TokenPair) { this.pair = pair }
        override fun clear() { pair = null }
    }

    private val authBody = """{"access_token":"NEW","refresh_token":"R2","token_type":"bearer","expires_in":900,
        "must_change_password":false,"user":{"id":"u1"},"device":null}"""

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.shutdown() }

    private fun client(tokens: TokenHolder): OkHttpClient {
        val refreshApi = Retrofit.Builder().baseUrl(server.url("/api/v1/")).client(OkHttpClient())
            .addConverterFactory(ApiJson.json.asConverterFactory("application/json".toMediaType())).build().create<AttendanceApi>()
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens, "test"))
            .authenticator(TokenAuthenticator(tokens, { refreshApi.refresh(it) }, ForcedLogoutListener { logouts += it }))
            .build()
    }

    private fun dispatcher(refreshResponse: () -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.endsWith("/auth/refresh") -> { refreshCalls.incrementAndGet(); Thread.sleep(100); refreshResponse() }
            request.getHeader("Authorization") == "Bearer NEW" -> MockResponse().setBody("""{"ok":true}""")
            else -> MockResponse().setResponseCode(401).setBody("""{"error":{"code":"token_expired","message":"expired"}}""")
        }
    }

    @Test
    fun refreshesOnceAndRetriesWithNewToken() {
        server.dispatcher = dispatcher { MockResponse().setBody(authBody) }
        server.start()
        val tokens = MemoryTokens(TokenPair("OLD", "R1", 0))
        val c = client(tokens)
        val resp = c.newCall(Request.Builder().url(server.url("/api/v1/attendance/today")).build()).execute()
        assertEquals(200, resp.code)
        assertEquals("NEW", tokens.pair?.accessToken)
        assertEquals("R2", tokens.pair?.refreshToken)
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun concurrentUnauthorisedRequestsShareOneRefresh() {
        server.dispatcher = dispatcher { MockResponse().setBody(authBody) }
        server.start()
        val tokens = MemoryTokens(TokenPair("OLD", "R1", 0))
        val c = client(tokens)
        val pool = Executors.newFixedThreadPool(5)
        val done = CountDownLatch(5)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(5) {
            pool.execute {
                c.newCall(Request.Builder().url(server.url("/api/v1/attendance/today")).build()).execute().use { codes += it.code }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(List(5) { 200 }, codes.toList())
        assertEquals(1, refreshCalls.get(), "refresh must be single-flight")
    }

    @Test
    fun tokenReusedLogsOut() {
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"token_reused","message":"reused"}}""")
        }
        server.start()
        val tokens = MemoryTokens(TokenPair("OLD", "R1", 0))
        val resp = client(tokens).newCall(Request.Builder().url(server.url("/api/v1/attendance/today")).build()).execute()
        assertEquals(401, resp.code)
        assertNull(tokens.pair)
        assertEquals("token_reused", logouts.single().code)
    }
}
