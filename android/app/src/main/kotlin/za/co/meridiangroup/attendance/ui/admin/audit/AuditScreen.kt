package za.co.meridiangroup.attendance.ui.admin.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.data.remote.dto.AuditLogDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.DateField
import za.co.meridiangroup.attendance.ui.admin.PagedFooter
import za.co.meridiangroup.attendance.ui.admin.PagedUiState
import za.co.meridiangroup.attendance.ui.admin.SearchField
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class AuditUiState(
    val paged: PagedUiState<AuditLogDto> = PagedUiState(),
    val search: String = "",
    val action: String = "",
    val from: String? = null,
    val to: String? = null,
)

class AuditViewModel(private val repo: AdminRepository, now: () -> Long) : ViewModel() {
    private val today = JohannesburgTime.workDateOf(now())
    private val _state = MutableStateFlow(AuditUiState(from = JohannesburgTime.daysAgo(today, 13), to = today))
    val state: StateFlow<AuditUiState> = _state.asStateFlow()

    init { reload() }

    fun setSearch(v: String) { _state.update { it.copy(search = v) }; reload() }
    fun setAction(v: String) { _state.update { it.copy(action = v) }; reload() }
    fun setFrom(v: String) { _state.update { it.copy(from = v) }; reload() }
    fun setTo(v: String) { _state.update { it.copy(to = v) }; reload() }

    fun reload() { _state.update { it.copy(paged = it.paged.reset()) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.paged.loading || !s.paged.hasMore) return
        _state.update { it.copy(paged = it.paged.startLoading()) }
        viewModelScope.launch {
            when (val r = repo.auditLogs(null, s.action, null, s.from, s.to, s.search, s.paged.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(paged = it.paged.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(paged = it.paged.failed(r.userMessage())) }
            }
        }
    }
}

@Composable
fun AuditScreen(viewModel: AuditViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<AuditLogDto?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().testTag("audit"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { AdminTitle(stringResource(R.string.manage_audit), stringResource(R.string.manage_audit_body)) }
        item { SearchField(state.search, viewModel::setSearch, stringResource(R.string.audit_search), tag = "audit_search") }
        item { SearchField(state.action, viewModel::setAction, stringResource(R.string.audit_action), tag = "audit_action") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                DateField(stringResource(R.string.records_from), state.from, viewModel::setFrom, Modifier.weight(1f), tag = "audit_from")
                DateField(stringResource(R.string.records_to), state.to, viewModel::setTo, Modifier.weight(1f), tag = "audit_to")
            }
        }
        items(state.paged.items, key = { it.id }) { entry ->
            GlassCard(onClick = { detail = entry }, contentPadding = PaddingValues(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.action, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${JohannesburgTime.dateTime(entry.createdAt)} · ${entry.actor?.name ?: StatusTones.label(entry.actorType)}",
                            style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                        )
                    }
                }
            }
        }
        item {
            PagedFooter(
                state.paged, stringResource(R.string.audit_empty_title), stringResource(R.string.audit_empty_body),
                viewModel::reload, viewModel::loadMore,
            )
        }
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(entry.action) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    KeyValueRow(stringResource(R.string.audit_when), JohannesburgTime.dateTime(entry.createdAt))
                    KeyValueRow(stringResource(R.string.audit_actor), entry.actor?.name ?: StatusTones.label(entry.actorType))
                    KeyValueRow(stringResource(R.string.audit_entity), listOfNotNull(entry.entityType, entry.entityId).joinToString(" · "))
                    entry.ip?.let { KeyValueRow(stringResource(R.string.audit_ip), it) }
                    entry.before?.let {
                        Text(stringResource(R.string.audit_before), style = MaterialTheme.typography.titleSmall)
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                    entry.after?.let {
                        Text(stringResource(R.string.audit_after), style = MaterialTheme.typography.titleSmall)
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.action_done)) } },
        )
    }
}
