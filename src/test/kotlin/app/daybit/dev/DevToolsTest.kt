package app.daybit.dev

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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Import(TestcontainersConfiguration::class, MutableClockConfig::class)
@SpringBootTest(properties = ["daybit.dev-tools=true", "daybit.dev-admins=devadmin50, other"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevToolsTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val clock: MutableClock,
    @Autowired val batch: app.daybit.bit.BitBatchService,
) {
    private val f = Fixtures(mvc, json, jdbc)
    private val day: LocalDate = LocalDate.of(2026, 12, 1)

    @Test
    fun `17시 전에도 바로 열어서 풀고, 기록을 지우면 다시 풀 수 있어요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val courseId = courseWithQuestions(admin)
        f.join(courseId, member)
        clock.set(LocalDateTime.of(day, LocalTime.of(15, 0)))

        // 17시 전이라 지금 Bit 날짜는 어제예요. 평소라면 이 과목은 17시까지 Bit 가 없어요
        val body = mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", admin.bearer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.openDate") { value(day.minusDays(1).toString()) }
                jsonPath("$.created") { value(true) }
            }.andReturn().response.contentAsString
        val bitId = json.readTree(body).path("bitId").asLong()
        val today = json.readTree(mvc.get("/api/v1/bits/today") { header("Authorization", member.bearer) }.andReturn().response.contentAsString)
        assertThat(today.toList().map { it.path("courseId").asLong() to it.path("status").asString() }).contains(courseId to "open")

        solve(member, bitId)
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", member.bearer) }.andExpect { status { isConflict() } }

        // 다시 불러도 새로 만들지 않아요
        mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$.bitId") { value(bitId) }; jsonPath("$.created") { value(false) } }

        mvc.post("/api/v1/dev/bits/$bitId/reset-me") { header("Authorization", member.bearer) }.andExpect { status { isNoContent() } }
        solve(member, bitId)
    }

    @Test
    fun `여는 건 관리자 · 공동 관리자만, 멤버가 아니면 404, 낼 문제가 없으면 409`() {
        val admin = f.newUser()
        val member = f.newUser()
        val stranger = f.newUser()
        val courseId = courseWithQuestions(admin)
        f.join(courseId, member)
        clock.set(LocalDateTime.of(day, LocalTime.of(18, 0)))

        mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", member.bearer) }
            .andExpect { status { isForbidden() } }
        mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", stranger.bearer) }
            .andExpect { status { isNotFound() } }

        val bitId = json.readTree(
            mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", admin.bearer) }
                .andExpect { jsonPath("$.openDate") { value(day.toString()) } }.andReturn().response.contentAsString,
        ).path("bitId").asLong()
        mvc.post("/api/v1/dev/bits/$bitId/reset-me") { header("Authorization", stranger.bearer) }
            .andExpect { status { isNotFound() } }

        val empty = f.createCourse(admin).first
        mvc.post("/api/v1/dev/courses/$empty/bit/open-now") { header("Authorization", admin.bearer) }
            .andExpect { status { isConflict() }; jsonPath("$.error.code") { value("NO_QUESTION_READY") } }
    }

    @Test
    fun `지우면 아카이브에서 사라지고 문제를 다시 낼 수 있어요, 멤버는 못 지워요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val courseId = courseWithQuestions(admin)
        f.join(courseId, member)
        clock.set(LocalDateTime.of(day, LocalTime.of(15, 0)))
        val bitId = json.readTree(
            mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", admin.bearer) }.andReturn().response.contentAsString,
        ).path("bitId").asLong()
        solve(member, bitId)
        // 17:00 배치로 마감 · 아카이브된 뒤에 메모까지 남겨요
        clock.set(LocalDateTime.of(day, LocalTime.of(17, 0)))
        batch.closeDue(java.time.OffsetDateTime.now(clock))
        mvc.get("/api/v1/courses/$courseId/archive") { header("Authorization", member.bearer) }
            .andExpect { jsonPath("$.items[?(@.bitId == $bitId)]") { isNotEmpty() } }
        mvc.post("/api/v1/archive/$bitId/memos") {
            header("Authorization", member.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"body":"메모"}"""
        }.andExpect { status { isCreated() } }

        mvc.delete("/api/v1/dev/bits/$bitId") { header("Authorization", member.bearer) }.andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/dev/bits/$bitId") { header("Authorization", admin.bearer) }.andExpect { status { isNoContent() } }

        assertThat(count("select count(*) from bits where id = :id", bitId)).isZero()
        assertThat(count("select count(*) from bit_attempts where bit_id = :id", bitId)).isZero()
        assertThat(count("select count(*) from memos where bit_id = :id", bitId)).isZero()
        mvc.get("/api/v1/courses/$courseId/archive") { header("Authorization", member.bearer) }
            .andExpect { jsonPath("$.items[?(@.bitId == $bitId)]") { isEmpty() } }
        mvc.delete("/api/v1/dev/bits/$bitId") { header("Authorization", admin.bearer) }.andExpect { status { isNotFound() } }

        // 하나뿐인 문제가 돌아와서 다시 열 수 있어요
        mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", admin.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.created") { value(true) } }
    }

    @Test
    fun `개발자 계정은 멤버가 아닌 과목도 열고 지우고 다시 풀 수 있어요`() {
        val admin = f.newUser()
        val courseId = courseWithQuestions(admin)
        val dev = devAdmin()
        clock.set(LocalDateTime.of(day, LocalTime.of(15, 0)))

        val bitId = json.readTree(
            mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", dev.bearer) }
                .andExpect { status { isOk() }; jsonPath("$.created") { value(true) } }.andReturn().response.contentAsString,
        ).path("bitId").asLong()
        mvc.post("/api/v1/dev/bits/$bitId/reset-me") { header("Authorization", dev.bearer) }.andExpect { status { isNoContent() } }
        mvc.delete("/api/v1/dev/bits/$bitId") { header("Authorization", dev.bearer) }.andExpect { status { isNoContent() } }
        assertThat(count("select count(*) from bits where id = :id", bitId)).isZero()

        // 없는 과목은 404, 일반 API 권한은 그대로예요
        mvc.post("/api/v1/dev/courses/999999999/bit/open-now") { header("Authorization", dev.bearer) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/courses/$courseId") { header("Authorization", dev.bearer) }.andExpect { status { isNotFound() } }
    }

    /** daybit.dev-admins 에 적은 아이디로 가입해요. 테스트끼리 DB 를 같이 써서 한 번만 가입해요 */
    private fun devAdmin(): TestUser {
        val login = { mvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = DEV_LOGIN } }
        val res = login().andReturn().response
        val body = if (res.status == 200) res.contentAsString else
            mvc.post("/api/v1/auth/signup") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"loginId":"devadmin50","password":"password1","nickname":"개발자50"}"""
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val tree = json.readTree(body)
        val id = tree.path("user").path("id").asLong()
        return TestUser(id, tree.path("tokens").path("accessToken").asString(), "개발자50")
    }

    private fun count(sql: String, id: Long) = jdbc.sql(sql).param("id", id).query(Int::class.java).single()

    private fun courseWithQuestions(admin: TestUser): Long {
        val courseId = f.createCourse(admin).first
        val fileId = f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.")
        jdbc.sql(
            """
            insert into questions (course_id, scope, stem, choices, answer_idx, explanation, evidence_file_id, evidence_page, source, verified)
            values (:c, 'mid', '문제', '["A","B","C","D"]'::jsonb, 1, '해설', :f, 1, 'gemini', true)
            """.trimIndent(),
        ).param("c", courseId).param("f", fileId).update()
        return courseId
    }

    private fun solve(user: TestUser, bitId: Long) {
        mvc.post("/api/v1/bits/$bitId/start") { header("Authorization", user.bearer) }.andExpect { status { isOk() } }
        mvc.post("/api/v1/bits/$bitId/submit") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"choice":1}"""
        }.andExpect { status { isOk() }; jsonPath("$.correct") { value(true) } }
    }

    companion object {
        private const val DEV_LOGIN = """{"loginId":"devadmin50","password":"password1"}"""
    }
}
