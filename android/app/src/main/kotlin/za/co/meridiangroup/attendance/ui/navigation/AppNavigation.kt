package za.co.meridiangroup.attendance.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.data.repository.SessionState
import za.co.meridiangroup.attendance.ui.admin.AdminHomeScreen
import za.co.meridiangroup.attendance.ui.admin.AdminReviewScreen
import za.co.meridiangroup.attendance.ui.admin.AdminReviewViewModel
import za.co.meridiangroup.attendance.ui.admin.LiveAttendanceScreen
import za.co.meridiangroup.attendance.ui.admin.LiveAttendanceViewModel
import za.co.meridiangroup.attendance.ui.auth.ChangePasswordScreen
import za.co.meridiangroup.attendance.ui.auth.ChangePasswordViewModel
import za.co.meridiangroup.attendance.ui.auth.LoginScreen
import za.co.meridiangroup.attendance.ui.auth.LoginViewModel
import za.co.meridiangroup.attendance.ui.auth.SetupScreen
import za.co.meridiangroup.attendance.ui.auth.SetupViewModel
import za.co.meridiangroup.attendance.ui.common.graphViewModel
import za.co.meridiangroup.attendance.ui.common.rememberGraph
import za.co.meridiangroup.attendance.ui.confirm.ConfirmationScreen
import za.co.meridiangroup.attendance.ui.confirm.ConfirmationViewModel
import za.co.meridiangroup.attendance.ui.confirm.GraphConfirmationActions
import za.co.meridiangroup.attendance.ui.dashboard.DashboardScreen
import za.co.meridiangroup.attendance.ui.dashboard.DashboardViewModel
import za.co.meridiangroup.attendance.ui.history.HistoryScreen
import za.co.meridiangroup.attendance.ui.history.HistoryViewModel
import za.co.meridiangroup.attendance.ui.history.SessionDetailScreen
import za.co.meridiangroup.attendance.ui.history.SessionDetailViewModel
import za.co.meridiangroup.attendance.ui.notifications.NotificationsScreen
import za.co.meridiangroup.attendance.ui.notifications.NotificationsViewModel
import za.co.meridiangroup.attendance.ui.onboarding.OnboardingScreen
import za.co.meridiangroup.attendance.ui.profile.ProfileScreen
import za.co.meridiangroup.attendance.ui.profile.ProfileViewModel
import za.co.meridiangroup.attendance.ui.requests.NewRequestScreen
import za.co.meridiangroup.attendance.ui.requests.NewRequestViewModel
import za.co.meridiangroup.attendance.ui.requests.RequestsScreen
import za.co.meridiangroup.attendance.ui.requests.RequestsViewModel

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val REQUESTS = "requests"
    const val NOTIFICATIONS = "notifications"
    const val PROFILE = "profile"
    const val CONFIRM = "confirm/{ref}"
    const val SESSION = "session/{id}"
    const val NEW_REQUEST = "requests/new?sessionId={sessionId}&workDate={workDate}"
    const val PROFILE_CORRECTION = "profile/correction"
    const val CHANGE_PASSWORD = "profile/password"
    const val ONBOARDING = "onboarding"
    const val ADMIN = "admin"
    const val ADMIN_REVIEW = "admin/review"
    const val ADMIN_LIVE = "admin/live"

    fun confirm(ref: String) = "confirm/${Uri.encode(ref)}"
    fun session(id: String) = "session/${Uri.encode(id)}"
    fun newRequest(sessionId: String? = null, workDate: String? = null) =
        "requests/new?sessionId=${Uri.encode(sessionId.orEmpty())}&workDate=${Uri.encode(workDate.orEmpty())}"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    Tab(Routes.REQUESTS, R.string.nav_requests, Icons.Outlined.EditNote),
    Tab(Routes.NOTIFICATIONS, R.string.nav_notifications, Icons.Outlined.Notifications),
    Tab(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person),
)

/** Root: login → forced password change → onboarding → main app. */
@Composable
fun AppRoot(pendingDeepLink: Intent?, onDeepLinkConsumed: () -> Unit) {
    val graph = rememberGraph()
    val session by graph.authRepository.state.collectAsState()
    val onboardingDone by graph.prefs.onboardingDoneFlow.collectAsState(initial = true)
    val user by graph.prefs.userFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    when (val s = session) {
        SessionState.LoggedOut -> {
            val vm = graphViewModel(key = "login") { g -> LoginViewModel(g.authRepository) { g.onSessionActive() } }
            var setup by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val codeRequired = setup
            if (codeRequired != null) {
                val setupVm = graphViewModel(key = "setup-$codeRequired") { g -> SetupViewModel(g, codeRequired) }
                SetupScreen(setupVm) { message ->
                    setup = null
                    vm.onServerChanged()
                    message?.let(vm::showNotice)
                }
            } else {
                LoginScreen(vm, onStartSetup = { required -> setup = required })
            }
        }
        is SessionState.LoggedIn -> when {
            s.mustChangePassword -> {
                val vm = graphViewModel(key = "forced-pw") { g -> ChangePasswordViewModel(g.authRepository) { user?.employeeNumber } }
                ChangePasswordScreen(vm, forced = true, onDone = {}, onLogout = { scope.launch { graph.authRepository.logout() } })
            }
            !onboardingDone -> OnboardingScreen(onFinished = {})
            else -> MainScaffold(pendingDeepLink, onDeepLinkConsumed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(pendingDeepLink: Intent?, onDeepLinkConsumed: () -> Unit) {
    val graph = rememberGraph()
    val nav = rememberNavController()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val unread by graph.notificationRepository.unreadCount.collectAsState(initial = 0)
    val user by graph.prefs.userFlow.collectAsState(initial = null)
    val permissions = user?.permissions.orEmpty()
    val isTab = tabs.any { it.route == current }

    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null) {
            nav.handleDeepLink(pendingDeepLink)
            onDeepLinkConsumed()
        }
    }
    LaunchedEffect(Unit) { graph.sessionMessages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!isTab) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) }, modifier = Modifier.testTag("nav_back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = {
                                if (tab.route == Routes.NOTIFICATIONS && unread > 0) {
                                    BadgedBox(badge = { Badge { Text(unread.coerceAtMost(99).toString()) } }) { Icon(tab.icon, null) }
                                } else {
                                    Icon(tab.icon, null)
                                }
                            },
                            label = { Text(stringResource(tab.label)) },
                            modifier = Modifier.testTag("tab_${tab.route}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.DASHBOARD })) {
                DashboardScreen(
                    viewModel = graphViewModel { DashboardViewModel(it) },
                    onOpenConfirmation = { nav.navigate(Routes.confirm(it)) },
                    onOpenSession = { nav.navigate(Routes.session(it)) },
                    onOpenHistory = { nav.navigateTab(Routes.HISTORY) },
                    onOpenNotifications = { nav.navigateTab(Routes.NOTIFICATIONS) },
                    onOpenOnboarding = { nav.navigate(Routes.ONBOARDING) },
                    onOpenAdmin = { nav.navigate(Routes.ADMIN) },
                    onOpenLocationSettings = { context.startActivity(graph.permissionChecker.locationSettingsIntent()) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(graphViewModel { HistoryViewModel(it.attendanceRepository, it.now) }, onOpenSession = { nav.navigate(Routes.session(it)) },
                    onRequestCorrection = { nav.navigate(Routes.newRequest()) })
            }
            composable(Routes.REQUESTS, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.REQUESTS })) {
                RequestsScreen(graphViewModel { RequestsViewModel(it.requestRepository) }, onNewRequest = { nav.navigate(Routes.newRequest()) })
            }
            composable(Routes.NOTIFICATIONS, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.NOTIFICATIONS })) {
                NotificationsScreen(graphViewModel { NotificationsViewModel(it.notificationRepository) }) { uri ->
                    runCatching { nav.navigate(uri) }.onFailure { nav.navigate(Routes.HOME) }
                }
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    viewModel = graphViewModel { ProfileViewModel(it) },
                    onChangePassword = { nav.navigate(Routes.CHANGE_PASSWORD) },
                    onRequestCorrection = { nav.navigate(Routes.PROFILE_CORRECTION) },
                    onRerunOnboarding = { nav.navigate(Routes.ONBOARDING) },
                    onOpenAppSettings = { context.startActivity(graph.permissionChecker.appSettingsIntent()) },
                    onLoggedOut = {},
                )
            }
            composable(
                Routes.CONFIRM,
                arguments = listOf(navArgument("ref") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.CONFIRM_PATTERN }),
            ) { entry ->
                val ref = entry.arguments?.getString("ref").orEmpty()
                val vm = graphViewModel(key = "confirm-$ref") { g ->
                    ConfirmationViewModel(ref, g.attendanceRepository,
                        GraphConfirmationActions(g.attendanceRepository, g.signalCollector, g.notifier, g.reminderScheduler, g.syncEngine))
                }
                ConfirmationScreen(vm, onClose = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) })
            }
            composable(
                Routes.SESSION,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.SESSION_PATTERN }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                SessionDetailScreen(graphViewModel(key = "session-$id") { SessionDetailViewModel(id, it.attendanceRepository) }) { sid, date ->
                    nav.navigate(Routes.newRequest(sid, date))
                }
            }
            composable(
                Routes.NEW_REQUEST,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("workDate") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val sid = entry.arguments?.getString("sessionId")?.ifBlank { null }
                val date = entry.arguments?.getString("workDate")?.ifBlank { null }
                NewRequestScreen(graphViewModel(key = "new-$sid-$date") { NewRequestViewModel(it.requestRepository, it.now, false, sid, date) }) {
                    nav.popBackStack(); nav.navigateTab(Routes.REQUESTS)
                }
            }
            composable(Routes.PROFILE_CORRECTION) {
                NewRequestScreen(graphViewModel(key = "profile-correction") { NewRequestViewModel(it.requestRepository, it.now, true, null, null) }) { nav.popBackStack() }
            }
            composable(Routes.CHANGE_PASSWORD) {
                ChangePasswordScreen(
                    graphViewModel(key = "change-pw") { g -> ChangePasswordViewModel(g.authRepository) { user?.employeeNumber } },
                    forced = false, onDone = { nav.popBackStack() }, onLogout = null,
                )
            }
            composable(Routes.ONBOARDING, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.ONBOARDING })) {
                OnboardingScreen(onFinished = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) })
            }
            composable(Routes.ADMIN) {
                AdminHomeScreen(
                    canReview = ServerPermissions.canReview(permissions), canViewLive = ServerPermissions.canViewLive(permissions),
                    onReview = { nav.navigate(Routes.ADMIN_REVIEW) }, onLive = { nav.navigate(Routes.ADMIN_LIVE) },
                )
            }
            composable(Routes.ADMIN_REVIEW) {
                if (ServerPermissions.canReview(permissions)) AdminReviewScreen(graphViewModel { AdminReviewViewModel(it.adminRepository) })
                else AdminHomeScreen(false, ServerPermissions.canViewLive(permissions), {}, { nav.navigate(Routes.ADMIN_LIVE) })
            }
            composable(Routes.ADMIN_LIVE) {
                if (ServerPermissions.canViewLive(permissions)) LiveAttendanceScreen(graphViewModel { LiveAttendanceViewModel(it.adminRepository) })
                else AdminHomeScreen(ServerPermissions.canReview(permissions), false, { nav.navigate(Routes.ADMIN_REVIEW) }, {})
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
