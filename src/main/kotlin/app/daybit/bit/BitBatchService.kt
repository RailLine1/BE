package app.daybit.bit

import app.daybit.bit.generation.QuestionBankService
import app.daybit.notification.NotificationType
import app.daybit.notification.Notifier
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Bit 배치 (docs/api.md 배치 작업). 시각은 인자로 받아서 테스트에서 원하는 시점으로 돌릴 수 있어요.
 * 모든 단계는 여러 번 실행해도 결과가 같아요(이미 만든 Bit · 보낸 알림 · 마감한 Bit 는 건너뜀).
 * 그래서 서버가 16:30 · 17:00 을 놓쳤으면 다음 실행이나 서버 시작 때 따라잡아요(BitScheduler).
 */
@Service
class BitBatchService(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val notifier: Notifier,
    private val bank: QuestionBankService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ---------- 16:30 출제 준비 ----------

    /** P-BIT-03, 04, 06: 과목마다 범위를 정하고 문제 1개를 골라 date 의 Bit 를 만들어요. 만든 Bit 수를 돌려줘요 */
    fun prepare(date: LocalDate, now: OffsetDateTime): Int {
        val courseIds = jdbc.sql(
            """
            select c.id from courses c
             where c.state = 'active'
               and not exists (select 1 from bits b where b.course_id = c.id and b.open_date = :date)
             order by c.id
            """.trimIndent(),
        ).param("date", date).query(Long::class.java).list().filterNotNull()

        var created = 0
        courseIds.forEach { courseId ->
            runCatching { tx.execute { prepareCourse(courseId, date, now) } }
                .onSuccess { if (it == true) created++ }
                .onFailure { log.error("출제 준비 실패: course={}", courseId, it) }
        }
        return created
    }

    private fun prepareCourse(courseId: Long, date: LocalDate, now: OffsetDateTime): Boolean {
        // 같은 과목을 동시에 준비하지 않게 과목 행을 잠가요
        jdbc.sql("select id from courses where id = :id for update").param("id", courseId).query(Long::class.java).single()
        val exists = jdbc.sql("select count(*) from bits where course_id = :c and open_date = :date")
            .param("c", courseId).param("date", date).query(Int::class.java).single() > 0
        if (exists) return false

        val scope = scopeOf(courseId, date) ?: return false              // P-BIT-06: 처리 완료 자료가 없어요
        jdbc.sql("update courses set scope = :scope where id = :id").param("scope", scope).param("id", courseId).update()

        val questionId = pickQuestion(courseId, scope, now) ?: run {
            log.info("출제할 문제가 없어 건너뛰어요: course={}, scope={}", courseId, scope)
            return false
        }

        val no = jdbc.sql("select coalesce(max(no), 0) + 1 from bits where course_id = :c").param("c", courseId)
            .query(Int::class.java).single()
        jdbc.sql(
            """
            insert into bits (course_id, no, question_id, open_date, open_at, close_at)
            values (:c, :no, :q, :date, :openAt, :closeAt)
            """.trimIndent(),
        ).param("c", courseId).param("no", no).param("q", questionId).param("date", date)
            .param("openAt", BitDays.openAt(date)).param("closeAt", BitDays.closeAt(date)).update()

        jdbc.sql("update questions set used_at = :now where id = :q").param("now", now).param("q", questionId).update()
        jdbc.sql(
            """
            update concepts set times_used = times_used + 1, last_used_at = :now
             where id = (select concept_id from questions where id = :q)
            """.trimIndent(),
        ).param("now", now).param("q", questionId).update()
        return true
    }

    /**
     * P-BIT-03: 중간 시험일이 지났으면 final. 아니면 가장 최근에 올린 처리 완료 파일이 있는 폴더(mid · final).
     * 그 범위에 처리 완료 자료가 없으면 null (P-BIT-06).
     */
    internal fun scopeOf(courseId: Long, date: LocalDate): String? {
        val mid = jdbc.sql("select mid_exam_date from courses where id = :id").param("id", courseId)
            .query { rs, _ -> rs.getObject("mid_exam_date", LocalDate::class.java) }.optional().orElse(null)
        val scope = if (mid != null && date.isAfter(mid)) {
            "final"
        } else {
            jdbc.sql(
                """
                select d.kind from files f join folders d on d.id = f.folder_id
                 where f.course_id = :c and f.status = 'ready' and d.kind in ('mid', 'final')
                 order by f.created_at desc, f.id desc limit 1
                """.trimIndent(),
            ).param("c", courseId).query(String::class.java).optional().orElse(null) ?: return null
        }
        val hasReady = jdbc.sql(
            """
            select count(*) from files f join folders d on d.id = f.folder_id
             where f.course_id = :c and f.status = 'ready' and d.kind = :scope
            """.trimIndent(),
        ).param("c", courseId).param("scope", scope).query(Int::class.java).single() > 0
        return scope.takeIf { hasReady }
    }

    /**
     * P-BIT-04: 아직 안 낸 검증된 문제 중
     * 점수 = 최신 강의(0~1) + 개념 오답률(0~1) − 최근 3일 안에 낸 개념(1.5). 같으면 먼저 만든 문제.
     */
    internal fun pickQuestion(courseId: Long, scope: String, now: OffsetDateTime): Long? {
        data class Candidate(val id: Long, val fileRank: Int, val wrongRate: Double, val recentConcept: Boolean)

        val candidates = jdbc.sql(
            """
            select q.id,
                   dense_rank() over (order by f.created_at desc nulls last) as file_rank,
                   coalesce(c.wrong_rate, 0)                               as wrong_rate,
                   coalesce(c.last_used_at > :recent, false)               as recent_concept
              from questions q
              left join files f on f.id = q.evidence_file_id
              left join concepts c on c.id = q.concept_id
             where q.course_id = :c and q.scope = :scope and q.verified and q.used_at is null
             order by q.id
            """.trimIndent(),
        ).param("c", courseId).param("scope", scope).param("recent", now.minusDays(3))
            .query { rs, _ ->
                Candidate(rs.getLong("id"), rs.getInt("file_rank"), rs.getDouble("wrong_rate"), rs.getBoolean("recent_concept"))
            }.list()
        if (candidates.isEmpty()) return null

        val maxRank = candidates.maxOf { it.fileRank }
        return candidates.maxWith(
            compareBy<Candidate> { c ->
                val recency = if (maxRank == 1) 1.0 else 1.0 - (c.fileRank - 1).toDouble() / (maxRank - 1)
                recency + c.wrongRate - (if (c.recentConcept) 1.5 else 0.0)
            }.thenByDescending { it.id },
        ).id
    }

    // ---------- 17:00 열기 · 마감 ----------

    /** P-BIT-05, P-ARC-01: 지난 Bit 를 마감 · 집계해 아카이브에 올리고, 오늘 Bit 를 열어 알림을 남겨요 */
    fun open(date: LocalDate, now: OffsetDateTime) {
        closeDue(now)
        prepare(date, now)                       // 16:30 을 놓쳤으면 여기서 따라잡아요
        notifyOpened(date)
    }

    /** close_at 이 지났는데 아직 마감하지 않은 Bit 를 모두 마감해요 */
    fun closeDue(now: OffsetDateTime): Int {
        val due = jdbc.sql("select id from bits where archived_at is null and close_at < :now order by id")
            .param("now", now).query(Long::class.java).list().filterNotNull()
        due.forEach { bitId ->
            tx.executeWithoutResult {
                jdbc.sql(
                    """
                    update bits b
                       set attempts    = s.attempts,
                           accuracy    = s.accuracy,
                           average_sec = s.average_sec,
                           archived_at = :now
                      from (select count(*)                                                    as attempts,
                                   round(100.0 * avg(case when correct then 1 else 0 end), 1)  as accuracy,
                                   round(avg(elapsed_sec))::int                                as average_sec
                              from bit_attempts where bit_id = :id and submitted_at is not null) s
                     where b.id = :id and b.archived_at is null
                    """.trimIndent(),
                ).param("now", now).param("id", bitId).update()

                // P-BIT-04 오답률: 그 개념으로 낸 모든 Bit 의 제출 기준
                jdbc.sql(
                    """
                    update concepts c
                       set wrong_rate = s.wrong_rate
                      from (select q.concept_id, round(1 - avg(case when a.correct then 1.0 else 0.0 end), 3) as wrong_rate
                              from bits b
                              join questions q on q.id = b.question_id
                              join bit_attempts a on a.bit_id = b.id and a.submitted_at is not null
                             where q.concept_id = (select q2.concept_id from bits b2 join questions q2 on q2.id = b2.question_id where b2.id = :id)
                             group by q.concept_id) s
                     where c.id = s.concept_id
                    """.trimIndent(),
                ).param("id", bitId).update()
            }
        }
        return due.size
    }

    /** 알림을 켠 구독자에게 Bit 열림 알림을 남겨요 (P-BIT-05, P-NOTI-02). 이미 남긴 Bit 는 건너뛰어요 */
    private fun notifyOpened(date: LocalDate) {
        data class Opened(val bitId: Long, val courseId: Long, val courseName: String, val no: Int)

        val opened = jdbc.sql(
            """
            select b.id, b.course_id, c.name, b.no from bits b join courses c on c.id = b.course_id
             where b.open_date = :date
               and not exists (select 1 from notifications n
                                where n.type = 'bit_open' and n.payload ->> 'bitId' = b.id::text)
            """.trimIndent(),
        ).param("date", date)
            .query { rs, _ -> Opened(rs.getLong("id"), rs.getLong("course_id"), rs.getString("name"), rs.getInt("no")) }.list()

        opened.forEach { bit ->
            val users = jdbc.sql("select user_id from course_members where course_id = :c and status = 'active' and notify")
                .param("c", bit.courseId).query(Long::class.java).list().filterNotNull()
            notifier.notify(
                users, NotificationType.bit_open,
                mapOf("bitId" to bit.bitId, "courseId" to bit.courseId, "courseName" to bit.courseName, "no" to bit.no),
            )
        }
    }

    // ---------- 00:10 과목 상태 점검 ----------

    /**
     * P-BIT-16, 17, 03: 기말 OFF, 활동 없음 OFF, 중간 시험일 경과 시 범위 전환.
     * 이어서 진행 중인 과목의 문제 은행을 보충해요 (P-BIT-02).
     */
    fun dailyCheck(date: LocalDate, now: OffsetDateTime) {
        // P-BIT-17: 기말 시험일 다음 날 영구 종료
        jdbc.sql(
            """
            update courses set state = 'ended', ended_reason = 'final_exam', ended_at = :now
             where state = 'active' and final_exam_date is not null and final_exam_date < :date
            """.trimIndent(),
        ).param("now", now).param("date", date).update()

        // P-BIT-16: 마지막 업로드(없으면 만든 날) 후 2주가 지났고, 그 2주 동안 제출이 하나도 없으면 종료
        jdbc.sql(
            """
            update courses c set state = 'ended', ended_reason = 'inactive', ended_at = :now
             where c.state = 'active'
               and coalesce(c.last_upload_at, c.created_at) <= :cutoff
               and not exists (select 1 from bit_attempts a join bits b on b.id = a.bit_id
                                where b.course_id = c.id and a.submitted_at > :cutoff)
            """.trimIndent(),
        ).param("now", now).param("cutoff", now.minusDays(INACTIVE_DAYS)).update()

        // P-BIT-03: 중간 시험일이 지나면 final 범위
        jdbc.sql(
            """
            update courses set scope = 'final'
             where state = 'active' and mid_exam_date is not null and mid_exam_date < :date
               and scope is distinct from 'final'
            """.trimIndent(),
        ).param("date", date).update()

        replenishAll(date)
    }

    private fun replenishAll(date: LocalDate) {
        val courseIds = jdbc.sql("select id from courses where state = 'active' order by id")
            .query(Long::class.java).list().filterNotNull()
        courseIds.forEach { courseId ->
            val scope = scopeOf(courseId, date) ?: return@forEach
            runCatching { bank.replenish(courseId, scope) }
                .onFailure { log.warn("문제 은행 보충 실패: course={}", courseId, it) }
        }
    }

    /** 서버 시작 · 매 배치 실행 때 놓친 단계를 따라잡아요 */
    fun catchUp(now: ZonedDateTime) {
        val date = BitDays.current(now)
        open(date, now.toOffsetDateTime())
    }

    companion object {
        private const val INACTIVE_DAYS = 14L
    }
}
