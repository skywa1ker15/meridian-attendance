package za.co.meridiangroup.attendance.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MotionPhotosOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.BuildConfig
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ServerUrls
import za.co.meridiangroup.attendance.ui.auth.canUseBiometrics
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.theme.Spacing

class AppSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val prefs = graph.prefs

    val themeMode: StateFlow<String> = prefs.themeModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")
    val fontSize: StateFlow<Int> = prefs.fontSizeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)
    val reduceAnimations: StateFlow<Boolean> = prefs.reduceAnimationsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val notificationSound: StateFlow<Boolean> = prefs.notificationSoundFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val vibration: StateFlow<Boolean> = prefs.vibrationFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val biometricLock: StateFlow<Boolean> = prefs.biometricLockFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val autoLockTimeout: StateFlow<Int> = prefs.autoLockTimeoutFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)
    val showLocation: StateFlow<Boolean> = prefs.showLocationOnEventsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val offlineMode: StateFlow<Boolean> = prefs.offlineModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val user = prefs.userFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val apiHost: String get() = ServerUrls.display(ServerUrls.rootOf(graph.apiBaseUrl))

    fun setTheme(mode: String) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setFontSize(level: Int) = viewModelScope.launch { prefs.setFontSize(level) }
    fun setReduceAnimations(v: Boolean) = viewModelScope.launch { prefs.setReduceAnimations(v) }
    fun setNotificationSound(v: Boolean) = viewModelScope.launch { prefs.setNotificationSound(v) }
    fun setVibration(v: Boolean) = viewModelScope.launch { prefs.setVibration(v) }
    fun setBiometricLock(v: Boolean) = viewModelScope.launch { prefs.setBiometricLock(v) }
    fun setAutoLockTimeout(v: Int) = viewModelScope.launch { prefs.setAutoLockTimeout(v) }
    fun setShowLocation(v: Boolean) = viewModelScope.launch { prefs.setShowLocationOnEvents(v) }
    fun setOfflineMode(v: Boolean) = viewModelScope.launch { prefs.setOfflineMode(v) }
}

@Composable
fun AppSettingsScreen(viewModel: AppSettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val reduceAnimations by viewModel.reduceAnimations.collectAsStateWithLifecycle()
    val notifSound by viewModel.notificationSound.collectAsStateWithLifecycle()
    val vibration by viewModel.vibration.collectAsStateWithLifecycle()
    val biometricLock by viewModel.biometricLock.collectAsStateWithLifecycle()
    val autoLockTimeout by viewModel.autoLockTimeout.collectAsStateWithLifecycle()
    val showLocation by viewModel.showLocation.collectAsStateWithLifecycle()
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l)
            .testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        // ---- Display ----
        SectionHeader(stringResource(R.string.settings_display))
        GlassCard {
            // Theme
            SettingLabel(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_desc), Icons.Outlined.Brightness6)
            Column(Modifier.padding(start = Spacing.xl)) {
                ThemeOption(stringResource(R.string.settings_theme_system), "system", themeMode, Icons.Outlined.PhoneAndroid) { viewModel.setTheme(it) }
                ThemeOption(stringResource(R.string.settings_theme_light), "light", themeMode, Icons.Outlined.LightMode) { viewModel.setTheme(it) }
                ThemeOption(stringResource(R.string.settings_theme_dark), "dark", themeMode, Icons.Outlined.DarkMode) { viewModel.setTheme(it) }
            }

            Spacer(Modifier.height(Spacing.m))

            // Font size
            SettingLabel(stringResource(R.string.settings_font_size), stringResource(R.string.settings_font_size_desc), Icons.Outlined.FormatSize)
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = Spacing.s))
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { viewModel.setFontSize(it.toInt()) },
                    valueRange = 0f..2f,
                    steps = 1,
                    modifier = Modifier.weight(1f).testTag("font_size_slider"),
                )
                Text("A", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = Spacing.s))
            }
            Text(
                when (fontSize) { 0 -> stringResource(R.string.settings_font_small); 2 -> stringResource(R.string.settings_font_large); else -> stringResource(R.string.settings_font_medium) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xl),
            )

            Spacer(Modifier.height(Spacing.m))

            // Reduce animations
            SettingToggle(stringResource(R.string.settings_reduce_animations), stringResource(R.string.settings_reduce_animations_desc), Icons.Outlined.MotionPhotosOff, reduceAnimations, viewModel::setReduceAnimations)
        }

        // ---- Notifications ----
        SectionHeader(stringResource(R.string.settings_notifications))
        GlassCard {
            SettingToggle(stringResource(R.string.settings_notification_sound), stringResource(R.string.settings_notification_sound_desc), Icons.Outlined.VolumeUp, notifSound, viewModel::setNotificationSound)
            Spacer(Modifier.height(Spacing.s))
            SettingToggle(stringResource(R.string.settings_vibration), stringResource(R.string.settings_vibration_desc), Icons.Outlined.Vibration, vibration, viewModel::setVibration)
        }

        // ---- Privacy & Security ----
        SectionHeader(stringResource(R.string.settings_privacy_security))
        GlassCard {
            SettingToggle(stringResource(R.string.settings_biometric_lock), stringResource(R.string.settings_biometric_lock_desc), Icons.Outlined.Fingerprint, biometricLock) { enable ->
                if (enable && !canUseBiometrics(context)) {
                    Toast.makeText(context, context.getString(R.string.biometric_not_available), Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.setBiometricLock(enable)
                }
            }
            Spacer(Modifier.height(Spacing.s))

            AutoLockDropdown(autoLockTimeout, viewModel::setAutoLockTimeout)
            Spacer(Modifier.height(Spacing.s))

            SettingToggle(stringResource(R.string.settings_show_location), stringResource(R.string.settings_show_location_desc), Icons.Outlined.GpsFixed, showLocation, viewModel::setShowLocation)
        }

        // ---- Data & Storage ----
        SectionHeader(stringResource(R.string.settings_data_storage))
        GlassCard {
            SettingToggle(stringResource(R.string.settings_offline_mode), stringResource(R.string.settings_offline_mode_desc), Icons.Outlined.WifiOff, offlineMode, viewModel::setOfflineMode)
            Spacer(Modifier.height(Spacing.s))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
                        Text(stringResource(R.string.settings_clear_cache), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.settings_clear_cache_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showClearCacheDialog = true }, modifier = Modifier.testTag("clear_cache_btn")) {
                    Text(stringResource(R.string.settings_clear))
                }
            }
        }

        // ---- About ----
        SectionHeader(stringResource(R.string.settings_about))
        GlassCard {
            SettingInfo(stringResource(R.string.settings_app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Icons.Outlined.Info)
            Spacer(Modifier.height(Spacing.xs))
            SettingInfo(stringResource(R.string.settings_server), viewModel.apiHost, Icons.Outlined.Storage)
            Spacer(Modifier.height(Spacing.xs))
            SettingInfo(stringResource(R.string.settings_employee_number), user?.employeeNumber ?: "—", Icons.Outlined.Security)
            Spacer(Modifier.height(Spacing.xs))
            SettingInfo(stringResource(R.string.settings_device), "${Build.MANUFACTURER} ${Build.MODEL}", Icons.Outlined.PhoneAndroid)
            Spacer(Modifier.height(Spacing.xs))
            SettingInfo(stringResource(R.string.settings_android_version), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Icons.Outlined.Notifications)

            Spacer(Modifier.height(Spacing.m))
            TextButton(onClick = { showPrivacy = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_privacy_policy))
            }
        }

        Spacer(Modifier.height(Spacing.l))
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.settings_clear_cache)) },
            text = { Text(stringResource(R.string.settings_clear_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { context.cacheDir.deleteRecursively() }
                        Toast.makeText(context, context.getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.privacy_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.privacy_body)) } },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun SettingLabel(title: String, description: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingToggle(title: String, description: String, icon: ImageVector, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun SettingInfo(label: String, value: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.m))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThemeOption(label: String, value: String, selected: String, icon: ImageVector, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoLockDropdown(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(0 to "Immediately", 1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes")
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: "1 minute"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_auto_lock), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_auto_lock_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.padding(start = Spacing.xl, top = Spacing.xs)) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("auto_lock_dropdown"),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}
