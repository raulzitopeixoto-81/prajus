package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.ClientDoc
import com.example.data.security.Permissions
import com.example.ui.components.EmptyStateMessage
import com.example.ui.components.PrajusCard
import com.example.ui.components.PrajusSectionHeader
import com.example.ui.theme.LocalPrajusColors

@Composable
fun ClientesScreen(
    clients: List<ClientDoc>,
    query: String,
    showArchived: Boolean,
    permissions: Set<String>,
    onQueryChange: (String) -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onCreateClient: (String, String, String, String, () -> Unit, (String) -> Unit) -> Unit,
    onUpdateClient: (String, String, String, String, String, () -> Unit, (String) -> Unit) -> Unit,
    onArchiveClient: (String, Boolean) -> Unit
) {
    val canWrite = Permissions.WRITE in permissions
    val canArchive = Permissions.ARCHIVE in permissions
    val brandColors = LocalPrajusColors.current

    var editingClient by remember { mutableStateOf<ClientDoc?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var archivingClient by remember { mutableStateOf<ClientDoc?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (canWrite && !showArchived) {
                FloatingActionButton(
                    onClick = { showNewDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("new_client_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_novo_cliente))
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(modifier = Modifier.widthIn(max = 920.dp)) {
                        PrajusSectionHeader(
                            title = "Clientes",
                            trailing = {
                                if (canWrite) {
                                    Button(
                                        onClick = { showNewDialog = true },
                                        modifier = Modifier.testTag("new_client_button")
                                    ) {
                                        Text("Novo cliente")
                                    }
                                }
                            }
                        )

                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            label = { Text("Buscar por nome, CPF/CNPJ ou e-mail") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clients_search_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !showArchived,
                                onClick = { onToggleArchived(false) },
                                label = { Text("Ativos") }
                            )
                            FilterChip(
                                selected = showArchived,
                                onClick = { onToggleArchived(true) },
                                label = { Text("Arquivados") }
                            )
                        }
                    }
                }
            }

            if (clients.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                            EmptyStateMessage(
                                icon = Icons.Outlined.PersonOutline,
                                message = if (showArchived) "Nenhum cliente arquivado." else "Nenhum cliente."
                            )
                        }
                    }
                }
            } else {
                items(clients, key = { it.id }) { cl ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cl.nome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    val sub = listOfNotNull(cl.doc, cl.email, cl.tel).joinToString(" · ")
                                    if (sub.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = brandColors.mutedText)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (canWrite && !showArchived) {
                                        IconButton(onClick = { editingClient = cl }) {
                                            Icon(Icons.Outlined.Edit, contentDescription = "Editar cliente")
                                        }
                                    }
                                    if (canArchive) {
                                        if (showArchived) {
                                            OutlinedButton(onClick = { onArchiveClient(cl.id, true) }) {
                                                Text("Restaurar")
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { archivingClient = cl },
                                                modifier = Modifier.testTag("archive_client_${cl.id}")
                                            ) {
                                                Text("Arquivar")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        ClientFormDialog(
            title = "Novo cliente",
            initial = null,
            onDismiss = { showNewDialog = false },
            onSave = { nome, doc, email, tel, onErr ->
                onCreateClient(nome, doc, email, tel, { showNewDialog = false }, onErr)
            }
        )
    }

    editingClient?.let { target ->
        ClientFormDialog(
            title = "Editar cliente",
            initial = target,
            onDismiss = { editingClient = null },
            onSave = { nome, doc, email, tel, onErr ->
                onUpdateClient(target.id, nome, doc, email, tel, { editingClient = null }, onErr)
            }
        )
    }

    archivingClient?.let { target ->
        AlertDialog(
            onDismissRequest = { archivingClient = null },
            title = { Text("Arquivar cliente?") },
            text = { Text("Arquivar ${target.nome}? Você poderá restaurar depois.") },
            confirmButton = {
                Button(onClick = {
                    val id = target.id
                    archivingClient = null
                    onArchiveClient(id, false)
                }) { Text("Arquivar") }
            },
            dismissButton = {
                TextButton(onClick = { archivingClient = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun ClientFormDialog(
    title: String,
    initial: ClientDoc?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, (String) -> Unit) -> Unit
) {
    var nome by remember { mutableStateOf(initial?.nome.orEmpty()) }
    var doc by remember { mutableStateOf(initial?.doc.orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var tel by remember { mutableStateOf(initial?.tel.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it; err = null },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("client_name_input")
                )
                OutlinedTextField(
                    value = doc,
                    onValueChange = { doc = it },
                    label = { Text("CPF/CNPJ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; err = null },
                    label = { Text("E-mail") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tel,
                    onValueChange = { tel = it },
                    label = { Text("Telefone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (err != null) {
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(nome, doc, email, tel) { err = it } },
                modifier = Modifier.testTag("client_save_button")
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
