package app.daybit.support

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/** 쪽마다 한 줄씩 영문 텍스트가 든 PDF 를 만들어요 (기본 글꼴이라 한글은 못 써요) */
fun samplePdf(vararg pageTexts: String): ByteArray = PDDocument().use { doc ->
    val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    pageTexts.forEach { text ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText(); cs.setFont(font, 14f); cs.newLineAtOffset(72f, 700f); cs.showText(text); cs.endText()
        }
    }
    ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
}
