package za.co.meridiangroup.attendance.ui.admin.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.ChipFilters
import za.co.meridiangroup.attendance.ui.admin.NoteDialog
import za.co.meridiangroup.attendance.ui.admin.PagedUiState
import za.co.meridiangroup.attendance.ui.admin.PagedFooter
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class ReviewUiState(
    val tab: Int = 0,
    val status: String = "under_review",
    val events: PagedUiState<EventDto> = PagedUiState(),
    val requests: PagedUiState<ManualRequestDto> = PagedUiState(),
    val requestStatus: String = "pending",
    val message: String? = null,
    val busyId: String? = null,
)

/** Review queue: pending/under-review detections and pending manual requests (both need `attendance:review`). */
class AdminReviewViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init { reload(); reloadRequests() }

    fun selectTab(index: Int) {
        _state.update { it.copy(tab = index) }
        if (index == 1 && _state.value.requests.items.isEmpty()) reloadRequests()
    }

    fun setStatus(status: String) { _state.update { it.copy(status = status) }; reload() }
    fun setRequestStatus(status: String) { _state.update { it.copy(requestStatus = status) }; reloadRequests() }

    fun reload() { _state.update { it.copy(events = it.events.reset()) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.events.loading || !s.events.hasMore) return
        _state.update { it.copy(events = it.events.startLoading()) }
        viewModelScope.launch {
            when (val r = repo.reviewQueue(s.status, s.events.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(events = it.events.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(events = it.events.failed(r.userMessage())) }
            }
        }
    }

    fun reloadRequests() { _state.update { it.copy(requests = it.requests.reset()) }; loadMoreRequests() }

    fun loadMoreRequests() {
        val s = _state.value
        if (s.requests.loading || !s.requests.hasMore) return
        _state.update { it.copy(requests = it.requests.startLoading()) }
        viewModelScope.launch {
            when (val r = repo.manualRequests(s.requestStatus, null, null, s.requests.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(requests = it.requests.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(requests = it.requests.failed(r.userMessage())) }
            }
        }
    }

    fun decide(id: String, approve: Boolean, note: String) {
        _state.update { it.copy(busyId = id, message = null) }
        viewModelScope.launch {
            val r = if (approve) repo.approve(id, note) else repo.reject(id, note)
            _state.update { st ->
                if (r is ApiResult.Success) {
                    st.copy(busyId = null, events = st.events.copy(items = st.events.items.filterNot { it.id == id }),
                        message = if (approve) "Event approved." else "Event rejected.")
                } else {
                    st.copy(busyId = null, message = r.userMessage())
                }
            }
        }
    }

    fun decideRequest(id: String, approve: Boolean, note: String, clockInAt: String?, clockOutAt: String?) {
        _state.update { it.copy(busyId = id, message = null) }
        viewModelScope.launch {
            val r = if (approve) repo.approveRequest(id, note, clockInAt, clockOutAt) else repo.rejectRequest(id, note)
            _state.update { st ->
                if (r is ApiResult.Success) {
                    st.copy(busyId = null, requests = st.requests.copy(items = st.requests.items.filterNot { it.id == id }),
                        message = if (approve) "Request approved." else "Request rejected.")
                } else {
                    st.copy(busyId = null, message = r.userMessage())
                }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun AdminReviewScreen(viewModel: AdminReviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var decidingEvent by remember { mutableStateOf<Pair<EventDto, Boolean>?>(null) }
    var decidingRequest by remember { mutableStateOf<Pair<ManualRequestDto, Boolean>?>(null) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = state.tab) {
            Tab(selected = state.tab == 0, onClick = { viewModel.selectTab(0) }, text = { Text(stringResource(R.string.review_tab_events)) }, modifier = Modifier.testTag("tab_events"))
            Tab(selected = state.tab == 1, onClick = { viewModel.selectTab(1) }, text = { Text(stringResource(R.string.review_tab_requests)) }, modifier = Modifier.testTag("tab_requests"))
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("admin_review_list"),
            contentPadding = PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item { AdminTitle(stringResource(R.string.manage_review), stringResource(R.string.manage_review_body)) }
            item { AdminMessage(state.message, viewModel::clearMessage) }
            if (state.tab == 0) {
                item {
                    ChipFilters(
                        listOf("under_review" to StatusTones.label("under_review"), "pending" to StatusTones.label("pending")),
                        state.status, viewModel::setStatus,
                    )
                }
                items(state.events.items, key = { it.id }) { event ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(event.employee?.fullName ?: "—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(event.status)
                        }
                        KeyValueRow(stringResource(R.string.admin_event), "${StatusTones.label(event.eventType)} · ${StatusTones.label(event.source)}")
                        KeyValueRow(stringResource(R.string.confirm_detected), JohannesburgTime.dateTime(event.detectedAt))
                        KeyValueRow(stringResource(R.string.confirm_distance), Geo.formatDistance(event.distanceFromWorkplaceM))
                        KeyValueRow(stringResource(R.string.admin_score), event.verificationScore?.let { "%.2f".format(it) } ?: "—")
                        KeyValueRow(stringResource(R.string.review_wifi), stringResource(if (event.wifiVerified) R.string.review_wifi_matched else R.string.review_wifi_not_matched))
                        event.reviewReason?.let { KeyValueRow(stringResource(R.string.admin_reason), StatusTones.label(it)) }
                        event.verification?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                            Text(notes.joinToString(" · ") { StatusTones.label(it) }, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            PillButton(stringResource(R.string.admin_approve), { decidingEvent = event to true }, style = PillStyle.SUCCESS, loading = state.busyId == event.id)
                            PillButton(stringResource(R.string.admin_reject), { decidingEvent = event to false }, style = PillStyle.DANGER, enabled = state.busyId != event.id)
                        }
                    }
                }
                item {
                    PagedFooter(state.events, stringResource(R.string.admin_review_empty_title), stringResource(R.string.admin_review_empty_body), viewModel::reload, viewModel::loadMore)
                }
            } else {
                item {
                    ChipFilters(
                        listOf("pending" to StatusTones.label("pending"), "approved" to StatusTones.label("approved"), "rejected" to StatusTones.label("rejected")),
                        state.requestStatus, viewModel::setRequestStatus,
                    )
                }
                items(state.requests.items, key = { it.id }) { request ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(request.employee?.fullName ?: "—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(request.status)
                        }
                        KeyValueRow(stringResource(R.string.requests_type), StatusTones.label(request.type))
                        KeyValueRow(stringResource(R.string.requests_work_date), JohannesburgTime.workDate(request.workDate))
                        if (request.type == "profile_correction") {
                            KeyValueRow(StatusTones.label(request.field), request.requestedValue ?: "—")
                        } else {
                            KeyValueRow(stringResource(R.string.requests_clock_in), JohannesburgTime.time(request.requestedClockIn))
                            KeyValueRow(stringResource(R.string.requests_clock_out), JohannesburgTime.time(request.requestedClockOut))
                        }
                        KeyValueRow(stringResource(R.string.admin_reason), request.reason)
                        if (request.status == "pending") {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                PillButton(stringResource(R.string.admin_approve), { decidingRequest = request to true }, style = PillStyle.SUCCESS, loading = state.busyId == request.id)
                                PillButton(stringResource(R.string.admin_reject), { decidingRequest = request to false }, style = PillStyle.DANGER, enabled = state.busyId != request.id)
                            }
                        }
                    }
                }
                item {
                    PagedFooter(state.requests, stringResource(R.string.review_requests_empty_title), stringResource(R.string.review_requests_empty_body), viewModel::reloadRequests, viewModel::loadMoreRequests)
                }
            }
        }
    }

    decidingEvent?.let { (event, approve) ->
        NoteDialog(
            title = stringResource(if (approve) R.string.admin_approve_title else R.string.admin_reject_title),
            label = stringResource(R.string.admin_note),
            confirmLabel = stringResource(if (approve) R.string.admin_approve else R.string.admin_reject),
            required = !approve,
            body = event.employee?.fullName,
            onDismiss = { decidingEvent = null },
        ) { note -> decidingEvent = null; viewModel.decide(event.id, approve, note) }
    }
    decidingRequest?.let { (request, approve) ->
        if (approve) {
            ApproveRequestDialog(request, onDismiss = { decidingRequest = null }) { note, inAt, outAt ->
                decidingRequest = null
                viewModel.decideRequest(request.id, true, note, inAt, outAt)
            }
        } else {
            NoteDialog(
                title = stringResource(R.string.review_reject_request),
                label = stringResource(R.string.admin_reason),
                confirmLabel = stringResource(R.string.admin_reject),
                onDismiss = { decidingRequest = null },
            ) { note -> decidingRequest = null; viewModel.decideRequest(request.id, false, note, null, null) }
        }
    }
}

/** Approving a time request may adjust the requested clock-in/out (SAST times, sent as UTC). */
@Composable
private fun ApproveRequestDialog(
    request: ManualRequestDto,
    onDismiss: () -> Unit,
    onApprove: (String, String?, String?) -> Unit,
) {
    val isTime = request.type != "profile_correction"
    val workDate = request.workDate ?: JohannesburgTime.workDateOf(System.currentTimeMillis())
    var clockIn by remember { mutableStateOf(JohannesburgTime.localParts(request.requestedClockIn)?.second.orEmpty()) }
    var clockOut by remember { mutableStateOf(JohannesburgTime.localParts(request.requestedClockOut)?.second.orEmpty()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_approve_request)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    stringResource(R.string.review_request_summary, StatusTones.label(request.type), request.employee?.fullName ?: "—", JohannesburgTime.workDate(workDate)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isTime) {
                    Text(stringResource(R.string.records_times_sast), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                    OutlinedTextField(clockIn, { clockIn = it }, label = { Text(stringResource(R.string.requests_clock_in)) }, singleLine = true,
                        placeholder = { Text("08:00") }, modifier = Modifier.fillMaxWidth().testTag("approve_in"))
                    OutlinedTextField(clockOut, { clockOut = it }, label = { Text(stringResource(R.string.requests_clock_out)) }, singleLine = true,
                        placeholder = { Text("17:00") }, modifier = Modifier.fillMaxWidth().testTag("approve_out"))
                } else {
                    Text(stringResource(R.string.review_profile_change, StatusTones.label(request.field), request.requestedValue ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.admin_note)) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val inIso = if (isTime) JohannesburgTime.localToIsoUtc(workDate, clockIn) else null
                    val outIso = if (isTime) JohannesburgTime.localToIsoUtc(workDate, clockOut) else null
                    if (isTime && clockIn.isNotBlank() && inIso == null) { error = "Use a time like 08:00."; return@TextButton }
                    if (isTime && clockOut.isNotBlank() && outIso == null) { error = "Use a time like 17:00."; return@TextButton }
                    if (inIso != null && outIso != null && outIso <= inIso) { error = "Clock out must be after clock in."; return@TextButton }
                    onApprove(note, inIso, outIso)
                },
                modifier = Modifier.testTag("approve_request_submit"),
            ) { Text(stringResource(R.string.admin_approve)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
