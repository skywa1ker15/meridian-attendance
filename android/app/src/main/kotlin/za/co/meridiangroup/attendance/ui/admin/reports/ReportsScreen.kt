package za.co.meridiangroup.attendance.ui.admin.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.data.files.ExportStore
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.ReportDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.remote.dto.jsonText
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.DateField
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeePickerDialog
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** docs/API.md §7 report types. */
val REPORT_TYPES = listOf(
    "daily", "weekly", "monthly", "employee", "department", "late-arrivals",
    "absences", "missing-clock-outs", "manual-attendance", "pending-confirmations", "working-hours",
)

data class ReportsUiState(
    val report: String = "daily",
    val from: String? = null,
    val to: String? = null,
    val employee: UserDto? = null,
    val departmentId: String = "",
    val departments: List<AdminDepartmentDto> = emptyList(),
    val preview: ReportDto? = null,
    val loading: Boolean = false,
    val downloading: String? = null,
    val message: String? = null,
    val file: Pair<File, String>? = null,
)

class ReportsViewModel(private val graph: AppGraph, now: () -> Long) : ViewModel() {
    private val repo = graph.adminRepository
    private val today = JohannesburgTime.workDateOf(now())
    private val _state = MutableStateFlow(ReportsUiState(from = JohannesburgTime.daysAgo(today, 6), to = today))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()
    val exports: ExportStore get() = graph.exportStore

    init {
        viewModelScope.launch { repo.departments().getOrNull()?.let { d -> _state.update { it.copy(departments = d.items) } } }
        preview()
    }

    fun setReport(v: String) { _state.update { it.copy(report = v) }; preview() }
    fun setFrom(v: String) { _state.update { it.copy(from = v) } }
    fun setTo(v: String) { _state.update { it.copy(to = v) } }
    fun setEmployee(u: UserDto?) { _state.update { it.copy(employee = u) } }
    fun setDepartment(v: String) { _state.update { it.copy(departmentId = v) } }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearFile() = _state.update { it.copy(file = null) }

    fun preview() {
        val s = _state.value
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.report(s.report, s.from, s.to, s.employee?.id, s.departmentId, null)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, preview = r.value) }
                else -> _state.update { it.copy(loading = false, message = r.userMessage()) }
            }
        }
    }

    /** Downloads with the bearer token into the app cache; the screen then opens the share sheet. */
    fun download(format: String) {
        val s = _state.value
        _state.update { it.copy(downloading = format, message = null) }
        viewModelScope.launch {
            when (val r = repo.reportFile(s.report, format, s.from, s.to, s.employee?.id, s.departmentId, null)) {
                is ApiResult.Success -> _state.update { it.copy(downloading = null, file = r.value to format) }
                else -> _state.update { it.copy(downloading = null, message = r.userMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(viewModel: ReportsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val repo = za.co.meridiangroup.attendance.ui.common.rememberGraph().adminRepository
    val tableScroll = rememberScrollState()

    state.file?.let { (file, format) ->
        LaunchedEffect(file) {
            context.startActivity(viewModel.exports.shareIntent(file, ExportStore.mimeFor(format)))
            viewModel.clearFile()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("reports"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { AdminTitle(stringResource(R.string.manage_reports), stringResource(R.string.manage_reports_body)) }
        item { AdminMessage(state.message, viewModel::clearMessage) }
        item {
            AdminDropdown(
                stringResource(R.string.reports_type), REPORT_TYPES.map { it to StatusTones.label(it.replace('-', '_')) },
                state.report, viewModel::setReport, tag = "report_type",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                DateField(stringResource(R.string.records_from), state.from, viewModel::setFrom, Modifier.weight(1f), tag = "report_from")
                DateField(stringResource(R.string.records_to), state.to, viewModel::setTo, Modifier.weight(1f), tag = "report_to")
            }
        }
        item {
            PillButton(
                state.employee?.fullName ?: stringResource(R.string.records_any_employee), { picking = true },
                Modifier.fillMaxWidth().testTag("report_employee"), style = PillStyle.OUTLINE,
            )
        }
        item {
            AdminDropdown(
                stringResource(R.string.profile_department),
                listOf("" to stringResource(R.string.filter_all_departments)) + state.departments.map { it.id to it.name },
                state.departmentId, viewModel::setDepartment, tag = "report_department",
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PillButton(stringResource(R.string.reports_preview), viewModel::preview, loading = state.loading, modifier = Modifier.testTag("report_preview"))
                listOf("csv", "xlsx", "pdf").forEach { format ->
                    PillButton(
                        format.uppercase(), { viewModel.download(format) }, style = PillStyle.OUTLINE,
                        icon = Icons.Outlined.Download, loading = state.downloading == format, modifier = Modifier.testTag("report_$format"),
                    )
                }
            }
        }
        val preview = state.preview
        when {
            state.loading && preview == null -> item { LoadingState() }
            preview == null -> item { EmptyState(stringResource(R.string.reports_empty_title), stringResource(R.string.reports_empty_body)) }
            else -> {
                item {
                    GlassCard {
                        Text(
                            stringResource(R.string.reports_range, JohannesburgTime.workDate(preview.from), JohannesburgTime.workDate(preview.to)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(stringResource(R.string.reports_rows, preview.rows.size), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                        preview.summary?.forEach { (key, value) -> KeyValueRow(StatusTones.label(key), jsonText(value)) }
                    }
                }
                if (preview.rows.isEmpty()) {
                    item { EmptyState(stringResource(R.string.reports_no_rows_title), stringResource(R.string.reports_no_rows_body)) }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(tableScroll)) {
                            preview.columns.forEach { column ->
                                Text(column.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(140.dp))
                            }
                        }
                    }
                    items(preview.rows.take(100)) { row ->
                        Row(Modifier.fillMaxWidth().horizontalScroll(tableScroll)) {
                            preview.columns.forEach { column ->
                                Text(preview.cell(row, column.key), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp))
                            }
                        }
                    }
                    if (preview.rows.size > 100) {
                        item {
                            Text(
                                stringResource(R.string.reports_truncated, preview.rows.size),
                                style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        EmployeePickerDialog(
            repo = repo,
            onDismiss = { picking = false },
        ) { user -> picking = false; viewModel.setEmployee(user) }
    }
}
