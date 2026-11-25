package com.example.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nome: String = "",
    val email: String = "",
    val orgName: String = "",
    val oab: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ClientDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nome: String = "",
    val doc: String? = null,
    val email: String? = null,
    val tel: String? = null,
    val archivedAt: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LegalCaseDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val clientId: String = "",
    val titulo: String = "",
    val numero: String? = null,
    val area: String? = null,
    val status: String = "Ativo",
    val proxAcao: String? = null,
    val proxData: String? = null,
    val obs: String? = null,
    val archivedAt: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CaseItemDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val caseId: String = "",
    val tipo: String = "Tarefa",
    val titulo: String = "",
    val data: String = "",
    val status: String = "Pendente",
    val prio: String = "Média",
    val origem: String? = null,
    val revisado: Boolean = false,
    val archivedAt: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CaseDocumentDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val caseId: String = "",
    val nome: String = "",
    val tipo: String? = null,
    val versao: Long = 1L,
    val sha256: String = "",
    val size: Long = 0L,
    val localPath: String? = null,
    val archivedAt: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DocVersionDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val docId: String = "",
    val n: Long = 1L,
    val sha256: String = "",
    val size: Long = 0L,
    val porNome: String? = null,
    val localPath: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class TimelineDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val caseId: String = "",
    val txt: String = "",
    val autor: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class NotificationDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val txt: String = "",
    val notifKey: String = "",
    val lida: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AuditDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val acao: String = "",
    val entity: String? = null,
    val entityRef: String? = null,
    val usuario: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class TeamMemberDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nome: String = "",
    val email: String = "",
    val role: String = "advogado",
    val status: String = "ativo",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class TeamInviteDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nome: String? = null,
    val email: String = "",
    val role: String = "advogado",
    val status: String = "pendente",
    val expiresAt: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class BrandConfigDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val azulEscuro: String = "#1D2F4D",
    val azul: String = "#2F5C8E",
    val dourado: String = "#CFA75B",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
