package app.daybit.support

import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
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

    /** title 이 null 이면 보내지 않아요 (파일 이름을 쓰는 경우) */
    fun upload(
        user: TestUser, folderId: Long, bytes: ByteArray, title: String? = "강의 자료", consent: Boolean = true,
        filename: String = "lecture.pdf",
    ) =
        mvc.multipart("/api/v1/folders/$folderId/files") {
            header("Authorization", user.bearer)
            file(MockMultipartFile("file", filename, "application/pdf", bytes))
            title?.let { param("title", it) }
            param("consent", consent.toString())
        }

    /** 업로드하고 텍스트 추출이 끝날 때까지 기다린 뒤 fileId 를 돌려줘요 */
    fun uploadReady(user: TestUser, courseId: Long, vararg pageTexts: String, folder: String = "mid"): Long {
        val body = upload(user, folderId(user, courseId, folder), samplePdf(*pageTexts))
            .andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val fileId = json.readTree(body).path("fileId").asLong()
        check(waitFileStatus(fileId) == "ready")
        return fileId
    }

    fun folderId(user: TestUser, courseId: Long, name: String): Long {
        val body = mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(body).toList().first { it.path("name").asString() == name }.path("folderId").asLong()
    }

    /** 추출은 별도 스레드에서 돌아요. processing 이 아닐 때까지 기다려요 */
    fun waitFileStatus(fileId: Long): String {
        repeat(100) {
            val status = jdbc.sql("select status from files where id = :id").param("id", fileId).query(String::class.java).single()
            if (status != "processing") return status
            Thread.sleep(100)
        }
        error("10초 안에 처리되지 않았어요: $fileId")
    }

    fun notificationTypes(user: TestUser): List<String> =
        jdbc.sql("select type from notifications where user_id = :u order by id").param("u", user.id)
            .query(String::class.java).list().filterNotNull()

    companion object {
        private val seq = AtomicInteger()
    }
}
