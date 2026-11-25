package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.remote.AuditDoc
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.example.ui.components.EmptyStateMessage
import com.example.ui.components.PrajusCard
import com.example.ui.components.PrajusSectionHeader
import com.example.ui.theme.LocalPrajusColors

@Composable
fun AuditoriaScreen(
    entries: List<AuditDoc>,
    permissions: Set<String>,
    onExportJson: ((String) -> Unit) -> Unit,
    onImportJson: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val brandColors = LocalPrajusColors.current
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val createExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportContent
        pendingExportContent = null
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                onShowMessage("Dados exportados com sucesso.")
            }.onFailure {
                onShowMessage("Falha ao salvar exportação.")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Arquivo vazio.")
                onImportJson(text)
            }.onFailure {
                onShowMessage("Não foi possível ler o arquivo JSON.")
            }
        }
    }

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
                        title = "Auditoria",
                        subtitle = "Registro de ações, exportação e importação da organização"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Permissions.EXPORT in permissions) {
                            OutlinedButton(
                                onClick = {
                                    onExportJson { json ->
                                        pendingExportContent = json
                                        createExportLauncher.launch("prajus-export.json")
                                    }
                                },
                                modifier = Modifier.testTag("export_json_button")
                            ) {
                                Text("Exportar JSON")
                            }
                        }
                        if (Permissions.IMPORT in permissions) {
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                                modifier = Modifier.testTag("import_json_button")
                            ) {
                                Text("Importar JSON")
                            }
                        }
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    if (entries.isEmpty()) {
                        EmptyStateMessage(Icons.Outlined.History, "Sem registros.")
                    } else {
                        entries.forEachIndexed { idx, entry ->
                            if (idx > 0) HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 8.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(entry.acao, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${entry.usuario ?: "sistema"} · ${SecurityUtils.formatTimestampDateTimeBr(entry.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = brandColors.mutedText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
