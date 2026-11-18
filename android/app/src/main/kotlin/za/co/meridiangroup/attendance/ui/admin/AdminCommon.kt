package za.co.meridiangroup.attendance.ui.admin

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Generic page-by-page list state shared by every admin list screen. */
data class PagedUiState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val busyId: String? = null,
) {
    fun startLoading() = copy(loading = true, error = null)
    fun append(newItems: List<T>, page: Int, hasMore: Boolean) = copy(items = items + newItems, page = page, hasMore = hasMore, loading = false, error = null)
    fun failed(message: String?) = copy(loading = false, error = message)
    fun reset() = copy(items = emptyList(), page = 0, hasMore = true, error = null)
}

/** Screen title + optional subtitle, used at the top of every Manage screen. */
@Composable
fun AdminTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
        }
        action?.invoke()
    }
}

/** Standard footer for a paged list: error, spinner, empty state or "Load more" (put inside `item { }`). */
@Composable
fun <T> PagedFooter(
    state: PagedUiState<T>,
    emptyTitle: String,
    emptyBody: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        state.error != null && state.items.isEmpty() -> ErrorState(state.error, onRetry)
        state.loading -> LoadingState()
        state.items.isEmpty() -> EmptyState(emptyTitle, emptyBody)
        state.hasMore -> PillButton(stringResource(R.string.action_load_more), onLoadMore, Modifier.fillMaxWidth().testTag("load_more"), style = PillStyle.GHOST)
    }
}

/** Inline message banner (success or error) with a dismiss action. */
@Composable
fun AdminMessage(message: String?, onDismiss: () -> Unit, tone: StatusTone = StatusTone.NEUTRAL) {
    if (message == null) return
    Banner(message, "", tone, modifier = Modifier.testTag("admin_message"), actionLabel = stringResource(R.string.action_ok), onAction = onDismiss)
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, tag: String = "search") {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

/** A row of single-choice filter chips; the first entry is "All" (empty value). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFilters(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) }, modifier = Modifier.testTag("chip_${value.ifBlank { "all" }}"))
        }
    }
}

/** Read-only field that opens a Material 3 date picker; value is a `YYYY-MM-DD` work date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier, tag: String = "date") {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value.orEmpty(), onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
        trailingIcon = { TextButton(onClick = { open = true }) { Text(stringResource(R.string.action_pick)) } },
        modifier = modifier.testTag(tag),
    )
    if (open) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.parse(JohannesburgTime.workDateOf(System.currentTimeMillis()))
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}

/** Single-choice dropdown (departments, roles, report types…). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "dropdown",
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag(tag),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/** Dialog asking for a note/reason before a destructive or audited action. */
@Composable
fun NoteDialog(
    title: String,
    label: String,
    confirmLabel: String,
    required: Boolean = true,
    body: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    note, { note = it }, label = { Text(label) }, minLines = 2,
                    supportingText = { Text(stringResource(if (required) R.string.admin_note_hint else R.string.admin_note_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag("note_input"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }, enabled = !required || note.trim().length >= 3, modifier = Modifier.testTag("note_confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shows a one-time secret (temporary password) with copy and share actions. */
@Composable
fun SecretDialog(title: String, body: String, secret: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(body, style = MaterialTheme.typography.bodySmall)
                Text(secret, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("secret_value"))
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(secret)) }, modifier = Modifier.testTag("secret_copy")) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = { context.shareText(secret) }, modifier = Modifier.testTag("secret_share")) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
                    }
                }
                Text(stringResource(R.string.admin_secret_warning), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, null))
}

/** Shown instead of a screen when the signed-in user lacks the permission (the server would answer 403). */
@Composable
fun NoPermission(modifier: Modifier = Modifier) {
    EmptyState(
        stringResource(R.string.admin_no_access_title), stringResource(R.string.admin_no_access_body),
        modifier = modifier.testTag("no_permission"),
    )
}

/** Message for a failed call, or null on success. */
fun ApiResult<*>.messageOrNull(): String? = userMessage()

/** "Status: label" helper used in lists. */
fun statusText(wire: String?): String = StatusTones.label(wire)

@Composable
fun AdminSectionCard(title: String, subtitle: String? = null, onClick: () -> Unit, tag: String) {
    za.co.meridiangroup.attendance.ui.components.GlassCard(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText, modifier = Modifier.padding(top = 2.dp))
    }
}
