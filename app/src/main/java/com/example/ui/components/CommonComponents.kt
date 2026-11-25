package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.remote.NotificationDoc
import com.example.data.repository.GlobalSearchResults
import com.example.data.security.SecurityUtils
import com.example.ui.theme.LocalPrajusColors
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun PrajusSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val brandColors = LocalPrajusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brandColors.gold)
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = brandColors.mutedText
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun PrajusCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val brandColors = LocalPrajusColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, brandColors.divider)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    isAmber: Boolean = false
) {
    val brandColors = LocalPrajusColors.current
    val bg = if (isAmber) brandColors.amberSurface else brandColors.pillBlueSurface
    val fg = if (isAmber) brandColors.amberInk else brandColors.pillBlueInk
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(99.dp),
        color = bg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun BrandLogoImage(
    slot: String,
    customPath: String?,
    modifier: Modifier = Modifier,
    contentDescription: String = "Logotipo Prajus"
) {
    val customFile = customPath?.let { File(it) }?.takeIf { it.exists() }
    if (customFile != null) {
        AsyncImage(
            model = customFile,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        val resId = when (slot) {
            "horizontal" -> R.drawable.brand_horizontal
            "vertical" -> R.drawable.brand_vertical
            else -> R.drawable.brand_icon
        }
        Image(
            painter = painterResource(id = resId),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    isoDate: String,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "date_picker_field"
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = if (isoDate.isBlank()) "" else SecurityUtils.formatDateBr(isoDate),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        placeholder = { Text("Selecionar data") },
        trailingIcon = {
            IconButton(
                onClick = { showPicker = true },
                modifier = Modifier.testTag("${testTag}_btn")
            ) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = "Selecionar $label")
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .testTag(testTag),
        singleLine = true
    )

    if (showPicker) {
        val initialMillis = remember(isoDate) {
            runCatching {
                LocalDate.parse(isoDate.take(10))
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }.getOrElse {
                LocalDate.now()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                            onDateSelected(picked)
                        }
                        showPicker = false
                    }
                ) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancelar") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
fun EmptyStateMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    val brandColors = LocalPrajusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = brandColors.mutedText,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = brandColors.mutedText
        )
    }
}

@Composable
fun GlobalSearchDialog(
    results: GlobalSearchResults?,
    onSearch: (String) -> Unit,
    onOpenCase: (String) -> Unit,
    onOpenClients: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val brandColors = LocalPrajusColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Busca global") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        localError = null
                    },
                    label = { Text("Buscar casos, clientes, itens ou documentos") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (query.trim().length < 2) {
                                    localError = "Digite ao menos 2 caracteres."
                                } else {
                                    onSearch(query)
                                }
                            },
                            modifier = Modifier.testTag("global_search_submit")
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Pesquisar")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global_search_input")
                )
                if (localError != null) {
                    Text(
                        text = localError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (results != null) {
                    val isEmpty = results.casos.isEmpty() && results.clientes.isEmpty() &&
                        results.itens.isEmpty() && results.documentos.isEmpty()
                    if (isEmpty) {
                        Text(
                            "Nenhum resultado encontrado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = brandColors.mutedText
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            if (results.casos.isNotEmpty()) {
                                item {
                                    Text(
                                        "Casos",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                items(results.casos, key = { it.id }) { c ->
                                    ListItem(
                                        headlineContent = { Text(c.titulo) },
                                        supportingContent = { Text(c.numero ?: c.status) },
                                        modifier = Modifier.clickable {
                                            onOpenCase(c.id)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                            if (results.clientes.isNotEmpty()) {
                                item {
                                    Text(
                                        "Clientes",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                items(results.clientes, key = { it.id }) { cl ->
                                    ListItem(
                                        headlineContent = { Text(cl.nome) },
                                        supportingContent = { Text(cl.email ?: cl.doc ?: "Cliente") },
                                        modifier = Modifier.clickable {
                                            onOpenClients()
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                            if (results.itens.isNotEmpty()) {
                                item {
                                    Text(
                                        "Itens",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                items(results.itens, key = { it.id }) { it ->
                                    ListItem(
                                        headlineContent = { Text(it.titulo) },
                                        supportingContent = { Text("${it.tipo} · ${SecurityUtils.formatDateBr(it.data)}") },
                                        modifier = Modifier.clickable {
                                            onOpenCase(it.caseId)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                            if (results.documentos.isNotEmpty()) {
                                item {
                                    Text(
                                        "Documentos",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                items(results.documentos, key = { it.id }) { d ->
                                    ListItem(
                                        headlineContent = { Text(d.nome) },
                                        supportingContent = { Text(d.tipo ?: "Documento") },
                                        modifier = Modifier.clickable {
                                            onOpenCase(d.caseId)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.trim().length < 2) {
                        localError = "Digite ao menos 2 caracteres."
                    } else {
                        onSearch(query)
                    }
                }
            ) { Text("Buscar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
fun NotificationsDialog(
    notifications: List<NotificationDoc>,
    onMarkRead: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val brandColors = LocalPrajusColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alertas de prazo") },
        text = {
            if (notifications.isEmpty()) {
                Text(
                    "Nenhum alerta de prazo nos próximos dias.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = brandColors.mutedText
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(notifications, key = { it.id }) { n ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = n.txt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (n.lida) FontWeight.Normal else FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!n.lida) {
                                    TextButton(onClick = { onMarkRead(n.id) }) {
                                        Text("Marcar lida")
                                    }
                                }
                            }
                            Text(
                                text = SecurityUtils.formatTimestampDateTimeBr(n.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = brandColors.mutedText
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = brandColors.divider)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
fun OfficeProfileDialog(
    title: String,
    initialNome: String,
    initialOrgName: String,
    initialOab: String,
    dismissible: Boolean,
    onSave: (nome: String, orgName: String, oab: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var nome by remember(initialNome) { mutableStateOf(initialNome) }
    var orgName by remember(initialOrgName) { mutableStateOf(initialOrgName) }
    var oab by remember(initialOab) { mutableStateOf(initialOab) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it; err = null },
                    label = { Text("Seu nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("profile_name_input")
                )
                OutlinedTextField(
                    value = orgName,
                    onValueChange = { orgName = it; err = null },
                    label = { Text("Nome do escritório") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("profile_org_input")
                )
                OutlinedTextField(
                    value = oab,
                    onValueChange = { oab = it },
                    label = { Text("OAB (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("profile_oab_input")
                )
                if (err != null) {
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(nome, orgName, oab, onDismiss) { err = it } },
                modifier = Modifier.testTag("profile_save_button")
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            if (dismissible) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}
