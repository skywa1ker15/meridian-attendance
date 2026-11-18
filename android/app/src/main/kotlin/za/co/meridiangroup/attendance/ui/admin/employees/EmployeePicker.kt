package za.co.meridiangroup.attendance.ui.admin.employees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.SearchField
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Search-and-pick dialog over GET /admin/employees, used by record filters and manual sessions. */
@Composable
fun EmployeePickerDialog(repo: AdminRepository, onDismiss: () -> Unit, allowClear: Boolean = true, onPick: (UserDto?) -> Unit) {
    var search by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(search) {
        loading = true
        delay(250)
        when (val r = repo.employees(search, null, "active", null, 1)) {
            is ApiResult.Success -> { results = r.value.items; error = null }
            else -> error = r.userMessage()
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_employee)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SearchField(search, { search = it }, stringResource(R.string.employees_search), tag = "picker_search")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (loading) LoadingState()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { user ->
                        TextButton(onClick = { onPick(user) }, modifier = Modifier.fillMaxWidth().testTag("pick_${user.id}")) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(user.fullName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(user.employeeNumber, user.department?.name).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (allowClear) TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.picker_anyone)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
