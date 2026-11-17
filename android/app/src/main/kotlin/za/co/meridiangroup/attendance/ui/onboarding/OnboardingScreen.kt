package za.co.meridiangroup.attendance.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.PermissionSnapshot
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.ui.common.rememberGraph
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

private enum class Step { INTRO, NOTIFICATIONS, LOCATION, BACKGROUND, BATTERY, WIFI, DONE }

/**
 * Step-by-step permission onboarding. Each step re-checks the real state on resume, so returning from
 * Settings advances automatically, and a permission revoked later shows up again here.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = graph.permissionChecker
    var snapshot by remember { mutableStateOf(checker.snapshot()) }
    var wifiOn by remember { mutableStateOf(checker.wifiEnabled()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var skippedOptional by rememberSaveable { mutableStateOf(setOf<String>()) }

    LifecycleResumeEffect(refreshTick) {
        snapshot = checker.snapshot()
        wifiOn = checker.wifiEnabled()
        onPauseOrDispose { }
    }
    val recheck: () -> Unit = { snapshot = checker.snapshot(); wifiOn = checker.wifiEnabled(); refreshTick++ }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { recheck() }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }

    val step = Step.entries[stepIndex.coerceIn(0, Step.entries.lastIndex)]
    val satisfied = isSatisfied(step, snapshot, wifiOn) || step.name in skippedOptional
    val next = { stepIndex = (stepIndex + 1).coerceAtMost(Step.entries.lastIndex) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(Spacing.xl).testTag("onboarding"),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        LinearProgressIndicator(progress = { (stepIndex + 1f) / Step.entries.size }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.onboarding_step, stepIndex + 1, Step.entries.size), style = MaterialTheme.typography.labelMedium)

        when (step) {
            Step.INTRO -> StepCard(Icons.Outlined.PrivacyTip, R.string.ob_intro_title, R.string.ob_intro_body) {
                PillButton(stringResource(R.string.ob_intro_cta), next, Modifier.fillMaxWidth().testTag("ob_next"))
            }
            Step.NOTIFICATIONS -> StepCard(Icons.Outlined.Notifications, R.string.ob_notif_title, R.string.ob_notif_body, satisfied) {
                if (!satisfied) {
                    PillButton(stringResource(R.string.ob_allow), {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(checker.notificationSettingsIntent())
                    }, Modifier.fillMaxWidth().testTag("ob_allow"))
                    PillButton(stringResource(R.string.ob_open_settings), { context.startActivity(checker.notificationSettingsIntent()) }, Modifier.fillMaxWidth(), style = PillStyle.GHOST)
                }
            }
            Step.LOCATION -> StepCard(Icons.Outlined.MyLocation, R.string.ob_location_title, R.string.ob_location_body, satisfied) {
                if (!snapshot.locationServicesOn) {
                    Banner(stringResource(R.string.banner_location_off_title), stringResource(R.string.banner_location_off_body), StatusTone.WARNING,
                        actionLabel = stringResource(R.string.ob_turn_on_location), onAction = { context.startActivity(checker.locationSettingsIntent()) })
                }
                if (checker.coarseOnly()) {
                    Banner(stringResource(R.string.ob_precise_needed_title), stringResource(R.string.ob_precise_needed_body), StatusTone.WARNING)
                }
                if (!snapshot.preciseLocationGranted) {
                    PillButton(stringResource(R.string.ob_allow), {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, Modifier.fillMaxWidth().testTag("ob_allow"))
                    PillButton(stringResource(R.string.ob_open_settings), { context.startActivity(checker.appSettingsIntent()) }, Modifier.fillMaxWidth(), style = PillStyle.GHOST)
                }
            }
            Step.BACKGROUND -> StepCard(Icons.Outlined.LocationOn, R.string.ob_background_title, R.string.ob_background_body, satisfied) {
                if (!snapshot.preciseLocationGranted) {
                    Banner(stringResource(R.string.ob_background_needs_precise_title), stringResource(R.string.ob_background_needs_precise_body), StatusTone.WARNING)
                } else if (!satisfied) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Text(stringResource(R.string.ob_background_settings_hint), style = MaterialTheme.typography.bodySmall)
                        PillButton(stringResource(R.string.ob_open_settings), { context.startActivity(checker.appSettingsIntent()) }, Modifier.fillMaxWidth().testTag("ob_allow"))
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        PillButton(stringResource(R.string.ob_allow), { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, Modifier.fillMaxWidth().testTag("ob_allow"))
                    }
                }
            }
            Step.BATTERY -> StepCard(Icons.Outlined.BatteryChargingFull, R.string.ob_battery_title, R.string.ob_battery_body, satisfied) {
                if (!satisfied) {
                    PillButton(stringResource(R.string.ob_battery_cta), { context.startActivity(checker.batterySettingsIntent()) }, Modifier.fillMaxWidth().testTag("ob_allow"))
                    PillButton(stringResource(R.string.ob_skip), { skippedOptional = skippedOptional + step.name; next() }, Modifier.fillMaxWidth().testTag("ob_skip"), style = PillStyle.GHOST)
                }
            }
            Step.WIFI -> StepCard(Icons.Outlined.Wifi, R.string.ob_wifi_title, R.string.ob_wifi_body, satisfied) {
                if (!satisfied) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !snapshot.wifiGranted) {
                        PillButton(stringResource(R.string.ob_allow), { wifiLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES) }, Modifier.fillMaxWidth().testTag("ob_allow"))
                    }
                    if (!wifiOn) {
                        PillButton(stringResource(R.string.ob_turn_on_wifi), { context.startActivity(checker.wifiSettingsIntent()) }, Modifier.fillMaxWidth(), style = PillStyle.OUTLINE)
                    }
                    PillButton(stringResource(R.string.ob_skip), { skippedOptional = skippedOptional + step.name; next() }, Modifier.fillMaxWidth().testTag("ob_skip"), style = PillStyle.GHOST)
                }
            }
            Step.DONE -> StepCard(Icons.Outlined.CheckCircle, R.string.ob_done_title, R.string.ob_done_body, true) {
                PermissionSummary(snapshot)
                PillButton(stringResource(R.string.ob_finish), {
                    scope.launch {
                        graph.prefs.setOnboardingDone(true)
                        graph.configRepository.invalidateGeofences()
                        graph.configRepository.ensureGeofences(force = true)
                        onFinished()
                    }
                }, Modifier.fillMaxWidth().testTag("ob_finish"))
            }
        }

        if (step != Step.INTRO && step != Step.DONE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PillButton(stringResource(R.string.action_back), { stepIndex = (stepIndex - 1).coerceAtLeast(0) }, style = PillStyle.GHOST)
                PillButton(
                    stringResource(if (satisfied) R.string.action_continue else R.string.ob_continue_without),
                    next, Modifier.testTag("ob_next"),
                    style = if (satisfied) PillStyle.PRIMARY else PillStyle.OUTLINE,
                )
            }
            if (!satisfied && (step == Step.LOCATION || step == Step.BACKGROUND)) {
                Text(stringResource(R.string.ob_required_warning), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.onWarningContainer)
            }
        }
    }
}

private fun isSatisfied(step: Step, s: PermissionSnapshot, wifiOn: Boolean): Boolean = when (step) {
    Step.INTRO, Step.DONE -> true
    Step.NOTIFICATIONS -> s.notificationsGranted
    Step.LOCATION -> s.preciseLocationGranted && s.locationServicesOn
    Step.BACKGROUND -> s.backgroundLocationGranted
    Step.BATTERY -> s.batteryOptimisationIgnored
    Step.WIFI -> s.wifiGranted && wifiOn
}

@Composable
private fun StepCard(icon: ImageVector, title: Int, body: Int, done: Boolean = false, actions: @Composable () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(Spacing.m))
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (done) Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.ob_granted), tint = MeridianTheme.extended.success)
        }
        Spacer(Modifier.height(Spacing.m))
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Spacing.l))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) { actions() }
    }
}

@Composable
fun PermissionSummary(s: PermissionSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SummaryLine(R.string.perm_notifications, s.notificationsGranted)
        SummaryLine(R.string.perm_precise_location, s.preciseLocationGranted)
        SummaryLine(R.string.perm_background_location, s.backgroundLocationGranted)
        SummaryLine(R.string.perm_location_services, s.locationServicesOn)
        SummaryLine(R.string.perm_battery, s.batteryOptimisationIgnored)
        SummaryLine(R.string.perm_wifi, s.wifiGranted)
    }
}

@Composable
private fun SummaryLine(label: Int, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (ok) R.string.perm_on else R.string.perm_off),
            color = if (ok) MeridianTheme.extended.onSuccessContainer else MeridianTheme.extended.onWarningContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
