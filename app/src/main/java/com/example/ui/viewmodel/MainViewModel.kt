package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.*
import com.example.data.repository.*
import com.example.data.security.Permissions
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Ready(val profile: UserProfileDoc?) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

data class CasesOverviewStats(
    val activeCount: Int = 0,
    val overdueCasesCount: Int = 0,
    val upcomingDeadlinesCasesCount: Int = 0,
    val missingNextActionCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val repo: PrajusRepository,
    val currentUserId: String,
    val googleDisplayName: String,
    val googleEmail: String
) : ViewModel() {

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val _casesQuery = MutableStateFlow("")
    val casesQuery: StateFlow<String> = _casesQuery.asStateFlow()

    private val _showArchivedCases = MutableStateFlow(false)
    val showArchivedCases: StateFlow<Boolean> = _showArchivedCases.asStateFlow()

    private val _casesStatusFilter = MutableStateFlow<String?>(null)
    val casesStatusFilter: StateFlow<String?> = _casesStatusFilter.asStateFlow()

    private val _clientsQuery = MutableStateFlow("")
    val clientsQuery: StateFlow<String> = _clientsQuery.asStateFlow()

    private val _showArchivedClients = MutableStateFlow(false)
    val showArchivedClients: StateFlow<Boolean> = _showArchivedClients.asStateFlow()

    private val _searchResults = MutableStateFlow<GlobalSearchResults?>(null)
    val searchResults: StateFlow<GlobalSearchResults?> = _searchResults.asStateFlow()

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    val permissions: Set<String> = Permissions.forRole("admin")

    private fun <T> Flow<T>.safeStateIn(initial: T, label: String): StateFlow<T> {
        return this.catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing $label", rootError)
            _streamError.value = rootError.message ?: "Erro ao sincronizar $label."
            emit(initial)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initial)
    }

    val profileState: StateFlow<ProfileUiState> = repo.observeUserProfile(currentUserId)
        .map<UserProfileDoc?, ProfileUiState> { ProfileUiState.Ready(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing user profile", rootError)
            emit(ProfileUiState.Error(rootError.message ?: "Falha ao carregar perfil."))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfileUiState.Loading)

    val brandState: StateFlow<BrandState> = repo.observeBranding(currentUserId)
        .safeStateIn(BrandState(), "branding")

    private val allCasesFlow: StateFlow<List<LegalCaseDoc>> = repo.observeCases(currentUserId)
        .safeStateIn(emptyList(), "casos")

    private val allClientsFlow: StateFlow<List<ClientDoc>> = repo.observeClients(currentUserId)
        .safeStateIn(emptyList(), "clientes")

    private val allItemsFlow: StateFlow<List<CaseItemDoc>> = repo.observeAllItems(currentUserId)
        .safeStateIn(emptyList(), "itens")

    private val allDocumentsFlow: StateFlow<List<CaseDocumentDoc>> = repo.observeAllDocuments(currentUserId)
        .safeStateIn(emptyList(), "documentos")

    val hojeDashboard: StateFlow<HojeDashboard> = repo.observeHojeDashboard(currentUserId)
        .safeStateIn(HojeDashboard(), "hoje")

    val pendingItemsByCaseId: StateFlow<Map<String, List<CaseItemDoc>>> = allItemsFlow
        .map { items ->
            items.asSequence()
                .filter { it.archivedAt == null && it.status != "Concluída" }
                .sortedWith(
                    compareBy<CaseItemDoc> { it.data }
                        .thenBy { if (it.tipo == "Prazo") 0 else 1 }
                )
                .groupBy { it.caseId }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    val casesOverviewStats: StateFlow<CasesOverviewStats> = combine(
        allCasesFlow,
        pendingItemsByCaseId
    ) { cases, itemsByCase ->
        val activeCases = cases.filter { it.archivedAt == null }
        val today = com.example.data.security.SecurityUtils.todayIso()
        val in10Days = com.example.data.security.SecurityUtils.todayPlusDaysIso(10)
        var overdueCases = 0
        var upcomingCases = 0
        var missingNextAction = 0
        activeCases.forEach { c ->
            val pending = itemsByCase[c.id].orEmpty()
            if (pending.any { it.data < today }) {
                overdueCases++
            }
            if (pending.any { it.data in today..in10Days } || (c.proxData != null && c.proxData in today..in10Days)) {
                upcomingCases++
            }
            if (c.proxAcao.isNullOrBlank() && c.status == "Ativo") {
                missingNextAction++
            }
        }
        CasesOverviewStats(
            activeCount = activeCases.size,
            overdueCasesCount = overdueCases,
            upcomingDeadlinesCasesCount = upcomingCases,
            missingNextActionCount = missingNextAction
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CasesOverviewStats())

    val casesList: StateFlow<List<LegalCaseDoc>> = combine(
        combine(allCasesFlow, allClientsFlow, pendingItemsByCaseId) { cases, clients, itemsByCase ->
            Triple(cases, clients.associateBy({ it.id }, { it.nome }), itemsByCase)
        },
        _showArchivedCases,
        _casesQuery,
        _casesStatusFilter
    ) { (list, clientNames, itemsByCase), archived, q, statusFilter ->
        val filteredByArchive = list.filter { if (archived) it.archivedAt != null else it.archivedAt == null }
        val filteredByStatus = when {
            archived || statusFilter == null -> filteredByArchive
            statusFilter == "Com prazo" -> filteredByArchive.filter { c ->
                itemsByCase[c.id].orEmpty().isNotEmpty() || !c.proxData.isNullOrBlank()
            }
            else -> filteredByArchive.filter { it.status == statusFilter }
        }
        val clean = q.trim().lowercase()
        val searched = if (clean.isEmpty()) {
            filteredByStatus
        } else {
            filteredByStatus.filter {
                it.titulo.lowercase().contains(clean) ||
                    (it.numero?.lowercase()?.contains(clean) == true) ||
                    (it.obs?.lowercase()?.contains(clean) == true) ||
                    (it.area?.lowercase()?.contains(clean) == true) ||
                    (clientNames[it.clientId]?.lowercase()?.contains(clean) == true)
            }
        }
        if (archived) {
            searched
        } else {
            searched.sortedWith(
                compareBy<LegalCaseDoc> { c ->
                    itemsByCase[c.id]?.firstOrNull()?.data ?: c.proxData ?: "9999-12-31"
                }.thenBy { it.titulo.lowercase() }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val activeClientsForMap: StateFlow<List<ClientDoc>> = allClientsFlow
        .map { list -> list.filter { it.archivedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val clientsList: StateFlow<List<ClientDoc>> = combine(
        allClientsFlow,
        _showArchivedClients,
        _clientsQuery
    ) { list, archived, q ->
        val filteredByArchive = list.filter { if (archived) it.archivedAt != null else it.archivedAt == null }
        val clean = q.trim().lowercase()
        if (clean.isEmpty()) filteredByArchive
        else filteredByArchive.filter {
            it.nome.lowercase().contains(clean) ||
                (it.doc?.lowercase()?.contains(clean) == true) ||
                (it.email?.lowercase()?.contains(clean) == true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val teamMembersList: StateFlow<List<TeamMemberDoc>> = repo.observeTeamMembers(currentUserId)
        .safeStateIn(emptyList(), "equipe")

    val invitesList: StateFlow<List<TeamInviteDoc>> = repo.observeInvites(currentUserId)
        .safeStateIn(emptyList(), "convites")

    val auditList: StateFlow<List<AuditDoc>> = repo.observeAudit(currentUserId)
        .safeStateIn(emptyList(), "auditoria")

    val notificationsList: StateFlow<List<NotificationDoc>> = repo.observeNotifications(currentUserId)
        .safeStateIn(emptyList(), "notificações")

    init {
        viewModelScope.launch {
            repo.syncDeadlineNotifications(currentUserId)
        }
    }

    private fun currentAuthorName(): String {
        val prof = (profileState.value as? ProfileUiState.Ready)?.profile
        return prof?.nome?.takeIf { it.isNotBlank() }
            ?: googleDisplayName.takeIf { it.isNotBlank() }
            ?: googleEmail.ifBlank { "Advogado(a)" }
    }

    private fun friendlyError(e: Throwable, fallback: String): String {
        return when (e) {
            is DomainException -> e.message ?: fallback
            is IllegalArgumentException -> e.message ?: fallback
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    fun emitMessage(msg: String) {
        _snackbar.tryEmit(msg)
    }

    fun dismissStreamError() {
        _streamError.value = null
    }

    // ---------- Profile ----------

    fun saveUserProfile(
        nome: String,
        orgName: String,
        oab: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = { _snackbar.tryEmit(it) }
    ) {
        viewModelScope.launch {
            val emailToSave = googleEmail.ifBlank { "usuario@prajus.app" }
            repo.saveUserProfile(currentUserId, nome, emailToSave, orgName, oab)
                .onSuccess {
                    _snackbar.tryEmit("Perfil do escritório salvo.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao salvar perfil.")) }
        }
    }

    // ---------- Cases, Items & Clients ----------

    fun setCasesQuery(q: String) { _casesQuery.value = q }
    fun setShowArchivedCases(archived: Boolean) { _showArchivedCases.value = archived }
    fun setCasesStatusFilter(status: String?) { _casesStatusFilter.value = status }
    fun setClientsQuery(q: String) { _clientsQuery.value = q }
    fun setShowArchivedClients(archived: Boolean) { _showArchivedClients.value = archived }

    fun observeCase(caseId: String): Flow<LegalCaseDoc?> =
        allCasesFlow.map { list -> list.firstOrNull { it.id == caseId } }

    fun observeCaseItems(caseId: String): Flow<List<CaseItemDoc>> =
        allItemsFlow.map { list ->
            list.filter { it.caseId == caseId && it.archivedAt == null }
                .sortedBy { it.data }
        }

    fun observeCaseDocuments(caseId: String): Flow<List<CaseDocumentDoc>> =
        allDocumentsFlow.map { list ->
            list.filter { it.caseId == caseId && it.archivedAt == null }
        }

    fun observeTimeline(caseId: String): Flow<List<TimelineDoc>> =
        repo.observeTimeline(currentUserId, caseId)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Error observing timeline", firestoreError ?: error)
                emit(emptyList())
            }

    fun createClient(
        nome: String,
        doc: String,
        email: String,
        tel: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.createClient(currentUserId, nome, doc, email, tel, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit("Cliente cadastrado.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao cadastrar cliente.")) }
        }
    }

    fun updateClient(
        clientId: String,
        nome: String,
        doc: String,
        email: String,
        tel: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.updateClient(currentUserId, clientId, nome, doc, email, tel, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit("Cliente atualizado.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao atualizar cliente.")) }
        }
    }

    fun archiveClient(clientId: String, restore: Boolean = false) {
        viewModelScope.launch {
            repo.archiveClient(currentUserId, clientId, restore, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit(if (restore) "Cliente restaurado." else "Cliente arquivado.")
                }
                .onFailure {
                    _snackbar.tryEmit(friendlyError(it, "Erro ao atualizar cliente."))
                }
        }
    }

    fun createCase(
        titulo: String,
        clientId: String,
        numero: String,
        area: String,
        obs: String,
        onCreated: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.createCase(currentUserId, titulo, clientId, numero, area, obs, currentAuthorName())
                .onSuccess { id -> onCreated(id) }
                .onFailure { onError(friendlyError(it, "Erro ao criar caso.")) }
        }
    }

    fun updateCaseNextAction(
        caseId: String,
        proxAcao: String,
        proxData: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.updateCaseNextAction(currentUserId, caseId, proxAcao, proxData, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit("Próxima ação atualizada.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao atualizar próxima ação.")) }
        }
    }

    fun updateCaseDetails(
        caseId: String,
        titulo: String,
        numero: String,
        area: String,
        status: String,
        obs: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.updateCaseDetails(currentUserId, caseId, titulo, numero, area, status, obs, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit("Caso atualizado.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao atualizar caso.")) }
        }
    }

    fun archiveCase(caseId: String, caseTitle: String, restore: Boolean = false, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.archiveCase(currentUserId, caseId, caseTitle, restore, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit(if (restore) "Caso restaurado." else "Caso arquivado.")
                    onDone()
                }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao arquivar caso.")) }
        }
    }

    fun createItem(
        caseId: String,
        tipo: String,
        titulo: String,
        data: String,
        prio: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.createItem(currentUserId, caseId, tipo, titulo, data, prio, currentAuthorName())
                .onSuccess {
                    repo.syncDeadlineNotifications(currentUserId)
                    _snackbar.tryEmit("$tipo adicionada(o).")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao criar item.")) }
        }
    }

    fun completeItem(item: CaseItemDoc) {
        viewModelScope.launch {
            repo.updateItemStatus(currentUserId, item, "Concluída", currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Item concluído.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao concluir item.")) }
        }
    }

    fun confirmDeadline(item: CaseItemDoc) {
        viewModelScope.launch {
            repo.confirmDeadline(currentUserId, item, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Prazo confirmado por revisão humana.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao confirmar prazo.")) }
        }
    }

    fun updateItemDate(
        item: CaseItemDoc,
        newDate: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.updateItemDate(currentUserId, item, newDate, currentAuthorName())
                .onSuccess {
                    repo.syncDeadlineNotifications(currentUserId)
                    _snackbar.tryEmit("Data atualizada.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao alterar data.")) }
        }
    }

    // ---------- Documents ----------

    fun uploadDocument(caseId: String, uri: Uri, tipo: String?, existingDocId: String? = null) {
        viewModelScope.launch {
            repo.uploadDocument(currentUserId, caseId, uri, tipo, existingDocId, currentAuthorName())
                .onSuccess { version -> _snackbar.tryEmit("Arquivo enviado (versão $version).") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao enviar documento.")) }
        }
    }

    fun loadDocumentVersions(docId: String, onLoaded: (List<DocVersionDoc>) -> Unit) {
        viewModelScope.launch {
            repo.getDocumentVersions(currentUserId, docId)
                .onSuccess(onLoaded)
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao carregar versões.")) }
        }
    }

    fun prepareDocumentDownload(docId: String, version: Long, onReady: (File, String) -> Unit) {
        viewModelScope.launch {
            repo.getDownloadFile(currentUserId, docId, version, currentAuthorName())
                .onSuccess { (file, name) -> onReady(file, name) }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao baixar documento.")) }
        }
    }

    fun archiveDocument(docId: String) {
        viewModelScope.launch {
            repo.archiveDocument(currentUserId, docId, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Documento arquivado.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao arquivar documento.")) }
        }
    }

    // ---------- Team, Search, Notifications, Audit, Export/Import, Branding ----------

    fun createInvite(
        nome: String,
        email: String,
        role: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            repo.createInvite(currentUserId, nome, email, role, currentAuthorName())
                .onSuccess {
                    _snackbar.tryEmit("Convite registrado na nuvem.")
                    onSuccess()
                }
                .onFailure { onError(friendlyError(it, "Erro ao criar convite.")) }
        }
    }

    fun resendInvite(inviteId: String) {
        viewModelScope.launch {
            repo.resendInvite(currentUserId, inviteId, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Validade do convite renovada por 7 dias.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao reenviar convite.")) }
        }
    }

    fun revokeInvite(inviteId: String) {
        viewModelScope.launch {
            repo.revokeInvite(currentUserId, inviteId, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Convite revogado.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao revogar convite.")) }
        }
    }

    fun updateUserRoleOrStatus(memberId: String, role: String, status: String) {
        viewModelScope.launch {
            repo.updateTeamMemberRoleOrStatus(currentUserId, memberId, role, status, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Membro atualizado.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao atualizar membro.")) }
        }
    }

    fun runGlobalSearch(query: String) {
        val clean = query.trim().lowercase()
        if (clean.length < 2) {
            _snackbar.tryEmit("Digite ao menos 2 caracteres.")
            return
        }
        val activeCases = allCasesFlow.value.filter { it.archivedAt == null }
        val caseMap = activeCases.associateBy { it.id }
        val casos = activeCases.filter {
            it.titulo.lowercase().contains(clean) ||
                (it.numero?.lowercase()?.contains(clean) == true) ||
                (it.area?.lowercase()?.contains(clean) == true) ||
                (it.obs?.lowercase()?.contains(clean) == true)
        }
        val clientes = allClientsFlow.value.filter { it.archivedAt == null }.filter {
            it.nome.lowercase().contains(clean) ||
                (it.doc?.lowercase()?.contains(clean) == true) ||
                (it.email?.lowercase()?.contains(clean) == true)
        }
        val itens = allItemsFlow.value
            .filter { it.archivedAt == null && it.caseId in caseMap }
            .filter { it.titulo.lowercase().contains(clean) || it.tipo.lowercase().contains(clean) }
            .map { ItemWithCaseTitle(it, caseMap[it.caseId]?.titulo ?: "") }
        val documentos = allDocumentsFlow.value
            .filter { it.archivedAt == null && it.caseId in caseMap }
            .filter { it.nome.lowercase().contains(clean) || (it.tipo?.lowercase()?.contains(clean) == true) }

        _searchResults.value = GlobalSearchResults(
            casos = casos,
            clientes = clientes,
            itens = itens,
            documentos = documentos
        )
    }

    fun clearGlobalSearch() {
        _searchResults.value = null
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            repo.markNotificationRead(currentUserId, notificationId)
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao marcar notificação.")) }
        }
    }

    fun exportJson(onJsonReady: (String) -> Unit) {
        viewModelScope.launch {
            repo.exportOrganizationJson(currentUserId, currentAuthorName())
                .onSuccess(onJsonReady)
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao exportar JSON.")) }
        }
    }

    fun importJson(rawJson: String) {
        viewModelScope.launch {
            repo.importOrganizationJson(currentUserId, rawJson, currentAuthorName())
                .onSuccess { (clients, cases, items) ->
                    repo.syncDeadlineNotifications(currentUserId)
                    _snackbar.tryEmit("Importado: $clients clientes, $cases casos, $items itens.")
                }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao importar JSON.")) }
        }
    }

    fun updateBrandColors(azulEscuro: String, azul: String, dourado: String) {
        viewModelScope.launch {
            repo.updateBrandColors(currentUserId, azulEscuro, azul, dourado, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Cores sincronizadas na nuvem.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao salvar cores.")) }
        }
    }

    fun uploadBrandLogo(slot: String, uri: Uri) {
        viewModelScope.launch {
            repo.uploadBrandLogo(currentUserId, slot, uri, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Logotipo atualizado.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao atualizar logotipo.")) }
        }
    }

    fun resetLogoSlot(slot: String) {
        viewModelScope.launch {
            repo.resetLogoSlot(currentUserId, slot, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Logotipo restaurado ao padrão.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao restaurar logotipo.")) }
        }
    }

    fun resetAllBranding() {
        viewModelScope.launch {
            repo.resetAllBranding(currentUserId, currentAuthorName())
                .onSuccess { _snackbar.tryEmit("Identidade visual padrão restaurada.") }
                .onFailure { _snackbar.tryEmit(friendlyError(it, "Erro ao restaurar identidade visual.")) }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
