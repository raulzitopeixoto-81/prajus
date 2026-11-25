package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.remote.CaseItemDoc
import com.example.data.remote.LegalCaseDoc
import com.example.data.repository.HojeDashboard
import com.example.data.repository.ItemWithCaseTitle
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.example.ui.components.*
import com.example.ui.theme.LocalPrajusColors

@Composable
fun HojeScreen(
    dashboard: HojeDashboard,
    permissions: Set<String>,
    onCompleteItem: (CaseItemDoc) -> Unit,
    onConfirmDeadline: (CaseItemDoc) -> Unit,
    onOpenCase: (String) -> Unit
) {
    val canWrite = Permissions.WRITE in permissions
    val brandColors = LocalPrajusColors.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = Modifier.widthIn(max = 920.dp)) {
                    PrajusSectionHeader(
                        title = "Hoje",
                        subtitle = "Resumo diário de pendências, prazos e casos"
                    )
                    if (dashboard.aguardandoRevisaoCount > 0) {
                        StatusPill(
                            text = "${dashboard.aguardandoRevisaoCount} prazo(s) aguardando confirmação humana",
                            isAmber = true
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        item {
            DashboardSectionCard(
                title = "Vencidas",
                items = dashboard.vencidas,
                emptyMessage = "Nada vencido.",
                canWrite = canWrite,
                onCompleteItem = onCompleteItem,
                onConfirmDeadline = onConfirmDeadline,
                onOpenCase = onOpenCase
            )
        }

        item {
            DashboardSectionCard(
                title = "Para hoje",
                items = dashboard.hoje,
                emptyMessage = "Nada para hoje.",
                canWrite = canWrite,
                onCompleteItem = onCompleteItem,
                onConfirmDeadline = onConfirmDeadline,
                onOpenCase = onOpenCase
            )
        }

        item {
            DashboardSectionCard(
                title = "Prazos nos próximos 10 dias",
                items = dashboard.prazosProximos,
                emptyMessage = "Sem prazos próximos.",
                canWrite = canWrite,
                onCompleteItem = onCompleteItem,
                onConfirmDeadline = onConfirmDeadline,
                onOpenCase = onOpenCase
            )
        }

        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text(
                        text = "Casos sem próxima ação",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (dashboard.casosSemProximaAcao.isEmpty()) {
                        EmptyStateMessage(
                            icon = Icons.Outlined.CheckCircle,
                            message = "Todos têm próxima ação."
                        )
                    } else {
                        dashboard.casosSemProximaAcao.forEachIndexed { idx, c ->
                            if (idx > 0) HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 8.dp))
                            CaseMissingActionRow(c = c, onOpenCase = onOpenCase)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardSectionCard(
    title: String,
    items: List<ItemWithCaseTitle>,
    emptyMessage: String,
    canWrite: Boolean,
    onCompleteItem: (CaseItemDoc) -> Unit,
    onConfirmDeadline: (CaseItemDoc) -> Unit,
    onOpenCase: (String) -> Unit
) {
    val brandColors = LocalPrajusColors.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (items.isEmpty()) {
                EmptyStateMessage(
                    icon = Icons.Outlined.EventAvailable,
                    message = emptyMessage
                )
            } else {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = brandColors.divider,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    HojeItemRow(
                        item = item,
                        canWrite = canWrite,
                        onComplete = { onCompleteItem(item.item) },
                        onConfirm = { onConfirmDeadline(item.item) },
                        onOpenCase = { onOpenCase(item.caseId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HojeItemRow(
    item: ItemWithCaseTitle,
    canWrite: Boolean,
    onComplete: () -> Unit,
    onConfirm: () -> Unit,
    onOpenCase: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.titulo,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.tipo} · ${item.casoTitulo} · ${SecurityUtils.formatDateBr(item.data)} · ${item.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = brandColors.mutedText
                )
                if (item.tipo == "Prazo" && !item.revisado) {
                    Spacer(modifier = Modifier.height(6.dp))
                    StatusPill(text = "aguarda revisão", isAmber = true)
                }
            }
            IconButton(
                onClick = onOpenCase,
                modifier = Modifier.testTag("hoje_open_case_${item.caseId}")
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Abrir caso")
            }
        }
        if (canWrite && (item.status == "Pendente" || (item.tipo == "Prazo" && !item.revisado))) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == "Pendente") {
                    OutlinedButton(
                        onClick = onComplete,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("hoje_complete_item_${item.id}")
                    ) {
                        Text("Concluir", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (item.tipo == "Prazo" && !item.revisado) {
                    FilledTonalButton(
                        onClick = onConfirm,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("hoje_confirm_deadline_${item.id}")
                    ) {
                        Text("Confirmar prazo", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseMissingActionRow(
    c: LegalCaseDoc,
    onOpenCase: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = c.titulo,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = { onOpenCase(c.id) },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("open_missing_action_case_${c.id}")
        ) {
            Text("Abrir")
        }
    }
}
