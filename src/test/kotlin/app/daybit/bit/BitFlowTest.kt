package app.daybit.bit

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
import app.daybit.support.MutableClock
import app.daybit.support.MutableClockConfig
import app.daybit.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Import(TestcontainersConfiguration::class, MutableClockConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BitFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val clock: MutableClock,
    @Autowired val batch: BitBatchService,
) {
    private val f = Fixtures(mvc, json, jdbc)
    private val day: LocalDate = LocalDate.of(2026, 11, 10)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = clock.set(LocalDateTime.of(date, LocalTime.of(h, m)))
    private fun seoul(date: LocalDate, h: Int) = date.atTime(h, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime()

    /** 16:30 준비 → 17:00 열기 */
    private fun openDay(date: LocalDate) {
        at(date, 16, 30); batch.prepare(date, seoul(date, 16).plusMinutes(30))
        at(date, 17); batch.open(date, seoul(date, 17))
    }

    @Test
    fun `16시 30분에 고른 문제가 17시에 열리고, 한 번만 풀 수 있어요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val quiet = f.newUser()
        val courseId = courseWithQuestions(admin, 3)
        f.join(courseId, member)
        f.join(courseId, quiet)
        jdbc.sql("update course_members set notify = false where course_id = :c and user_id = :u").param("c", courseId).param("u", quiet.id).update()

        at(day, 16, 30)
        assertThat(batch.prepare(day, seoul(day, 16).plusMinutes(30))).isGreaterThanOrEqualTo(1)
        // 17:00 전에는 오늘 티켓이 없어요
        assertThat(tickets(member)).noneMatch { it.first == courseId }

        at(day, 17)
        batch.open(day, seoul(day, 17))
        val bitId = bitOf(courseId, day)
        assertThat(tickets(member)).contains(courseId to "open")
        // P-BIT-05: 알림을 켠 구독자에게만
        assertThat(f.notificationTypes(member)).containsExactly("bit_open")
        assertThat(f.notificationTypes(quiet)).isEmpty()

        // P-BIT-08: 문제만 내려주고 정답 · 해설은 없어요
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", member.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.question.choices.length()") { value(4) }
            jsonPath("$.answer") { doesNotExist() }
        }
        clock.advance(Duration.ofSeconds(30))
        val answer = answerOf(bitId)
        submit(member, bitId, answer).andExpect {
            status { isOk() }
            jsonPath("$.correct") { value(true) }
            jsonPath("$.elapsedSec") { value(30) }
            jsonPath("$.score") { value(970) }
            jsonPath("$.topPercent") { value(1) }
            jsonPath("$.courseCode") { isString() }
            jsonPath("$.concept") { doesNotExist() }   // 이 테스트의 문제는 개념 없이 넣었다
            jsonPath("$.streak") { value(1) }
            jsonPath("$.evidence.page") { value(1) }
        }
        submit(member, bitId, answer).andExpect { status { isConflict() }; jsonPath("$.error.code") { value("ALREADY_ATTEMPTED") } }
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", member.bearer) }.andExpect { status { isConflict() } }
        mvc.get("/api/v1/bits/$bitId/ticket") { header("Authorization", member.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.score") { value(970) } }

        assertThat(tickets(member)).contains(courseId to "done")
    }

    @Test
    fun `시작하지 않고 제출하면 400, 멤버가 아니면 404, 순위는 점수와 제출 순서로 정해요`() {
        val admin = f.newUser()
        val fast = f.newUser()
        val slow = f.newUser()
        val stranger = f.newUser()
        val courseId = courseWithQuestions(admin, 2)
        f.join(courseId, fast)
        f.join(courseId, slow)
        openDay(day)
        val bitId = bitOf(courseId, day)
        val answer = answerOf(bitId)

        submit(fast, bitId, answer).andExpect { status { isBadRequest() } }
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", stranger.bearer) }.andExpect { status { isNotFound() } }

        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", slow.bearer) }
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", fast.bearer) }
        clock.advance(Duration.ofSeconds(10))
        submit(fast, bitId, answer).andExpect { jsonPath("$.topPercent") { value(1) } }
        clock.advance(Duration.ofSeconds(400))
        // 오답 + 300초 상한 → 0 − 300
        submit(slow, bitId, (answer + 1) % 4).andExpect {
            jsonPath("$.correct") { value(false) }
            jsonPath("$.score") { value(-300) }
            jsonPath("$.topPercent") { value(50) }
            jsonPath("$.averageSec") { value(210) }   // (10 + 410) / 2
        }

        mvc.get("/api/v1/courses/$courseId/records?day=today") { header("Authorization", fast.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.bitNo") { value(1) }
            jsonPath("$.people[0].userId") { value(fast.id) }
            jsonPath("$.people[1].userId") { value(slow.id) }
            jsonPath("$.people[0].me") { value(true) }
            jsonPath("$.explanation.answer") { value(answer) }
        }
        // 아직 안 푼 사람에게는 해설을 주지 않아요 (P-BIT-09)
        mvc.get("/api/v1/courses/$courseId/records?day=today") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$.explanation") { doesNotExist() } }
    }

    @Test
    fun `다음 날 17시에 마감 · 집계 · 아카이브되고, 마감된 Bit 는 409, 어제 해설은 모두에게 보여요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val courseId = courseWithQuestions(admin, 3)
        f.join(courseId, member)
        openDay(day)
        val bitId = bitOf(courseId, day)
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", member.bearer) }
        clock.advance(Duration.ofSeconds(20))
        submit(member, bitId, answerOf(bitId))

        val next = day.plusDays(1)
        at(next, 16, 59)
        assertThat(archivedAt(bitId)).isNull()
        openDay(next)
        assertThat(archivedAt(bitId)).isNotNull()
        val stats = jdbc.sql("select attempts, accuracy::float, average_sec from bits where id = :id").param("id", bitId)
            .query { rs, _ -> Triple(rs.getInt(1), rs.getDouble(2), rs.getInt(3)) }.single()
        assertThat(stats).isEqualTo(Triple(1, 100.0, 20))
        assertThat(bitOf(courseId, next)).isNotEqualTo(bitId)

        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", admin.bearer) }
            .andExpect { status { isConflict() }; jsonPath("$.error.code") { value("BIT_CLOSED") } }
        mvc.get("/api/v1/courses/$courseId/records?day=yesterday") { header("Authorization", admin.bearer) }.andExpect {
            jsonPath("$.bitNo") { value(1) }
            jsonPath("$.explanation.myChoice") { doesNotExist() }
            jsonPath("$.explanation.text") { isNotEmpty() }
        }
        mvc.get("/api/v1/courses/$courseId/records?day=today") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$.bitNo") { value(2) } }
    }

    @Test
    fun `범위에 처리된 자료가 없으면 건너뛰고, 중간 시험일 다음 날부터 final 에서 내요`() {
        val admin = f.newUser()
        val noMaterial = f.createCourse(admin).first
        val midPassed = courseWithQuestions(admin, 2, extra = ""","midExamDate":"${day.minusDays(1)}"""")

        at(day, 16, 30)
        batch.prepare(day, seoul(day, 16).plusMinutes(30))
        assertThat(bitIdOrNull(noMaterial, day)).isNull()
        // 중간 시험이 끝났는데 final 폴더에 자료가 없어요
        assertThat(bitIdOrNull(midPassed, day)).isNull()

        val fileId = f.uploadReady(admin, midPassed, "Final scope material", folder = "final")
        insertQuestions(midPassed, fileId, "final", 1)
        batch.prepare(day, seoul(day, 16).plusMinutes(31))
        assertThat(bitIdOrNull(midPassed, day)).isNotNull()
        assertThat(jdbc.sql("select scope from courses where id = :id").param("id", midPassed).query(String::class.java).single())
            .isEqualTo("final")
    }

    @Test
    fun `최근 3일 안에 낸 개념은 피하고 오답률 높은 개념을 먼저 골라요`() {
        val admin = f.newUser()
        val courseId = f.createCourse(admin).first
        val fileId = f.uploadReady(admin, courseId, "Concept text")
        val recent = concept(courseId, fileId, "Recent", lastUsedDaysAgo = 1, wrongRate = 0.9)
        val easy = concept(courseId, fileId, "Easy", lastUsedDaysAgo = null, wrongRate = 0.1)
        val hard = concept(courseId, fileId, "Hard", lastUsedDaysAgo = 10, wrongRate = 0.8)
        insertQuestions(courseId, fileId, "mid", 1, conceptId = recent)
        insertQuestions(courseId, fileId, "mid", 1, conceptId = easy)
        val expected = insertQuestions(courseId, fileId, "mid", 1, conceptId = hard).single()

        val now = seoul(day, 16)
        assertThat(batch.pickQuestion(courseId, "mid", now)).isEqualTo(expected)
    }

    @Test
    fun `연속 일수와 학습 기록 격자는 과목 구분 없이 세고 프로필에도 보여요`() {
        val admin = f.newUser()
        val user = f.newUser()
        val other = f.newUser()
        val a = courseWithQuestions(admin, 3)
        val b = courseWithQuestions(admin, 3, name = "자료구조")
        f.join(a, user); f.join(b, user)

        openDay(day)
        solve(user, bitOf(a, day)); solve(user, bitOf(b, day))
        val next = day.plusDays(1)
        openDay(next)
        solve(user, bitOf(a, next))

        mvc.get("/api/v1/me/streak?weeks=1") { header("Authorization", user.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.current") { value(2) }
            jsonPath("$.best") { value(2) }
            jsonPath("$.thisMonth") { value(2) }
            jsonPath("$.days.length()") { value(7) }
            jsonPath("$.days[5].count") { value(2) }
            jsonPath("$.days[6].count") { value(1) }
        }
        // 과목 a 는 이틀 연속, b 는 하루
        mvc.get("/api/v1/courses/$a/records") { header("Authorization", user.bearer) }
            .andExpect { jsonPath("$.mine.streak") { value(2) }; jsonPath("$.mine.accuracy") { value(100) }; jsonPath("$.mine.grid.length()") { value(35) } }
        // 다른 사람도 프로필에서 격자를 봐요. 과목 정보는 없어요 (P-SOC-02)
        mvc.get("/api/v1/users/${user.id}/profile") { header("Authorization", other.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.nickname") { value(user.nickname) }
            jsonPath("$.streak.current") { value(2) }
            jsonPath("$.streak.days[0].courseId") { doesNotExist() }
        }
        mvc.get("/api/v1/users/999999/profile") { header("Authorization", other.bearer) }.andExpect { status { isNotFound() } }

        mvc.get("/api/v1/me/welcome") { header("Authorization", user.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.bitsLeft") { value(1) }     // 과목 b 의 오늘 Bit
            jsonPath("$.streak") { value(2) }
        }
    }

    @Test
    fun `기말 시험일 다음 날, 2주 동안 업로드 · 응시가 없으면 과목이 종료돼요`() {
        val admin = f.newUser()
        val finalDone = f.createCourse(admin, extra = ""","finalExamDate":"${day.minusDays(1)}"""").first
        val idle = f.createCourse(admin).first
        val busy = courseWithQuestions(admin, 2)
        jdbc.sql("update courses set created_at = :t, last_upload_at = null where id = :id")
            .param("t", seoul(day, 0).minusDays(20)).param("id", idle).update()
        jdbc.sql("update courses set created_at = :t, last_upload_at = :t where id = :id")
            .param("t", seoul(day, 0).minusDays(20)).param("id", busy).update()
        // busy 는 최근 2주 안에 누군가 풀었어요
        openDay(day.minusDays(3))
        solve(admin, bitOf(busy, day.minusDays(3)))

        at(day, 0, 10)
        batch.dailyCheck(day, seoul(day, 0).plusMinutes(10))
        assertThat(stateOf(finalDone)).isEqualTo("ended" to "final_exam")
        assertThat(stateOf(idle)).isEqualTo("ended" to "inactive")
        assertThat(stateOf(busy)).isEqualTo("active" to null)
    }

    // ---------- 도우미 ----------

    private fun courseWithQuestions(admin: TestUser, count: Int, extra: String = "", name: String = "운영체제"): Long {
        val courseId = f.createCourse(admin, extra = extra, name = name).first
        val fileId = f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.")
        insertQuestions(courseId, fileId, "mid", count)
        return courseId
    }

    private fun insertQuestions(courseId: Long, fileId: Long, scope: String, count: Int, conceptId: Long? = null): List<Long> =
        (1..count).map { i ->
            jdbc.sql(
                """
                insert into questions (course_id, concept_id, scope, stem, choices, answer_idx, explanation, evidence_file_id, evidence_page, source, verified)
                values (:c, :concept, :scope, :stem, '["A","B","C","D"]'::jsonb, :answer, '해설', :f, 1, 'gemini', true)
                returning id
                """.trimIndent(),
            ).param("c", courseId).param("concept", conceptId).param("scope", scope).param("stem", "문제 $i")
                .param("answer", i % 4).param("f", fileId).query(Long::class.java).single()
        }

    private fun concept(courseId: Long, fileId: Long, name: String, lastUsedDaysAgo: Int?, wrongRate: Double): Long =
        jdbc.sql(
            """
            insert into concepts (course_id, file_id, name, wrong_rate, last_used_at)
            values (:c, :f, :name, :rate, :last) returning id
            """.trimIndent(),
        ).param("c", courseId).param("f", fileId).param("name", name).param("rate", wrongRate)
            .param("last", lastUsedDaysAgo?.let { seoul(day, 16).minusDays(it.toLong()) })
            .query(Long::class.java).single()

    private fun submit(user: TestUser, bitId: Long, choice: Int) =
        mvc.post("/api/v1/bits/$bitId/submit") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"choice":$choice}"""
        }

    private fun solve(user: TestUser, bitId: Long) {
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", user.bearer) }.andExpect { status { isOk() } }
        submit(user, bitId, answerOf(bitId)).andExpect { status { isOk() } }
    }

    private fun tickets(user: TestUser): List<Pair<Long, String>> {
        val body = mvc.get("/api/v1/bits/today") { header("Authorization", user.bearer) }.andReturn().response.contentAsString
        return json.readTree(body).toList().map { it.path("courseId").asLong() to it.path("status").asString() }
    }

    private fun bitIdOrNull(courseId: Long, date: LocalDate): Long? =
        jdbc.sql("select id from bits where course_id = :c and open_date = :d").param("c", courseId).param("d", date)
            .query(Long::class.java).optional().orElse(null)

    private fun bitOf(courseId: Long, date: LocalDate) = bitIdOrNull(courseId, date) ?: error("Bit 가 없어요: $courseId $date")

    private fun answerOf(bitId: Long) =
        jdbc.sql("select q.answer_idx from bits b join questions q on q.id = b.question_id where b.id = :id").param("id", bitId)
            .query(Int::class.java).single()

    private fun archivedAt(bitId: Long) =
        jdbc.sql("select archived_at from bits where id = :id").param("id", bitId)
            .query { rs, _ -> rs.getObject("archived_at") }.list().first()

    private fun stateOf(courseId: Long) =
        jdbc.sql("select state, ended_reason from courses where id = :id").param("id", courseId)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
}
