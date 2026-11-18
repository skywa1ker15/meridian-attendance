package za.co.meridiangroup.attendance.data.repository

import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import za.co.meridiangroup.attendance.BuildConfig
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.TokenPair
import za.co.meridiangroup.attendance.data.local.TokenStore
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.AuthResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.ChangePasswordRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceInfoDto
import za.co.meridiangroup.attendance.data.remote.dto.FirstAdminRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ForgotPasswordRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.LoginRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.LogoutRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.MeResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.NotificationPreferencesDto
import za.co.meridiangroup.attendance.data.remote.dto.PatchMeRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SetupStatusDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto

/** Session state that drives the top-level navigation. */
sealed class SessionState {
    object LoggedOut : SessionState()
    data class LoggedIn(val mustChangePassword: Boolean) : SessionState()
}

class AuthRepository(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val tokenStore: TokenStore,
    private val prefs: AppPreferences,
    private val now: () -> Long,
    /** Wipes local caches, outbox, geofences and notifications. Supplied by AppGraph. */
    private val onLocalDataWipe: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val userFlow: Flow<UserDto?> = prefs.userFlow

    /** Called at app start: restore the session from the encrypted token store. */
    suspend fun restore() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        _state.value = if (tokenStore.isLoggedIn) SessionState.LoggedIn(prefs.mustChangePassword()) else SessionState.LoggedOut
    }

    fun deviceInfo(fcmToken: String?): DeviceInfoDto = DeviceInfoDto(
        deviceId = prefs.installationId(),
        model = Build.MODEL ?: "unknown",
        manufacturer = Build.MANUFACTURER ?: "unknown",
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        fcmToken = fcmToken,
    )

    suspend fun login(identifier: String, password: String): ApiResult<AuthResponseDto> {
        val result = caller.call { api.login(LoginRequestDto(identifier.trim(), password, deviceInfo(prefs.fcmToken()))) }
        if (result is ApiResult.Success) onAuthenticated(result.value)
        return result
    }

    /** docs/API.md §0: is this server still waiting for its first administrator? */
    suspend fun setupStatus(): ApiResult<SetupStatusDto> = caller.call { api.setupStatus() }

    /**
     * §0 POST /setup/first-admin — creates the very first superadmin and signs them in. The response is the
     * login response, so the tokens and the cached user are stored exactly as after [login].
     */
    suspend fun createFirstAdmin(body: FirstAdminRequestDto): ApiResult<AuthResponseDto> {
        val result = caller.call { api.setupFirstAdmin(body.copy(device = deviceInfo(prefs.fcmToken()))) }
        if (result is ApiResult.Success) onAuthenticated(result.value)
        return result
    }

    private suspend fun onAuthenticated(body: AuthResponseDto) {
        tokenStore.save(TokenPair(body.accessToken, body.refreshToken, now() + body.expiresIn * 1000L))
        val mustChange = body.mustChangePassword || body.user.mustChangePassword
        prefs.saveUser(body.user, mustChange)
        prefs.setDeviceStatus(body.device?.status)
        prefs.fcmToken()?.let { prefs.setFcmTokenUploaded(it) }
        _state.value = SessionState.LoggedIn(mustChange)
    }

    suspend fun forgotPassword(identifier: String): ApiResult<Unit> =
        caller.call { api.forgotPassword(ForgotPasswordRequestDto(identifier.trim())) }

    suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
        val r = caller.call { api.changePassword(ChangePasswordRequestDto(current, new)) }
        if (r is ApiResult.Success) {
            prefs.setMustChangePassword(false)
            _state.value = SessionState.LoggedIn(mustChangePassword = false)
        }
        return r
    }

    suspend fun refreshMe(): ApiResult<MeResponseDto> {
        val r = caller.call { api.me() }
        if (r is ApiResult.Success) {
            prefs.saveUser(r.value.user, r.value.user.mustChangePassword || prefs.mustChangePassword())
            prefs.setDeviceStatus(r.value.device?.status)
        }
        return r
    }

    suspend fun myDevice(): ApiResult<DeviceDto> = caller.call { api.myDevice() }

    suspend fun updatePhone(phone: String): ApiResult<UserDto> {
        val r = caller.call { api.patchMe(PatchMeRequestDto(phone = phone.trim())) }
        if (r is ApiResult.Success) prefs.saveUser(r.value, prefs.mustChangePassword())
        return r
    }

    suspend fun setPushEnabled(enabled: Boolean): ApiResult<UserDto> {
        val r = caller.call { api.patchMe(PatchMeRequestDto(notificationPreferences = NotificationPreferencesDto(push = enabled))) }
        if (r is ApiResult.Success) {
            prefs.saveUser(r.value, prefs.mustChangePassword())
            prefs.setPushEnabled(enabled)
        }
        return r
    }

    /** User-initiated logout: revoke the refresh token server-side (best effort), then wipe local data. */
    suspend fun logout() {
        tokenStore.current()?.let { pair -> caller.call { api.logout(LogoutRequestDto(pair.refreshToken)) } }
        wipe()
    }

    /** Server-initiated logout (token_reused, device_revoked…): no network call, just wipe. */
    suspend fun forceLogout() = wipe()

    private suspend fun wipe() {
        tokenStore.clear()
        prefs.clearSession()
        onLocalDataWipe()
        _state.value = SessionState.LoggedOut
    }
}
