package app.daybit.bit.generation

import app.daybit.material.MaterialReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * 작업 공간(#10)으로 생성을 맡기는 쪽. 요청을 보냈으면 true(결과는 나중에 비동기로 와요).
 * 보내지 못했으면 출처를 Gemini 로 되돌리고 false 를 돌려줘요 (P-BIT-15).
 */
fun interface WorkspaceGeneration {
    fun request(courseId: Long, fileId: Long, count: Int): Boolean
}

/** 작업 공간 생성이 실패 · 만료돼 Gemini 로 전환됐어요. 그 자료를 Gemini 로 다시 만들어요 */
data class WorkspaceFallbackEvent(val fileId: Long)

/**
 * 과목별 문제 은행을 채워요 (P-BIT-01, 02, 14).
 * - 자료가 ready 가 되면 그 자료로 문제를 만들어요.
 * - 출제 배치(#5)가 replenish() 로 모자란 범위를 다시 채워요. 생성이 실패한 자료도 이때 다시 시도돼요.
 * - 퀴즈 출처가 작업 공간인 과목은 작업 공간에 맡기고, 실패하면 Gemini 로 만들어요 (#10, P-BIT-15).
 */
@Service
class QuestionBankService(
    private val jdbc: JdbcClient,
    private val generator: QuestionGenerator,
    private val store: QuestionStore,
    private val workspace: ObjectProvider<WorkspaceGeneration>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onMaterialReady(event: MaterialReadyEvent) {
        runCatching { generateForFile(event.fileId) }
            .onFailure { log.warn("문제 생성 실패: fileId={}", event.fileId, it) }
    }

    @Async
    @EventListener
    fun onWorkspaceFallback(event: WorkspaceFallbackEvent) {
        runCatching { generateForFile(event.fileId) }
            .onFailure { log.warn("작업 공간 대신 Gemini 생성 실패: fileId={}", event.fileId, it) }
    }

    /**
     * 범위(mid · final)의 안 낸 문제가 3일치보다 적으면 그 범위의 최신 자료로 문제를 더 만들어요 (P-BIT-02).
     * 만든 문제 수를 돌려줘요(작업 공간에 맡긴 경우 0).
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

    /** 자료 하나로 문제를 만들어요. 만든 문제 수를 돌려줘요(작업 공간에 맡긴 경우 0) */
    fun generateForFile(fileId: Long): Int {
        val courseId = jdbc.sql("select course_id from files where id = :id and status = 'ready'").param("id", fileId)
            .query(Long::class.java).optional().orElse(null) ?: return 0
        val course = course(courseId) ?: return 0
        return generate(fileId, course)
    }

    private fun generate(fileId: Long, course: CourseRow): Int {
        val material = store.material(fileId) ?: return 0
        val count = (target(course, material.scope) - unused(course.id, material.scope)).coerceIn(MIN_PER_FILE, MAX_PER_CALL)

        if (course.quizSourceType == "workspace") {
            val sent = workspace.ifAvailable?.request(course.id, fileId, count) ?: false
            if (sent) return 0
            // 보내지 못했으면 이미 Gemini 로 전환됐어요. 아래에서 바로 만들어요
        }
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
