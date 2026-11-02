package app.daybit.course

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    @Test
    fun `과목을 만들면 코드 · 관리자 · 기본 폴더가 생겨요`() {
        val admin = f.newUser()
        val (id, code) = f.createCourse(admin)

        assertThat(code).matches("^[A-Z0-9]{6}$")
        val folders = jdbc.sql("select kind from folders where course_id = :c order by sort").param("c", id)
            .query(String::class.java).list()
        assertThat(folders).containsExactly("mid", "final")

        mvc.get("/api/v1/courses/mine") { header("Authorization", admin.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$[0].courseId") { value(id) }
            jsonPath("$[0].name") { value("운영체제") }
        }
        mvc.get("/api/v1/courses/$id") { header("Authorization", admin.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.role") { value("admin") }
            jsonPath("$.subscribers") { value(1) }
            jsonPath("$.archiveCount") { value(0) }
            jsonPath("$.state") { value("active") }
        }
    }

    @Test
    fun `기말이 중간보다 앞서거나 연결 안 된 AI 를 고르면 400`() {
        val admin = f.newUser()
        mvc.post("/api/v1/courses") {
            header("Authorization", admin.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"a","icon":"book","visibility":"public","midExamDate":"2026-12-10","finalExamDate":"2026-12-01"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("VALIDATION_FAILED") } }

        mvc.post("/api/v1/courses") {
            header("Authorization", admin.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"a","icon":"book","visibility":"public","quizSource":{"type":"workspace","ai":"codex"}}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("QUIZ_SOURCE_UNAVAILABLE") } }
    }

    @Test
    fun `대기 중이거나 남의 과목은 없는 과목처럼 404, 강의실에도 안 보여요`() {
        val admin = f.newUser()
        val stranger = f.newUser()
        val waiting = f.newUser()
        val (id, _) = f.createCourse(admin, visibility = "private")
        f.join(id, waiting, status = "pending")

        mvc.get("/api/v1/courses/$id") { header("Authorization", stranger.bearer) }
            .andExpect { status { isNotFound() } }
        mvc.get("/api/v1/courses/$id") { header("Authorization", waiting.bearer) }
            .andExpect { status { isNotFound() } }
        mvc.get("/api/v1/courses/mine") { header("Authorization", waiting.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }
        mvc.get("/api/v1/courses/999999") { header("Authorization", admin.bearer) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `멤버는 설정에서 코드 · 권한 · 알림만 보고 바꿀 수 없어요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val (id, code) = f.createCourse(admin)
        f.join(id, member)

        mvc.get("/api/v1/courses/$id/settings") { header("Authorization", member.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(code) }
            jsonPath("$.role") { value("member") }
            jsonPath("$.notify") { value(true) }
            jsonPath("$.visibility") { doesNotExist() }
        }
        mvc.get("/api/v1/courses/$id/settings") { header("Authorization", admin.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.visibility") { value("public") }
            jsonPath("$.quizSource.type") { value("default") }
        }
        mvc.patch("/api/v1/courses/$id") {
            header("Authorization", member.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"icon":"star"}"""
        }.andExpect { status { isForbidden() } }

        mvc.patch("/api/v1/courses/$id/subscription") {
            header("Authorization", member.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"notify":false}"""
        }.andExpect { status { isNoContent() } }
        mvc.get("/api/v1/courses/$id/settings") { header("Authorization", member.bearer) }
            .andExpect { jsonPath("$.notify") { value(false) } }
    }

    @Test
    fun `비공개를 공개로 바꾸면 대기 중인 요청이 승인되고 알림이 쌓여요`() {
        val admin = f.newUser()
        val waiting = f.newUser()
        val (id, _) = f.createCourse(admin, visibility = "private")
        f.join(id, waiting, status = "pending")

        mvc.patch("/api/v1/courses/$id") {
            header("Authorization", admin.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"visibility":"public","intro":"바뀐 소개","midExamDate":"2026-10-20"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.visibility") { value("public") }
            jsonPath("$.intro") { value("바뀐 소개") }
            jsonPath("$.midExamDate") { value("2026-10-20") }
        }

        mvc.get("/api/v1/courses/$id") { header("Authorization", waiting.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.role") { value("member") } }
        assertThat(f.notificationTypes(waiting)).containsExactly("join_approved")
    }

    @Test
    fun `관리자가 나가면 가장 먼저 공동 관리자가 된 사람이 승격돼요`() {
        val admin = f.newUser()
        val olderCo = f.newUser()
        val newerCo = f.newUser()
        val (id, _) = f.createCourse(admin)
        f.join(id, newerCo, role = "co", coSinceDaysAgo = 1)
        f.join(id, olderCo, role = "co", coSinceDaysAgo = 5)

        mvc.post("/api/v1/courses/$id/leave") { header("Authorization", admin.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.result") { value("promoted") }
            jsonPath("$.newAdmin") { value(olderCo.nickname) }
        }
        mvc.get("/api/v1/courses/$id") { header("Authorization", olderCo.bearer) }
            .andExpect { jsonPath("$.role") { value("admin") } }
        mvc.get("/api/v1/courses/$id") { header("Authorization", admin.bearer) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `공동 관리자 없이 관리자가 나가면 과목이 지워지고, 멤버는 그냥 나가요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val (id, _) = f.createCourse(admin)
        f.join(id, member)

        mvc.post("/api/v1/courses/$id/leave") { header("Authorization", member.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.result") { value("left") } }
        mvc.post("/api/v1/courses/$id/leave") { header("Authorization", admin.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.result") { value("deleted") } }

        val left = jdbc.sql("select count(*) from courses where id = :c").param("c", id).query(Int::class.java).single()
        assertThat(left).isZero()
    }

    @Test
    fun `종료된 과목은 지난 강의에만 나와요`() {
        val admin = f.newUser()
        val (id, _) = f.createCourse(admin)
        jdbc.sql("update courses set state = 'ended', ended_reason = 'final_exam', ended_at = now() where id = :c")
            .param("c", id).update()

        mvc.get("/api/v1/courses/mine") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$.length()") { value(0) } }
        mvc.get("/api/v1/courses/mine?state=ended") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$[0].courseId") { value(id) } }
    }
}
