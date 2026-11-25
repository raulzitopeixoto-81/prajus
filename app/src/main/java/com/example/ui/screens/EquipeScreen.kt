package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.remote.TeamInviteDoc
import com.example.data.remote.TeamMemberDoc
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.example.ui.components.EmptyStateMessage
import com.example.ui.components.PrajusCard
import com.example.ui.components.PrajusSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalPrajusColors

@Composable
fun EquipeScreen(
    members: List<TeamMemberDoc>,
    invites: List<TeamInviteDoc>,
    onCreateInvite: (String, String, String, () -> Unit, (String) -> Unit) -> Unit,
    onResendInvite: (String) -> Unit,
    onRevokeInvite: (String) -> Unit,
    onUpdateMember: (String, String, String) -> Unit
) {
    val brandColors = LocalPrajusColors.current
    var showInviteDialog by remember { mutableStateOf(false) }
    var editingMember by remember { mutableStateOf<TeamMemberDoc?>(null) }

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
                        title = "Equipe",
                        trailing = {
                            Button(
                                onClick = { showInviteDialog = true },
                                modifier = Modifier.testTag("invite_person_button")
                            ) {
                                Text("Convidar")
                            }
                        }
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text(
                        "Membros",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (members.isEmpty()) {
                        EmptyStateMessage(Icons.Outlined.Group, "Nenhum membro cadastrado.")
                    } else {
                        members.forEachIndexed { idx, u ->
                            if (idx > 0) HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(u.nome, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${u.email} · ${Permissions.roleLabel(u.role)} · ${u.status}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = brandColors.mutedText
                                    )
                                }
                                IconButton(onClick = { editingMember = u }) {
                                    Icon(Icons.Outlined.ManageAccounts, contentDescription = "Alterar função ou status")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text(
                        "Convites",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (invites.isEmpty()) {
                        EmptyStateMessage(Icons.Outlined.MailOutline, "Nenhum convite.")
                    } else {
                        invites.forEachIndexed { idx, inv ->
                            if (idx > 0) HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 8.dp))
                            val statusText = when (inv.status) {
                                "aceito" -> "aceito"
                                "revogado" -> "revogado"
                                else -> "pendente até ${SecurityUtils.formatDateBr(inv.expiresAt)}"
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(inv.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${Permissions.roleLabel(inv.role)} · $statusText",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = brandColors.mutedText
                                        )
                                    }
                                    StatusPill(
                                        text = when (inv.status) {
                                            "aceito" -> "Aceito"
                                            "revogado" -> "Revogado"
                                            else -> "Pendente"
                                        },
                                        isAmber = inv.status == "pendente"
                                    )
                                }
                                if (inv.status == "pendente") {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { onResendInvite(inv.id) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Reenviar", style = MaterialTheme.typography.labelMedium)
                                        }
                                        OutlinedButton(
                                            onClick = { onRevokeInvite(inv.id) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Revogar", style = MaterialTheme.typography.labelMedium)
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

    if (showInviteDialog) {
        var nome by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("advogado") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Convidar pessoa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nome,
                        onValueChange = { nome = it },
                        label = { Text("Nome") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; err = null },
                        label = { Text("E-mail") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("invite_email_input")
                    )
                    Text("Função", style = MaterialTheme.typography.labelMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Permissions.ROLES.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = role == r, onClick = { role = r })
                                Text(Permissions.roleLabel(r), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreateInvite(nome, email, role, { showInviteDialog = false }, { err = it })
                    },
                    modifier = Modifier.testTag("invite_submit_button")
                ) { Text("Convidar") }
            },
            dismissButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text("Cancelar") }
            }
        )
    }

    editingMember?.let { target ->
        var role by remember { mutableStateOf(target.role) }
        var status by remember { mutableStateOf(target.status) }
        AlertDialog(
            onDismissRequest = { editingMember = null },
            title = { Text("Editar membro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.nome, fontWeight = FontWeight.SemiBold)
                    Text("Função", style = MaterialTheme.typography.labelMedium)
                    Permissions.ROLES.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = role == r, onClick = { role = r })
                            Text(Permissions.roleLabel(r))
                        }
                    }
                    Text("Status", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = status == "ativo", onClick = { status = "ativo" }, label = { Text("Ativo") })
                        FilterChip(selected = status == "inativo", onClick = { status = "inativo" }, label = { Text("Inativo") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val id = target.id
                    editingMember = null
                    onUpdateMember(id, role, status)
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { editingMember = null }) { Text("Cancelar") }
            }
        )
    }
}
