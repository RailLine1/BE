package app.daybit.explore

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
import app.daybit.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExploreFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    /** 다른 테스트의 과목과 섞이지 않게 이름에 고유한 꼬리표를 붙여요 */
    private fun tag() = UUID.randomUUID().toString().take(6)

    private fun subscribe(user: TestUser, code: String) =
        mvc.post("/api/v1/explore/courses/$code/subscribe") { header("Authorization", user.bearer) }

    private fun listCodes(user: TestUser, sort: String): List<String> {
        val body = mvc.get("/api/v1/explore/courses?sort=$sort&size=50") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(body).path("items").toList().map { it.path("code").asString() }
    }

    @Test
    fun `공개 과목은 바로 구독되고, 다시 눌러도 같은 결과예요`() {
        val admin = f.newUser()
        val user = f.newUser()
        val (id, code) = f.createCourse(admin)

        subscribe(user, code.lowercase()).andExpect {
            status { isOk() }
            jsonPath("$.result") { value("subscribed") }
            jsonPath("$.courseId") { value(id) }
        }
        subscribe(user, code).andExpect { jsonPath("$.result") { value("subscribed") } }

        mvc.get("/api/v1/courses/$id") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.role") { value("member") }; jsonPath("$.subscribers") { value(2) } }
        mvc.get("/api/v1/explore/courses/$code") { header("Authorization", user.bearer) }
            .andExpect { jsonPath("$.myStatus") { value("subscribed") } }
    }

    @Test
    fun `비공개 과목은 가입 요청이 가고 승인되면 들어가요`() {
        val admin = f.newUser()
        val user = f.newUser()
        val (id, code) = f.createCourse(admin, visibility = "private")

        subscribe(user, code).andExpect { status { isOk() }; jsonPath("$.result") { value("pending") } }
        mvc.get("/api/v1/explore/courses/$code") { header("Authorization", user.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.myStatus") { value("pending") }
            jsonPath("$.visibility") { value("private") }
            jsonPath("$.admin") { value(admin.nickname) }
        }
        mvc.get("/api/v1/courses/$id") { header("Authorization", user.bearer) }.andExpect { status { isNotFound() } }

        mvc.post("/api/v1/courses/$id/join-requests/${user.id}/approve") { header("Authorization", admin.bearer) }
            .andExpect { status { isNoContent() } }
        subscribe(user, code).andExpect { jsonPath("$.result") { value("subscribed") } }
    }

    @Test
    fun `종료된 과목은 코드로 보여도 구독할 수 없고, 없는 코드는 404`() {
        val admin = f.newUser()
        val user = f.newUser()
        val (id, code) = f.createCourse(admin)
        jdbc.sql("update courses set state = 'ended', ended_reason = 'final_exam', ended_at = now() where id = :c")
            .param("c", id).update()

        mvc.get("/api/v1/explore/courses/$code") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.ended") { value(true) }; jsonPath("$.myStatus") { value("none") } }
        subscribe(user, code).andExpect { status { isConflict() }; jsonPath("$.error.code") { value("COURSE_ENDED") } }
        subscribe(user, "ZZZZZZ").andExpect { status { isNotFound() } }
    }

    @Test
    fun `탐색 목록에는 진행 중인 공개 과목만, 인기순과 최신순으로 나와요`() {
        val t = tag()
        val admin = f.newUser()
        val (_, popular) = f.createCourse(admin, name = "인기$t")
        val (_, fresh) = f.createCourse(admin, name = "새과목$t")
        val (_, hidden) = f.createCourse(admin, name = "비공개$t", visibility = "private")
        val (endedId, ended) = f.createCourse(admin, name = "종료$t")
        jdbc.sql("update courses set state = 'ended' where id = :c").param("c", endedId).update()
        repeat(3) { subscribe(f.newUser(), popular) }

        val viewer = f.newUser()
        val newest = listCodes(viewer, "newest")
        assertThat(newest.first()).isEqualTo(fresh)
        assertThat(newest).doesNotContain(hidden, ended)

        val byPopularity = listCodes(viewer, "subscribers")
        assertThat(byPopularity.indexOf(popular)).isLessThan(byPopularity.indexOf(fresh))

        mvc.get("/api/v1/explore/courses?sort=newest&page=1&size=1") { header("Authorization", viewer.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].code") { value(fresh) }
            jsonPath("$.items[0].subscribers") { doesNotExist() }
            jsonPath("$.page") { value(1) }
            jsonPath("$.size") { value(1) }
        }
        mvc.get("/api/v1/explore/courses?size=0") { header("Authorization", viewer.bearer) }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `검색은 이름 부분 일치(공개·진행 중) + 코드 전체 일치(모든 과목)예요`() {
        val t = tag()
        val admin = f.newUser()
        val (_, open) = f.createCourse(admin, name = "알고리즘$t")
        val (_, secret) = f.createCourse(admin, name = "알고리즘$t 비밀", visibility = "private")
        val viewer = f.newUser()

        fun search(q: String): List<String> {
            val body = mvc.get("/api/v1/explore/search") { header("Authorization", viewer.bearer); param("q", q) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString
            return json.readTree(body).toList().map { it.path("code").asString() }
        }

        assertThat(search("고리즘$t")).containsExactly(open)
        assertThat(search(secret.lowercase())).containsExactly(secret)
        assertThat(search(secret.take(5))).doesNotContain(secret)  // 코드는 전체 일치만
        assertThat(search("%")).doesNotContain(secret)             // LIKE 특수문자는 글자 그대로
        assertThat(search("   ")).isEmpty()
    }
}
