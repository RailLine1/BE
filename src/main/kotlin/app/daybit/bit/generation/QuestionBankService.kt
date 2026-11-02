package app.daybit.bit.generation

import app.daybit.material.MaterialReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * 과목별 문제 은행을 채워요 (P-BIT-01, 02, 14).
 * - 자료가 ready 가 되면 그 자료로 문제를 만들어요.
 * - 출제 배치(#5)가 replenish() 로 모자란 범위를 다시 채워요. 생성이 실패한 자료도 이때 다시 시도돼요.
 * 퀴즈 출처가 작업 공간인 과목은 여기서 만들지 않아요(#10).
 */
@Service
class QuestionBankService(
    private val jdbc: JdbcClient,
    private val generator: QuestionGenerator,
    private val tx: TransactionTemplate,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onMaterialReady(event: MaterialReadyEvent) {
        runCatching { generateForFile(event.fileId) }
            .onFailure { log.warn("문제 생성 실패: fileId={}", event.fileId, it) }
    }

    /**
     * 범위(mid · final)의 안 낸 문제가 3일치보다 적으면 그 범위의 최신 자료로 문제를 더 만들어요 (P-BIT-02).
     * 만든 문제 수를 돌려줘요.
     */
    fun replenish(courseId: Long, scope: String): Int {
        val course = course(courseId) ?: return 0
        if (unused(courseId, scope) >= LOW_WATER_DAYS) return 0
        val fileId = jdbc.sql(
            """
            select f.id from files f join folders d on d.id = f.folder_id
             where f.course_id = :courseId and d.kind = :scope and f.status = 'ready'
             order by (select count(*) from questions q where q.evidence_file_id = f.id), f.created_at desc
             limit 1
            """.trimIndent(),
        ).param("courseId", courseId).param("scope", scope).query(Long::class.java).optional().orElse(null) ?: return 0
        return generate(fileId, course)
    }

    /** 자료 하나로 문제를 만들어요. 만든 문제 수를 돌려줘요 */
    fun generateForFile(fileId: Long): Int {
        val courseId = jdbc.sql("select course_id from files where id = :id and status = 'ready'").param("id", fileId)
            .query(Long::class.java).optional().orElse(null) ?: return 0
        val course = course(courseId) ?: return 0
        return generate(fileId, course)
    }

    private fun generate(fileId: Long, course: CourseRow): Int {
        if (course.quizSourceType != "default") return 0          // 작업 공간 출처는 #10 경로
        if (!generator.enabled) {
            log.info("Gemini 키가 없어 문제 생성을 건너뛰어요: fileId={}", fileId)
            return 0
        }
        val file = jdbc.sql(
            """
            select f.title, d.kind from files f join folders d on d.id = f.folder_id where f.id = :id
            """.trimIndent(),
        ).param("id", fileId).query { rs, _ -> rs.getString("title") to rs.getString("kind") }.single()
        val (title, kind) = file
        val scope = kind.takeIf { it == "mid" || it == "final" }

        val pages = jdbc.sql("select page_no, text from file_pages where file_id = :id order by page_no").param("id", fileId)
            .query { rs, _ -> PageText(rs.getInt("page_no"), rs.getString("text") ?: "") }.list()
            .filter { it.text.isNotBlank() }
        if (pages.isEmpty()) return 0
        val trimmed = trimToBudget(pages)

        val count = (target(course, scope) - unused(course.id, scope)).coerceIn(MIN_PER_FILE, MAX_PER_CALL)
        val request = GenerationRequest(course.name, title, trimmed, count)
        val generated = callWithRetry(request)

        val pageText = trimmed.associate { it.page to it.text }
        val valid = generated.filter { it.isWellFormed(pageText.keys) }
        if (valid.isEmpty()) return 0

        tx.executeWithoutResult {
            valid.groupBy { it.concept.trim().take(80) }.forEach { (concept, questions) ->
                val conceptId = jdbc.sql(
                    """
                    insert into concepts (course_id, file_id, name, page_from, page_to, capacity)
                    values (:courseId, :fileId, :name, :from, :to, :capacity)
                    returning id
                    """.trimIndent(),
                ).param("courseId", course.id).param("fileId", fileId).param("name", concept)
                    .param("from", questions.minOf { it.evidencePage }).param("to", questions.maxOf { it.evidencePage })
                    .param("capacity", questions.size).query(Long::class.java).single()

                questions.forEach { q ->
                    jdbc.sql(
                        """
                        insert into questions (course_id, concept_id, scope, stem, choices, answer_idx, explanation,
                                               evidence_file_id, evidence_page, source, verified)
                        values (:courseId, :conceptId, :scope, :stem, cast(:choices as jsonb), :answer, :explanation,
                                :fileId, :page, 'gemini', :verified)
                        """.trimIndent(),
                    ).param("courseId", course.id).param("conceptId", conceptId).param("scope", scope)
                        .param("stem", q.stem.trim()).param("choices", json.writeValueAsString(q.choices.map { it.trim() }))
                        .param("answer", q.answerIdx).param("explanation", q.explanation.trim())
                        .param("fileId", fileId).param("page", q.evidencePage)
                        .param("verified", isGrounded(q, pageText.getValue(q.evidencePage)))
                        .update()
                }
            }
        }
        return valid.size
    }

    // ---------- 검증 ----------

    /** 형식이 맞지 않는 문제는 저장하지 않아요 */
    private fun GeneratedQuestion.isWellFormed(pages: Set<Int>): Boolean =
        concept.isNotBlank() && stem.isNotBlank() && explanation.isNotBlank() &&
            choices.size == 4 && choices.all { it.isNotBlank() } && choices.map { it.trim() }.toSet().size == 4 &&
            answerIdx in 0..3 && evidencePage in pages

    /**
     * 근거 검증 (07_핵심기술 3): 모델이 옮긴 근거 문장이 근거 페이지 본문에 실제로 있는지 봐요.
     * PDF 추출과 모델 출력의 줄바꿈 · 공백이 달라서 공백을 모두 지우고 비교해요.
     */
    internal fun isGrounded(q: GeneratedQuestion, pageText: String): Boolean {
        val quote = normalize(q.evidenceQuote)
        return quote.length >= MIN_QUOTE && normalize(pageText).contains(quote)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), "")

    // ---------- 목표 개수 (P-BIT-02) ----------

    /** 목표 = 남은 일수 × 1.5. 시험일이 없거나 지났으면 14일로 봐요 */
    private fun target(course: CourseRow, scope: String?): Int {
        val today = LocalDate.now(clock)
        val exam = when (scope) {
            "mid" -> course.midExamDate
            "final" -> course.finalExamDate
            else -> null
        }
        val days = exam?.let { ChronoUnit.DAYS.between(today, it).toInt() }?.takeIf { it > 0 } ?: DEFAULT_DAYS
        return ceil(days * 1.5).toInt()
    }

    /** 근거 검증을 통과했고 아직 안 낸 문제 수 */
    private fun unused(courseId: Long, scope: String?): Int =
        jdbc.sql(
            """
            select count(*) from questions
             where course_id = :courseId and scope is not distinct from :scope and verified and used_at is null
            """.trimIndent(),
        ).param("courseId", courseId).param("scope", scope).query(Int::class.java).single()

    // ---------- 공통 ----------

    private fun callWithRetry(request: GenerationRequest): List<GeneratedQuestion> {
        var last: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return generator.generate(request)
            } catch (e: Exception) {
                last = e
                log.warn("문제 생성 호출 실패 ({}/{})", attempt + 1, ATTEMPTS, e)
            }
        }
        throw last!!
    }

    /** 입력이 너무 길면 앞에서부터 예산까지만 보내요 */
    private fun trimToBudget(pages: List<PageText>): List<PageText> {
        var total = 0
        return pages.takeWhile { total += it.text.length; total <= MAX_CHARS }.ifEmpty { listOf(pages.first().copy(text = pages.first().text.take(MAX_CHARS))) }
    }

    private data class CourseRow(
        val id: Long,
        val name: String,
        val quizSourceType: String,
        val midExamDate: LocalDate?,
        val finalExamDate: LocalDate?,
    )

    private fun course(courseId: Long): CourseRow? =
        jdbc.sql("select id, name, quiz_source_type, mid_exam_date, final_exam_date from courses where id = :id")
            .param("id", courseId)
            .query { rs, _ ->
                CourseRow(
                    rs.getLong("id"), rs.getString("name"), rs.getString("quiz_source_type"),
                    rs.getObject("mid_exam_date", LocalDate::class.java), rs.getObject("final_exam_date", LocalDate::class.java),
                )
            }.optional().orElse(null)

    companion object {
        private const val DEFAULT_DAYS = 14
        private const val LOW_WATER_DAYS = 3      // 하루 1문항 × 3일
        private const val MIN_PER_FILE = 3
        private const val MAX_PER_CALL = 10
        private const val MIN_QUOTE = 10
        private const val MAX_CHARS = 80_000
        private const val ATTEMPTS = 2
    }
}
