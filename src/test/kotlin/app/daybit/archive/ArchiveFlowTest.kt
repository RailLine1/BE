package app.daybit.archive

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArchiveFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    @Test
    fun `기록은 마감된 Bit 만 최근 것부터 보여 주고, 통계와 메모 수를 줘요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val stranger = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, member)
        val first = bit(courseId, 1, archived = true)
        val second = bit(courseId, 2, archived = true)
        bit(courseId, 3, archived = false)
        addMemo(member, second, "외워 두기").andExpect { status { isCreated() } }

        mvc.get("/api/v1/courses/$courseId/archive?page=1&size=1") { header("Authorization", member.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].bitId") { value(second) }
            jsonPath("$.items[0].answer") { value(2) }
            jsonPath("$.items[0].stats.attempts") { value(5) }
            jsonPath("$.items[0].stats.accuracy") { value(60.0) }
            jsonPath("$.items[0].memoCount") { value(1) }
            jsonPath("$.items[0].choices.length()") { value(4) }
        }
        mvc.get("/api/v1/courses/$courseId/archive?page=2&size=1") { header("Authorization", member.bearer) }
            .andExpect { jsonPath("$.items[0].bitId") { value(first) } }
        mvc.get("/api/v1/courses/$courseId/archive") { header("Authorization", stranger.bearer) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `메모는 작성자 없이 보이고, 수정은 작성자만, 삭제는 작성자와 관리자 · 공동 관리자가 해요`() {
        val admin = f.newUser()
        val co = f.newUser()
        val writer = f.newUser()
        val other = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, co, role = "co", coSinceDaysAgo = 1)
        f.join(courseId, writer)
        f.join(courseId, other)
        val bitId = bit(courseId, 1, archived = true)

        val memoId = addMemo(writer, bitId, "  페이지 12 다시 보기  ").andExpect {
            status { isCreated() }
            jsonPath("$.body") { value("페이지 12 다시 보기") }
            jsonPath("$.mine") { value(true) }
        }.andReturn().response.contentAsString.let { json.readTree(it).path("memoId").asLong() }
        val second = addMemo(other, bitId, "두 번째").andReturn().response.contentAsString.let { json.readTree(it).path("memoId").asLong() }

        mvc.get("/api/v1/archive/$bitId/memos") { header("Authorization", writer.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].mine") { value(true) }
            jsonPath("$[1].mine") { value(false) }
            jsonPath("$[0].userId") { doesNotExist() }
            jsonPath("$[0].nickname") { doesNotExist() }
        }

        editMemo(other, memoId, "남의 메모").andExpect { status { isForbidden() } }
        editMemo(admin, memoId, "관리자도 수정은 못 해요").andExpect { status { isForbidden() } }
        editMemo(writer, memoId, "고친 메모").andExpect { status { isOk() }; jsonPath("$.body") { value("고친 메모") } }

        mvc.delete("/api/v1/memos/$memoId") { header("Authorization", other.bearer) }.andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/memos/$memoId") { header("Authorization", co.bearer) }.andExpect { status { isNoContent() } }
        mvc.delete("/api/v1/memos/$second") { header("Authorization", other.bearer) }.andExpect { status { isNoContent() } }
        // 지운 메모는 목록에서 빠지고 다시 다룰 수 없어요
        mvc.delete("/api/v1/memos/$memoId") { header("Authorization", writer.bearer) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/archive/$bitId/memos") { header("Authorization", writer.bearer) }
            .andExpect { jsonPath("$.length()") { value(0) } }

        val deletedBy = jdbc.sql("select deleted_by from memos where id = :id").param("id", memoId).query(Long::class.java).single()
        assertThat(deletedBy).isEqualTo(co.id)
    }

    @Test
    fun `마감 전 Bit 와 멤버가 아닌 사람에게는 메모가 404 이고, 빈 메모는 400 이에요`() {
        val admin = f.newUser()
        val stranger = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val open = bit(courseId, 1, archived = false)
        val closed = bit(courseId, 2, archived = true)

        addMemo(admin, open, "아직 열린 문제").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/archive/$open/memos") { header("Authorization", admin.bearer) }.andExpect { status { isNotFound() } }
        addMemo(stranger, closed, "남의 과목").andExpect { status { isNotFound() } }
        addMemo(admin, closed, "   ").andExpect { status { isBadRequest() } }
        addMemo(admin, closed, "가".repeat(501)).andExpect { status { isBadRequest() } }
    }

    // ---------- 도우미 ----------

    /** 문제와 Bit 를 DB 에 바로 넣어요. 마감된 Bit 는 응시 5명 · 정답률 60% 로 둬요 */
    private fun bit(courseId: Long, no: Int, archived: Boolean): Long {
        val questionId = jdbc.sql(
            """
            insert into questions (course_id, scope, stem, choices, answer_idx, explanation, evidence_page, source, verified, used_at)
            values (:c, 'mid', :stem, '["A","B","C","D"]'::jsonb, 2, '해설', 3, 'gemini', true, now())
            returning id
            """.trimIndent(),
        ).param("c", courseId).param("stem", "문제 $no").query(Long::class.java).single()
        val date = LocalDate.of(2026, 10, 1).plusDays(no.toLong())
        return jdbc.sql(
            """
            insert into bits (course_id, no, question_id, open_date, open_at, close_at, archived_at, attempts, accuracy, average_sec)
            values (:c, :no, :q, :d, now(), now(), case when :archived then now() end,
                    case when :archived then 5 end, case when :archived then 60.0 end, case when :archived then 42 end)
            returning id
            """.trimIndent(),
        ).param("c", courseId).param("no", no).param("q", questionId).param("d", date).param("archived", archived)
            .query(Long::class.java).single()
    }

    private fun addMemo(user: TestUser, bitId: Long, body: String) =
        mvc.post("/api/v1/archive/$bitId/memos") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("body" to body))
        }

    private fun editMemo(user: TestUser, memoId: Long, body: String) =
        mvc.patch("/api/v1/memos/$memoId") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("body" to body))
        }
}
