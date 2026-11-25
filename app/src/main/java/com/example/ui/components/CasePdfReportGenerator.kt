package com.example.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.remote.CaseDocumentDoc
import com.example.data.remote.CaseItemDoc
import com.example.data.remote.LegalCaseDoc
import com.example.data.remote.TimelineDoc
import com.example.data.security.SecurityUtils
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CasePdfReportGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 44
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    fun suggestedFileName(caseTitle: String): String {
        val decomposed = Normalizer.normalize(caseTitle.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val slug = "\\p{InCombiningDiacriticalMarks}+".toRegex()
            .replace(decomposed, "")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')
            .take(40)
            .ifBlank { "caso" }
        return "relatorio-$slug-${SecurityUtils.todayIso()}.pdf"
    }

    fun writeReportToUri(
        context: Context,
        outputUri: Uri,
        orgName: String,
        authorName: String,
        caseEntity: LegalCaseDoc,
        clientName: String,
        pendingItems: List<CaseItemDoc>,
        documents: List<CaseDocumentDoc>,
        timeline: List<TimelineDoc>
    ): Result<Unit> = runCatching {
        val pdf = PdfDocument()
        val navyPaint = Paint().apply { color = Color.rgb(29, 47, 77) }
        val goldPaint = Paint().apply {
            color = Color.rgb(207, 167, 91)
            strokeWidth = 2f
        }
        val dividerPaint = Paint().apply {
            color = Color.rgb(220, 225, 234)
            strokeWidth = 1f
        }
        val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 232, 244)
            textSize = 9.5f
        }
        val caseTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(26, 34, 51)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sectionTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(29, 47, 77)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(93, 103, 120)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(26, 34, 51)
            textSize = 9.5f
        }
        val docTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(47, 92, 142)
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(93, 103, 120)
            textSize = 8.8f
        }

        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = 0f

        fun drawFooter() {
            canvas.drawText(
                "Prajus - Relatório confidencial  |  Página $pageNumber",
                MARGIN.toFloat(),
                (PAGE_HEIGHT - 24).toFloat(),
                mutedPaint
            )
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_HEIGHT - 44) {
                drawFooter()
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = MARGIN.toFloat()
            }
        }

        fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.0f)
                .build()
        }

        fun drawSectionHeading(title: String) {
            ensureSpace(34f)
            canvas.drawText(title, MARGIN.toFloat(), y, sectionTitlePaint)
            y += 6f
            canvas.drawLine(MARGIN.toFloat(), y, (MARGIN + 70).toFloat(), y, goldPaint)
            canvas.drawLine((MARGIN + 70).toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y, dividerPaint)
            y += 16f
        }

        fun drawKeyValue(label: String, value: String) {
            val cleanVal = value.ifBlank { "—" }
            val valLayout = buildLayout(cleanVal, bodyPaint, CONTENT_WIDTH - 98)
            ensureSpace(valLayout.height + 8f)
            canvas.drawText(label, MARGIN.toFloat(), y + 10f, labelPaint)
            canvas.save()
            canvas.translate((MARGIN + 98).toFloat(), y)
            valLayout.draw(canvas)
            canvas.restore()
            y += maxOf(valLayout.height.toFloat(), 14f) + 6f
        }

        // Top Banner
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 76f, navyPaint)
        canvas.drawLine(0f, 76f, PAGE_WIDTH.toFloat(), 76f, goldPaint)
        canvas.drawText(orgName.ifBlank { "Prajus – Gestão Jurídica" }, MARGIN.toFloat(), 32f, headerTitlePaint)
        val generatedAt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText(
            "Relatório do Caso  |  Gerado em $generatedAt  |  Por $authorName",
            MARGIN.toFloat(),
            54f,
            headerSubPaint
        )
        y = 98f

        // Case Title
        val titleLayout = buildLayout(caseEntity.titulo, caseTitlePaint, CONTENT_WIDTH)
        canvas.save()
        canvas.translate(MARGIN.toFloat(), y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 16f

        // Case Info
        drawSectionHeading("Informações do Caso")
        drawKeyValue("Cliente:", clientName)
        drawKeyValue("Status:", caseEntity.status)
        drawKeyValue("Número:", caseEntity.numero ?: "Sem número")
        drawKeyValue("Área:", caseEntity.area ?: "Geral")
        val prox = if (!caseEntity.proxAcao.isNullOrBlank()) {
            val dt = caseEntity.proxData?.takeIf { it.isNotBlank() }?.let { " (${SecurityUtils.formatDateBr(it)})" }.orEmpty()
            "${caseEntity.proxAcao}$dt"
        } else {
            "Não definida"
        }
        drawKeyValue("Próxima ação:", prox)
        drawKeyValue("Itens pendentes:", pendingItems.count { it.status != "Concluída" }.toString())
        drawKeyValue("Observações:", caseEntity.obs ?: "Sem observações registradas.")
        y += 10f

        // Documents List
        drawSectionHeading("Documentos Associados (${documents.size})")
        if (documents.isEmpty()) {
            ensureSpace(20f)
            canvas.drawText("Nenhum documento associado a este caso.", MARGIN.toFloat(), y, mutedPaint)
            y += 20f
        } else {
            documents.forEachIndexed { idx, d ->
                val titleL = buildLayout("${idx + 1}. ${d.nome}", docTitlePaint, CONTENT_WIDTH - 8)
                val metaText = "${d.tipo ?: "Outro"}  |  v${d.versao}  |  ${formatBytes(d.size)}  |  ${SecurityUtils.formatTimestampDateTimeBr(d.updatedAt ?: d.createdAt)}"
                val metaL = buildLayout(metaText, mutedPaint, CONTENT_WIDTH - 8)
                val shaText = if (d.sha256.isNotBlank()) "SHA-256: ${d.sha256}" else ""
                val shaH = if (shaText.isNotEmpty()) 14f else 0f
                ensureSpace(titleL.height + metaL.height + shaH + 12f)

                canvas.save()
                canvas.translate((MARGIN + 4).toFloat(), y)
                titleL.draw(canvas)
                canvas.restore()
                y += titleL.height + 2f

                canvas.save()
                canvas.translate((MARGIN + 4).toFloat(), y)
                metaL.draw(canvas)
                canvas.restore()
                y += metaL.height + 2f

                if (shaText.isNotEmpty()) {
                    canvas.drawText(shaText, (MARGIN + 4).toFloat(), y + 9f, mutedPaint)
                    y += 13f
                }
                y += 8f
            }
        }
        y += 8f

        // Timeline
        drawSectionHeading("Linha do Tempo (${timeline.size})")
        if (timeline.isEmpty()) {
            ensureSpace(20f)
            canvas.drawText("Sem eventos registrados na linha do tempo.", MARGIN.toFloat(), y, mutedPaint)
            y += 20f
        } else {
            timeline.forEach { t ->
                val eventL = buildLayout(t.txt, bodyPaint, CONTENT_WIDTH - 16)
                val metaLine = "${SecurityUtils.formatTimestampDateTimeBr(t.createdAt)}${t.autor?.takeIf { it.isNotBlank() }?.let { "  |  $it" }.orEmpty()}"
                ensureSpace(eventL.height + 24f)

                canvas.drawLine(
                    (MARGIN + 4).toFloat(),
                    y,
                    (MARGIN + 4).toFloat(),
                    y + eventL.height + 12f,
                    goldPaint
                )
                canvas.save()
                canvas.translate((MARGIN + 14).toFloat(), y)
                eventL.draw(canvas)
                canvas.restore()
                y += eventL.height + 4f

                canvas.drawText(metaLine, (MARGIN + 14).toFloat(), y + 9f, mutedPaint)
                y += 18f
            }
        }

        drawFooter()
        pdf.finishPage(page)

        context.contentResolver.openOutputStream(outputUri)?.use { out ->
            pdf.writeTo(out)
        } ?: error("Não foi possível gravar o arquivo PDF selecionado.")
        pdf.close()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
