package app.daybit.myai

import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.bit.generation.QuestionBankService
import app.daybit.bit.generation.QuestionPrompt
import app.daybit.bit.generation.QuestionStore
import app.daybit.connector.ToolError
import app.daybit.course.CourseService
import app.daybit.global.common.MyAi
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 내 AI 출제 (#48, P-AI-07~11, P-BIT-14 · 15 · 20).
 * 관리자 AI 앱의 예약 작업이 커넥터 도구로 자료를 받아 가고(get_jobs) 문제를 보내요(submit_questions).
 * 서버는 구독 계정에 닿지 않고, 받은 문제를 Gemini 문제와 같은 형식 · 근거 검사로 저장해요.
 */
@Service
class MyAiService(
    private val jdbc: JdbcClient,
    private val store: QuestionStore,
    private val prompt: QuestionPrompt,
    private val bank: QuestionBankService,
    private val courses: CourseService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduleTemplate = ClassPathResource("prompts/myai-schedule.md").inputStream
        .use { String(it.readAllBytes(), Charsets.UTF_8) }.trim()

    // ---------- 앱 화면 (GET /me/myai) ----------

    fun status(userId: Long): MyAiResponse {
        val runs = jdbc.sql("select ai, last_run_at from myai_runs where user_id = :u").param("u", userId)
            .query { rs, _ -> rs.getString("ai") to rs.getObject("last_run_at", OffsetDateTime::class.java) }
            .list().toMap()
        return MyAiResponse(MyAi.entries.map { ai ->
            MyAiItem(ai, registered = runs[ai.name] != null, lastRunAt = runs[ai.name], schedulePrompt = schedulePrompt(ai))
        })
    }

    fun schedulePrompt(ai: MyAi): String = scheduleTemplate.replace("{ai}", ai.name)

    // ---------- 커넥터 도구 ----------

    /** get_jobs: 실행을 기록하고, 아직 문제가 없는 자료를 하나 줘요 (P-AI-08, 09) */
    @Transactional
    fun jobs(userId: Long, ai: MyAi): Map<String, Any?> {
        recordRun(userId, ai)
        val pending = pendingFiles(userId, ai)
        val material = pending.asSequence().mapNotNull { store.material(it) }.firstOrNull()
            ?: return mapOf("job" to null, "remaining" to 0, "message" to "새 자료가 없어요. 이번 실행은 여기서 끝내요.")
        val count = bank.countFor(material)
        return mapOf(
            "job" to mapOf(
                "fileId" to material.fileId,
                "course" to material.courseName,
                "title" to material.title,
                "count" to count,
                "prompt" to prompt.user(material.request(count)),
            ),
            "remaining" to pending.size - 1,
            "rules" to prompt.system,
            "schema" to prompt.schema,
        )
    }

    /** submit_questions: get_jobs 와 같은 조건을 다시 보고, 형식 · 근거 검사로 저장해요 */
    @Transactional
    fun submit(userId: Long, ai: MyAi, fileId: Long, questions: List<GeneratedQuestion>): Map<String, Any?> {
        recordRun(userId, ai)
        // 같은 자료를 두 번 동시에 제출해도 한 번만 저장되게 자료 행을 잠가요
        val target = jdbc.sql(
            """
            select f.id from files f join courses c on c.id = f.course_id
              join course_members m on m.course_id = c.id and m.user_id = :u and m.status = 'active' and m.role in ('admin', 'co')
             where f.id = :f and f.status = 'ready'
               and c.quiz_source_type = 'myai' and c.quiz_source_user_id = :u and c.quiz_source_ai = :ai
               for update of f
            """.trimIndent(),
        ).param("u", userId).param("f", fileId).param("ai", ai.name).query(Long::class.java).optional().orElse(null)
            ?: throw ToolError("이 자료는 내 AI 출제 대상이 아니에요. get_jobs 로 받은 fileId 를 보내 주세요.")
        if (hasQuestions(target)) throw ToolError("이미 문제가 있는 자료예요. get_jobs 로 다음 자료를 받아 주세요.")
        val material = store.material(target) ?: throw ToolError("자료 본문을 읽을 수 없어요.")

        val pages = material.pages.associate { it.page to it.text }
        val valid = questions.filter { store.isWellFormed(it, pages.keys) }
        if (valid.isEmpty()) throw ToolError("형식이 맞는 문제가 없어요. schema 와 rules 를 다시 확인해 주세요. (받은 ${questions.size}개)")
        val saved = store.save(material, valid, ai.name)
        val verified = valid.count { store.isGrounded(it, pages.getValue(it.evidencePage)) }
        return mapOf(
            "saved" to saved,
            "verified" to verified,
            "rejected" to questions.size - valid.size,
            "message" to "저장했어요. 근거 문장이 자료에서 확인된 문제(verified)만 출제돼요. get_jobs 로 다음 자료를 받아 주세요.",
        )
    }

    // ---------- Gemini 대체 (10분마다) ----------

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT3M")
    fun scheduledSweep() {
        runCatching { sweep(OffsetDateTime.now(clock)) }.onFailure { log.warn("내 AI 출제 점검 실패", it) }
    }

    /**
     * - 마지막 예약 실행이 48시간 전이면 출처를 Gemini 로 되돌리고 알려요(P-BIT-15). 기다리던 자료는 바로 Gemini 로 만들어요.
     * - 올린 지 24시간이 지나도 문제가 없는 자료는 Gemini 로 만들어요(P-BIT-20).
     */
    fun sweep(now: OffsetDateTime): SweepResult {
        val stale = jdbc.sql(
            """
            select c.id, c.quiz_source_user_id from courses c
              left join myai_runs r on r.user_id = c.quiz_source_user_id and r.ai = c.quiz_source_ai
             where c.quiz_source_type = 'myai' and (r.last_run_at is null or r.last_run_at < :cutoff)
            """.trimIndent(),
        ).param("cutoff", now.minus(STALE_RUN)).query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
        stale.forEach { (courseId, userId) -> courses.releaseQuizSource(courseId, userId) }
        val released = stale.flatMap { (courseId, _) -> waitingFiles(courseId) }

        val late = jdbc.sql(
            """
            select f.id from files f join courses c on c.id = f.course_id
             where c.quiz_source_type = 'myai' and f.status = 'ready' and f.created_at < :cutoff
               and not exists (select 1 from questions q where q.evidence_file_id = f.id)
             order by f.created_at limit :limit
            """.trimIndent(),
        ).param("cutoff", now.minus(WAIT_FOR_AI)).param("limit", FALLBACK_LIMIT).query(Long::class.java).list().filterNotNull()

        var generated = 0
        (released + late).distinct().forEach { fileId ->
            runCatching { generated += bank.generateForFile(fileId, force = true) }
                .onFailure { log.warn("내 AI 출제 대신 Gemini 생성 실패: fileId={}", fileId, it) }
        }
        return SweepResult(released = stale.size, fallbackFiles = (released + late).distinct().size, generated = generated)
    }

    data class SweepResult(val released: Int, val fallbackFiles: Int, val generated: Int)

    // ---------- 공통 ----------

    private fun recordRun(userId: Long, ai: MyAi) {
        jdbc.sql(
            """
            insert into myai_runs (user_id, ai) values (:u, :ai)
            on conflict (user_id, ai) do update set last_run_at = now()
            """.trimIndent(),
        ).param("u", userId).param("ai", ai.name).update()
    }

    /** 출처가 (나, 이 AI)이고 내가 관리자 · 공동 관리자인 과목의, 아직 문제가 없는 ready 자료 (오래된 것부터) */
    private fun pendingFiles(userId: Long, ai: MyAi): List<Long> =
        jdbc.sql(
            """
            select f.id from files f join courses c on c.id = f.course_id
              join course_members m on m.course_id = c.id and m.user_id = :u and m.status = 'active' and m.role in ('admin', 'co')
             where c.quiz_source_type = 'myai' and c.quiz_source_user_id = :u and c.quiz_source_ai = :ai
               and f.status = 'ready'
               and not exists (select 1 from questions q where q.evidence_file_id = f.id)
             order by f.created_at, f.id
            """.trimIndent(),
        ).param("u", userId).param("ai", ai.name).query(Long::class.java).list().filterNotNull()

    /** 출처를 되돌린 과목에서 출제 AI 를 기다리던(문제가 없는) 자료 */
    private fun waitingFiles(courseId: Long): List<Long> =
        jdbc.sql(
            """
            select f.id from files f
             where f.course_id = :c and f.status = 'ready'
               and not exists (select 1 from questions q where q.evidence_file_id = f.id)
             order by f.created_at limit :limit
            """.trimIndent(),
        ).param("c", courseId).param("limit", FALLBACK_LIMIT).query(Long::class.java).list().filterNotNull()

    private fun hasQuestions(fileId: Long): Boolean =
        jdbc.sql("select count(*) from questions where evidence_file_id = :f").param("f", fileId).query(Int::class.java).single() > 0

    companion object {
        val WAIT_FOR_AI: Duration = Duration.ofHours(24)
        val STALE_RUN: Duration = Duration.ofHours(48)
        private const val FALLBACK_LIMIT = 10
    }
}
