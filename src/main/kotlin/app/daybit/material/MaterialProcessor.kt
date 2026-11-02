package app.daybit.material

import app.daybit.global.storage.FileStorage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate

/** 텍스트 추출이 끝나 ready 가 되면 발행해요. 문제 생성(#4)이 이어 받아요 */
data class MaterialReadyEvent(val fileId: Long, val courseId: Long)

/**
 * 업로드된 PDF 에서 페이지별 텍스트를 뽑아 file_pages 에 저장하고 상태를 ready · failed 로 바꿔요 (P-MAT-03).
 * 업로드 트랜잭션이 커밋된 뒤 별도 스레드에서 돌아서 업로드 응답을 늦추지 않아요.
 */
@Component
class MaterialProcessor(
    private val jdbc: JdbcClient,
    private val storage: FileStorage,
    private val tx: TransactionTemplate,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onUploaded(event: MaterialUploadedEvent) = process(event.fileId)

    fun process(fileId: Long) {
        val row = jdbc.sql("select course_id, storage_key from files where id = :id and status = 'processing'")
            .param("id", fileId)
            .query { rs, _ -> rs.getLong("course_id") to rs.getString("storage_key") }
            .optional().orElse(null) ?: return   // 그 사이 지워졌거나 이미 처리됐어요
        val (courseId, key) = row

        val pages = try {
            extract(storage.get(key))
        } catch (e: Exception) {
            log.warn("PDF 텍스트 추출 실패: fileId={}", fileId, e)
            jdbc.sql("update files set status = 'failed' where id = :id and status = 'processing'").param("id", fileId).update()
            return
        }

        val saved = tx.execute {
            val updated = jdbc.sql("update files set status = 'ready', pages = :pages where id = :id and status = 'processing'")
                .param("pages", pages.size).param("id", fileId).update()
            if (updated == 0) return@execute false
            pages.forEachIndexed { i, text ->
                jdbc.sql("insert into file_pages (file_id, page_no, text) values (:id, :no, :text)")
                    .param("id", fileId).param("no", i + 1).param("text", text).update()
            }
            true
        } ?: false
        if (saved) events.publishEvent(MaterialReadyEvent(fileId, courseId))
    }

    /** 페이지 순서대로 텍스트를 돌려줘요. Postgres text 에 넣을 수 없는 NUL 문자는 지워요 */
    private fun extract(bytes: ByteArray): List<String> =
        Loader.loadPDF(bytes).use { doc ->
            if (doc.numberOfPages == 0) error("빈 PDF")
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(doc).replace("\u0000", "").trim()
            }
        }
}
