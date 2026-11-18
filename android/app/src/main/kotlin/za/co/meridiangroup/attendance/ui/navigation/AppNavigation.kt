package za.co.meridiangroup.attendance.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
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
import za.co.meridiangroup.attendance.ui.admin.NoPermission
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeeProfileScreen
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeeProfileViewModel
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeesScreen
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeesViewModel
import za.co.meridiangroup.attendance.ui.admin.live.LiveAttendanceScreen
import za.co.meridiangroup.attendance.ui.admin.live.LiveAttendanceViewModel
import za.co.meridiangroup.attendance.ui.admin.overview.ManageActions
import za.co.meridiangroup.attendance.ui.admin.overview.ManageHomeScreen
import za.co.meridiangroup.attendance.ui.admin.overview.ManageHomeViewModel
import za.co.meridiangroup.attendance.ui.admin.review.AdminReviewScreen
import za.co.meridiangroup.attendance.ui.admin.review.AdminReviewViewModel
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
    const val MANAGE = "manage"
    const val MANAGE_EMPLOYEES = "manage/employees"
    const val MANAGE_EMPLOYEE = "manage/employees/{id}"
    const val MANAGE_LIVE = "manage/live"
    const val MANAGE_RECORDS = "manage/records"
    const val MANAGE_SESSION = "manage/records/{id}"
    const val MANAGE_REVIEW = "manage/review"
    const val MANAGE_EXCEPTIONS = "manage/exceptions"
    const val MANAGE_REPORTS = "manage/reports"
    const val MANAGE_WORKPLACES = "manage/workplaces"
    const val MANAGE_POLICY = "manage/policy"
    const val MANAGE_DEPARTMENTS = "manage/departments"
    const val MANAGE_DEVICES = "manage/devices"
    const val MANAGE_ROLES = "manage/roles"
    const val MANAGE_AUDIT = "manage/audit"
    const val MANAGE_BROADCAST = "manage/broadcast"

    fun manageEmployee(id: String) = "manage/employees/${Uri.encode(id)}"
    fun manageSession(id: String) = "manage/records/${Uri.encode(id)}"

    fun confirm(ref: String) = "confirm/${Uri.encode(ref)}"
    fun session(id: String) = "session/${Uri.encode(id)}"
    fun newRequest(sessionId: String? = null, workDate: String? = null) =
        "requests/new?sessionId=${Uri.encode(sessionId.orEmpty())}&workDate=${Uri.encode(workDate.orEmpty())}"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val employeeTabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    Tab(Routes.REQUESTS, R.string.nav_requests, Icons.Outlined.EditNote),
    Tab(Routes.NOTIFICATIONS, R.string.nav_notifications, Icons.Outlined.Notifications),
    Tab(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person),
)

/**
 * Managers/administrators get a "Manage" tab. They keep Home/History/Requests because an administrator is
 * usually an employee too; notifications stay reachable from the dashboard and Manage home bells.
 */
private val managerTabs = listOf(
    Tab(Routes.MANAGE, R.string.nav_manage, Icons.Outlined.AdminPanelSettings),
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    Tab(Routes.REQUESTS, R.string.nav_requests, Icons.Outlined.EditNote),
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
    val isManager = ServerPermissions.hasAdminSection(permissions)
    val tabs = if (isManager) managerTabs else employeeTabs
    val isTab = tabs.any { it.route == current }
    val manageActions = remember(nav) {
        ManageActions(
            onEmployees = { nav.navigate(Routes.MANAGE_EMPLOYEES) },
            onLive = { nav.navigate(Routes.MANAGE_LIVE) },
            onRecords = { nav.navigate(Routes.MANAGE_RECORDS) },
            onReview = { nav.navigate(Routes.MANAGE_REVIEW) },
            onExceptions = { nav.navigate(Routes.MANAGE_EXCEPTIONS) },
            onReports = { nav.navigate(Routes.MANAGE_REPORTS) },
            onWorkplaces = { nav.navigate(Routes.MANAGE_WORKPLACES) },
            onPolicy = { nav.navigate(Routes.MANAGE_POLICY) },
            onDepartments = { nav.navigate(Routes.MANAGE_DEPARTMENTS) },
            onDevices = { nav.navigate(Routes.MANAGE_DEVICES) },
            onRoles = { nav.navigate(Routes.MANAGE_ROLES) },
            onAudit = { nav.navigate(Routes.MANAGE_AUDIT) },
            onBroadcast = { nav.navigate(Routes.MANAGE_BROADCAST) },
            onNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
        )
    }

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
        NavHost(nav, startDestination = if (isManager) Routes.MANAGE else Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.DASHBOARD })) {
                DashboardScreen(
                    viewModel = graphViewModel { DashboardViewModel(it) },
                    onOpenConfirmation = { nav.navigate(Routes.confirm(it)) },
                    onOpenSession = { nav.navigate(Routes.session(it)) },
                    onOpenHistory = { nav.navigateTab(Routes.HISTORY) },
                    onOpenNotifications = { nav.navigateTab(Routes.NOTIFICATIONS) },
                    onOpenOnboarding = { nav.navigate(Routes.ONBOARDING) },
                    onOpenAdmin = { nav.navigate(Routes.MANAGE) },
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
            composable(Routes.MANAGE) {
                ManageHomeScreen(
                    viewModel = graphViewModel(key = "manage-home") { ManageHomeViewModel(it) },
                    permissions = permissions,
                    unread = unread,
                    actions = manageActions,
                )
            }
            composable(Routes.MANAGE_EMPLOYEES) {
                Gated(ServerPermissions.canRead(permissions)) {
                    EmployeesScreen(graphViewModel(key = "employees") { EmployeesViewModel(it.adminRepository) }, permissions) { id ->
                        nav.navigate(Routes.manageEmployee(id))
                    }
                }
            }
            composable(Routes.MANAGE_EMPLOYEE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                Gated(ServerPermissions.canRead(permissions)) {
                    EmployeeProfileScreen(graphViewModel(key = "employee-$id") { EmployeeProfileViewModel(it, id) }, permissions)
                }
            }
            composable(Routes.MANAGE_LIVE) {
                Gated(ServerPermissions.canViewLive(permissions)) {
                    LiveAttendanceScreen(graphViewModel(key = "live") { LiveAttendanceViewModel(it.adminRepository) })
                }
            }
            composable(Routes.MANAGE_REVIEW) {
                Gated(ServerPermissions.canReview(permissions)) {
                    AdminReviewScreen(graphViewModel(key = "review") { AdminReviewViewModel(it.adminRepository) })
                }
            }
        }
    }
}

/** Renders [content] only when the server granted the permission; otherwise an explanatory empty state. */
@Composable
private fun Gated(allowed: Boolean, content: @Composable () -> Unit) {
    if (allowed) content() else NoPermission()
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
