package za.co.meridiangroup.attendance.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.RequestType
import za.co.meridiangroup.attendance.core.Validation
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestCreateDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestDto
import za.co.meridiangroup.attendance.data.repository.RequestRepository
import za.co.meridiangroup.attendance.data.repository.SubmitOutcome

data class RequestsUiState(
    val status: String? = null,
    val items: List<ManualRequestDto> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val cancellingId: String? = null,
    val message: String? = null,
)

class RequestsViewModel(private val repo: RequestRepository) : ViewModel() {
    private val _state = MutableStateFlow(RequestsUiState())
    val state: StateFlow<RequestsUiState> = _state.asStateFlow()

    init { reload() }

    fun setStatus(status: String?) { _state.update { it.copy(status = status) }; reload() }

    fun reload() {
        _state.update { it.copy(items = emptyList(), page = 0, hasMore = true, error = null) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = repo.list(s.status, s.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, items = it.items + r.value.items, page = r.value.page, hasMore = r.value.hasMore) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun cancel(id: String) {
        _state.update { it.copy(cancellingId = id, message = null) }
        viewModelScope.launch {
            when (val r = repo.cancel(id)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(cancellingId = null, items = st.items.map { if (it.id == id) r.value else it }, message = "Request cancelled.")
                }
                else -> _state.update { it.copy(cancellingId = null, message = r.userMessage()) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

data class NewRequestUiState(
    val type: RequestType = RequestType.MISSED_CLOCK_OUT,
    val workDate: String? = null,
    val clockInTime: String? = null,   // HH:mm local
    val clockOutTime: String? = null,  // HH:mm local
    val reason: String = "",
    val sessionId: String? = null,
    val errors: Map<String, String> = emptyMap(),
    val submitting: Boolean = false,
    val result: String? = null,
    val done: Boolean = false,
    // Profile correction mode
    val profileField: String = "phone",
    val profileValue: String = "",
)

/** Manual attendance request (and, with [profileMode], a profile_correction request). */
class NewRequestViewModel(
    private val repo: RequestRepository,
    private val now: () -> Long,
    val profileMode: Boolean,
    prefillSessionId: String?,
    prefillWorkDate: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        NewRequestUiState(
            type = if (prefillSessionId != null) RequestType.CORRECTION else RequestType.MISSED_CLOCK_OUT,
            sessionId = prefillSessionId,
            workDate = prefillWorkDate ?: JohannesburgTime.workDateOf(now()),
        ),
    )
    val state: StateFlow<NewRequestUiState> = _state.asStateFlow()

    fun setType(t: RequestType) = _state.update { it.copy(type = t, errors = emptyMap()) }
    fun setDate(d: String) = _state.update { it.copy(workDate = d, errors = it.errors - "work_date") }
    fun setClockIn(t: String) = _state.update { it.copy(clockInTime = t, errors = it.errors - "requested_clock_in") }
    fun setClockOut(t: String) = _state.update { it.copy(clockOutTime = t, errors = it.errors - "requested_clock_out") }
    fun setReason(r: String) = _state.update { it.copy(reason = r, errors = it.errors - "reason") }
    fun setProfileField(f: String) = _state.update { it.copy(profileField = f, errors = emptyMap()) }
    fun setProfileValue(v: String) = _state.update { it.copy(profileValue = v, errors = it.errors - "requested_value") }

    private fun instant(date: String?, time: String?): Instant? =
        if (date == null || time == null) null else JohannesburgTime.localToInstant(date, time)

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val errors: Map<String, String>
        val builder: (String) -> ManualRequestCreateDto
        if (profileMode) {
            val e = LinkedHashMap<String, String>()
            Validation.profileCorrection(s.profileField, s.profileValue)?.let { e["requested_value"] = it }
            Validation.reason(s.reason)?.let { e["reason"] = it }
            errors = e
            builder = { id ->
                ManualRequestCreateDto(
                    clientRequestId = id, type = RequestType.PROFILE_CORRECTION.wire, workDate = null,
                    reason = s.reason.trim(), field = s.profileField, requestedValue = s.profileValue.trim(),
                )
            }
        } else {
            val clockIn = if (s.type.needsClockIn) instant(s.workDate, s.clockInTime) else null
            val clockOut = if (s.type.needsClockOut) instant(s.workDate, s.clockOutTime) else null
            errors = Validation.manualRequest(Validation.ManualRequestInput(s.type, s.workDate, clockIn, clockOut, s.reason, now()))
            builder = { id ->
                ManualRequestCreateDto(
                    clientRequestId = id, type = s.type.wire, workDate = s.workDate,
                    requestedClockIn = clockIn?.let { JohannesburgTime.toIsoUtc(it) },
                    requestedClockOut = clockOut?.let { JohannesburgTime.toIsoUtc(it) },
                    reason = s.reason.trim(), sessionId = s.sessionId,
                )
            }
        }
        if (errors.isNotEmpty()) { _state.update { it.copy(errors = errors) }; return }
        _state.update { it.copy(submitting = true, result = null) }
        viewModelScope.launch {
            when (val o = repo.submit(builder)) {
                is SubmitOutcome.Created -> _state.update { it.copy(submitting = false, done = true, result = "Request submitted. A manager will review it.") }
                SubmitOutcome.Queued -> _state.update { it.copy(submitting = false, done = true, result = "Saved on your phone. It will be sent automatically when you're online.") }
                is SubmitOutcome.Failed -> _state.update { it.copy(submitting = false, errors = o.fieldErrors, result = o.message) }
            }
        }
    }
}
