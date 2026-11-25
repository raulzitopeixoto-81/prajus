package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.data.repository.BrandState
import com.example.data.security.Permissions
import com.example.ui.components.*
import com.example.ui.navigation.PrajusRoute
import com.example.ui.theme.LocalPrajusColors
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ProfileUiState
import kotlinx.coroutines.launch

private data class NavDestinationItem(
    val route: PrajusRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val requiredPerm: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    viewModel: MainViewModel,
    brand: BrandState,
    widthSizeClass: WindowWidthSizeClass,
    snackbarHostState: SnackbarHostState,
    onSignOut: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val streamError by viewModel.streamError.collectAsStateWithLifecycle()
    val hojeDashboard by viewModel.hojeDashboard.collectAsStateWithLifecycle()
    val casesList by viewModel.casesList.collectAsStateWithLifecycle()
    val pendingItemsByCase by viewModel.pendingItemsByCaseId.collectAsStateWithLifecycle()
    val documentsByCase by viewModel.documentsByCaseId.collectAsStateWithLifecycle()
    val casesOverviewStats by viewModel.casesOverviewStats.collectAsStateWithLifecycle()
    val casesQuery by viewModel.casesQuery.collectAsStateWithLifecycle()
    val showArchivedCases by viewModel.showArchivedCases.collectAsStateWithLifecycle()
    val casesStatusFilter by viewModel.casesStatusFilter.collectAsStateWithLifecycle()
    val casesSortOption by viewModel.casesSortOption.collectAsStateWithLifecycle()
    val activeClients by viewModel.activeClientsForMap.collectAsStateWithLifecycle()
    val clientsList by viewModel.clientsList.collectAsStateWithLifecycle()
    val clientsQuery by viewModel.clientsQuery.collectAsStateWithLifecycle()
    val showArchivedClients by viewModel.showArchivedClients.collectAsStateWithLifecycle()
    val teamMembers by viewModel.teamMembersList.collectAsStateWithLifecycle()
    val invitesList by viewModel.invitesList.collectAsStateWithLifecycle()
    val auditList by viewModel.auditList.collectAsStateWithLifecycle()
    val notificationsList by viewModel.notificationsList.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }

    val permissions = viewModel.permissions
    val profile = (profileState as? ProfileUiState.Ready)?.profile
    val displayOrgName = profile?.orgName ?: "Meu Escritório"
    val displayUserName = profile?.nome ?: viewModel.googleDisplayName.ifBlank { "Advogado(a)" }
    val displayEmail = profile?.email ?: viewModel.googleEmail

    val unreadCount = remember(notificationsList) { notificationsList.count { !it.lida } }

    val allNavItems = remember(permissions) {
        listOf(
            NavDestinationItem(PrajusRoute.Hoje, "Hoje", Icons.Filled.Today, Icons.Outlined.Today),
            NavDestinationItem(PrajusRoute.Casos, "Casos", Icons.Filled.Folder, Icons.Outlined.Folder),
            NavDestinationItem(PrajusRoute.Clientes, "Clientes", Icons.Filled.People, Icons.Outlined.People),
            NavDestinationItem(PrajusRoute.Equipe, "Equipe", Icons.Filled.Groups, Icons.Outlined.Groups, Permissions.USERS),
            NavDestinationItem(PrajusRoute.Auditoria, "Auditoria", Icons.Filled.History, Icons.Outlined.History, Permissions.AUDIT),
            NavDestinationItem(PrajusRoute.Aparencia, "Aparência", Icons.Filled.Palette, Icons.Outlined.Palette, Permissions.BRAND)
        ).filter { it.requiredPerm == null || it.requiredPerm in permissions }
    }

    val navigateTopLevel: (PrajusRoute) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(PrajusRoute.Hoje) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val isCompact = widthSizeClass == WindowWidthSizeClass.Compact

    val drawerContent: @Composable () -> Unit = {
        PrajusNavSidebarContent(
            orgName = displayOrgName,
            userName = displayUserName,
            userEmail = displayEmail,
            brand = brand,
            navItems = allNavItems,
            isSelected = { route ->
                when (route) {
                    PrajusRoute.Casos -> destination?.hasRoute(PrajusRoute.Casos::class) == true ||
                        destination?.hasRoute(PrajusRoute.CasoDetail::class) == true
                    else -> destination?.hasRoute(route::class) == true
                }
            },
            onSelect = { route ->
                navigateTopLevel(route)
                if (isCompact) scope.launch { drawerState.close() }
            },
            onOpenProfile = {
                if (isCompact) scope.launch { drawerState.close() }
                showEditProfileDialog = true
            },
            onLogout = {
                if (isCompact) scope.launch { drawerState.close() }
                onSignOut()
            }
        )
    }

    val mainContent: @Composable () -> Unit = {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        if (isCompact) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("open_drawer_button")
                            ) {
                                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu))
                            }
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BrandLogoImage(
                                slot = "horizontal",
                                customPath = brand.logoHorizontalPath,
                                modifier = Modifier.height(28.dp).widthIn(max = 120.dp),
                                contentDescription = stringResource(R.string.cd_logo_prajus)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showSearchDialog = true },
                            modifier = Modifier.testTag("top_search_button")
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_buscar))
                        }
                        IconButton(
                            onClick = { showNotificationsDialog = true },
                            modifier = Modifier.testTag("top_notifications_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadCount > 0) {
                                        Badge { Text(unreadCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.Notifications,
                                    contentDescription = stringResource(R.string.cd_notificacoes)
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (isCompact) {
                    val bottomItems = allNavItems.take(4)
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        bottomItems.forEach { item ->
                            val selected = when (item.route) {
                                PrajusRoute.Casos -> destination?.hasRoute(PrajusRoute.Casos::class) == true ||
                                    destination?.hasRoute(PrajusRoute.CasoDetail::class) == true
                                else -> destination?.hasRoute(item.route::class) == true
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, maxLines = 1) },
                                modifier = Modifier.testTag("bottom_nav_${item.label.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (streamError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = streamError!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.dismissStreamError() }) {
                                Text("Fechar")
                            }
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    NavHost(
                        navController = navController,
                        startDestination = PrajusRoute.Hoje
                    ) {
                        composable<PrajusRoute.Hoje> {
                            HojeScreen(
                                dashboard = hojeDashboard,
                                permissions = permissions,
                                onCompleteItem = { viewModel.completeItem(it) },
                                onConfirmDeadline = { viewModel.confirmDeadline(it) },
                                onOpenCase = { navController.navigate(PrajusRoute.CasoDetail(it)) }
                            )
                        }
                        composable<PrajusRoute.Casos> {
                            CasosScreen(
                                cases = casesList,
                                clients = activeClients,
                                deadlinesByCase = pendingItemsByCase,
                                documentsByCase = documentsByCase,
                                overviewStats = casesOverviewStats,
                                query = casesQuery,
                                showArchived = showArchivedCases,
                                statusFilter = casesStatusFilter,
                                sortOption = casesSortOption,
                                permissions = permissions,
                                onQueryChange = { viewModel.setCasesQuery(it) },
                                onToggleArchived = { viewModel.setShowArchivedCases(it) },
                                onStatusFilterChange = { viewModel.setCasesStatusFilter(it) },
                                onSortOptionChange = { viewModel.setCasesSortOption(it) },
                                onOpenCase = { navController.navigate(PrajusRoute.CasoDetail(it)) },
                                onCreateCase = { t, cid, num, area, obs, ok, err ->
                                    viewModel.createCase(t, cid, num, area, obs, ok, err)
                                },
                                onSaveCaseObs = { c, obs, ok, err ->
                                    viewModel.updateCaseDetails(
                                        c.id,
                                        c.titulo,
                                        c.numero.orEmpty(),
                                        c.area.orEmpty(),
                                        c.status,
                                        obs,
                                        ok,
                                        err
                                    )
                                },
                                onUploadDocument = { caseId, uri, tipo, existingDocId ->
                                    viewModel.uploadDocument(caseId, uri, tipo, existingDocId)
                                },
                                onDownloadDocument = { docId, version, onReady ->
                                    viewModel.prepareDocumentDownload(docId, version, onReady)
                                },
                                onArchiveDocument = { docId ->
                                    viewModel.archiveDocument(docId)
                                },
                                onLoadTimelineForReport = { caseId, onLoaded ->
                                    viewModel.loadTimelineForReport(caseId, onLoaded)
                                },
                                onRestoreCase = { c ->
                                    viewModel.archiveCase(c.id, c.titulo, restore = true)
                                },
                                onShowMessage = { viewModel.emitMessage(it) }
                            )
                        }
                        composable<PrajusRoute.CasoDetail> { entry ->
                            val args = entry.toRoute<PrajusRoute.CasoDetail>()
                            CasoDetailScreen(
                                caseId = args.caseId,
                                viewModel = viewModel,
                                permissions = permissions,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable<PrajusRoute.Clientes> {
                            ClientesScreen(
                                clients = clientsList,
                                query = clientsQuery,
                                showArchived = showArchivedClients,
                                permissions = permissions,
                                onQueryChange = { viewModel.setClientsQuery(it) },
                                onToggleArchived = { viewModel.setShowArchivedClients(it) },
                                onCreateClient = { n, d, e, t, ok, err -> viewModel.createClient(n, d, e, t, ok, err) },
                                onUpdateClient = { id, n, d, e, t, ok, err -> viewModel.updateClient(id, n, d, e, t, ok, err) },
                                onArchiveClient = { id, restore -> viewModel.archiveClient(id, restore) }
                            )
                        }
                        composable<PrajusRoute.Equipe> {
                            EquipeScreen(
                                members = teamMembers,
                                invites = invitesList,
                                onCreateInvite = { n, e, r, ok, err -> viewModel.createInvite(n, e, r, ok, err) },
                                onResendInvite = { viewModel.resendInvite(it) },
                                onRevokeInvite = { viewModel.revokeInvite(it) },
                                onUpdateMember = { id, r, s -> viewModel.updateUserRoleOrStatus(id, r, s) }
                            )
                        }
                        composable<PrajusRoute.Auditoria> {
                            AuditoriaScreen(
                                entries = auditList,
                                permissions = permissions,
                                onExportJson = { viewModel.exportJson(it) },
                                onImportJson = { viewModel.importJson(it) },
                                onShowMessage = { viewModel.emitMessage(it) }
                            )
                        }
                        composable<PrajusRoute.Aparencia> {
                            AparenciaScreen(
                                brand = brand,
                                onUploadLogo = { slot, uri -> viewModel.uploadBrandLogo(slot, uri) },
                                onResetLogo = { viewModel.resetLogoSlot(it) },
                                onSaveColors = { c1, c2, c3 -> viewModel.updateBrandColors(c1, c2, c3) },
                                onResetAll = { viewModel.resetAllBranding() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (isCompact) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(268.dp),
                    drawerContainerColor = LocalPrajusColors.current.navy
                ) {
                    drawerContent()
                }
            },
            content = mainContent
        )
    } else {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    modifier = Modifier.width(240.dp),
                    drawerContainerColor = LocalPrajusColors.current.navy
                ) {
                    drawerContent()
                }
            },
            content = mainContent
        )
    }

    if (showSearchDialog) {
        GlobalSearchDialog(
            results = searchResults,
            onSearch = { viewModel.runGlobalSearch(it) },
            onOpenCase = { navController.navigate(PrajusRoute.CasoDetail(it)) },
            onOpenClients = { navigateTopLevel(PrajusRoute.Clientes) },
            onDismiss = {
                showSearchDialog = false
                viewModel.clearGlobalSearch()
            }
        )
    }

    if (showNotificationsDialog) {
        NotificationsDialog(
            notifications = notificationsList,
            onMarkRead = { viewModel.markNotificationRead(it) },
            onDismiss = { showNotificationsDialog = false }
        )
    }

    // Onboarding on first Google login when /users/{uid} has not been created yet
    val needsOnboarding = profileState is ProfileUiState.Ready && profile == null
    if (needsOnboarding || showEditProfileDialog) {
        OfficeProfileDialog(
            title = if (needsOnboarding) "Configurar escritório" else "Perfil do escritório",
            initialNome = profile?.nome ?: viewModel.googleDisplayName.ifBlank { "Advogado(a)" },
            initialOrgName = profile?.orgName ?: "Meu Escritório",
            initialOab = profile?.oab.orEmpty(),
            dismissible = !needsOnboarding,
            onSave = { nome, orgName, oab, onSuccess, onError ->
                viewModel.saveUserProfile(nome, orgName, oab, onSuccess, onError)
            },
            onDismiss = { showEditProfileDialog = false }
        )
    }
}

@Composable
private fun PrajusNavSidebarContent(
    orgName: String,
    userName: String,
    userEmail: String,
    brand: BrandState,
    navItems: List<NavDestinationItem>,
    isSelected: (PrajusRoute) -> Boolean,
    onSelect: (PrajusRoute) -> Unit,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(brandColors.navy)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) {
            BrandLogoImage(
                slot = "horizontal",
                customPath = brand.logoHorizontalPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentDescription = stringResource(R.string.cd_logo_prajus)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(brandColors.gold)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            navItems.forEach { item ->
                val selected = isSelected(item.route)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0x26FFFFFF) else Color.Transparent)
                        .clickable { onSelect(item.route) }
                        .testTag("drawer_nav_${item.label.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(brandColors.gold)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                        tint = Color(0xFFE9EDF5),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = item.label,
                        color = Color(0xFFE9EDF5),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = orgName,
                color = brandColors.gold,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = "$userName\n$userEmail\n${Permissions.roleLabel("admin")}",
                color = Color(0xBFE9EDF5),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenProfile)
                    .testTag("edit_profile_button"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Business, contentDescription = null, tint = Color(0xFFE9EDF5), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Perfil do escritório", color = Color(0xFFE9EDF5), style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onLogout)
                    .testTag("logout_button"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = Color(0xFFE9EDF5), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.nav_sair), color = Color(0xFFE9EDF5), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
