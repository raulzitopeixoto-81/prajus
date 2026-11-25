package com.example.data.security

import com.google.firebase.Timestamp
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object SecurityUtils {
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val hexColorRegex = Regex("^#[0-9a-fA-F]{6}$")

    val ALLOWED_DOC_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "odt", "png", "jpg", "jpeg", "txt"
    )

    const val MAX_DOC_BYTES = 25L * 1024L * 1024L
    const val MAX_LOGO_BYTES = 600L * 1024L

    fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    fun laterIso(hours: Long = 0, days: Long = 0): String {
        return Instant.now()
            .plus(hours, ChronoUnit.HOURS)
            .plus(days, ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()
    }

    fun todayIso(): String = LocalDate.now().toString()

    fun todayPlusDaysIso(days: Long): String = LocalDate.now().plusDays(days).toString()

    fun formatDateBr(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        val part = iso.take(10)
        return runCatching {
            val d = LocalDate.parse(part)
            d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }.getOrElse { part }
    }

    fun formatTimestampDateBr(ts: Timestamp?): String {
        if (ts == null) return "—"
        return runCatching {
            val local = ts.toDate().toInstant().atZone(ZoneOffset.systemDefault())
            local.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }.getOrElse { "—" }
    }

    fun formatTimestampDateTimeBr(ts: Timestamp?): String {
        if (ts == null) return "—"
        return runCatching {
            val local = ts.toDate().toInstant().atZone(ZoneOffset.systemDefault())
            local.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }.getOrElse { "—" }
    }

    fun formatDateTimeBr(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return runCatching {
            val inst = Instant.parse(iso)
            val local = inst.atZone(ZoneOffset.systemDefault())
            local.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }.getOrElse { formatDateBr(iso) }
    }

    fun isValidIsoDate(value: String): Boolean {
        return runCatching {
            LocalDate.parse(value.trim())
            true
        }.getOrDefault(false)
    }

    fun isValidHexColor(value: String): Boolean = hexColorRegex.matches(value.trim())

    fun normalizeEmail(raw: String): String {
        val e = raw.trim().lowercase()
        require(emailRegex.matches(e) && e.length <= 254) { "E-mail inválido." }
        return e
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object Permissions {
    const val READ = "read"
    const val WRITE = "write"
    const val ARCHIVE = "archive"
    const val EXPORT = "export"
    const val IMPORT = "import"
    const val USERS = "users"
    const val AUDIT = "audit"
    const val BRAND = "brand"

    val ROLES = listOf("admin", "advogado", "assistente", "leitura")

    fun roleLabel(role: String): String = when (role) {
        "admin" -> "Administrador"
        "advogado" -> "Advogado"
        "assistente" -> "Assistente"
        "leitura" -> "Somente leitura"
        else -> role
    }

    fun forRole(role: String): Set<String> = when (role) {
        "admin" -> setOf(READ, WRITE, ARCHIVE, EXPORT, IMPORT, USERS, AUDIT, BRAND)
        "advogado" -> setOf(READ, WRITE, ARCHIVE, EXPORT)
        "assistente" -> setOf(READ, WRITE)
        "leitura" -> setOf(READ)
        else -> emptySet()
    }
}
