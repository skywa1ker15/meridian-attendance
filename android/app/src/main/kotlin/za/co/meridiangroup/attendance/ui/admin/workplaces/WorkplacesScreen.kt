package za.co.meridiangroup.attendance.ui.admin.workplaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.data.remote.dto.WorkplaceDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplacePayload
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Accuracy above this is too coarse to define a geofence centre reliably. */
const val GOOD_ACCURACY_M = 30.0

data class WorkplacesUiState(
    val items: List<WorkplaceDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
    /** Workplace currently open in the editor; `id == ""` means "new". */
    val editing: WorkplaceDto? = null,
    val original: WorkplaceDto? = null,
    val locating: Boolean = false,
    val accuracyM: Double? = null,
    val locationError: String? = null,
)

class WorkplacesViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.adminRepository
    private val _state = MutableStateFlow(WorkplacesUiState())
    val state: StateFlow<WorkplacesUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.workplaces()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, items = r.value.items) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun startNew() = _state.update {
        val blank = WorkplaceDto(id = "", name = "", coordinatesConfirmed = false)
        it.copy(editing = blank, original = null, accuracyM = null, locationError = null, message = null)
    }

    fun edit(workplace: WorkplaceDto) = _state.update {
        it.copy(editing = workplace, original = workplace, accuracyM = null, locationError = null, message = null)
    }

    fun cancelEdit() = _state.update { it.copy(editing = null, original = null, accuracyM = null, locationError = null) }
    fun update(block: (WorkplaceDto) -> WorkplaceDto) = _state.update { st -> st.copy(editing = st.editing?.let(block)) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Fresh high-accuracy fix for the geofence centre (FusedLocationProviderClient.getCurrentLocation). */
    fun useCurrentLocation() {
        if (!graph.permissionChecker.preciseLocationGranted()) {
            _state.update { it.copy(locationError = "Allow precise location for this app first (Profile → Permissions).") }
            return
        }
        if (!graph.permissionChecker.locationServicesOn()) {
            _state.update { it.copy(locationError = "Turn location services on, then try again.") }
            return
        }
        _state.update { it.copy(locating = true, locationError = null) }
        viewModelScope.launch {
            val sample = graph.signalCollector.currentLocation()
            if (sample == null) {
                _state.update { it.copy(locating = false, locationError = "Couldn't get a location fix. Go outside or near a window and try again.") }
            } else {
                _state.update { st ->
                    st.copy(
                        locating = false,
                        accuracyM = sample.accuracyM,
                        editing = st.editing?.copy(latitude = sample.latitude, longitude = sample.longitude),
                    )
                }
            }
        }
    }

    /** Fills SSID/BSSID from the Wi-Fi this phone is connected to (needs the Wi-Fi permissions). */
    fun useCurrentWifi() {
        viewModelScope.launch {
            val (ssid, bssid) = graph.signalCollector.currentWifi()
            if (ssid == null && bssid == null) {
                _state.update { it.copy(locationError = "No Wi-Fi details available. Connect to the workplace Wi-Fi and allow nearby-devices access.") }
                return@launch
            }
            _state.update { st ->
                st.copy(
                    locationError = null,
                    editing = st.editing?.let { w ->
                        w.copy(
                            wifiSsid = ssid ?: w.wifiSsid,
                            wifiBssids = if (bssid != null && bssid !in w.wifiBssids) w.wifiBssids + bssid else w.wifiBssids,
                            wifiVerificationEnabled = true,
                        )
                    },
                )
            }
        }
    }

    fun save() {
        val edited = _state.value.editing ?: return
        val original = _state.value.original
        val error = validate(edited)
        if (error != null) { _state.update { it.copy(message = error) }; return }
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val r = if (original == null) {
                repo.createWorkplace(WorkplacePayload.full(edited))
            } else {
                val diff = WorkplacePayload.diff(original, edited)
                if (diff.isEmpty()) {
                    _state.update { it.copy(saving = false, message = "No changes to save.") }
                    return@launch
                }
                repo.updateWorkplace(edited.id, diff)
            }
            when (r) {
                is ApiResult.Success -> {
                    _state.update { it.copy(saving = false, editing = null, original = null, message = "Workplace saved. Devices update on their next sync.") }
                    graph.onWorkplacesChanged()
                    load()
                }
                else -> _state.update { it.copy(saving = false, message = r.userMessage()) }
            }
        }
    }

    private fun validate(w: WorkplaceDto): String? = when {
        w.name.isBlank() -> "Enter a name for the workplace."
        !Geo.isValidLatitude(w.latitude) || !Geo.isValidLongitude(w.longitude) -> "Latitude and longitude are not valid."
        w.latitude == 0.0 && w.longitude == 0.0 -> "Set the real coordinates — 0,0 is the placeholder."
        w.radiusM < 30 || w.radiusM > 5000 -> "The radius must be between 30 m and 5000 m."
        w.accuracyThresholdM < 5 || w.accuracyThresholdM > 2000 -> "The accuracy threshold must be between 5 m and 2000 m."
        w.wifiRequired && !w.wifiVerificationEnabled -> "Enable Wi-Fi verification before requiring a Wi-Fi match."
        w.coordinatesConfirmed == true && w.latitude == 0.0 -> "Confirm real coordinates first."
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkplacesScreen(viewModel: WorkplacesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editing = state.editing

    if (editing != null) {
        WorkplaceEditor(state, editing, viewModel)
        return
    }

    when {
        state.loading && state.items.isEmpty() -> LoadingState(Modifier.fillMaxSize())
        state.error != null && state.items.isEmpty() -> ErrorState(state.error.orEmpty(), viewModel::load)
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag("workplaces"),
            contentPadding = PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            item {
                AdminTitle(stringResource(R.string.manage_workplaces), stringResource(R.string.manage_workplaces_body)) {
                    PillButton(stringResource(R.string.workplace_new), viewModel::startNew, icon = Icons.Outlined.AddLocationAlt, modifier = Modifier.testTag("workplace_new"))
                }
            }
            item { AdminMessage(state.message, viewModel::clearMessage) }
            if (state.items.isEmpty()) {
                item { EmptyState(stringResource(R.string.workplace_empty_title), stringResource(R.string.workplace_empty_body)) }
            }
            items(state.items, key = { it.id }) { workplace ->
                GlassCard(onClick = { viewModel.edit(workplace) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(workplace.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!workplace.active) StatusChip("inactive")
                        StatusChip(
                            if (workplace.coordinatesConfirmed == true) "confirmed" else "under_review",
                            label = stringResource(if (workplace.coordinatesConfirmed == true) R.string.workplace_confirmed else R.string.workplace_unconfirmed),
                        )
                    }
                    workplace.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText) }
                    KeyValueRow(stringResource(R.string.workplace_coordinates), "%.6f, %.6f".format(workplace.latitude, workplace.longitude))
                    KeyValueRow(stringResource(R.string.workplace_radius), "${workplace.radiusM.toInt()} m")
                    if (workplace.wifiVerificationEnabled) KeyValueRow(stringResource(R.string.workplace_wifi), workplace.wifiSsid ?: "—")
                    if (workplace.coordinatesConfirmed != true) {
                        Banner(
                            stringResource(R.string.workplace_unconfirmed_title), stringResource(R.string.workplace_unconfirmed_body),
                            StatusTone.WARNING, modifier = Modifier.testTag("workplace_warning"),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkplaceEditor(state: WorkplacesUiState, workplace: WorkplaceDto, viewModel: WorkplacesViewModel) {
    val isNew = state.original == null
    LazyColumn(
        Modifier.fillMaxSize().testTag("workplace_editor"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { AdminTitle(stringResource(if (isNew) R.string.workplace_new else R.string.workplace_edit), workplace.name.ifBlank { null }) }
        item { AdminMessage(state.message, viewModel::clearMessage) }
        if (workplace.coordinatesConfirmed != true) {
            item {
                Banner(
                    stringResource(R.string.workplace_unconfirmed_title), stringResource(R.string.workplace_unconfirmed_body),
                    StatusTone.WARNING, modifier = Modifier.testTag("editor_warning"),
                )
            }
        }
        item {
            OutlinedTextField(
                workplace.name, { v -> viewModel.update { it.copy(name = v) } }, label = { Text(stringResource(R.string.workplace_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wp_name"),
            )
        }
        item {
            OutlinedTextField(
                workplace.address.orEmpty(), { v -> viewModel.update { it.copy(address = v) } }, label = { Text(stringResource(R.string.workplace_address)) },
                modifier = Modifier.fillMaxWidth().testTag("wp_address"),
            )
        }
        item { SectionHeader(stringResource(R.string.workplace_location)) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                NumberField(workplace.latitude.toString(), stringResource(R.string.workplace_latitude), Modifier.weight(1f), "wp_lat", resetKey = state.accuracyM) { v ->
                    v.toDoubleOrNull()?.let { d -> viewModel.update { it.copy(latitude = d) } }
                }
                NumberField(workplace.longitude.toString(), stringResource(R.string.workplace_longitude), Modifier.weight(1f), "wp_lng", resetKey = state.accuracyM) { v ->
                    v.toDoubleOrNull()?.let { d -> viewModel.update { it.copy(longitude = d) } }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PillButton(
                    stringResource(R.string.workplace_use_location), viewModel::useCurrentLocation,
                    icon = Icons.Outlined.MyLocation, loading = state.locating, modifier = Modifier.testTag("wp_use_location"),
                )
                PillButton(
                    stringResource(R.string.workplace_use_wifi), viewModel::useCurrentWifi,
                    style = PillStyle.OUTLINE, icon = Icons.Outlined.Wifi, modifier = Modifier.testTag("wp_use_wifi"),
                )
            }
        }
        state.accuracyM?.let { accuracy ->
            item {
                Banner(
                    stringResource(R.string.workplace_accuracy, Geo.formatDistance(accuracy)),
                    stringResource(if (accuracy > GOOD_ACCURACY_M) R.string.workplace_accuracy_poor else R.string.workplace_accuracy_good),
                    if (accuracy > GOOD_ACCURACY_M) StatusTone.WARNING else StatusTone.SUCCESS,
                    modifier = Modifier.testTag("wp_accuracy"),
                )
            }
        }
        state.locationError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                NumberField(workplace.radiusM.toInt().toString(), stringResource(R.string.workplace_radius), Modifier.weight(1f), "wp_radius") { v ->
                    v.toDoubleOrNull()?.let { d -> viewModel.update { it.copy(radiusM = d) } }
                }
                NumberField(workplace.accuracyThresholdM.toInt().toString(), stringResource(R.string.workplace_accuracy_threshold), Modifier.weight(1f), "wp_accuracy_threshold") { v ->
                    v.toDoubleOrNull()?.let { d -> viewModel.update { it.copy(accuracyThresholdM = d) } }
                }
            }
        }
        item {
            SwitchRow(stringResource(R.string.workplace_coordinates_confirmed), stringResource(R.string.workplace_coordinates_confirmed_body), workplace.coordinatesConfirmed == true, "wp_confirmed") { v ->
                viewModel.update { it.copy(coordinatesConfirmed = v) }
            }
        }
        item { SectionHeader(stringResource(R.string.workplace_detection)) }
        item {
            SwitchRow(stringResource(R.string.workplace_entry), null, workplace.entryDetectionEnabled, "wp_entry") { v -> viewModel.update { it.copy(entryDetectionEnabled = v) } }
        }
        item {
            SwitchRow(stringResource(R.string.workplace_exit), null, workplace.exitDetectionEnabled, "wp_exit") { v -> viewModel.update { it.copy(exitDetectionEnabled = v) } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                NumberField(workplace.dwellSeconds.toString(), stringResource(R.string.workplace_dwell), Modifier.weight(1f), "wp_dwell") { v ->
                    v.toIntOrNull()?.let { d -> viewModel.update { it.copy(dwellSeconds = d) } }
                }
                NumberField(workplace.exitDebounceSeconds.toString(), stringResource(R.string.workplace_exit_debounce), Modifier.weight(1f), "wp_debounce") { v ->
                    v.toIntOrNull()?.let { d -> viewModel.update { it.copy(exitDebounceSeconds = d) } }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.workplace_wifi)) }
        item {
            SwitchRow(stringResource(R.string.workplace_wifi_enabled), null, workplace.wifiVerificationEnabled, "wp_wifi_enabled") { v ->
                viewModel.update { it.copy(wifiVerificationEnabled = v) }
            }
        }
        item {
            OutlinedTextField(
                workplace.wifiSsid.orEmpty(), { v -> viewModel.update { it.copy(wifiSsid = v) } }, label = { Text(stringResource(R.string.workplace_ssid)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wp_ssid"),
            )
        }
        item {
            OutlinedTextField(
                workplace.wifiBssids.joinToString(", "),
                { v -> viewModel.update { w -> w.copy(wifiBssids = v.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }) } },
                label = { Text(stringResource(R.string.workplace_bssids)) },
                supportingText = { Text(stringResource(R.string.workplace_bssids_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("wp_bssids"),
            )
        }
        item {
            SwitchRow(stringResource(R.string.workplace_wifi_required), stringResource(R.string.workplace_wifi_required_body), workplace.wifiRequired, "wp_wifi_required") { v ->
                viewModel.update { it.copy(wifiRequired = v) }
            }
        }
        item {
            SwitchRow(stringResource(R.string.workplace_active), null, workplace.active, "wp_active") { v -> viewModel.update { it.copy(active = v) } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PillButton(stringResource(R.string.action_save), viewModel::save, Modifier.weight(1f).testTag("wp_save"), loading = state.saving)
                PillButton(stringResource(R.string.action_cancel), viewModel::cancelEdit, Modifier.weight(1f), style = PillStyle.GHOST)
            }
        }
    }
}

/**
 * Numeric field that keeps its own text while typing (so "-", "" and "29." are possible) and reports every
 * change; the parent only stores values that parse. [resetKey] re-seeds the text (e.g. after a GPS fix).
 */
@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, tag: String, resetKey: Any? = null, onChange: (String) -> Unit) {
    var text by androidx.compose.runtime.remember(resetKey, value.takeIf { resetKey != null }) { androidx.compose.runtime.mutableStateOf(value) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onChange(it) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = modifier.testTag(tag),
    )
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
