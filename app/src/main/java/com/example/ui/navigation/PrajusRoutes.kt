package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface PrajusRoute {
    @Serializable
    data object Hoje : PrajusRoute

    @Serializable
    data object Casos : PrajusRoute

    @Serializable
    data class CasoDetail(val caseId: String) : PrajusRoute

    @Serializable
    data object Clientes : PrajusRoute

    @Serializable
    data object Equipe : PrajusRoute

    @Serializable
    data object Auditoria : PrajusRoute

    @Serializable
    data object Aparencia : PrajusRoute
}
