package za.co.meridiangroup.attendance.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import za.co.meridiangroup.attendance.core.ApiErrorMapper
import za.co.meridiangroup.attendance.core.ApiFailure
import za.co.meridiangroup.attendance.data.local.TokenPair
import za.co.meridiangroup.attendance.data.local.TokenHolder
import za.co.meridiangroup.attendance.data.remote.dto.RefreshRequestDto

/** Adds `Authorization: Bearer …` plus the standard headers to every request that has a token. */
class AuthInterceptor(private val tokenStore: TokenHolder, private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
        if (original.header("Authorization") == null) {
            tokenStore.current()?.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        }
        return chain.proceed(builder.build())
    }
}

/**
 * Refreshes the access token exactly once when several requests fail with 401 at the same time
 * (single-flight), retries the request with the new token, and logs the device out when the refresh
 * itself fails with 401 (`token_reused`, `token_invalid`) or when a request still fails after a fresh token.
 */
class TokenAuthenticator(
    private val tokenStore: TokenHolder,
    private val refreshCall: suspend (RefreshRequestDto) -> retrofit2.Response<za.co.meridiangroup.attendance.data.remote.dto.AuthResponseDto>,
    private val forcedLogout: ForcedLogoutListener,
) : Authenticator {
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Never loop: at most one retry per original request.
        if (responseCount(response) >= 2) {
            forcedLogout.onForcedLogout(ApiFailure(401, "token_invalid", "Still unauthorised after refresh"))
            return null
        }
        val failedWith = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        tokenStore.current() ?: return null

        val fresh: TokenPair? = runBlocking {
            mutex.withLock {
                val latest = tokenStore.current() ?: return@withLock null
                // Another request already refreshed while we were waiting: reuse its token.
                if (failedWith != null && latest.accessToken != failedWith) return@withLock latest
                doRefresh(latest)
            }
        }
        if (fresh == null) return null
        return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
    }

    private suspend fun doRefresh(current: TokenPair): TokenPair? {
        val result = runCatching { refreshCall(RefreshRequestDto(current.refreshToken)) }.getOrNull()
        if (result == null) return null // network problem: let the original 401 propagate; caller will retry later
        if (result.isSuccessful) {
            val body = result.body() ?: return null
            val pair = TokenPair(body.accessToken, body.refreshToken, System.currentTimeMillis() + body.expiresIn * 1000L)
            tokenStore.save(pair)
            return pair
        }
        val failure = ApiErrorParser.parse(result.code(), result.errorBody()?.string())
        if (result.code() == 401 || result.code() == 403 || ApiErrorMapper.requiresLogout(result.code(), failure.code)) {
            tokenStore.clear()
            forcedLogout.onForcedLogout(failure)
        }
        return null
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) { count++; prior = prior.priorResponse }
        return count
    }
}
