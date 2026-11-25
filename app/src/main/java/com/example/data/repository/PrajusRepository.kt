package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.R
import com.example.data.remote.*
import com.example.data.security.Permissions
import com.example.data.security.SecurityUtils
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class DomainException(message: String) : Exception(message)

data class ItemWithCaseTitle(
    val item: CaseItemDoc,
    val casoTitulo: String
) {
    val id: String get() = item.id
    val caseId: String get() = item.caseId
    val tipo: String get() = item.tipo
    val titulo: String get() = item.titulo
    val data: String get() = item.data
    val status: String get() = item.status
    val prio: String get() = item.prio
    val revisado: Boolean get() = item.revisado
}

data class HojeDashboard(
    val vencidas: List<ItemWithCaseTitle> = emptyList(),
    val hoje: List<ItemWithCaseTitle> = emptyList(),
    val prazosProximos: List<ItemWithCaseTitle> = emptyList(),
    val casosSemProximaAcao: List<LegalCaseDoc> = emptyList(),
    val aguardandoRevisaoCount: Int = 0
)

data class GlobalSearchResults(
    val casos: List<LegalCaseDoc> = emptyList(),
    val clientes: List<ClientDoc> = emptyList(),
    val itens: List<ItemWithCaseTitle> = emptyList(),
    val documentos: List<CaseDocumentDoc> = emptyList()
)

data class BrandState(
    val azulEscuro: String = DEFAULT_AZUL_ESCURO,
    val azul: String = DEFAULT_AZUL,
    val dourado: String = DEFAULT_DOURADO,
    val logoHorizontalPath: String? = null,
    val logoVerticalPath: String? = null,
    val iconePath: String? = null
) {
    companion object {
        const val DEFAULT_AZUL_ESCURO = "#1D2F4D"
        const val DEFAULT_AZUL = "#2F5C8E"
        const val DEFAULT_DOURADO = "#CFA75B"
    }
}

@Serializable
data class ExportClientDto(
    val id: String,
    val nome: String,
    val doc: String? = null,
    val email: String? = null,
    val tel: String? = null
)

@Serializable
data class ExportCaseDto(
    val id: String,
    val client_id: String,
    val titulo: String,
    val numero: String? = null,
    val area: String? = null,
    val status: String = "Ativo",
    val prox_acao: String? = null,
    val prox_data: String? = null,
    val obs: String? = null
)

@Serializable
data class ExportItemDto(
    val id: String,
    val case_id: String,
    val tipo: String,
    val titulo: String,
    val data: String,
    val status: String = "Pendente",
    val prio: String = "Média",
    val origem: String? = null
)

@Serializable
data class ExportDocumentDto(
    val id: String,
    val case_id: String,
    val nome: String,
    val tipo: String? = null
)

@Serializable
data class ExportTimelineDto(
    val id: String,
    val case_id: String,
    val txt: String,
    val at: String
)

@Serializable
data class ExportBundle(
    val clients: List<ExportClientDto> = emptyList(),
    val cases: List<ExportCaseDto> = emptyList(),
    val items: List<ExportItemDto> = emptyList(),
    val documents: List<ExportDocumentDto> = emptyList(),
    val timeline: List<ExportTimelineDto> = emptyList()
)

class PrajusRepository(
    private val db: FirebaseFirestore,
    private val context: Context,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        context.applicationContext
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val localLogoPaths = MutableStateFlow<Triple<String?, String?, String?>>(Triple(null, null, null))

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("É necessário entrar com sua conta Google.")
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)
    private fun clientsCol(uid: String) = userDoc(uid).collection("clients")
    private fun casesCol(uid: String) = userDoc(uid).collection("cases")
    private fun itemsCol(uid: String) = userDoc(uid).collection("items")
    private fun docsCol(uid: String) = userDoc(uid).collection("documents")
    private fun timelineCol(uid: String) = userDoc(uid).collection("timeline")
    private fun notificationsCol(uid: String) = userDoc(uid).collection("notifications")
    private fun auditCol(uid: String) = userDoc(uid).collection("audit")
    private fun teamCol(uid: String) = userDoc(uid).collection("team_members")
    private fun invitesCol(uid: String) = userDoc(uid).collection("invites")
    private fun brandingDoc(uid: String) = userDoc(uid).collection("branding").document("config")

    private fun logoPrefs(uid: String) =
        context.getSharedPreferences("prajus_logos_$uid", Context.MODE_PRIVATE)

    fun refreshLocalLogos(uid: String) {
        if (uid.isBlank()) return
        val prefs = logoPrefs(uid)
        localLogoPaths.value = Triple(
            prefs.getString("horizontal", null)?.takeIf { File(it).exists() },
            prefs.getString("vertical", null)?.takeIf { File(it).exists() },
            prefs.getString("icone", null)?.takeIf { File(it).exists() }
        )
    }

    private fun <T> guardedSnapshotFlow(
        uid: String,
        op: OperationType,
        path: String,
        source: () -> Flow<T>
    ): Flow<T> = flow {
        require(uid.isNotBlank()) { "Authenticated userId is required" }
        emitAll(
            source().catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(exceptionToLog, op, path)
                }
                throw error
            }
        )
    }

    private suspend fun appendAudit(
        uid: String,
        acao: String,
        entity: String? = null,
        entityRef: String? = null,
        usuario: String? = null
    ) {
        val docRef = auditCol(uid).document()
        val payload = mapOf(
            "userId" to uid,
            "acao" to acao.trim().take(300).ifBlank { "Ação" },
            "entity" to entity?.trim()?.take(60)?.ifBlank { null },
            "entityRef" to entityRef?.trim()?.take(128)?.ifBlank { null },
            "usuario" to usuario?.trim()?.take(200)?.ifBlank { null },
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }
        docRef.set(payload).await()
    }

    private suspend fun appendTimeline(
        uid: String,
        caseId: String,
        txt: String,
        autor: String? = null
    ) {
        val docRef = timelineCol(uid).document()
        val payload = mapOf(
            "userId" to uid,
            "caseId" to caseId,
            "txt" to txt.trim().take(500).ifBlank { "Atualização" },
            "autor" to autor?.trim()?.take(200)?.ifBlank { null },
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }
        docRef.set(payload).await()
    }

    // ---------- Profile & Workspace ----------

    fun observeUserProfile(uid: String): Flow<UserProfileDoc?> =
        guardedSnapshotFlow(uid, OperationType.GET, "users/$uid") {
            userDoc(uid).snapshots().map { snap ->
                if (!snap.exists()) null
                else snap.toObject(UserProfileDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
        }

    suspend fun saveUserProfile(
        uid: String,
        nome: String,
        email: String,
        orgName: String,
        oab: String
    ): Result<Unit> {
        val docPath = "users/$uid"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid) { "Usuário autenticado divergente." }
            val cleanNome = nome.trim().take(200)
            val cleanOrg = orgName.trim().take(200)
            val cleanEmail = SecurityUtils.normalizeEmail(email)
            val cleanOab = oab.trim().take(30).ifBlank { null }
            if (cleanNome.isBlank() || cleanOrg.isBlank()) {
                throw DomainException("Informe seu nome e o nome do escritório.")
            }

            val ref = userDoc(uid)
            val snap = ref.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "nome" to cleanNome,
                    "email" to cleanEmail,
                    "orgName" to cleanOrg,
                    "oab" to cleanOab,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                ref.set(payload).await()

                val memberRef = teamCol(uid).document(uid)
                if (!memberRef.get().await().exists()) {
                    memberRef.set(
                        mapOf(
                            "userId" to uid,
                            "nome" to cleanNome,
                            "email" to cleanEmail,
                            "role" to "admin",
                            "status" to "ativo",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                appendAudit(uid, "Escritório configurado: $cleanOrg", "users", uid, cleanNome)
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "nome" to cleanNome,
                    "orgName" to cleanOrg,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                updateMap["oab"] = cleanOab ?: FieldValue.delete()
                ref.update(updateMap).await()
                appendAudit(uid, "Atualizou perfil do escritório", "users", uid, cleanNome)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    // ---------- Clients ----------

    fun observeClients(uid: String): Flow<List<ClientDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/clients") {
            clientsCol(uid).snapshots().map { snap ->
                snap.toObjects(ClientDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.nome.lowercase() }
            }
        }

    suspend fun createClient(
        uid: String,
        nome: String,
        doc: String,
        email: String,
        tel: String,
        authorName: String
    ): Result<String> {
        var docPath = "users/$uid/clients"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val cleanNome = nome.trim().take(200)
            if (cleanNome.isBlank()) throw DomainException("Informe o nome do cliente.")
            val cleanEmail = email.trim().takeIf { it.isNotEmpty() }?.let {
                SecurityUtils.normalizeEmail(it)
            }
            val ref = clientsCol(uid).document()
            docPath = ref.path
            val payload = mapOf(
                "userId" to uid,
                "nome" to cleanNome,
                "doc" to doc.trim().take(30).ifBlank { null },
                "email" to cleanEmail,
                "tel" to tel.trim().take(30).ifBlank { null },
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            ref.set(payload).await()
            appendAudit(uid, "Criou cliente: $cleanNome", "clients", ref.id, authorName)
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateClient(
        uid: String,
        clientId: String,
        nome: String,
        doc: String,
        email: String,
        tel: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/clients/$clientId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(clientId.isNotBlank())
            val cleanNome = nome.trim().take(200)
            if (cleanNome.isBlank()) throw DomainException("Informe o nome do cliente.")
            val cleanEmail = email.trim().takeIf { it.isNotEmpty() }?.let {
                SecurityUtils.normalizeEmail(it)
            }
            val updateMap = mutableMapOf<String, Any>(
                "nome" to cleanNome,
                "doc" to (doc.trim().take(30).ifBlank { null } ?: FieldValue.delete()),
                "email" to (cleanEmail ?: FieldValue.delete()),
                "tel" to (tel.trim().take(30).ifBlank { null } ?: FieldValue.delete()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            clientsCol(uid).document(clientId).update(updateMap).await()
            appendAudit(uid, "Alterou cliente: $cleanNome", "clients", clientId, authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun archiveClient(
        uid: String,
        clientId: String,
        restore: Boolean,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/clients/$clientId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(clientId.isNotBlank())
            if (!restore) {
                val activeCases = casesCol(uid)
                    .whereEqualTo("clientId", clientId)
                    .get()
                    .await()
                    .toObjects(LegalCaseDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .count { it.archivedAt == null }
                if (activeCases > 0) {
                    throw DomainException("Arquive os casos do cliente primeiro.")
                }
            }
            val archivedValue: Any = if (restore) FieldValue.delete() else SecurityUtils.nowIso().take(40)
            clientsCol(uid).document(clientId).update(
                mapOf(
                    "archivedAt" to archivedValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(
                uid,
                if (restore) "Restaurou cliente" else "Arquivou cliente",
                "clients",
                clientId,
                authorName
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // ---------- Cases ----------

    fun observeCases(uid: String): Flow<List<LegalCaseDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/cases") {
            casesCol(uid).snapshots().map { snap ->
                snap.toObjects(LegalCaseDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: Timestamp(0, 0) }
            }
        }

    fun observeCase(uid: String, caseId: String): Flow<LegalCaseDoc?> =
        guardedSnapshotFlow(uid, OperationType.GET, "users/$uid/cases/$caseId") {
            casesCol(uid).document(caseId).snapshots().map { snap ->
                if (!snap.exists()) null
                else snap.toObject(LegalCaseDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
        }

    suspend fun createCase(
        uid: String,
        titulo: String,
        clientId: String,
        numero: String,
        area: String,
        obs: String,
        authorName: String
    ): Result<String> {
        var docPath = "users/$uid/cases"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val cleanTitulo = titulo.trim().take(300)
            if (cleanTitulo.isBlank()) throw DomainException("Informe o título do caso.")
            if (clientId.isBlank()) throw DomainException("Cliente inválido.")

            val clientSnap = clientsCol(uid).document(clientId).get().await()
            if (!clientSnap.exists()) throw DomainException("Cliente inválido.")

            val ref = casesCol(uid).document()
            docPath = ref.path
            val payload = mapOf(
                "userId" to uid,
                "clientId" to clientId,
                "titulo" to cleanTitulo,
                "numero" to numero.trim().take(40).ifBlank { null },
                "area" to area.trim().take(80).ifBlank { null },
                "status" to "Ativo",
                "obs" to obs.trim().take(5000).ifBlank { null },
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            ref.set(payload).await()
            appendAudit(uid, "Criou caso: $cleanTitulo", "cases", ref.id, authorName)
            appendTimeline(uid, ref.id, "Caso criado: $cleanTitulo", authorName)
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateCaseNextAction(
        uid: String,
        caseId: String,
        proxAcao: String,
        proxData: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/cases/$caseId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(caseId.isNotBlank())
            val cleanDate = proxData.trim().take(20).ifBlank { null }
            if (cleanDate != null && !SecurityUtils.isValidIsoDate(cleanDate)) {
                throw DomainException("Data inválida (use AAAA-MM-DD).")
            }
            val cleanAction = proxAcao.trim().take(300).ifBlank { null }
            val updateMap = mapOf(
                "proxAcao" to (cleanAction ?: FieldValue.delete()),
                "proxData" to (cleanDate ?: FieldValue.delete()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            casesCol(uid).document(caseId).update(updateMap).await()
            appendAudit(uid, "Alterou próxima ação", "cases", caseId, authorName)
            appendTimeline(
                uid,
                caseId,
                "Próxima ação atualizada: ${cleanAction ?: "removida"}",
                authorName
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateCaseDetails(
        uid: String,
        caseId: String,
        titulo: String,
        numero: String,
        area: String,
        status: String,
        obs: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/cases/$caseId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(caseId.isNotBlank())
            val cleanTitulo = titulo.trim().take(300)
            if (cleanTitulo.isBlank()) throw DomainException("Informe o título do caso.")
            if (status !in setOf("Ativo", "Aguardando", "Encerrado")) {
                throw DomainException("Status inválido.")
            }
            val updateMap = mapOf(
                "titulo" to cleanTitulo,
                "numero" to (numero.trim().take(40).ifBlank { null } ?: FieldValue.delete()),
                "area" to (area.trim().take(80).ifBlank { null } ?: FieldValue.delete()),
                "status" to status,
                "obs" to (obs.trim().take(5000).ifBlank { null } ?: FieldValue.delete()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            casesCol(uid).document(caseId).update(updateMap).await()
            appendAudit(uid, "Alterou caso: $cleanTitulo", "cases", caseId, authorName)
            appendTimeline(uid, caseId, "Caso alterado: $cleanTitulo ($status)", authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun archiveCase(
        uid: String,
        caseId: String,
        caseTitle: String,
        restore: Boolean,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/cases/$caseId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(caseId.isNotBlank())
            val archivedValue: Any = if (restore) FieldValue.delete() else SecurityUtils.nowIso().take(40)
            casesCol(uid).document(caseId).update(
                mapOf(
                    "archivedAt" to archivedValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(
                uid,
                if (restore) "Restaurou caso" else "Arquivou caso",
                "cases",
                caseId,
                authorName
            )
            appendTimeline(
                uid,
                caseId,
                (if (restore) "Restaurado: " else "Arquivado: ") + caseTitle,
                authorName
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // ---------- Items & Hoje Dashboard ----------

    fun observeAllItems(uid: String): Flow<List<CaseItemDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/items") {
            itemsCol(uid).snapshots().map { snap ->
                snap.toObjects(CaseItemDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.data }
            }
        }

    fun observeHojeDashboard(uid: String): Flow<HojeDashboard> {
        return combine(observeAllItems(uid), observeCases(uid)) { items, cases ->
            val activeCasesById = cases.filter { it.archivedAt == null }.associateBy { it.id }
            val pendingWithCase = items
                .filter { it.archivedAt == null && it.status == "Pendente" && it.caseId in activeCasesById }
                .map { ItemWithCaseTitle(it, activeCasesById[it.caseId]?.titulo ?: "") }
                .sortedBy { it.data }

            val today = SecurityUtils.todayIso()
            val limit = SecurityUtils.todayPlusDaysIso(10)
            val prioOrder = mapOf("Alta" to 0, "Média" to 1, "Baixa" to 2)

            val casesSemProx = activeCasesById.values
                .filter { it.status == "Ativo" && it.proxAcao.isNullOrBlank() }
                .sortedBy { it.titulo.lowercase() }

            HojeDashboard(
                vencidas = pendingWithCase.filter { it.data < today && it.tipo != "Compromisso" },
                hoje = pendingWithCase.filter { it.data == today }
                    .sortedBy { prioOrder[it.prio] ?: 1 },
                prazosProximos = pendingWithCase.filter { it.tipo == "Prazo" && it.data > today && it.data <= limit },
                casosSemProximaAcao = casesSemProx,
                aguardandoRevisaoCount = pendingWithCase.count { it.tipo == "Prazo" && !it.revisado }
            )
        }
    }

    suspend fun createItem(
        uid: String,
        caseId: String,
        tipo: String,
        titulo: String,
        data: String,
        prio: String,
        authorName: String
    ): Result<String> {
        var docPath = "users/$uid/items"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            if (tipo !in setOf("Tarefa", "Prazo", "Compromisso")) throw DomainException("Tipo inválido.")
            val cleanTitulo = titulo.trim().take(300)
            if (cleanTitulo.isBlank()) throw DomainException("Informe o título do item.")
            val cleanData = data.trim()
            if (!SecurityUtils.isValidIsoDate(cleanData) || cleanData.length != 10) {
                throw DomainException("Data inválida (use AAAA-MM-DD).")
            }
            if (prio !in setOf("Baixa", "Média", "Alta")) throw DomainException("Prioridade inválida.")

            val ref = itemsCol(uid).document()
            docPath = ref.path
            val payload = mapOf(
                "userId" to uid,
                "caseId" to caseId,
                "tipo" to tipo,
                "titulo" to cleanTitulo,
                "data" to cleanData,
                "status" to "Pendente",
                "prio" to prio,
                "revisado" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            appendAudit(uid, "Criou item: $cleanTitulo", "items", ref.id, authorName)
            appendTimeline(uid, caseId, "Item criado: $cleanTitulo", authorName)
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateItemStatus(
        uid: String,
        item: CaseItemDoc,
        newStatus: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/items/${item.id}"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(item.id.isNotBlank())
            if (newStatus !in setOf("Pendente", "Concluída", "Cancelada")) {
                throw DomainException("Status inválido.")
            }
            itemsCol(uid).document(item.id).update(
                mapOf(
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Alterou item: $newStatus", "items", item.id, authorName)
            appendTimeline(uid, item.caseId, "Item alterado ($newStatus): ${item.titulo}", authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateItemDate(
        uid: String,
        item: CaseItemDoc,
        newDate: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/items/${item.id}"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(item.id.isNotBlank())
            val cleanDate = newDate.trim()
            if (!SecurityUtils.isValidIsoDate(cleanDate) || cleanDate.length != 10) {
                throw DomainException("Data inválida (use AAAA-MM-DD).")
            }
            val resetReview = item.tipo == "Prazo" && cleanDate != item.data
            val nextRevisado = if (resetReview) false else item.revisado
            itemsCol(uid).document(item.id).update(
                mapOf(
                    "data" to cleanDate,
                    "revisado" to nextRevisado,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Alterou data do item", "items", item.id, authorName)
            appendTimeline(
                uid,
                item.caseId,
                "Data alterada (${SecurityUtils.formatDateBr(cleanDate)}): ${item.titulo}",
                authorName
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun confirmDeadline(
        uid: String,
        item: CaseItemDoc,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/items/${item.id}"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            require(item.id.isNotBlank())
            if (item.tipo != "Prazo") throw DomainException("Prazo não encontrado.")
            itemsCol(uid).document(item.id).update(
                mapOf(
                    "revisado" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Prazo confirmado por revisão humana", "items", item.id, authorName)
            appendTimeline(uid, item.caseId, "Prazo confirmado: ${item.titulo}", authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // ---------- Documents & Versioning ----------

    fun observeAllDocuments(uid: String): Flow<List<CaseDocumentDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/documents") {
            docsCol(uid).snapshots().map { snap ->
                snap.toObjects(CaseDocumentDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: Timestamp(0, 0) }
            }
        }

    fun observeTimeline(uid: String, caseId: String): Flow<List<TimelineDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/timeline") {
            timelineCol(uid)
                .whereEqualTo("caseId", caseId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(TimelineDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
        }

    suspend fun getDocumentVersions(uid: String, docId: String): Result<List<DocVersionDoc>> {
        val path = "users/$uid/documents/$docId/versions"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val list = docsCol(uid).document(docId).collection("versions")
                .get()
                .await()
                .toObjects(DocVersionDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.n }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun uploadDocument(
        uid: String,
        caseId: String,
        uri: Uri,
        tipo: String?,
        existingDocId: String?,
        authorName: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        var path = "users/$uid/documents"
        try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val fileName = resolveFileName(uri).take(300)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext !in SecurityUtils.ALLOWED_DOC_EXTENSIONS) {
                throw DomainException("Tipo de arquivo não permitido. Use pdf, doc, docx, xls, xlsx, odt, png, jpg ou txt.")
            }

            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = input.readBytes()
                if (buf.size.toLong() > SecurityUtils.MAX_DOC_BYTES) {
                    throw DomainException("Arquivo maior que 25 MB.")
                }
                buf
            } ?: throw DomainException("Não foi possível ler o arquivo selecionado.")

            val dir = File(context.filesDir, "uploads/$uid").apply { mkdirs() }
            val dest = File(dir, "${UUID.randomUUID()}.$ext")
            dest.writeBytes(bytes)

            val sha = SecurityUtils.sha256Hex(bytes)
            val size = bytes.size.toLong()
            val localPath = dest.absolutePath.take(600)

            val finalDocRef = if (existingDocId != null) docsCol(uid).document(existingDocId) else docsCol(uid).document()
            path = finalDocRef.path

            val targetCaseId: String
            val newVersion: Long
            if (existingDocId != null) {
                val existingSnap = finalDocRef.get().await()
                val existingDoc = existingSnap.toObject(CaseDocumentDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?: throw DomainException("Documento não encontrado.")
                targetCaseId = existingDoc.caseId
                newVersion = existingDoc.versao + 1L
                finalDocRef.update(
                    mapOf(
                        "versao" to newVersion,
                        "sha256" to sha,
                        "size" to size,
                        "localPath" to localPath,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                targetCaseId = caseId
                newVersion = 1L
                val docPayload = mapOf(
                    "userId" to uid,
                    "caseId" to targetCaseId,
                    "nome" to fileName,
                    "tipo" to (tipo?.trim()?.take(60)?.ifBlank { "Outro" } ?: "Outro"),
                    "versao" to newVersion,
                    "sha256" to sha,
                    "size" to size,
                    "localPath" to localPath,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                finalDocRef.set(docPayload).await()
            }

            val verRef = finalDocRef.collection("versions").document("v$newVersion")
            path = verRef.path
            val verPayload = mapOf(
                "userId" to uid,
                "docId" to finalDocRef.id,
                "n" to newVersion,
                "sha256" to sha,
                "size" to size,
                "porNome" to authorName.trim().take(200).ifBlank { null },
                "localPath" to localPath,
                "createdAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            verRef.set(verPayload).await()

            appendAudit(uid, "Documento '$fileName' versão $newVersion", "documents", finalDocRef.id, authorName)
            appendTimeline(uid, targetCaseId, "Documento “$fileName” enviado (versão $newVersion)", authorName)
            Result.success(newVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun getDownloadFile(
        uid: String,
        docId: String,
        version: Long,
        authorName: String
    ): Result<Pair<File, String>> = withContext(Dispatchers.IO) {
        val path = "users/$uid/documents/$docId/versions"
        try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val docSnap = docsCol(uid).document(docId).get().await()
            val doc = docSnap.toObject(CaseDocumentDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: throw DomainException("Documento não encontrado.")
            val vers = docsCol(uid).document(docId).collection("versions")
                .whereEqualTo("n", version)
                .get()
                .await()
                .toObjects(DocVersionDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            val ver = vers.firstOrNull() ?: throw DomainException("Versão não encontrada.")
            val filePath = ver.localPath ?: doc.localPath
                ?: throw DomainException("Arquivo disponível apenas no dispositivo onde foi enviado.")
            val file = File(filePath)
            if (!file.exists()) {
                throw DomainException("Arquivo não encontrado neste dispositivo.")
            }
            appendAudit(uid, "Download de documento", "documents", docId, authorName)
            Result.success(file to doc.nome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun archiveDocument(uid: String, docId: String, authorName: String): Result<Unit> {
        val docPath = "users/$uid/documents/$docId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            docsCol(uid).document(docId).update(
                mapOf(
                    "archivedAt" to SecurityUtils.nowIso().take(40),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Arquivou documento", "documents", docId, authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    private fun resolveFileName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"
    }

    // ---------- Notifications, Audit, Team, Invites ----------

    fun observeNotifications(uid: String): Flow<List<NotificationDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/notifications") {
            notificationsCol(uid).snapshots().map { snap ->
                snap.toObjects(NotificationDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
        }

    suspend fun syncDeadlineNotifications(uid: String): Result<Unit> {
        var path = "users/$uid/notifications"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val soon = SecurityUtils.todayPlusDaysIso(3)
            val activeCaseIds = casesCol(uid).get().await()
                .toObjects(LegalCaseDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter { it.archivedAt == null }
                .map { it.id }
                .toSet()

            val dueItems = itemsCol(uid).get().await()
                .toObjects(CaseItemDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter {
                    it.archivedAt == null &&
                        it.caseId in activeCaseIds &&
                        it.status == "Pendente" &&
                        it.tipo == "Prazo" &&
                        it.data <= soon
                }

            val existingKeys = notificationsCol(uid).get().await()
                .toObjects(NotificationDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .map { it.notifKey }
                .toSet()

            for (p in dueItems) {
                val notifKey = "prazo:${p.id}:${p.data}".take(150)
                if (notifKey in existingKeys) continue
                val safeId = "prazo_${p.id}_${p.data}".replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
                val ref = notificationsCol(uid).document(safeId)
                path = ref.path
                if (!ref.get().await().exists()) {
                    ref.set(
                        mapOf(
                            "userId" to uid,
                            "txt" to "Prazo “${p.titulo}” em ${SecurityUtils.formatDateBr(p.data)}.".take(500),
                            "notifKey" to notifKey,
                            "lida" to false,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(uid: String, notificationId: String): Result<Unit> {
        val docPath = "users/$uid/notifications/$notificationId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            notificationsCol(uid).document(notificationId).update(
                mapOf(
                    "lida" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeAudit(uid: String): Flow<List<AuditDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/audit") {
            auditCol(uid).snapshots().map { snap ->
                snap.toObjects(AuditDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
        }

    fun observeTeamMembers(uid: String): Flow<List<TeamMemberDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/team_members") {
            teamCol(uid).snapshots().map { snap ->
                snap.toObjects(TeamMemberDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.nome.lowercase() }
            }
        }

    fun observeInvites(uid: String): Flow<List<TeamInviteDoc>> =
        guardedSnapshotFlow(uid, OperationType.LIST, "users/$uid/invites") {
            invitesCol(uid).snapshots().map { snap ->
                snap.toObjects(TeamInviteDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
        }

    suspend fun createInvite(
        uid: String,
        nome: String,
        emailRaw: String,
        role: String,
        authorName: String
    ): Result<String> {
        var docPath = "users/$uid/invites"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val cleanEmail = SecurityUtils.normalizeEmail(emailRaw)
            if (role !in Permissions.ROLES) throw DomainException("Função inválida.")
            val ref = invitesCol(uid).document()
            docPath = ref.path
            val payload = mapOf(
                "userId" to uid,
                "nome" to nome.trim().take(200).ifBlank { null },
                "email" to cleanEmail,
                "role" to role,
                "status" to "pendente",
                "expiresAt" to SecurityUtils.laterIso(days = 7).take(40),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            ref.set(payload).await()
            appendAudit(uid, "Convite criado para $cleanEmail", "invites", ref.id, authorName)
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun resendInvite(uid: String, inviteId: String, authorName: String): Result<Unit> {
        val docPath = "users/$uid/invites/$inviteId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            invitesCol(uid).document(inviteId).update(
                mapOf(
                    "expiresAt" to SecurityUtils.laterIso(days = 7).take(40),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Convite reenviado", "invites", inviteId, authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun revokeInvite(uid: String, inviteId: String, authorName: String): Result<Unit> {
        val docPath = "users/$uid/invites/$inviteId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            invitesCol(uid).document(inviteId).update(
                mapOf(
                    "status" to "revogado",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Convite revogado", "invites", inviteId, authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateTeamMemberRoleOrStatus(
        uid: String,
        memberId: String,
        newRole: String,
        newStatus: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/team_members/$memberId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            if (newRole !in Permissions.ROLES || newStatus !in setOf("ativo", "inativo")) {
                throw DomainException("Dados inválidos.")
            }
            teamCol(uid).document(memberId).update(
                mapOf(
                    "role" to newRole,
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAudit(uid, "Membro alterado ($newRole/$newStatus)", "team_members", memberId, authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // ---------- Export / Import JSON ----------

    suspend fun exportOrganizationJson(uid: String, authorName: String): Result<String> {
        val path = "users/$uid"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val clients = clientsCol(uid).get().await()
                .toObjects(ClientDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            val cases = casesCol(uid).get().await()
                .toObjects(LegalCaseDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            val items = itemsCol(uid).get().await()
                .toObjects(CaseItemDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            val docs = docsCol(uid).get().await()
                .toObjects(CaseDocumentDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            val timeline = timelineCol(uid).get().await()
                .toObjects(TimelineDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

            val bundle = ExportBundle(
                clients = clients.map { ExportClientDto(it.id, it.nome, it.doc, it.email, it.tel) },
                cases = cases.map {
                    ExportCaseDto(
                        it.id, it.clientId, it.titulo, it.numero, it.area,
                        it.status, it.proxAcao, it.proxData, it.obs
                    )
                },
                items = items.map {
                    ExportItemDto(it.id, it.caseId, it.tipo, it.titulo, it.data, it.status, it.prio, it.origem)
                },
                documents = docs.map { ExportDocumentDto(it.id, it.caseId, it.nome, it.tipo) },
                timeline = timeline.map {
                    ExportTimelineDto(
                        it.id,
                        it.caseId,
                        it.txt,
                        it.createdAt?.toDate()?.toInstant()?.toString() ?: SecurityUtils.nowIso()
                    )
                }
            )
            appendAudit(uid, "Exportou dados da organização", "users", uid, authorName)
            Result.success(json.encodeToString(bundle))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun importOrganizationJson(
        uid: String,
        rawJson: String,
        authorName: String
    ): Result<Triple<Int, Int, Int>> {
        var path = "users/$uid"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val bundle = runCatching { json.decodeFromString<ExportBundle>(rawJson) }
                .getOrElse { throw DomainException("JSON de importação inválido.") }

            val clientMap = mutableMapOf<String, String>()
            val caseMap = mutableMapOf<String, String>()

            for (c in bundle.clients) {
                val ref = clientsCol(uid).document()
                path = ref.path
                val payload = mapOf(
                    "userId" to uid,
                    "nome" to c.nome.trim().take(200).ifBlank { "Cliente" },
                    "doc" to c.doc?.trim()?.take(30)?.ifBlank { null },
                    "email" to c.email?.trim()?.take(254)?.ifBlank { null },
                    "tel" to c.tel?.trim()?.take(30)?.ifBlank { null },
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                ref.set(payload).await()
                clientMap[c.id] = ref.id
            }

            for (c in bundle.cases) {
                val mappedClient = clientMap[c.client_id] ?: continue
                val ref = casesCol(uid).document()
                path = ref.path
                val validStatus = if (c.status in setOf("Ativo", "Aguardando", "Encerrado")) c.status else "Ativo"
                val payload = mapOf(
                    "userId" to uid,
                    "clientId" to mappedClient,
                    "titulo" to c.titulo.trim().take(300).ifBlank { "Caso" },
                    "numero" to c.numero?.trim()?.take(40)?.ifBlank { null },
                    "area" to c.area?.trim()?.take(80)?.ifBlank { null },
                    "status" to validStatus,
                    "proxAcao" to c.prox_acao?.trim()?.take(300)?.ifBlank { null },
                    "proxData" to c.prox_data?.trim()?.take(20)?.ifBlank { null },
                    "obs" to c.obs?.trim()?.take(5000)?.ifBlank { null },
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                ref.set(payload).await()
                caseMap[c.id] = ref.id
            }

            var itemCount = 0
            for (i in bundle.items) {
                val mappedCase = caseMap[i.case_id] ?: continue
                if (i.tipo in setOf("Tarefa", "Prazo", "Compromisso") && SecurityUtils.isValidIsoDate(i.data)) {
                    val ref = itemsCol(uid).document()
                    path = ref.path
                    val validStatus = if (i.status in setOf("Pendente", "Concluída", "Cancelada")) i.status else "Pendente"
                    val validPrio = if (i.prio in setOf("Baixa", "Média", "Alta")) i.prio else "Média"
                    val payload = mapOf(
                        "userId" to uid,
                        "caseId" to mappedCase,
                        "tipo" to i.tipo,
                        "titulo" to i.titulo.trim().take(300).ifBlank { "Item" },
                        "data" to i.data.trim().take(10),
                        "status" to validStatus,
                        "prio" to validPrio,
                        "origem" to i.origem?.trim()?.take(300)?.ifBlank { null },
                        "revisado" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).filterValues { it != null }
                    ref.set(payload).await()
                    itemCount++
                }
            }

            appendAudit(uid, "Importou dados (${clientMap.size} clientes, ${caseMap.size} casos)", "users", uid, authorName)
            Result.success(Triple(clientMap.size, caseMap.size, itemCount))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // ---------- Visual Branding (Cloud colors + Local logos) ----------

    fun observeBranding(uid: String): Flow<BrandState> {
        refreshLocalLogos(uid)
        val cloudFlow = guardedSnapshotFlow(uid, OperationType.GET, "users/$uid/branding/config") {
            brandingDoc(uid).snapshots().map { snap ->
                if (!snap.exists()) null
                else snap.toObject(BrandConfigDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
        }
        return combine(cloudFlow, localLogoPaths.asStateFlow()) { cloud, logos ->
            BrandState(
                azulEscuro = cloud?.azulEscuro ?: BrandState.DEFAULT_AZUL_ESCURO,
                azul = cloud?.azul ?: BrandState.DEFAULT_AZUL,
                dourado = cloud?.dourado ?: BrandState.DEFAULT_DOURADO,
                logoHorizontalPath = logos.first,
                logoVerticalPath = logos.second,
                iconePath = logos.third
            )
        }
    }

    suspend fun updateBrandColors(
        uid: String,
        azulEscuro: String,
        azul: String,
        dourado: String,
        authorName: String
    ): Result<Unit> {
        val docPath = "users/$uid/branding/config"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            val c1 = azulEscuro.trim().uppercase()
            val c2 = azul.trim().uppercase()
            val c3 = dourado.trim().uppercase()
            if (!SecurityUtils.isValidHexColor(c1) || !SecurityUtils.isValidHexColor(c2) || !SecurityUtils.isValidHexColor(c3)) {
                throw DomainException("Cor inválida (use #RRGGBB).")
            }
            val ref = brandingDoc(uid)
            if (ref.get().await().exists()) {
                ref.update(
                    mapOf(
                        "azulEscuro" to c1,
                        "azul" to c2,
                        "dourado" to c3,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(
                    mapOf(
                        "userId" to uid,
                        "azulEscuro" to c1,
                        "azul" to c2,
                        "dourado" to c3,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            appendAudit(uid, "Alterou as cores do sistema", "branding", "config", authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun uploadBrandLogo(uid: String, slot: String, uri: Uri, authorName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val currentUid = requireUserId()
                check(currentUid == uid)
                if (slot !in setOf("horizontal", "vertical", "icone")) {
                    throw DomainException("Logotipo desconhecido.")
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = input.readBytes()
                    if (buf.size.toLong() > SecurityUtils.MAX_LOGO_BYTES) {
                        throw DomainException("O logotipo deve ter até 600 KB.")
                    }
                    buf
                } ?: throw DomainException("Não foi possível ler a imagem.")

                val ext = sniffImageExtension(bytes) ?: throw DomainException("Use PNG, JPG ou WEBP.")
                val dir = File(context.filesDir, "brand/$uid").apply { mkdirs() }
                val dest = File(dir, "${slot}_${UUID.randomUUID()}.$ext")
                dest.writeBytes(bytes)

                logoPrefs(uid).edit().putString(slot, dest.absolutePath).apply()
                refreshLocalLogos(uid)
                appendAudit(uid, "Trocou o logotipo ($slot)", "branding", slot, authorName)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun resetLogoSlot(uid: String, slot: String, authorName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val currentUid = requireUserId()
                check(currentUid == uid)
                if (slot !in setOf("horizontal", "vertical", "icone")) {
                    throw DomainException("Logotipo desconhecido.")
                }
                logoPrefs(uid).edit().remove(slot).apply()
                refreshLocalLogos(uid)
                appendAudit(uid, "Restaurou o logotipo padrão ($slot)", "branding", slot, authorName)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun resetAllBranding(uid: String, authorName: String): Result<Unit> {
        val docPath = "users/$uid/branding/config"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid)
            logoPrefs(uid).edit().clear().apply()
            refreshLocalLogos(uid)
            val ref = brandingDoc(uid)
            if (ref.get().await().exists()) {
                ref.delete().await()
            }
            appendAudit(uid, "Restaurou a identidade visual padrão", "branding", "config", authorName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    private fun sniffImageExtension(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return "png"
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return "jpg"
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) return "webp"
        return null
    }
}
