package za.co.meridiangroup.attendance.ui.notifications

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.data.data
import za.co.meridiangroup.attendance.data.local.db.NotificationEntity
import za.co.meridiangroup.attendance.data.repository.NotificationRepository
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.navigation.DeepLinks
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

class NotificationsViewModel(private val repo: NotificationRepository) : ViewModel() {
    val items: StateFlow<List<NotificationEntity>> = repo.inbox.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var page = 1

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            page = 1
            error.value = repo.refresh(page).userMessage()
            refreshing.value = false
        }
    }

    fun loadMore() {
        viewModelScope.launch { page += 1; repo.refresh(page) }
    }

    fun markRead(id: String) { viewModelScope.launch { repo.markRead(id) } }
    fun markAllRead() { viewModelScope.launch { error.value = repo.markAllRead().userMessage() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel, onOpenDeepLink: (Uri) -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().testTag("notifications")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notifications_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (items.any { it.readAt == null }) PillButton(stringResource(R.string.notifications_mark_all), viewModel::markAllRead, style = PillStyle.GHOST)
                }
            }
            error?.let { item { Banner(stringResource(R.string.banner_refresh_failed), it, StatusTone.NEUTRAL, actionLabel = stringResource(R.string.action_retry), onAction = viewModel::refresh) } }
            if (items.isEmpty()) {
                item { EmptyState(stringResource(R.string.notifications_empty_title), stringResource(R.string.notifications_empty_body), icon = Icons.Outlined.NotificationsNone) }
            }
            items(items, key = { it.id }) { n ->
                NotificationRow(n) {
                    if (n.readAt == null) viewModel.markRead(n.id)
                    onOpenDeepLink(DeepLinks.forNotification(n.type, n.data()))
                }
            }
            if (items.size >= 30) item { PillButton(stringResource(R.string.action_load_more), viewModel::loadMore, Modifier.fillMaxWidth(), style = PillStyle.GHOST) }
        }
    }
}

@Composable
private fun NotificationRow(n: NotificationEntity, onClick: () -> Unit) {
    val unread = n.readAt == null
    GlassCard(onClick = onClick, contentPadding = PaddingValues(Spacing.m)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (unread) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface))
            Column(Modifier.weight(1f).padding(start = Spacing.m)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
                Text(n.body, style = MaterialTheme.typography.bodySmall)
                Text(JohannesburgTime.relative(JohannesburgTime.parse(n.createdAt), System.currentTimeMillis()), style = MaterialTheme.typography.labelSmall, color = MeridianTheme.extended.mutedText)
            }
        }
    }
}
