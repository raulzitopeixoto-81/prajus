package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.CaseItemDoc
import com.example.data.remote.ClientDoc
import com.example.data.remote.LegalCaseDoc
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.example.ui.components.*
import com.example.ui.theme.LocalPrajusColors
import com.example.ui.viewmodel.CasesOverviewStats
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun CasosScreen(
    cases: List<LegalCaseDoc>,
    clients: List<ClientDoc>,
    deadlinesByCase: Map<String, List<CaseItemDoc>>,
    overviewStats: CasesOverviewStats,
    query: String,
    showArchived: Boolean,
    statusFilter: String?,
    permissions: Set<String>,
    onQueryChange: (String) -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onStatusFilterChange: (String?) -> Unit,
    onOpenCase: (String) -> Unit,
    onCreateCase: (String, String, String, String, String, (String) -> Unit, (String) -> Unit) -> Unit,
    onRestoreCase: (LegalCaseDoc) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val canWrite = Permissions.WRITE in permissions
    val canArchive = Permissions.ARCHIVE in permissions
    val clientMap = remember(clients) { clients.associateBy({ it.id }, { it.nome }) }
    var showNewCaseDialog by remember { mutableStateOf(false) }
    val todayIso = remember { SecurityUtils.todayIso() }
    val plus3Iso = remember { SecurityUtils.todayPlusDaysIso(3) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (canWrite && !showArchived) {
                FloatingActionButton(
                    onClick = {
                        if (clients.isEmpty()) {
                            onShowMessage("Cadastre um cliente primeiro.")
                        } else {
                            showNewCaseDialog = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("new_case_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_novo_caso))
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxSize()
                    .testTag("cases_vertical_grid"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        PrajusSectionHeader(
                            title = "Casos",
                            subtitle = "Central de processos ativos, status e próximos prazos",
                            trailing = {
                                if (canWrite) {
                                    Button(
                                        onClick = {
                                            if (clients.isEmpty()) {
                                                onShowMessage("Cadastre um cliente primeiro.")
                                            } else {
                                                showNewCaseDialog = true
                                            }
                                        },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("new_case_button")
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Novo caso")
                                    }
                                }
                            }
                        )

                        if (!showArchived) {
                            CasesSummaryRow(
                                stats = overviewStats,
                                onSelectDeadlinesFilter = {
                                    onStatusFilterChange(
                                        if (statusFilter == "Com prazo") null else "Com prazo"
                                    )
                                },
                                isDeadlinesFilterActive = statusFilter == "Com prazo"
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            label = { Text("Buscar por título, cliente, número ou área") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { onQueryChange("") }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Limpar busca")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cases_search_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val statusOptions = remember {
                            listOf("Ativo", "Aguardando", "Encerrado", "Com prazo")
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                FilterChip(
                                    selected = !showArchived,
                                    onClick = { onToggleArchived(false) },
                                    label = { Text("Ativos") },
                                    modifier = Modifier.testTag("cases_filter_active")
                                )
                            }
                            item {
                                FilterChip(
                                    selected = showArchived,
                                    onClick = { onToggleArchived(true) },
                                    label = { Text("Arquivados") },
                                    modifier = Modifier.testTag("cases_filter_archived")
                                )
                            }
                            if (!showArchived) {
                                item {
                                    VerticalDivider(
                                        modifier = Modifier
                                            .height(22.dp)
                                            .padding(horizontal = 2.dp),
                                        color = LocalPrajusColors.current.divider
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = statusFilter == null,
                                        onClick = { onStatusFilterChange(null) },
                                        label = { Text("Todos") },
                                        modifier = Modifier.testTag("cases_status_all")
                                    )
                                }
                                lazyRowItems(statusOptions) { st ->
                                    FilterChip(
                                        selected = statusFilter == st,
                                        onClick = {
                                            onStatusFilterChange(if (statusFilter == st) null else st)
                                        },
                                        label = { Text(st) },
                                        modifier = Modifier.testTag("cases_status_$st")
                                    )
                                }
                            }
                        }
                    }
                }

                if (cases.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PrajusCard {
                            val emptyMsg = when {
                                showArchived -> "Nenhum caso arquivado."
                                query.isNotBlank() || statusFilter != null ->
                                    "Nenhum caso encontrado para o filtro atual."
                                else -> "Nenhum caso ativo. Crie o primeiro."
                            }
                            EmptyStateMessage(
                                icon = Icons.Outlined.FolderOpen,
                                message = emptyMsg
                            )
                        }
                    }
                } else {
                    items(cases, key = { it.id }) { c ->
                        val caseDeadlines = deadlinesByCase[c.id].orEmpty()
                        CaseGridCard(
                            caseEntity = c,
                            clientName = clientMap[c.clientId] ?: "—",
                            pendingItems = caseDeadlines,
                            todayIso = todayIso,
                            plus3Iso = plus3Iso,
                            showRestore = showArchived && canArchive,
                            onClick = { onOpenCase(c.id) },
                            onRestore = { onRestoreCase(c) }
                        )
                    }
                }
            }
        }
    }

    if (showNewCaseDialog) {
        NewCaseDialog(
            clients = clients,
            onDismiss = { showNewCaseDialog = false },
            onCreate = { titulo, clientId, numero, area, obs, onErr ->
                onCreateCase(
                    titulo, clientId, numero, area, obs,
                    { newId ->
                        showNewCaseDialog = false
                        onOpenCase(newId)
                    },
                    onErr
                )
            }
        )
    }
}

@Composable
private fun CasesSummaryRow(
    stats: CasesOverviewStats,
    isDeadlinesFilterActive: Boolean,
    onSelectDeadlinesFilter: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryMetricTile(
            label = "Ativos",
            value = stats.activeCount.toString(),
            icon = Icons.Outlined.Folder,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        val deadlineAccent = if (stats.overdueCasesCount > 0) {
            MaterialTheme.colorScheme.error
        } else {
            brandColors.gold
        }
        val deadlineValue = if (stats.overdueCasesCount > 0) {
            "${stats.upcomingDeadlinesCasesCount} (${stats.overdueCasesCount} venc.)"
        } else {
            stats.upcomingDeadlinesCasesCount.toString()
        }
        SummaryMetricTile(
            label = "Prazos (10d)",
            value = deadlineValue,
            icon = Icons.Outlined.Event,
            accentColor = deadlineAccent,
            selected = isDeadlinesFilterActive,
            onClick = onSelectDeadlinesFilter,
            modifier = Modifier
                .weight(1f)
                .testTag("cases_summary_deadlines_tile")
        )
        SummaryMetricTile(
            label = "Sem ação",
            value = stats.missingNextActionCount.toString(),
            icon = Icons.Outlined.PendingActions,
            accentColor = if (stats.missingNextActionCount > 0) brandColors.amberInk else brandColors.mutedText,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryMetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val brandColors = LocalPrajusColors.current
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) brandColors.pillBlueSurface else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else brandColors.divider
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = brandColors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class DeadlineUrgency {
    OVERDUE, TODAY, SOON, UPCOMING
}

private data class DeadlineBadgeInfo(
    val urgency: DeadlineUrgency,
    val label: String,
    val formattedDate: String
)

private fun buildDeadlineBadge(
    isoDate: String,
    todayIso: String,
    plus3Iso: String
): DeadlineBadgeInfo {
    val formatted = SecurityUtils.formatDateBr(isoDate)
    val urgency = when {
        isoDate < todayIso -> DeadlineUrgency.OVERDUE
        isoDate == todayIso -> DeadlineUrgency.TODAY
        isoDate <= plus3Iso -> DeadlineUrgency.SOON
        else -> DeadlineUrgency.UPCOMING
    }
    val label = when (urgency) {
        DeadlineUrgency.OVERDUE -> "Vencido"
        DeadlineUrgency.TODAY -> "Hoje"
        DeadlineUrgency.SOON -> {
            val days = runCatching {
                ChronoUnit.DAYS.between(LocalDate.parse(todayIso), LocalDate.parse(isoDate.take(10)))
            }.getOrNull()
            if (days != null && days in 1L..3L) "Em ${days}d" else "Em breve"
        }
        DeadlineUrgency.UPCOMING -> "Prazo"
    }
    return DeadlineBadgeInfo(urgency, label, formatted)
}

@Composable
private fun CaseGridCard(
    caseEntity: LegalCaseDoc,
    clientName: String,
    pendingItems: List<CaseItemDoc>,
    todayIso: String,
    plus3Iso: String,
    showRestore: Boolean,
    onClick: () -> Unit,
    onRestore: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    val nearestItem = pendingItems.firstOrNull()
    val deadlineBadge = remember(nearestItem?.data, caseEntity.proxData, todayIso, plus3Iso) {
        val targetDate = nearestItem?.data?.takeIf { it.isNotBlank() } ?: caseEntity.proxData?.takeIf { it.isNotBlank() }
        targetDate?.let { buildDeadlineBadge(it, todayIso, plus3Iso) }
    }
    val hasOverdue = deadlineBadge?.urgency == DeadlineUrgency.OVERDUE
    val hasUrgent = deadlineBadge?.urgency == DeadlineUrgency.TODAY || deadlineBadge?.urgency == DeadlineUrgency.SOON

    val cardBorderColor = when {
        hasOverdue -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
        hasUrgent -> brandColors.gold.copy(alpha = 0.7f)
        else -> brandColors.divider
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("case_card_${caseEntity.id}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Subtle top accent strip reflecting deadline/status priority
            val accentStripColor = when {
                hasOverdue -> MaterialTheme.colorScheme.error
                hasUrgent -> brandColors.gold
                caseEntity.status == "Ativo" -> MaterialTheme.colorScheme.primary
                caseEntity.status == "Aguardando" -> brandColors.amberInk
                else -> brandColors.divider
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accentStripColor)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header: Area + Case Status pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = caseEntity.area?.takeIf { it.isNotBlank() } ?: "Geral",
                        style = MaterialTheme.typography.labelMedium,
                        color = brandColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CaseStatusBadge(status = caseEntity.status)
                }

                // Case Title & Metadata
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = caseEntity.titulo,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = brandColors.mutedText,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = clientName,
                            style = MaterialTheme.typography.bodySmall,
                            color = brandColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!caseEntity.numero.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Gavel,
                                contentDescription = null,
                                tint = brandColors.mutedText,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = caseEntity.numero,
                                style = MaterialTheme.typography.bodySmall,
                                color = brandColors.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Upcoming Deadline & Next Action Panel
                UpcomingDeadlineBlock(
                    nearestItem = nearestItem,
                    totalPendingCount = pendingItems.size,
                    proxAcao = caseEntity.proxAcao,
                    proxData = caseEntity.proxData,
                    badge = deadlineBadge
                )

                // Footer
                if (showRestore) {
                    OutlinedButton(
                        onClick = onRestore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restaurar caso")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val actionSummary = caseEntity.proxAcao?.takeIf { it.isNotBlank() }
                        Text(
                            text = if (actionSummary != null && nearestItem != null) {
                                "Próx.: $actionSummary"
                            } else {
                                "Ver detalhes"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "Abrir caso ${caseEntity.titulo}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseStatusBadge(status: String) {
    val brandColors = LocalPrajusColors.current
    val (bg, fg) = when (status) {
        "Ativo" -> brandColors.pillBlueSurface to brandColors.pillBlueInk
        "Aguardando" -> brandColors.amberSurface to brandColors.amberInk
        else -> MaterialTheme.colorScheme.surfaceVariant to brandColors.mutedText
    }
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = bg
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun UpcomingDeadlineBlock(
    nearestItem: CaseItemDoc?,
    totalPendingCount: Int,
    proxAcao: String?,
    proxData: String?,
    badge: DeadlineBadgeInfo?
) {
    val brandColors = LocalPrajusColors.current
    val panelColor = when (badge?.urgency) {
        DeadlineUrgency.OVERDUE -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        DeadlineUrgency.TODAY, DeadlineUrgency.SOON -> brandColors.amberSurface.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = panelColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (nearestItem != null && badge != null) {
                val badgeBg = when (badge.urgency) {
                    DeadlineUrgency.OVERDUE -> MaterialTheme.colorScheme.error
                    DeadlineUrgency.TODAY, DeadlineUrgency.SOON -> brandColors.amberInk
                    DeadlineUrgency.UPCOMING -> MaterialTheme.colorScheme.primary
                }
                val badgeFg = when (badge.urgency) {
                    DeadlineUrgency.OVERDUE -> MaterialTheme.colorScheme.onError
                    else -> Color.White
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeBg
                        ) {
                            Text(
                                text = badge.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = badge.formattedDate,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (totalPendingCount > 1) {
                        Text(
                            text = "+${totalPendingCount - 1} pendente(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = brandColors.mutedText
                        )
                    }
                }
                Text(
                    text = "${nearestItem.tipo}: ${nearestItem.titulo}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nearestItem.tipo == "Prazo" && !nearestItem.revisado) {
                    Text(
                        text = "Aguarda revisão humana",
                        style = MaterialTheme.typography.labelSmall,
                        color = brandColors.amberInk,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (!proxAcao.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (!proxData.isNullOrBlank()) {
                            "Próxima ação (${SecurityUtils.formatDateBr(proxData)})"
                        } else {
                            "Próxima ação"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = proxAcao,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EventAvailable,
                        contentDescription = null,
                        tint = brandColors.mutedText,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Sem prazos pendentes",
                        style = MaterialTheme.typography.bodySmall,
                        color = brandColors.mutedText
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewCaseDialog(
    clients: List<ClientDoc>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String, (String) -> Unit) -> Unit
) {
    var titulo by remember { mutableStateOf("") }
    var selectedClient by remember { mutableStateOf(clients.first()) }
    var clientExpanded by remember { mutableStateOf(false) }
    var numero by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var obs by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo caso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = titulo,
                    onValueChange = { titulo = it; err = null },
                    label = { Text("Título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_case_title_input")
                )
                ExposedDropdownMenuBox(
                    expanded = clientExpanded,
                    onExpandedChange = { clientExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedClient.nome,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Cliente") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("new_case_client_dropdown")
                    )
                    ExposedDropdownMenu(
                        expanded = clientExpanded,
                        onDismissRequest = { clientExpanded = false }
                    ) {
                        clients.forEach { cl ->
                            DropdownMenuItem(
                                text = { Text(cl.nome) },
                                onClick = {
                                    selectedClient = cl
                                    clientExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = numero,
                    onValueChange = { numero = it },
                    label = { Text("Número do processo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = area,
                    onValueChange = { area = it },
                    label = { Text("Área") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = obs,
                    onValueChange = { obs = it },
                    label = { Text("Observações") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                if (err != null) {
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(titulo, selectedClient.id, numero, area, obs) { err = it } },
                modifier = Modifier.testTag("new_case_save_button")
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
