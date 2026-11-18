package za.co.meridiangroup.attendance.core

/** A parsed API error (`{ "error": { "code", "message", "details" } }`) plus the HTTP status. */
data class ApiFailure(
    val httpStatus: Int,
    val code: String,
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
    val retryAfterSeconds: Int? = null,
) {
    val isAuthFailure: Boolean get() = httpStatus == 401
    val requiresLogout: Boolean get() = ApiErrorMapper.requiresLogout(httpStatus, code)
    val isRetryable: Boolean get() = ApiErrorMapper.isRetryable(httpStatus)
}

/** Result of a network call without exceptions crossing layer boundaries. */
sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>()
    data class Offline(val cause: String) : ApiResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
        is Offline -> this
    }

    fun getOrNull(): T? = (this as? Success)?.value
    val isSuccess: Boolean get() = this is Success

    /** User-facing message for any non-success result. */
    fun userMessage(): String? = when (this) {
        is Success -> null
        is Failure -> ApiErrorMapper.userMessage(failure)
        is Offline -> ApiErrorMapper.OFFLINE_MESSAGE
    }
}

object ApiErrorMapper {
    const val OFFLINE_MESSAGE = "You're offline. We'll retry automatically when you're back online."
    const val NETWORK_ERROR_CODE = "network_error"
    const val PARSE_ERROR_CODE = "parse_error"

    private val logoutCodes = setOf("token_reused", "token_invalid", "device_revoked", "account_inactive")

    /** Login-time codes that say something about the account, not about this device's session. */
    private val sessionlessCodes = setOf("invalid_credentials", "token_expired", "invitation_pending")

    /**
     * Codes that mean the device must drop its session and show the login screen.
     * Only 401/403 answers can do that: an invitation or reset link that has expired also answers
     * `token_invalid`, but with 400, and must never sign an administrator out mid-session.
     */
    fun requiresLogout(httpStatus: Int, code: String): Boolean {
        if (code in sessionlessCodes) return false
        if (httpStatus != 401 && httpStatus != 403) return false
        return code in logoutCodes || httpStatus == 401
    }

    fun isRetryable(httpStatus: Int): Boolean = httpStatus == 408 || httpStatus == 429 || httpStatus >= 500

    fun userMessage(failure: ApiFailure): String {
        val fieldHint = failure.fieldErrors.entries.firstOrNull()?.let { "${StatusTones.label(it.key)}: ${it.value}" }
        return when (failure.code) {
            "invalid_credentials" -> "Your employee number/email or password is incorrect."
            "account_locked" -> "Too many failed attempts. Your account is locked for about 15 minutes."
            "account_inactive" -> "Your account is inactive. Please contact HR."
            "device_revoked" -> "This device has been revoked. Please sign in again or contact an administrator."
            "device_pending_approval" -> "This device is waiting for administrator approval."
            "password_change_required" -> "You must change your password before continuing."
            "invitation_pending" ->
                "You haven't set a password yet. Open the invitation link we emailed you to finish setting up your account."
            "token_invalid" ->
                if (failure.httpStatus == 400) "That link is no longer valid. Ask your manager to send a new one."
                else "Your session has expired. Please sign in again."
            "token_expired", "token_reused" -> "Your session has expired. Please sign in again."
            "forbidden" -> "You don't have permission to do that."
            "not_found" -> "That record no longer exists."
            "invalid_state" -> failure.message.ifBlank { "This action is no longer possible for this record." }
            "duplicate", "conflict" -> failure.message.ifBlank { "This was already submitted." }
            "rate_limited" -> {
                val secs = failure.retryAfterSeconds
                if (secs != null && secs > 0) "Too many requests. Please try again in $secs seconds." else "Too many requests. Please try again shortly."
            }
            "validation_error" -> fieldHint ?: failure.message.ifBlank { "Please check the highlighted fields." }
            "server_error" -> "Something went wrong on the server. Please try again."
            NETWORK_ERROR_CODE -> OFFLINE_MESSAGE
            PARSE_ERROR_CODE -> "The server sent an unexpected response. Please update the app or try again."
            else -> when (failure.httpStatus) {
                in 500..599 -> "The server is having trouble. Please try again later."
                else -> failure.message.ifBlank { "Request failed (${failure.httpStatus})." }
            }
        }
    }

    /** Builds a failure for a response whose body could not be parsed as the standard error envelope. */
    fun fallback(httpStatus: Int): ApiFailure = ApiFailure(
        httpStatus = httpStatus,
        code = when (httpStatus) {
            401 -> "token_invalid"
            403 -> "forbidden"
            404 -> "not_found"
            429 -> "rate_limited"
            in 500..599 -> "server_error"
            else -> "http_$httpStatus"
        },
        message = "",
    )
}
