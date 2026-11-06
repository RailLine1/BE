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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    private fun changeRole(by: String, courseId: Long, targetId: Long, role: String) =
        mvc.patch("/api/v1/courses/$courseId/members/$targetId") {
            header("Authorization", by)
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"$role"}"""
        }

    @Test
    fun `멤버 목록은 관리자 → 공동 관리자(날짜순) → 멤버 순이고 멤버는 못 봐요`() {
        val admin = f.newUser()
        val newerCo = f.newUser()
        val olderCo = f.newUser()
        val member = f.newUser()
        val (id, _) = f.createCourse(admin)
        f.join(id, member)
        f.join(id, newerCo, role = "co", coSinceDaysAgo = 1)
        f.join(id, olderCo, role = "co", coSinceDaysAgo = 3)

        mvc.get("/api/v1/courses/$id/members") { header("Authorization", newerCo.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$[*].userId") { value(listOf(admin.id, olderCo.id, newerCo.id, member.id).map { it.toInt() }) }
            jsonPath("$[0].role") { value("admin") }
            jsonPath("$[1].coSince") { isNotEmpty() }
            jsonPath("$[3].coSince") { doesNotExist() }
        }
        mvc.get("/api/v1/courses/$id/members") { header("Authorization", member.bearer) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `관리자만 멤버 ↔ 공동 관리자를 바꿀 수 있어요`() {
        val admin = f.newUser()
        val co = f.newUser()
        val member = f.newUser()
        val (id, _) = f.createCourse(admin)
        f.join(id, co, role = "co", coSinceDaysAgo = 1)
        f.join(id, member)

        changeRole(admin.bearer, id, member.id, "co").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("co") }
            jsonPath("$.coSince") { isNotEmpty() }
        }
        changeRole(admin.bearer, id, member.id, "member").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("member") }
            jsonPath("$.coSince") { doesNotExist() }
        }
        changeRole(co.bearer, id, member.id, "co").andExpect { status { isForbidden() } }
        changeRole(admin.bearer, id, admin.id, "member").andExpect { status { isForbidden() } }
        changeRole(admin.bearer, id, 999999, "co").andExpect { status { isNotFound() } }
    }

    @Test
    fun `공동 관리자는 멤버만 내보낼 수 있고, 관리자는 공동 관리자도 내보내요`() {
        val admin = f.newUser()
        val co = f.newUser()
        val co2 = f.newUser()
        val member = f.newUser()
        val (id, _) = f.createCourse(admin)
        f.join(id, co, role = "co", coSinceDaysAgo = 2)
        f.join(id, co2, role = "co", coSinceDaysAgo = 1)
        f.join(id, member)

        mvc.delete("/api/v1/courses/$id/members/${co2.id}") { header("Authorization", co.bearer) }
            .andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/courses/$id/members/${admin.id}") { header("Authorization", co.bearer) }
            .andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/courses/$id/members/${co.id}") { header("Authorization", co.bearer) }
            .andExpect { status { isForbidden() } }

        mvc.delete("/api/v1/courses/$id/members/${member.id}") { header("Authorization", co.bearer) }
            .andExpect { status { isNoContent() } }
        mvc.delete("/api/v1/courses/$id/members/${co2.id}") { header("Authorization", admin.bearer) }
            .andExpect { status { isNoContent() } }

        // P-ROLE-05: 내보내진 사람은 과목에 들어갈 수 없어요
        mvc.get("/api/v1/courses/$id") { header("Authorization", member.bearer) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `퀴즈 출처였던 공동 관리자를 내보내면 Gemini 로 돌아가고 관리자에게 알려요`() {
        val admin = f.newUser()
        val co = f.newUser()
        val (id, _) = f.createCourse(admin)
        f.join(id, co, role = "co", coSinceDaysAgo = 1)
        jdbc.sql(
            "update courses set quiz_source_type = 'myai', quiz_source_user_id = :u, quiz_source_ai = 'claude' where id = :c",
        ).param("u", co.id).param("c", id).update()

        mvc.delete("/api/v1/courses/$id/members/${co.id}") { header("Authorization", admin.bearer) }
            .andExpect { status { isNoContent() } }

        mvc.get("/api/v1/courses/$id/settings") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$.quizSource.type") { value("default") } }
        assertThat(f.notificationTypes(admin)).containsExactly("quiz_source_fallback")
    }

    @Test
    fun `가입 요청을 보고 승인 · 거절 · 모두 승인할 수 있어요`() {
        val admin = f.newUser()
        val co = f.newUser()
        val member = f.newUser()
        val a = f.newUser()
        val b = f.newUser()
        val c = f.newUser()
        val d = f.newUser()
        val (id, _) = f.createCourse(admin, visibility = "private")
        f.join(id, co, role = "co", coSinceDaysAgo = 1)
        f.join(id, member)
        listOf(a, b, c, d).forEach { f.join(id, it, status = "pending") }

        mvc.get("/api/v1/courses/$id/join-requests") { header("Authorization", co.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(4) }
            jsonPath("$[0].requestId") { value(a.id) }
            jsonPath("$[0].nickname") { value(a.nickname) }
        }
        mvc.get("/api/v1/courses/$id/join-requests") { header("Authorization", member.bearer) }
            .andExpect { status { isForbidden() } }

        mvc.post("/api/v1/courses/$id/join-requests/${a.id}/approve") { header("Authorization", co.bearer) }
            .andExpect { status { isNoContent() } }
        mvc.post("/api/v1/courses/$id/join-requests/${b.id}/reject") { header("Authorization", co.bearer) }
            .andExpect { status { isNoContent() } }
        // 이미 처리했거나 멤버인 사람은 요청이 아니에요
        mvc.post("/api/v1/courses/$id/join-requests/${a.id}/approve") { header("Authorization", co.bearer) }
            .andExpect { status { isNotFound() } }
        mvc.post("/api/v1/courses/$id/join-requests/${b.id}/reject") { header("Authorization", co.bearer) }
            .andExpect { status { isNotFound() } }

        mvc.post("/api/v1/courses/$id/join-requests/approve-all") { header("Authorization", admin.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.approved") { value(2) } }

        mvc.get("/api/v1/courses/$id") { header("Authorization", a.bearer) }.andExpect { status { isOk() } }
        mvc.get("/api/v1/courses/$id") { header("Authorization", b.bearer) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/courses/$id") { header("Authorization", d.bearer) }.andExpect { status { isOk() } }
        assertThat(f.notificationTypes(a)).containsExactly("join_approved")
        assertThat(f.notificationTypes(b)).isEmpty()
        assertThat(f.notificationTypes(c)).containsExactly("join_approved")
    }
}
