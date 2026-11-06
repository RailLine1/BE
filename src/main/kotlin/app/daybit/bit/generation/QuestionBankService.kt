package app.daybit.bit.generation

import app.daybit.material.MaterialReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * 과목별 문제 은행을 채워요 (P-BIT-01, 02, 14).
 * - 자료가 ready 가 되면 그 자료로 문제를 만들어요.
 * - 출제 배치(#5)가 replenish() 로 모자란 범위를 다시 채워요. 생성이 실패한 자료도 이때 다시 시도돼요. 재보충은 출처와 상관없이 Gemini 가 해요.
 * - 퀴즈 출처가 출제 AI(myai)인 과목은 새 자료를 바로 만들지 않고 예약 실행을 기다려요.
 *   24시간 안에 안 들어오면 MyAiService 가 generateForFile(force = true)로 Gemini 를 불러요 (P-BIT-20).
 */
@Service
class QuestionBankService(
    private val jdbc: JdbcClient,
    private val generator: QuestionGenerator,
    private val store: QuestionStore,
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

    /**
     * 자료 하나로 문제를 만들어요. 만든 문제 수를 돌려줘요.
     * 출처가 출제 AI 인 과목은 force 가 아니면 예약 실행을 기다리며 0 을 돌려줘요 (P-BIT-20).
     */
    fun generateForFile(fileId: Long, force: Boolean = false): Int {
        val courseId = jdbc.sql("select course_id from files where id = :id and status = 'ready'").param("id", fileId)
            .query(Long::class.java).optional().orElse(null) ?: return 0
        val course = course(courseId) ?: return 0
        if (course.quizSourceType == "myai" && !force) return 0
        return generate(fileId, course)
    }

    /** 출제 AI 에 넘길 문제 수. Gemini 와 같은 목표 개수 규칙을 써요 (P-BIT-02) */
    fun countFor(material: Material): Int {
        val course = course(material.courseId) ?: return MIN_PER_FILE
        return (target(course, material.scope) - unused(course.id, material.scope)).coerceIn(MIN_PER_FILE, MAX_PER_CALL)
    }

    private fun generate(fileId: Long, course: CourseRow): Int {
        val material = store.material(fileId) ?: return 0
        val count = (target(course, material.scope) - unused(course.id, material.scope)).coerceIn(MIN_PER_FILE, MAX_PER_CALL)
        if (!generator.enabled) {
            log.info("Gemini 키가 없어 문제 생성을 건너뛰어요: fileId={}", fileId)
            return 0
        }
        return store.save(material, callWithRetry(material.request(count)), "gemini")
    }

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
        private const val ATTEMPTS = 2
    }
}
