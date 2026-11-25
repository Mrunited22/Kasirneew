package com.iyonzkasir

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.iyonzkasir.core.*
import com.iyonzkasir.data.local.OrderEntity
import com.iyonzkasir.di.AppContainer
import com.iyonzkasir.hardware.ThermalPrinter
import com.iyonzkasir.ui.activity.*
import com.iyonzkasir.ui.auth.*
import com.iyonzkasir.ui.home.HomeV2Screen
import com.iyonzkasir.ui.manage.*
import com.iyonzkasir.ui.pos.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IyonzApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IyonzApp).container
        setContent { IyonzAppTheme { IyonzRoot(container) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) ThermalPrinter.disconnect()
    }
}

@Composable
fun IyonzRoot(app: AppContainer) {
    val nav = rememberNavController()
    var ready by remember { mutableStateOf(false) }
    var startDest by remember { mutableStateOf("auth") }
    var showRestore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.loadInitialPreferences()
        val hasBiz = SessionManager.businessId.isNotBlank() && app.gateway.currentOwnerUid() != null
        startDest = if (hasBiz) "pin" else "auth"
        ready = true
    }

    if (!ready) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    NavHost(navController = nav, startDestination = startDest) {
        composable("auth") {
            AuthEntryScreen(
                app = app,
                onAuthenticated = { isNew ->
                    if (isNew) nav.navigate("main") { popUpTo(0) { inclusive = true } }
                    else nav.navigate("device_outlet") { popUpTo("auth") { inclusive = true } }
                },
                onOpenRestore = { showRestore = true }
            )
        }
        composable("device_outlet") {
            DeviceOutletPickerScreen(app) {
                nav.navigate("pin") { popUpTo("device_outlet") { inclusive = true } }
            }
        }
        composable("pin") {
            StaffPinScreen(app) {
                nav.navigate("main") { popUpTo(0) { inclusive = true } }
            }
        }
        composable("main") {
            MainShell(
                app = app,
                onSwitchUser = {
                    app.stopRealtimeListeners()
                    nav.navigate("pin") { popUpTo(0) { inclusive = true } }
                },
                onLogoutBusiness = {
                    nav.navigate("auth") { popUpTo(0) { inclusive = true } }
                }
            )
        }
    }

    if (showRestore) RestoreFromAuthDialog { showRestore = false }
}

@Composable
private fun RestoreFromAuthDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pwd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pulihkan Backup (.iyonz)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSp.sm)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pickedUri == null) "Pilih File Backup" else "File Dipilih ✓")
                }
                if (pickedUri != null) {
                    OutlinedTextField(pwd, { pwd = it }, label = { Text("Password Backup") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.brand) }
            }
        },
        confirmButton = {
            TextButton(enabled = pickedUri != null && pwd.length >= 6, onClick = {
                scope.launch {
                    val res = ctx.contentResolver.openInputStream(pickedUri!!)?.use {
                        V2BackupHelper.restore(ctx, pwd, it)
                    } ?: Result.failure(Exception("Gagal membuka file"))
                    if (res.isSuccess) {
                        msg = "✅ Berhasil! Memulai ulang..."
                        delay(800); V2BackupHelper.restartApp(ctx)
                    } else msg = "❌ ${res.exceptionOrNull()?.message}"
                }
            }) { Text("Pulihkan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(app: AppContainer, onSwitchUser: () -> Unit, onLogoutBusiness: () -> Unit) {
    val ctx = LocalContext.current
    val user by SessionManager.userState
    val shellNav = rememberNavController()
    val posController = remember(app) { PosV2Controller(app) }
    var lastPaidOrder by remember { mutableStateOf<OrderEntity?>(null) }
    var activitySection by remember { mutableStateOf<ActivitySection?>(null) }

    LaunchedEffect(Unit) { app.startRealtimeListeners(); app.triggerSyncInBackground() }
    LaunchedEffect(user) { if (user == null) onSwitchUser() }

    val canHome = SessionManager.can(AppAccess.HOME)
    val canPos = SessionManager.can(AppAccess.POS)
    val canAct = SessionManager.canAny(AppAccess.KITCHEN, AppAccess.ORDERS, AppAccess.KASBON, AppAccess.WEB_ORDERS)
    val canManage = SessionManager.canAny(
        AppAccess.MENU_MGMT, AppAccess.STOCK, AppAccess.EXPENSE, AppAccess.REPORT,
        AppAccess.SHIFT, AppAccess.MANAGE_TEAM, AppAccess.WEB_ORDERS, AppAccess.SETTINGS
    ) || SessionManager.isOwner()

    val startTab = when {
        canHome -> "tab_home"
        canPos -> "tab_pos"
        canAct -> "tab_activity"
        else -> "tab_manage"
    }
    val tabRoutes = setOf("tab_home", "tab_pos", "tab_activity", "tab_manage")
    val backStack by shellNav.currentBackStackEntryAsState()
    val curRoute = backStack?.destination?.route
    val pendingWeb by app.orderRepo.observePendingWebCount(SessionManager.viewingOutletId.ifBlank { "ALL" }).collectAsState(initial = 0)

    Scaffold(
        topBar = {
            if (!canHome && !canManage && curRoute in tabRoutes) {
                TopAppBar(
                    title = { Text(user?.name ?: "iyonzkasir") },
                    actions = {
                        TextButton(onClick = { SessionManager.logoutStaff(ctx); onSwitchUser() }) { Text("Ganti Akun") }
                    }
                )
            }
        },
        bottomBar = {
            if (curRoute in tabRoutes) {
                NavigationBar(containerColor = AppColors.card) {
                    if (canHome) NavigationBarItem(selected = curRoute == "tab_home", onClick = { shellNav.navigate("tab_home") { popUpTo(startTab); launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text("Beranda") })
                    if (canPos) NavigationBarItem(selected = curRoute == "tab_pos", onClick = { shellNav.navigate("tab_pos") { popUpTo(startTab); launchSingleTop = true } },
                        icon = { Icon(Icons.Default.PointOfSale, null) }, label = { Text("Kasir") })
                    if (canAct) NavigationBarItem(selected = curRoute == "tab_activity", onClick = { activitySection = null; shellNav.navigate("tab_activity") { popUpTo(startTab); launchSingleTop = true } },
                        icon = {
                            BadgedBox(badge = { if (pendingWeb > 0 && SessionManager.can(AppAccess.WEB_ORDERS)) Badge { Text("$pendingWeb") } }) {
                                Icon(Icons.Default.ReceiptLong, null)
                            }
                        }, label = { Text("Aktivitas") })
                    if (canManage) NavigationBarItem(selected = curRoute == "tab_manage", onClick = { shellNav.navigate("tab_manage") { popUpTo(startTab); launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Kelola") })
                }
            }
        }
    ) { pad ->
        NavHost(shellNav, startDestination = startTab, modifier = Modifier.padding(pad)) {
            composable("tab_home") {
                HomeV2Screen(
                    app = app,
                    onOpenPos = { shellNav.navigate("tab_pos") { popUpTo(startTab); launchSingleTop = true } },
                    onOpenKitchen = { activitySection = ActivitySection.KITCHEN; shellNav.navigate("tab_activity") { popUpTo(startTab); launchSingleTop = true } },
                    onOpenExpense = { shellNav.navigate("manage_expense") },
                    onOpenKasbon = { activitySection = ActivitySection.KASBON; shellNav.navigate("tab_activity") { popUpTo(startTab); launchSingleTop = true } },
                    onOpenWebOrders = { activitySection = ActivitySection.WEB; shellNav.navigate("tab_activity") { popUpTo(startTab); launchSingleTop = true } },
                    onOpenReport = { shellNav.navigate("manage_report") },
                    onSwitchUser = { SessionManager.logoutStaff(ctx); onSwitchUser() }
                )
            }
            composable("tab_pos") {
                PosV2Screen(app, posController, onOpenCart = { shellNav.navigate("pos_checkout") })
            }
            composable("tab_activity") {
                ActivityHubV2Screen(app, initialSection = activitySection) { orderId ->
                    posController.loadOpenBill(orderId); shellNav.navigate("pos_checkout")
                }
            }
            composable("tab_manage") {
                ManageHubV2Screen(
                    app = app,
                    onOpenCatalog = { tab -> shellNav.navigate("manage_catalog/$tab") },
                    onOpenBundles = { shellNav.navigate("manage_bundles") },
                    onOpenExpense = { shellNav.navigate("manage_expense") },
                    onOpenReport = { shellNav.navigate("manage_report") },
                    onOpenShift = { shellNav.navigate("manage_shift") },
                    onOpenTeam = { shellNav.navigate("manage_team") },
                    onOpenWebStore = { shellNav.navigate("manage_web_store") },
                    onOpenPrinter = { shellNav.navigate("manage_printer") },
                    onOpenThemeBackup = { shellNav.navigate("manage_theme_backup") },
                    onOpenAudit = { shellNav.navigate("manage_audit") },
                    onSwitchUser = { SessionManager.logoutStaff(ctx); onSwitchUser() },
                    onLogoutBusiness = onLogoutBusiness
                )
            }
            composable("pos_checkout") {
                PosCheckoutScreen(
                    app = app, controller = posController,
                    onBack = { shellNav.popBackStack() },
                    onPaid = { order ->
                        lastPaidOrder = order
                        shellNav.navigate("pos_success") { popUpTo("pos_checkout") { inclusive = true } }
                    },
                    onSavedOpenBill = { shellNav.popBackStack() }
                )
            }
            composable("pos_success") {
                val order = lastPaidOrder
                if (order != null) OrderSuccessV2Screen(app, order) { shellNav.popBackStack() }
            }
            composable("manage_catalog/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) { e ->
                CatalogManageV2Screen(app, e.arguments?.getInt("tab") ?: 0) { shellNav.popBackStack() }
            }
            composable("manage_bundles") { BundleManageV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_expense") { ExpenseV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_report") { ReportV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_shift") { ShiftV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_team") { TeamManageV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_web_store") { WebStoreV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_printer") { PrinterAndReceiptV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_theme_backup") { ThemeAndBackupV2Screen(app) { shellNav.popBackStack() } }
            composable("manage_audit") { AuditLogV2Screen(app) { shellNav.popBackStack() } }
        }
    }
}
