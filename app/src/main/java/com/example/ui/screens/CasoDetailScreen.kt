package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.remote.*
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.example.ui.components.*
import com.example.ui.theme.LocalPrajusColors
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@Composable
fun CasoDetailScreen(
    caseId: String,
    viewModel: MainViewModel,
    permissions: Set<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val brandColors = LocalPrajusColors.current
    val canWrite = Permissions.WRITE in permissions
    val canArchive = Permissions.ARCHIVE in permissions

    val caseState by viewModel.observeCase(caseId).collectAsStateWithLifecycle(initialValue = null)
    val items by viewModel.observeCaseItems(caseId).collectAsStateWithLifecycle(initialValue = emptyList())
    val docs by viewModel.observeCaseDocuments(caseId).collectAsStateWithLifecycle(initialValue = emptyList())
    val timeline by viewModel.observeTimeline(caseId).collectAsStateWithLifecycle(initialValue = emptyList())

    var showNextActionDialog by remember { mutableStateOf(false) }
    var showEditCaseDialog by remember { mutableStateOf(false) }
    var showArchiveConfirm by remember { mutableStateOf(false) }
    var showNewItemDialog by remember { mutableStateOf(false) }
    var editingDateItem by remember { mutableStateOf<CaseItemDoc?>(null) }
    var uploadTypeDialog by remember { mutableStateOf(false) }
    var pendingDocType by remember { mutableStateOf("") }
    var pendingVersionDocId by remember { mutableStateOf<String?>(null) }
    var versionsSheetDoc by remember { mutableStateOf<Pair<CaseDocumentDoc, List<DocVersionDoc>>?>(null) }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadDocument(
                caseId = caseId,
                uri = uri,
                tipo = pendingDocType.ifBlank { "Outro" },
                existingDocId = pendingVersionDocId
            )
            pendingVersionDocId = null
            pendingDocType = ""
        }
    }

    val openDownloadedFile: (File, String) -> Unit = { file, displayName ->
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
            viewModel.emitMessage("Arquivo salvo localmente: $displayName (${file.length()} bytes)")
        }
    }

    val c = caseState
    if (c == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val activeClients by viewModel.activeClientsForMap.collectAsStateWithLifecycle()
    val clientName = remember(activeClients, c.clientId) {
        activeClients.firstOrNull { it.id == c.clientId }?.nome ?: "—"
    }

    val pdfReportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            viewModel.loadTimelineForReport(c.id) { loadedTimeline, orgName, authorName ->
                CasePdfReportGenerator.writeReportToUri(
                    context = context,
                    outputUri = uri,
                    orgName = orgName,
                    authorName = authorName,
                    caseEntity = c,
                    clientName = clientName,
                    pendingItems = items.filter { it.status != "Concluída" },
                    documents = docs,
                    timeline = loadedTimeline.ifEmpty { timeline }
                ).onSuccess {
                    viewModel.emitMessage("Relatório PDF salvo com sucesso.")
                }.onFailure { err ->
                    viewModel.emitMessage(err.localizedMessage ?: "Erro ao gerar relatório PDF.")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = Modifier.widthIn(max = 920.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("case_detail_back")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_voltar)
                            )
                        }
                        Text("Casos", style = MaterialTheme.typography.bodyMedium, color = brandColors.mutedText)
                    }

                    PrajusSectionHeader(
                        title = c.titulo,
                        trailing = {
                            StatusPill(text = c.status)
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                pdfReportLauncher.launch(CasePdfReportGenerator.suggestedFileName(c.titulo))
                            },
                            modifier = Modifier.testTag("case_download_report_button")
                        ) {
                            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Baixar relatório")
                        }
                        if (canWrite) {
                            OutlinedButton(
                                onClick = { showNextActionDialog = true },
                                modifier = Modifier.testTag("case_next_action_button")
                            ) {
                                Text("Próxima ação")
                            }
                            OutlinedButton(
                                onClick = { showEditCaseDialog = true },
                                modifier = Modifier.testTag("case_edit_button")
                            ) {
                                Text("Editar")
                            }
                        }
                        if (canArchive) {
                            if (c.archivedAt == null) {
                                OutlinedButton(
                                    onClick = { showArchiveConfirm = true },
                                    modifier = Modifier.testTag("case_archive_button")
                                ) {
                                    Text("Arquivar")
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.archiveCase(c.id, c.titulo, restore = true) },
                                    modifier = Modifier.testTag("case_restore_button")
                                ) {
                                    Text("Restaurar")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Case details card
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text(
                        text = "${c.numero ?: "Sem número"} · ${c.area ?: "—"}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val prox = if (!c.proxAcao.isNullOrBlank()) {
                        "${c.proxAcao} (${SecurityUtils.formatDateBr(c.proxData)})"
                    } else "não definida"
                    Text(
                        text = "Próxima ação: $prox",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!c.obs.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = c.obs,
                            style = MaterialTheme.typography.bodySmall,
                            color = brandColors.mutedText
                        )
                    }
                }
            }
        }

        // Tasks, Deadlines, Appointments
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tarefas, prazos e compromissos",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (canWrite) {
                            FilledTonalButton(
                                onClick = { showNewItemDialog = true },
                                modifier = Modifier.testTag("case_add_item_button")
                            ) {
                                Text("Adicionar")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (items.isEmpty()) {
                        EmptyStateMessage(Icons.Outlined.CheckBox, "Nada cadastrado.")
                    } else {
                        items.forEachIndexed { idx, it ->
                            if (idx > 0) HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 8.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(it.titulo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${it.tipo} · ${SecurityUtils.formatDateBr(it.data)} · ${it.prio} · ${it.status}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = brandColors.mutedText
                                        )
                                        if (it.tipo == "Prazo" && !it.revisado) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            StatusPill("aguarda revisão", isAmber = true)
                                        }
                                    }
                                    if (canWrite) {
                                        IconButton(onClick = { editingDateItem = it }) {
                                            Icon(Icons.Outlined.EditCalendar, contentDescription = "Alterar data")
                                        }
                                    }
                                }
                                if (canWrite && (it.status == "Pendente" || (it.tipo == "Prazo" && !it.revisado))) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (it.status == "Pendente") {
                                            OutlinedButton(
                                                onClick = { viewModel.completeItem(it) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Text("Concluir", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        if (it.tipo == "Prazo" && !it.revisado) {
                                            FilledTonalButton(
                                                onClick = { viewModel.confirmDeadline(it) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("confirm_deadline_${it.id}")
                                            ) {
                                                Text("Confirmar prazo", style = MaterialTheme.typography.labelMedium)
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

        // Documents
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Documentos",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (canWrite) {
                            FilledTonalButton(
                                onClick = {
                                    pendingVersionDocId = null
                                    uploadTypeDialog = true
                                },
                                modifier = Modifier.testTag("case_upload_doc_button")
                            ) {
                                Text("Enviar arquivo")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (docs.isEmpty()) {
                        EmptyStateMessage(Icons.Outlined.Description, "Nenhum documento.")
                    } else {
                        docs.forEachIndexed { idx, d ->
                            if (idx > 0) HorizontalDivider(color = brandColors.divider, modifier = Modifier.padding(vertical = 8.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(d.nome, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${d.tipo ?: "Outro"} · v${d.versao} · ${formatDocSize(d.size)} · ${SecurityUtils.formatTimestampDateTimeBr(d.updatedAt ?: d.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = brandColors.mutedText
                                )
                                if (d.sha256.isNotBlank()) {
                                    Text(
                                        "SHA-256: ${d.sha256.take(16)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = brandColors.mutedText
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.prepareDocumentDownload(d.id, d.versao, openDownloadedFile)
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Baixar", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.loadDocumentVersions(d.id) { vers ->
                                                versionsSheetDoc = d to vers
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Versões", style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (canWrite) {
                                        OutlinedButton(
                                            onClick = {
                                                pendingVersionDocId = d.id
                                                pendingDocType = ""
                                                documentPickerLauncher.launch(arrayOf("*/*"))
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Nova versão", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Gold-bordered Timeline
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrajusCard(modifier = Modifier.widthIn(max = 920.dp)) {
                    Text(
                        text = "Linha do tempo",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    if (timeline.isEmpty()) {
                        EmptyStateMessage(Icons.Outlined.History, "Sem eventos.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            timeline.forEach { entry ->
                                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .fillMaxHeight()
                                            .background(brandColors.gold)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(entry.txt, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${SecurityUtils.formatTimestampDateTimeBr(entry.createdAt)} · ${entry.autor ?: ""}",
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
    }

    if (showNextActionDialog) {
        var acao by remember { mutableStateOf(c.proxAcao.orEmpty()) }
        var data by remember { mutableStateOf(c.proxData ?: SecurityUtils.todayIso()) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showNextActionDialog = false },
            title = { Text("Próxima ação") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = acao,
                        onValueChange = { acao = it; err = null },
                        label = { Text("O que precisa ser feito?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("next_action_input")
                    )
                    DatePickerField(
                        label = "Data",
                        isoDate = data,
                        onDateSelected = { data = it; err = null }
                    )
                    if (err != null) {
                        Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateCaseNextAction(c.id, acao, data, { showNextActionDialog = false }, { err = it })
                    },
                    modifier = Modifier.testTag("next_action_save_button")
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showNextActionDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showEditCaseDialog) {
        var titulo by remember { mutableStateOf(c.titulo) }
        var numero by remember { mutableStateOf(c.numero.orEmpty()) }
        var area by remember { mutableStateOf(c.area.orEmpty()) }
        var status by remember { mutableStateOf(c.status) }
        var obs by remember { mutableStateOf(c.obs.orEmpty()) }
        var err by remember { mutableStateOf<String?>(null) }
        val voiceLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
                if (!spoken.isNullOrEmpty()) {
                    obs = if (obs.isBlank()) spoken else "${obs.trimEnd()}\n$spoken"
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showEditCaseDialog = false },
            title = { Text("Editar caso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = titulo, onValueChange = { titulo = it; err = null }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = numero, onValueChange = { numero = it }, label = { Text("Número") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = area, onValueChange = { area = it }, label = { Text("Área") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Ativo", "Aguardando", "Encerrado").forEach { st ->
                            FilterChip(selected = status == st, onClick = { status = st }, label = { Text(st) })
                        }
                    }
                    OutlinedTextField(
                        value = obs,
                        onValueChange = { obs = it },
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
                                        viewModel.emitMessage("Ditado por voz indisponível neste dispositivo.")
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("case_edit_voice_memo_button")
                            ) {
                                Icon(Icons.Outlined.Mic, contentDescription = "Ditar nota de voz")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateCaseDetails(c.id, titulo, numero, area, status, obs, { showEditCaseDialog = false }, { err = it })
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showEditCaseDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showArchiveConfirm) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirm = false },
            title = { Text("Arquivar caso?") },
            text = { Text("Arquivar? Você poderá restaurar depois.") },
            confirmButton = {
                Button(onClick = {
                    showArchiveConfirm = false
                    viewModel.archiveCase(c.id, c.titulo, restore = false, onDone = onBack)
                }) { Text("Arquivar") }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    if (showNewItemDialog) {
        var tipo by remember { mutableStateOf("Prazo") }
        var titulo by remember { mutableStateOf("") }
        var data by remember { mutableStateOf(SecurityUtils.todayIso()) }
        var prio by remember { mutableStateOf("Média") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showNewItemDialog = false },
            title = { Text("Novo item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tipo", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Tarefa", "Prazo", "Compromisso").forEach { t ->
                            FilterChip(selected = tipo == t, onClick = { tipo = t }, label = { Text(t) })
                        }
                    }
                    OutlinedTextField(
                        value = titulo,
                        onValueChange = { titulo = it; err = null },
                        label = { Text("Título") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_item_title_input")
                    )
                    DatePickerField(label = "Data", isoDate = data, onDateSelected = { data = it; err = null })
                    Text("Prioridade", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Baixa", "Média", "Alta").forEach { p ->
                            FilterChip(selected = prio == p, onClick = { prio = p }, label = { Text(p) })
                        }
                    }
                    if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createItem(c.id, tipo, titulo, data, prio, { showNewItemDialog = false }, { err = it })
                    },
                    modifier = Modifier.testTag("new_item_save_button")
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showNewItemDialog = false }) { Text("Cancelar") }
            }
        )
    }

    editingDateItem?.let { target ->
        var newDate by remember { mutableStateOf(target.data) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editingDateItem = null },
            title = { Text("Alterar data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.titulo, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (target.tipo == "Prazo") {
                        Text(
                            "Alterar a data de um prazo exige nova confirmação humana.",
                            style = MaterialTheme.typography.bodySmall,
                            color = brandColors.amberInk
                        )
                    }
                    DatePickerField(label = "Nova data", isoDate = newDate, onDateSelected = { newDate = it; err = null })
                    if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateItemDate(target, newDate, { editingDateItem = null }, { err = it })
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { editingDateItem = null }) { Text("Cancelar") }
            }
        )
    }

    if (uploadTypeDialog) {
        val typePresets = remember { listOf("Petição", "Contrato", "Procuração", "Comprovante", "Outro") }
        AlertDialog(
            onDismissRequest = { uploadTypeDialog = false },
            title = { Text("Enviar documento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Formatos: pdf, doc, docx, xls, xlsx, odt, png, jpg, txt (até 25 MB). Metadados salvos no Firestore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = brandColors.mutedText
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        typePresets.forEach { preset ->
                            FilterChip(
                                selected = pendingDocType.equals(preset, ignoreCase = true),
                                onClick = { pendingDocType = preset },
                                label = { Text(preset) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pendingDocType,
                        onValueChange = { pendingDocType = it },
                        label = { Text("Tipo (ex.: Petição, Contrato…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    uploadTypeDialog = false
                    documentPickerLauncher.launch(arrayOf("*/*"))
                }) { Text("Escolher arquivo") }
            },
            dismissButton = {
                TextButton(onClick = { uploadTypeDialog = false }) { Text("Cancelar") }
            }
        )
    }

    versionsSheetDoc?.let { (doc, vers) ->
        AlertDialog(
            onDismissRequest = { versionsSheetDoc = null },
            title = { Text("Versões — ${doc.nome}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vers.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Versão ${v.n} (${v.size} bytes)", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${SecurityUtils.formatTimestampDateTimeBr(v.createdAt)} · ${v.porNome ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = brandColors.mutedText
                                )
                                Text(
                                    "SHA-256: ${v.sha256.take(12)}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = brandColors.mutedText
                                )
                            }
                            TextButton(onClick = {
                                viewModel.prepareDocumentDownload(doc.id, v.n, openDownloadedFile)
                            }) { Text("Baixar") }
                        }
                    }
                }
            },
            confirmButton = {
                if (canArchive) {
                    TextButton(onClick = {
                        versionsSheetDoc = null
                        viewModel.archiveDocument(doc.id)
                    }) { Text("Arquivar doc", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { versionsSheetDoc = null }) { Text("Fechar") }
            }
        )
    }
}

private fun formatDocSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
