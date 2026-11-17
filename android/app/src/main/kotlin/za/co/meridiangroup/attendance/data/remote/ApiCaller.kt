package za.co.meridiangroup.attendance.data.remote

import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import za.co.meridiangroup.attendance.core.ApiErrorMapper
import za.co.meridiangroup.attendance.core.ApiFailure
import za.co.meridiangroup.attendance.core.ApiResult

/** Hook invoked when the server tells us the session is gone for good (token_reused, device_revoked…). */
fun interface ForcedLogoutListener {
    fun onForcedLogout(failure: ApiFailure)
}

/**
 * Executes a Retrofit call and converts the outcome into an [ApiResult]: network problems become
 * [ApiResult.Offline], non-2xx become [ApiResult.Failure] with the parsed error envelope.
 */
class ApiCaller(private val forcedLogout: ForcedLogoutListener) {

    suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> {
        val response = try {
            block()
        } catch (e: IOException) {
            return ApiResult.Offline(e.message ?: "io")
        } catch (e: SerializationException) {
            return ApiResult.Failure(ApiFailure(200, ApiErrorMapper.PARSE_ERROR_CODE, e.message ?: "parse"))
        } catch (e: IllegalStateException) {
            // Retrofit / converter misuse surfaces as ISE; never crash the caller.
            return ApiResult.Failure(ApiFailure(200, ApiErrorMapper.PARSE_ERROR_CODE, e.message ?: "state"))
        }
        if (response.isSuccessful) {
            val body = response.body()
            @Suppress("UNCHECKED_CAST")
            return if (body != null) ApiResult.Success<T>(body) else ApiResult.Success<T>(Unit as T)
        }
        val failure = ApiErrorParser.parse(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"])
        if (failure.requiresLogout) forcedLogout.onForcedLogout(failure)
        return ApiResult.Failure(failure)
    }
}
