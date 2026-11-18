package za.co.meridiangroup.attendance.ui.admin.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.ChipFilters
import za.co.meridiangroup.attendance.ui.admin.NoteDialog
import za.co.meridiangroup.attendance.ui.admin.PagedFooter
import za.co.meridiangroup.attendance.ui.admin.PagedUiState
import za.co.meridiangroup.attendance.ui.admin.SearchField
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class DevicesUiState(
    val paged: PagedUiState<DeviceDto> = PagedUiState(),
    val status: String = "",
    val search: String = "",
    val message: String? = null,
    val busyId: String? = null,
)

class DevicesViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init { reload() }

    fun setStatus(v: String) { _state.update { it.copy(status = v) }; reload() }
    fun setSearch(v: String) { _state.update { it.copy(search = v) }; reload() }

    fun reload() { _state.update { it.copy(paged = it.paged.reset()) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.paged.loading || !s.paged.hasMore) return
        _state.update { it.copy(paged = it.paged.startLoading()) }
        viewModelScope.launch {
            when (val r = repo.devices(s.status, null, s.search, s.paged.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(paged = it.paged.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(paged = it.paged.failed(r.userMessage())) }
            }
        }
    }

    fun approve(id: String) = act(id) { repo.approveDevice(id) }
    fun revoke(id: String, reason: String) = act(id) { repo.revokeDevice(id, reason) }

    private fun act(id: String, block: suspend () -> ApiResult<DeviceDto>) {
        _state.update { it.copy(busyId = id, message = null) }
        viewModelScope.launch {
            val r = block()
            _state.update { st ->
                if (r is ApiResult.Success) {
                    val updated = r.value
                    st.copy(busyId = null, message = "Device ${StatusTones.label(updated.status).lowercase()}.",
                        paged = st.paged.copy(items = st.paged.items.map { if (it.id == id) updated else it }))
                } else {
                    st.copy(busyId = null, message = r.userMessage())
                }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun DevicesScreen(viewModel: DevicesViewModel, permissions: List<String>) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revoking by remember { mutableStateOf<DeviceDto?>(null) }
    val canWrite = ServerPermissions.canWriteDevices(permissions)

    LazyColumn(
        Modifier.fillMaxSize().testTag("devices"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { AdminTitle(stringResource(R.string.manage_devices), stringResource(R.string.manage_devices_body)) }
        item { AdminMessage(state.message, viewModel::clearMessage) }
        item { SearchField(state.search, viewModel::setSearch, stringResource(R.string.devices_search), tag = "devices_search") }
        item {
            ChipFilters(
                listOf(
                    "" to stringResource(R.string.filter_all),
                    "pending_approval" to StatusTones.label("pending_approval"),
                    "active" to stringResource(R.string.status_active),
                    "revoked" to StatusTones.label("revoked"),
                ),
                state.status, viewModel::setStatus,
            )
        }
        items(state.paged.items, key = { it.id }) { device ->
            GlassCard(contentPadding = PaddingValues(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.employee?.fullName ?: device.deviceId, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${device.manufacturer.orEmpty()} ${device.model.orEmpty()}".trim().ifBlank { device.deviceId },
                            style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                        )
                    }
                    StatusChip(device.status)
                }
                KeyValueRow(stringResource(R.string.devices_app_version), device.appVersion ?: "—")
                KeyValueRow(stringResource(R.string.employee_device_seen, JohannesburgTime.dateTime(device.lastSeenAt)), "")
                device.integrityVerdict?.let { KeyValueRow(stringResource(R.string.devices_integrity), StatusTones.label(it)) }
                if (canWrite) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        if (device.status == "pending_approval") {
                            PillButton(stringResource(R.string.devices_approve), { viewModel.approve(device.id) }, style = PillStyle.SUCCESS, loading = state.busyId == device.id)
                        }
                        if (device.status != "revoked") {
                            PillButton(stringResource(R.string.devices_revoke), { revoking = device }, style = PillStyle.DANGER, enabled = state.busyId != device.id)
                        }
                    }
                }
            }
        }
        item {
            PagedFooter(
                state.paged, stringResource(R.string.devices_empty_title), stringResource(R.string.devices_empty_body),
                viewModel::reload, viewModel::loadMore,
            )
        }
    }

    revoking?.let { device ->
        NoteDialog(
            stringResource(R.string.devices_revoke), stringResource(R.string.admin_reason), stringResource(R.string.devices_revoke),
            body = stringResource(R.string.devices_revoke_body),
            onDismiss = { revoking = null },
        ) { reason -> revoking = null; viewModel.revoke(device.id, reason) }
    }
}
