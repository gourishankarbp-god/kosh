package com.prash.kosh

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.prash.kosh.ui.DashboardViewModel
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.TransactionsViewModel
import com.prash.kosh.ui.screens.AccountsScreen
import com.prash.kosh.ui.screens.BackupScreen
import com.prash.kosh.ui.screens.CaptureScreen
import com.prash.kosh.ui.screens.CategoriesScreen
import com.prash.kosh.ui.screens.DashboardScreen
import com.prash.kosh.ui.screens.EditTransactionScreen
import com.prash.kosh.ui.screens.IgnoredScreen
import com.prash.kosh.ui.screens.KeywordRulesScreen
import com.prash.kosh.ui.screens.MerchantRulesScreen
import com.prash.kosh.ui.screens.MoreScreen
import com.prash.kosh.ui.screens.ReviewScreen
import com.prash.kosh.ui.screens.SecurityScreen
import com.prash.kosh.ui.screens.TransactionsScreen
import com.prash.kosh.ui.theme.KoshTheme
import com.prash.kosh.ui.theme.isDark
import com.prash.kosh.ui.screens.AppearanceScreen

class MainActivity : FragmentActivity() {

    private val unlocked = mutableStateOf(false)
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = KoshApp.get(this)
        if (!app.settings.appLockEnabled) unlocked.value = true
        setContent {
            val mode by app.themeMode
            val dark = isDark(mode)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            KoshTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (unlocked.value) KoshNav(app) else LockedScreen { authenticate() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = KoshApp.get(this).settings
        if (settings.appLockEnabled && backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > LOCK_AFTER_MS) {
            unlocked.value = false
        }
        if (!unlocked.value && settings.appLockEnabled) authenticate()
        if (!settings.appLockEnabled) unlocked.value = true
        // Fetch changes from other devices on open, at most every 15 minutes.
        val sync = KoshApp.get(this).sync
        if (sync.state.enabled && System.currentTimeMillis() - sync.state.lastSyncAt > 15 * 60_000) com.prash.kosh.sync.SyncManager.soon(this, 0)
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    private fun authenticate() {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
        // No screen lock or biometrics set up on the phone: there is nothing to check against.
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { unlocked.value = true; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked.value = true }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Kosh").apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
        }.build()
        prompt.authenticate(info)
    }

    companion object { private const val LOCK_AFTER_MS = 2 * 60 * 1000L }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Kosh is locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("txns", "Activity", Icons.Default.ReceiptLong),
    Tab("review", "Review", Icons.Default.FactCheck),
    Tab("plan", "Plan", Icons.Default.Insights),
    Tab("more", "More", Icons.Default.MoreHoriz),
)

@Composable
internal fun KoshNav(app: KoshApp) {
    val nav = rememberNavController()
    val main: MainViewModel = viewModel { MainViewModel(app.repo) }
    val dashboard: DashboardViewModel = viewModel { DashboardViewModel(app.repo, main.month, main.categoriesById) }
    val txnsVm: TransactionsViewModel = viewModel { TransactionsViewModel(app.repo, main.month) }
    val reviewCount by main.reviewCount.collectAsState()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    val onTab = current in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (onTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { nav.goTab(t.route) },
                        icon = {
                            if (t.route == "review" && reviewCount > 0) BadgedBox(badge = { Badge { Text("$reviewCount") } }) { Icon(t.icon, t.label) }
                            else Icon(t.icon, t.label)
                        },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (current == "home" || current == "txns") FloatingActionButton(onClick = { nav.navigate("add") }) { Icon(Icons.Default.Add, "Add transaction") }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            val open: (Long) -> Unit = { id -> nav.navigate("edit/$id") }
            composable("home") { DashboardScreen(main, dashboard, open, onOpenReview = { nav.goTab("review") }, onOpen = { nav.navigate(it) }) }
            composable("txns") { TransactionsScreen(main, txnsVm, open) }
            composable("review") { ReviewScreen(main, open) }
            composable("more") { MoreScreen { nav.navigate(it) } }
            composable("add") { EditTransactionScreen(main, null) { nav.popBackStack() } }
            composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                EditTransactionScreen(main, it.arguments?.getLong("id")) { nav.popBackStack() }
            }
            val back: () -> Unit = { nav.popBackStack() }
            composable("capture") { CaptureScreen(back) }
            composable("accounts") { AccountsScreen(main, back) { id -> nav.navigate("account/$id") } }
            composable("account/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                com.prash.kosh.ui.screens.AccountHistoryScreen(main, it.arguments!!.getLong("id"), back, open)
            }
            composable("cash") { com.prash.kosh.ui.screens.CashScreen(main, back, open) }
            composable("reports") { com.prash.kosh.ui.screens.ReportsScreen(main, back) }
            composable("sync") { com.prash.kosh.ui.screens.SyncScreen(main, back) { m -> nav.navigate("archive/$m") } }
            composable("archive/{m}") { com.prash.kosh.ui.screens.ArchiveMonthScreen(it.arguments?.getString("m") ?: "", back) }
            composable("assistant") { com.prash.kosh.ui.screens.AssistantScreen(main, back) }
            composable("recognition") { com.prash.kosh.ui.screens.RecognitionSettingsScreen(back) }
            composable("categories") { CategoriesScreen(main, back) }
            composable("merchants") { MerchantRulesScreen(main, back) }
            composable("keywords") { KeywordRulesScreen(main, back) }
            composable("ignored") { IgnoredScreen(main, back) }
            composable("backup") { BackupScreen(main, back) }
            composable("security") { SecurityScreen(back) }
            composable("appearance") { AppearanceScreen(back) }
            composable("plan") { com.prash.kosh.ui.screens.PlanScreen(main) { nav.navigate(it) } }
            composable("budgets") { com.prash.kosh.ui.screens.BudgetsScreen(main, back) }
            composable("upcoming") { com.prash.kosh.ui.screens.UpcomingScreen(main, back) }
            composable("goals") { com.prash.kosh.ui.screens.GoalsScreen(main, back) }
            composable("insights") { com.prash.kosh.ui.screens.InsightsScreen(main, back) }
            composable("investments") {
                com.prash.kosh.ui.screens.InvestmentsScreen(main, back, onOpen = { id -> nav.navigate("investment/$id") }, onAdd = { nav.navigate("investment-edit/0") })
            }
            composable("investment/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                val id = it.arguments!!.getLong("id")
                com.prash.kosh.ui.screens.InvestmentDetailScreen(main, id, back) { nav.navigate("investment-edit/$id") }
            }
            composable("investment-edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                val id = it.arguments!!.getLong("id").takeIf { v -> v != 0L }
                com.prash.kosh.ui.screens.InvestmentEditScreen(main, id) { saved ->
                    nav.popBackStack()
                    if (id == null && saved != null) nav.navigate("investment/$saved")
                }
            }
        }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}
