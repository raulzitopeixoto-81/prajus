package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.remote.CaseDocumentDoc
import com.example.data.remote.CaseItemDoc
import com.example.data.remote.ClientDoc
import com.example.data.remote.LegalCaseDoc
import com.example.data.remote.TimelineDoc
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.example.ui.components.*
import com.example.ui.theme.LocalPrajusColors
import com.example.ui.viewmodel.CaseSortOption
import com.example.ui.viewmodel.CasesOverviewStats
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun CasosScreen(
    cases: List<LegalCaseDoc>,
    clients: List<ClientDoc>,
    deadlinesByCase: Map<String, List<CaseItemDoc>>,
    documentsByCase: Map<String, List<CaseDocumentDoc>>,
    overviewStats: CasesOverviewStats,
    query: String,
    showArchived: Boolean,
    statusFilter: String?,
    sortOption: CaseSortOption,
    permissions: Set<String>,
    onQueryChange: (String) -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onStatusFilterChange: (String?) -> Unit,
    onSortOptionChange: (CaseSortOption) -> Unit,
    onOpenCase: (String) -> Unit,
    onCreateCase: (String, String, String, String, String, (String) -> Unit, (String) -> Unit) -> Unit,
    onSaveCaseObs: (LegalCaseDoc, String, () -> Unit, (String) -> Unit) -> Unit,
    onUploadDocument: (String, Uri, String?, String?) -> Unit,
    onDownloadDocument: (String, Long, (File, String) -> Unit) -> Unit,
    onArchiveDocument: (String) -> Unit,
    onLoadTimelineForReport: (String, (List<TimelineDoc>, String, String) -> Unit) -> Unit,
    onRestoreCase: (LegalCaseDoc) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val canWrite = Permissions.WRITE in permissions
    val canArchive = Permissions.ARCHIVE in permissions
    val clientMap = remember(clients) { clients.associateBy({ it.id }, { it.nome }) }
    var showNewCaseDialog by remember { mutableStateOf(false) }
    var quickDialogCaseId by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pinned top header & search bar above the Cases grid
            Column(
                modifier = Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
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

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Buscar por cliente, número do processo ou título") },
                    placeholder = { Text("Ex.: Maria Silva ou 0001234-56.2026...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Buscar casos") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("cases_search_clear")
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Limpar busca")
                            }
                        }
                    },
                    supportingText = if (query.isNotBlank()) {
                        {
                            Text(
                                text = "${cases.size} caso(s) encontrado(s)",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cases_search_input")
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier
                    .widthIn(max = 1120.dp)
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("cases_vertical_grid"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        val statusOptions = remember {
                            listOf("Ativo", "Aguardando", "Encerrado", "Com prazo")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CaseSortDropdownMenu(
                                selected = sortOption,
                                onSelect = onSortOptionChange
                            )
                            VerticalDivider(
                                modifier = Modifier.height(22.dp),
                                color = LocalPrajusColors.current.divider
                            )
                            LazyRow(
                                modifier = Modifier.weight(1f),
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
                }

                if (cases.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PrajusCard {
                            val hasFilters = query.isNotBlank() || statusFilter != null
                            val emptyMsg = when {
                                showArchived && query.isNotBlank() ->
                                    "Nenhum caso arquivado encontrado para “$query”."
                                showArchived -> "Nenhum caso arquivado."
                                hasFilters ->
                                    "Nenhum caso encontrado para o filtro atual."
                                else -> "Nenhum caso ativo. Crie o primeiro."
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                EmptyStateMessage(
                                    icon = Icons.Outlined.FolderOpen,
                                    message = emptyMsg
                                )
                                if (hasFilters) {
                                    OutlinedButton(
                                        onClick = {
                                            onQueryChange("")
                                            onStatusFilterChange(null)
                                        },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("cases_clear_filters_button")
                                    ) {
                                        Text("Limpar busca e filtros")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(cases, key = { it.id }) { c ->
                        val caseDeadlines = deadlinesByCase[c.id].orEmpty()
                        val caseDocs = documentsByCase[c.id].orEmpty()
                        CaseGridCard(
                            caseEntity = c,
                            clientName = clientMap[c.clientId] ?: "—",
                            pendingItems = caseDeadlines,
                            documentCount = caseDocs.size,
                            todayIso = todayIso,
                            plus3Iso = plus3Iso,
                            showRestore = showArchived && canArchive,
                            onClick = { onOpenCase(c.id) },
                            onOpenQuickDialog = { quickDialogCaseId = c.id },
                            onRestore = { onRestoreCase(c) }
                        )
                    }
                }
            }
        }
    }

    quickDialogCaseId?.let { targetCaseId ->
        val targetCase = cases.firstOrNull { it.id == targetCaseId }
        if (targetCase != null) {
            CaseQuickDetailDialog(
                caseEntity = targetCase,
                clientName = clientMap[targetCase.clientId] ?: "—",
                pendingItems = deadlinesByCase[targetCase.id].orEmpty(),
                documents = documentsByCase[targetCase.id].orEmpty(),
                canWrite = canWrite,
                canArchive = canArchive,
                onDismiss = { quickDialogCaseId = null },
                onOpenFullCase = {
                    quickDialogCaseId = null
                    onOpenCase(targetCase.id)
                },
                onSaveObs = { newObs, onOk, onErr ->
                    onSaveCaseObs(targetCase, newObs, onOk, onErr)
                },
                onUploadDocument = { uri, tipo, existingDocId ->
                    onUploadDocument(targetCase.id, uri, tipo, existingDocId)
                },
                onDownloadDocument = onDownloadDocument,
                onArchiveDocument = onArchiveDocument,
                onLoadTimelineForReport = onLoadTimelineForReport,
                onShowMessage = onShowMessage
            )
        } else {
            quickDialogCaseId = null
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
private fun CaseSortDropdownMenu(
    selected: CaseSortOption,
    onSelect: (CaseSortOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = "Ordenar casos",
                    modifier = Modifier.size(18.dp)
                )
            },
            label = {
                Text(
                    text = selected.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .testTag("cases_sort_dropdown")
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CaseSortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    trailingIcon = if (option == selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selecionado",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    modifier = Modifier.testTag("cases_sort_option_${option.name.lowercase()}")
                )
            }
        }
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
    documentCount: Int,
    todayIso: String,
    plus3Iso: String,
    showRestore: Boolean,
    onClick: () -> Unit,
    onOpenQuickDialog: () -> Unit,
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
                    if (documentCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = null,
                                tint = brandColors.mutedText,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "$documentCount documento(s)",
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onOpenQuickDialog,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 36.dp)
                                    .testTag("case_quick_dialog_${caseEntity.id}")
                            ) {
                                Text("Diálogo", style = MaterialTheme.typography.labelMedium)
                            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseQuickDetailDialog(
    caseEntity: LegalCaseDoc,
    clientName: String,
    pendingItems: List<CaseItemDoc>,
    documents: List<CaseDocumentDoc>,
    canWrite: Boolean,
    canArchive: Boolean,
    onDismiss: () -> Unit,
    onOpenFullCase: () -> Unit,
    onSaveObs: (String, () -> Unit, (String) -> Unit) -> Unit,
    onUploadDocument: (Uri, String?, String?) -> Unit,
    onDownloadDocument: (String, Long, (File, String) -> Unit) -> Unit,
    onArchiveDocument: (String) -> Unit,
    onLoadTimelineForReport: (String, (List<TimelineDoc>, String, String) -> Unit) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val brandColors = LocalPrajusColors.current
    var obsText by remember(caseEntity.id, caseEntity.obs) { mutableStateOf(caseEntity.obs.orEmpty()) }
    var docType by remember { mutableStateOf("Petição") }
    var pendingVersionDocId by remember { mutableStateOf<String?>(null) }
    var memoError by remember { mutableStateOf<String?>(null) }

    val pdfReportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            onLoadTimelineForReport(caseEntity.id) { timeline, orgName, authorName ->
                CasePdfReportGenerator.writeReportToUri(
                    context = context,
                    outputUri = uri,
                    orgName = orgName,
                    authorName = authorName,
                    caseEntity = caseEntity,
                    clientName = clientName,
                    pendingItems = pendingItems,
                    documents = documents,
                    timeline = timeline
                ).onSuccess {
                    onShowMessage("Relatório PDF salvo com sucesso.")
                }.onFailure { err ->
                    onShowMessage(err.localizedMessage ?: "Erro ao gerar relatório PDF.")
                }
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                obsText = if (obsText.isBlank()) spoken else "${obsText.trimEnd()}\n$spoken"
            }
        }
    }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onUploadDocument(uri, docType.ifBlank { "Outro" }, pendingVersionDocId)
            pendingVersionDocId = null
        }
    }

    val openFile: (File, String) -> Unit = { file, displayName ->
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)
        val mime = context.contentResolver.getType(contentUri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Abrir $displayName"))
        }.onFailure {
            onShowMessage("Arquivo salvo localmente: $displayName (${file.length()} bytes)")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(caseEntity.titulo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CaseStatusBadge(status = caseEntity.status)
                    Text(
                        text = clientName,
                        style = MaterialTheme.typography.bodySmall,
                        color = brandColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${caseEntity.area?.takeIf { it.isNotBlank() } ?: "Geral"} · ${caseEntity.numero?.takeIf { it.isNotBlank() } ?: "Sem número"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = brandColors.mutedText
                        )
                        val nearest = pendingItems.firstOrNull()
                        if (nearest != null) {
                            Text(
                                text = "Próximo item: ${nearest.tipo} · ${nearest.titulo} (${SecurityUtils.formatDateBr(nearest.data)})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (!caseEntity.proxAcao.isNullOrBlank()) {
                            Text(
                                text = "Próxima ação: ${caseEntity.proxAcao} (${SecurityUtils.formatDateBr(caseEntity.proxData)})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (canWrite) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = obsText,
                            onValueChange = { obsText = it; memoError = null },
                            label = { Text("Observações / Nota de voz") },
                            minLines = 2,
                            maxLines = 4,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale sua nota para o caso")
                                        }
                                        runCatching { voiceLauncher.launch(speechIntent) }.onFailure {
                                            onShowMessage("Ditado por voz indisponível neste dispositivo.")
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("case_dialog_voice_memo_button")
                                ) {
                                    Icon(Icons.Outlined.Mic, contentDescription = "Ditar nota de voz")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (memoError != null) {
                            Text(memoError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (obsText != caseEntity.obs.orEmpty()) {
                            OutlinedButton(
                                onClick = { onSaveObs(obsText, {}, { memoError = it }) },
                                modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                            ) {
                                Text("Salvar observações")
                            }
                        }
                    }
                }

                // Documents section using Firestore metadata
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, brandColors.divider),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().testTag("case_dialog_documents_section")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Documentos (${documents.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Firestore",
                                style = MaterialTheme.typography.labelSmall,
                                color = brandColors.mutedText
                            )
                        }

                        if (canWrite) {
                            val presets = remember { listOf("Petição", "Contrato", "Procuração", "Comprovante", "Outro") }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                presets.forEach { preset ->
                                    FilterChip(
                                        selected = docType == preset,
                                        onClick = { docType = preset },
                                        label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    pendingVersionDocId = null
                                    docPickerLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 44.dp)
                                    .testTag("case_dialog_upload_doc_button")
                            ) {
                                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Enviar documento ($docType)")
                            }
                        }

                        if (documents.isEmpty()) {
                            Text(
                                text = "Nenhum documento associado a este caso.",
                                style = MaterialTheme.typography.bodySmall,
                                color = brandColors.mutedText
                            )
                        } else {
                            documents.forEachIndexed { idx, d ->
                                if (idx > 0) HorizontalDivider(color = brandColors.divider)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(d.nome, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${d.tipo ?: "Outro"} · v${d.versao} · ${formatQuickDocSize(d.size)} · ${SecurityUtils.formatTimestampDateTimeBr(d.updatedAt ?: d.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = brandColors.mutedText
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { onDownloadDocument(d.id, d.versao, openFile) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                        ) {
                                            Text("Baixar", style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (canWrite) {
                                            OutlinedButton(
                                                onClick = {
                                                    pendingVersionDocId = d.id
                                                    docPickerLauncher.launch(arrayOf("*/*"))
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                            ) {
                                                Text("Nova versão", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        if (canArchive) {
                                            TextButton(
                                                onClick = { onArchiveDocument(d.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                            ) {
                                                Text("Arquivar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        pdfReportLauncher.launch(CasePdfReportGenerator.suggestedFileName(caseEntity.titulo))
                    },
                    modifier = Modifier.testTag("case_dialog_download_report_button")
                ) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Baixar relatório")
                }
                Button(
                    onClick = onOpenFullCase,
                    modifier = Modifier.testTag("case_dialog_open_full_button")
                ) {
                    Text("Página completa")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

private fun formatQuickDocSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
