package app.daybit.bit

import app.daybit.course.CourseService
import app.daybit.global.common.Evidence
import app.daybit.global.config.ClockConfig.Companion.SEOUL
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.min

@Service
class BitService(
    private val jdbc: JdbcClient,
    private val courses: CourseService,
    private val json: JsonMapper,
    private val clock: Clock,
) {

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock.withZone(SEOUL))

    // ---------- 오늘의 티켓 ----------

    /** P-BIT-07: 오늘 열린 Bit 가 있는 내 과목. 안 푼 티켓이 앞 */
    @Transactional(readOnly = true)
    fun today(userId: Long): List<BitTicket> {
        val date = BitDays.current(now())
        return jdbc.sql(
            """
            select b.id, b.course_id, c.name, c.icon, c.intro, b.no, a.submitted_at, a.elapsed_sec,
                   coalesce(s.current_streak, 0) as streak
              from course_members m
              join courses c on c.id = m.course_id and c.state = 'active'
              join bits b on b.course_id = c.id and b.open_date = :date
              left join bit_attempts a on a.bit_id = b.id and a.user_id = :u
              left join user_course_stats s on s.user_id = :u and s.course_id = c.id
             where m.user_id = :u and m.status = 'active'
            """.trimIndent(),
        ).param("date", date).param("u", userId)
            .query { rs, _ ->
                val done = rs.getObject("submitted_at") != null
                val courseId = rs.getLong("course_id")
                BitTicket(
                    bitId = rs.getLong("id"),
                    courseId = courseId,
                    courseName = rs.getString("name"),
                    icon = rs.getString("icon"),
                    intro = rs.getString("intro"),
                    no = rs.getInt("no"),
                    status = if (done) TicketStatus.done else TicketStatus.open,
                    elapsedSec = if (done) rs.getInt("elapsed_sec") else null,
                    streak = rs.getInt("streak"),
                    week = emptyList(),
                )
            }.list()
            .map { it.copy(week = week(userId, it.courseId, date)) }
            .sortedWith(compareBy<BitTicket> { it.status == TicketStatus.done }.thenBy { it.courseName }.thenBy { it.bitId })
    }

    // ---------- 응시 ----------

    /** P-BIT-08: 서버가 시작 시각을 기록해요. 다시 불러도 처음 시각을 그대로 써요 */
    @Transactional
    fun start(userId: Long, bitId: Long): StartResponse {
        val bit = openBit(userId, bitId)
        jdbc.sql("insert into bit_attempts (bit_id, user_id, started_at) values (:b, :u, :now) on conflict do nothing")
            .param("b", bitId).param("u", userId).param("now", now().toOffsetDateTime()).update()
        val attempt = attempt(bitId, userId)!!
        if (attempt.submittedAt != null) throw ApiException(ErrorCode.ALREADY_ATTEMPTED)
        return StartResponse(attempt.startedAt, bit.no, BitQuestion(bit.stem, bit.choices))
    }

    /** P-BIT-09, 10: 한 번만 제출. 점수 = 정답 × 1000 − min(초, 300) */
    @Transactional
    fun submit(userId: Long, bitId: Long, choice: Int): ResultTicket {
        val bit = openBit(userId, bitId)
        val attempt = attempt(bitId, userId, lock = true)
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "먼저 풀기를 시작해 주세요.")
        if (attempt.submittedAt != null) throw ApiException(ErrorCode.ALREADY_ATTEMPTED)

        val now = now().toOffsetDateTime()
        val elapsed = Duration.between(attempt.startedAt, now).seconds.toInt().coerceAtLeast(0)
        val correct = choice == bit.answer
        val score = (if (correct) 1000 else 0) - min(elapsed, 300)
        jdbc.sql(
            """
            update bit_attempts set submitted_at = :now, choice_idx = :choice, correct = :correct, elapsed_sec = :elapsed, score = :score
             where bit_id = :b and user_id = :u
            """.trimIndent(),
        ).param("now", now).param("choice", choice).param("correct", correct).param("elapsed", elapsed)
            .param("score", score).param("b", bitId).param("u", userId).update()

        updateStats(userId, bit, correct)
        return ticketOf(userId, bit)
    }

    /** P-BIT-11: 제출한 사람만 */
    @Transactional(readOnly = true)
    fun ticket(userId: Long, bitId: Long): ResultTicket {
        val bit = bit(bitId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        courses.membership(userId, bit.courseId)
        if (attempt(bitId, userId)?.submittedAt == null) throw ApiException(ErrorCode.NOT_FOUND)
        return ticketOf(userId, bit)
    }

    /**
     * 연속 일수: 이 과목의 바로 전 Bit 도 풀었으면 +1, 아니면 1부터.
     * Bit 가 없던 날(자료 없음 등)은 건너뛰고 세서, 출제를 건너뛴 날 때문에 연속이 끊기지 않아요.
     */
    private fun updateStats(userId: Long, bit: BitRow, correct: Boolean) {
        val previousSolved = jdbc.sql(
            """
            select exists (
              select 1 from bit_attempts a
               where a.user_id = :u and a.submitted_at is not null
                 and a.bit_id = (select id from bits where course_id = :c and open_date < :date order by open_date desc limit 1))
            """.trimIndent(),
        ).param("u", userId).param("c", bit.courseId).param("date", bit.openDate).query(Boolean::class.java).single()

        jdbc.sql(
            """
            insert into user_course_stats (user_id, course_id, current_streak, best_streak, attempts, corrects, last_attempt_date)
            values (:u, :c, 1, 1, 1, :correct, :date)
            on conflict (user_id, course_id) do update
               set current_streak    = case when :prev then user_course_stats.current_streak + 1 else 1 end,
                   best_streak       = greatest(user_course_stats.best_streak,
                                                case when :prev then user_course_stats.current_streak + 1 else 1 end),
                   attempts          = user_course_stats.attempts + 1,
                   corrects          = user_course_stats.corrects + :correct,
                   last_attempt_date = :date
            """.trimIndent(),
        ).param("u", userId).param("c", bit.courseId).param("correct", if (correct) 1 else 0)
            .param("date", bit.openDate).param("prev", previousSolved).update()
    }

    private fun ticketOf(userId: Long, bit: BitRow): ResultTicket {
        val me = attempt(bit.id, userId)!!
        val stats = jdbc.sql(
            """
            select count(*) as total, coalesce(round(avg(elapsed_sec)), 0)::int as avg_sec,
                   count(*) filter (where score > :score or (score = :score and submitted_at < :at)) as ahead
              from bit_attempts where bit_id = :b and submitted_at is not null
            """.trimIndent(),
        ).param("b", bit.id).param("score", me.score!!).param("at", me.submittedAt!!)
            .query { rs, _ -> Triple(rs.getInt("total"), rs.getInt("avg_sec"), rs.getInt("ahead")) }.single()
        val (total, avgSec, ahead) = stats
        val streak = jdbc.sql("select current_streak from user_course_stats where user_id = :u and course_id = :c")
            .param("u", userId).param("c", bit.courseId).query(Int::class.java).optional().orElse(0)
        return ResultTicket(
            courseName = bit.courseName,
            no = bit.no,
            correct = me.correct!!,
            answer = bit.answer,
            myChoice = me.choice!!,
            elapsedSec = me.elapsedSec!!,
            averageSec = avgSec,
            // P-BIT-10 동점이면 먼저 제출한 사람이 앞. 내 앞에 있는 사람 비율이고, 1등은 1%다(혼자 풀어도 100%가 아니다)
            topPercent = ceil(ahead * 100.0 / total).toInt().coerceAtLeast(1),
            streak = streak,
            week = week(userId, bit.courseId, bit.openDate),
            score = me.score,
            explanation = bit.explanation,
            evidence = bit.evidence,
        )
    }

    // ---------- 오늘의 결과 (P-BIT-12) ----------

    @Transactional(readOnly = true)
    fun records(userId: Long, courseId: Long, day: RecordDay): RecordsResponse {
        val m = courses.membership(userId, courseId)
        val today = BitDays.current(now())
        val date = if (day == RecordDay.today) today else today.minusDays(1)
        val bitId = jdbc.sql("select id from bits where course_id = :c and open_date = :date")
            .param("c", courseId).param("date", date).query(Long::class.java).optional().orElse(null)
        val bit = bitId?.let { bit(it) }

        val people = if (bit == null) emptyList() else jdbc.sql(
            """
            select a.user_id, u.nickname, a.correct, a.elapsed_sec
              from bit_attempts a join users u on u.id = a.user_id
             where a.bit_id = :b and a.submitted_at is not null
             order by a.score desc, a.submitted_at
            """.trimIndent(),
        ).param("b", bit.id)
            .query { rs, _ ->
                PersonRecord(rs.getLong("user_id"), rs.getString("nickname"), rs.getBoolean("correct"), rs.getInt("elapsed_sec"), rs.getLong("user_id") == userId)
            }.list()

        val myAttempt = bit?.let { attempt(it.id, userId) }
        // P-BIT-09: 해설은 제출한 뒤에만. 마감된 Bit(어제)는 모두에게 공개
        val revealed = bit != null && (myAttempt?.submittedAt != null || bit.closeAt.isBefore(now().toOffsetDateTime()))
        val explanation = if (!revealed) null else Explanation(
            stem = bit.stem,
            choices = bit.choices,
            answer = bit.answer,
            myChoice = myAttempt?.choice,
            text = bit.explanation,
            evidence = bit.evidence,
        )

        val stats = jdbc.sql("select current_streak, best_streak, attempts, corrects from user_course_stats where user_id = :u and course_id = :c")
            .param("u", userId).param("c", courseId)
            .query { rs, _ -> listOf(rs.getInt("current_streak"), rs.getInt("best_streak"), rs.getInt("attempts"), rs.getInt("corrects")) }
            .optional().orElse(listOf(0, 0, 0, 0))
        val (current, best, attempts, corrects) = stats
        val grid = dailyCounts(userId, today.minusDays(GRID_DAYS - 1L), today).values.toList()

        return RecordsResponse(
            day = day,
            bitNo = bit?.no,
            people = people,
            mine = MyBit(current, best, if (attempts == 0) 0 else corrects * 100 / attempts, grid),
            notify = m.notify,
            explanation = explanation,
        )
    }

    // ---------- 학습 기록 (P-BIT-13) ----------

    @Transactional(readOnly = true)
    fun streak(userId: Long, weeks: Int): StreakResponse {
        val today = BitDays.current(now())
        val from = today.minusDays(weeks * 7L - 1)
        val counts = dailyCounts(userId, from, today)
        val solvedDays = jdbc.sql(
            """
            select distinct b.open_date from bit_attempts a join bits b on b.id = a.bit_id
             where a.user_id = :u and a.submitted_at is not null
             order by b.open_date
            """.trimIndent(),
        ).param("u", userId).query { rs, _ -> rs.getObject("open_date", LocalDate::class.java) }.list().toSet()

        // 오늘 아직 안 풀었으면 어제까지 이어진 연속을 보여 줘요
        var cursor = if (today in solvedDays) today else today.minusDays(1)
        var current = 0
        while (cursor in solvedDays) { current++; cursor = cursor.minusDays(1) }

        var best = 0
        var run = 0
        var prev: LocalDate? = null
        solvedDays.sorted().forEach { d ->
            run = if (prev != null && prev!!.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        val thisMonth = solvedDays.count { it.year == today.year && it.month == today.month }
        return StreakResponse(current, best, thisMonth, counts.map { (d, c) -> StreakDay(d, c) })
    }

    @Transactional(readOnly = true)
    fun profile(targetUserId: Long, weeks: Int = 15): ProfileResponse {
        val nickname = jdbc.sql("select nickname from users where id = :id").param("id", targetUserId)
            .query(String::class.java).optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
        return ProfileResponse(targetUserId, nickname, streak(targetUserId, weeks))
    }

    /** P-ACC-05 환영 화면: 최근 24시간 새 자료, 오늘 남은 Bit, 연속 일수 */
    @Transactional(readOnly = true)
    fun welcome(userId: Long): WelcomeResponse {
        val nickname = jdbc.sql("select nickname from users where id = :id").param("id", userId)
            .query(String::class.java).optional().orElseThrow { ApiException(ErrorCode.UNAUTHORIZED) }
        val newMaterials = jdbc.sql(
            """
            select c.id, c.name, count(*) as cnt
              from course_members m
              join courses c on c.id = m.course_id
              join files f on f.course_id = c.id and f.status = 'ready' and f.created_at > :since
             where m.user_id = :u and m.status = 'active'
             group by c.id, c.name order by c.name
            """.trimIndent(),
        ).param("u", userId).param("since", now().toOffsetDateTime().minusDays(1))
            .query { rs, _ -> WelcomeResponse.NewMaterials(rs.getLong("id"), rs.getString("name"), rs.getInt("cnt")) }.list()
        val bitsLeft = today(userId).count { it.status == TicketStatus.open }
        return WelcomeResponse(nickname, newMaterials, bitsLeft, streak(userId, 1).current)
    }

    // ---------- 공통 ----------

    /** from~to 날짜별로 모든 과목에서 푼 Bit 수 (과목 구분 없음) */
    private fun dailyCounts(userId: Long, from: LocalDate, to: LocalDate): LinkedHashMap<LocalDate, Int> {
        val rows = jdbc.sql(
            """
            select b.open_date, count(*) as cnt from bit_attempts a join bits b on b.id = a.bit_id
             where a.user_id = :u and a.submitted_at is not null and b.open_date between :from and :to
             group by b.open_date
            """.trimIndent(),
        ).param("u", userId).param("from", from).param("to", to)
            .query { rs, _ -> rs.getObject("open_date", LocalDate::class.java) to rs.getInt("cnt") }.list().toMap()
        val result = LinkedHashMap<LocalDate, Int>()
        var d = from
        while (!d.isAfter(to)) { result[d] = rows[d] ?: 0; d = d.plusDays(1) }
        return result
    }

    /** 이번 주(월~일) 7칸. 그 과목 Bit 를 푼 날이 1 */
    private fun week(userId: Long, courseId: Long, date: LocalDate): List<Int> {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val solved = jdbc.sql(
            """
            select b.open_date from bit_attempts a join bits b on b.id = a.bit_id
             where a.user_id = :u and b.course_id = :c and a.submitted_at is not null
               and b.open_date between :from and :to
            """.trimIndent(),
        ).param("u", userId).param("c", courseId).param("from", monday).param("to", monday.plusDays(6))
            .query { rs, _ -> rs.getObject("open_date", LocalDate::class.java) }.list().toSet()
        return (0..6).map { if (monday.plusDays(it.toLong()) in solved) 1 else 0 }
    }

    /** 열려 있는 Bit 만. 멤버가 아니면 404, 아직 안 열렸으면 404, 마감됐으면 409 */
    private fun openBit(userId: Long, bitId: Long): BitRow {
        val bit = bit(bitId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        courses.membership(userId, bit.courseId)
        val now = now().toOffsetDateTime()
        if (now.isBefore(bit.openAt)) throw ApiException(ErrorCode.NOT_FOUND)
        if (now.isAfter(bit.closeAt)) throw ApiException(ErrorCode.BIT_CLOSED)
        return bit
    }

    private data class BitRow(
        val id: Long,
        val courseId: Long,
        val courseName: String,
        val no: Int,
        val openDate: LocalDate,
        val openAt: OffsetDateTime,
        val closeAt: OffsetDateTime,
        val stem: String,
        val choices: List<String>,
        val answer: Int,
        val explanation: String,
        val evidence: Evidence,
    )

    private fun bit(bitId: Long): BitRow? =
        jdbc.sql(
            """
            select b.id, b.course_id, c.name as course_name, b.no, b.open_date, b.open_at, b.close_at,
                   q.stem, q.choices::text as choices, q.answer_idx, q.explanation, q.evidence_file_id, q.evidence_page,
                   coalesce(f.title, '') as file_title
              from bits b
              join courses c on c.id = b.course_id
              join questions q on q.id = b.question_id
              left join files f on f.id = q.evidence_file_id
             where b.id = :id
            """.trimIndent(),
        ).param("id", bitId)
            .query { rs, _ ->
                BitRow(
                    id = rs.getLong("id"),
                    courseId = rs.getLong("course_id"),
                    courseName = rs.getString("course_name"),
                    no = rs.getInt("no"),
                    openDate = rs.getObject("open_date", LocalDate::class.java),
                    openAt = rs.getObject("open_at", OffsetDateTime::class.java),
                    closeAt = rs.getObject("close_at", OffsetDateTime::class.java),
                    stem = rs.getString("stem"),
                    choices = json.readTree(rs.getString("choices")).toList().map { it.asString() },
                    answer = rs.getInt("answer_idx"),
                    explanation = rs.getString("explanation"),
                    evidence = Evidence(
                        fileId = rs.getObject("evidence_file_id")?.let { (it as Number).toLong() },
                        file = rs.getString("file_title"),
                        page = rs.getInt("evidence_page"),
                    ),
                )
            }.optional().orElse(null)

    private data class AttemptRow(
        val startedAt: OffsetDateTime,
        val submittedAt: OffsetDateTime?,
        val choice: Int?,
        val correct: Boolean?,
        val elapsedSec: Int?,
        val score: Int?,
    )

    private fun attempt(bitId: Long, userId: Long, lock: Boolean = false): AttemptRow? =
        jdbc.sql("select * from bit_attempts where bit_id = :b and user_id = :u" + if (lock) " for update" else "")
            .param("b", bitId).param("u", userId)
            .query { rs, _ ->
                AttemptRow(
                    startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                    submittedAt = rs.getObject("submitted_at", OffsetDateTime::class.java),
                    choice = rs.getObject("choice_idx")?.let { (it as Number).toInt() },
                    correct = rs.getObject("correct") as Boolean?,
                    elapsedSec = rs.getObject("elapsed_sec")?.let { (it as Number).toInt() },
                    score = rs.getObject("score")?.let { (it as Number).toInt() },
                )
            }.optional().orElse(null)

    companion object {
        private const val GRID_DAYS = 35   // P-BIT-12 5주 격자
    }
}
