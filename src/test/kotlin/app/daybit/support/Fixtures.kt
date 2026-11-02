package app.daybit.support

import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicInteger

data class TestUser(val id: Long, val token: String, val nickname: String) {
    val bearer get() = "Bearer $token"
}

/** 통합 테스트용 준비 도우미. 테스트끼리 DB 를 같이 쓰니까 아이디·닉네임에 순번을 붙여요. */
class Fixtures(private val mvc: MockMvc, private val json: JsonMapper, private val jdbc: JdbcClient) {

    fun newUser(): TestUser {
        val n = seq.incrementAndGet()
        val nickname = "유저$n"
        val body = mvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"fixture$n","password":"password1","nickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val tree = json.readTree(body)
        return TestUser(tree.path("user").path("id").asLong(), tree.path("tokens").path("accessToken").asString(), nickname)
    }

    /** 과목을 만들고 (id, code) 를 돌려줘요 */
    fun createCourse(owner: TestUser, visibility: String = "public", extra: String = "", name: String = "운영체제"): Pair<Long, String> {
        val body = mvc.post("/api/v1/courses") {
            header("Authorization", owner.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","icon":"book","intro":"한 줄 소개","term":"2026-2","visibility":"$visibility"$extra}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val tree = json.readTree(body)
        return tree.path("id").asLong() to tree.path("code").asString()
    }

    /** 탐색·구독 API 가 생기기 전까지 멤버십은 DB 에 바로 넣어요 */
    fun join(courseId: Long, user: TestUser, role: String = "member", status: String = "active", coSinceDaysAgo: Int? = null) {
        jdbc.sql(
            """
            insert into course_members (course_id, user_id, role, status, co_since, joined_at, requested_at)
            values (:c, :u, :role, :status,
                    case when :days::int is null then null else now() - make_interval(days => :days::int) end,
                    case when :status = 'active' then now() end,
                    case when :status = 'pending' then now() end)
            """.trimIndent(),
        ).param("c", courseId).param("u", user.id).param("role", role).param("status", status)
            .param("days", coSinceDaysAgo).update()
    }

    fun notificationTypes(user: TestUser): List<String> =
        jdbc.sql("select type from notifications where user_id = :u order by id").param("u", user.id)
            .query(String::class.java).list().filterNotNull()

    companion object {
        private val seq = AtomicInteger()
    }
}
