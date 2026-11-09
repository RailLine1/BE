package app.daybit.material

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
import app.daybit.support.TestUser
import app.daybit.support.samplePdf
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.URI

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    @Test
    fun `업로드하면 processing 이었다가 페이지별 텍스트가 저장되고 ready 가 돼요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val mid = f.folderId(admin, courseId, "mid")

        val fileId = f.upload(admin, mid, samplePdf("Process scheduling", "Round robin quantum")).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("processing") }
        }.id()

        assertThat(f.waitFileStatus(fileId)).isEqualTo("ready")
        val pages = jdbc.sql("select text from file_pages where file_id = :id order by page_no").param("id", fileId)
            .query(String::class.java).list()
        assertThat(pages).hasSize(2)
        assertThat(pages[0]).contains("Process scheduling")
        assertThat(pages[1]).contains("Round robin quantum")

        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", admin.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$[0].fileId") { value(fileId) }
            jsonPath("$[0].pages") { value(2) }
            jsonPath("$[0].status") { value("ready") }
        }
        // P-BIT-18: 업로드 시각을 남겨요
        val lastUpload = jdbc.sql("select last_upload_at from courses where id = :id").param("id", courseId)
            .query(java.time.OffsetDateTime::class.java).single()
        assertThat(lastUpload).isNotNull()
    }

    @Test
    fun `PDF 가 아니거나 동의가 없으면 400, 읽을 수 없는 PDF 는 failed 가 돼요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val mid = f.folderId(admin, courseId, "mid")

        f.upload(admin, mid, "hello".toByteArray()).andExpect { status { isBadRequest() } }
        f.upload(admin, mid, samplePdf("a"), consent = false).andExpect { status { isBadRequest() } }
        // 이름이 비면 파일 이름을 써요 (아래 이름 테스트 참고)

        val broken = f.upload(admin, mid, "%PDF-1.7 broken".toByteArray()).andExpect { status { isCreated() } }.id()
        assertThat(f.waitFileStatus(broken)).isEqualTo("failed")
    }

    @Test
    fun `멤버는 ready 자료만 보고 개수도 ready 만 세며, 업로드 · 폴더 관리는 못 해요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, member)
        val mid = f.folderId(admin, courseId, "mid")

        val ready = f.upload(admin, mid, samplePdf("ready one")).id()
        assertThat(f.waitFileStatus(ready)).isEqualTo("ready")
        // 처리 중인 파일을 흉내 내요
        val processing = f.upload(admin, mid, samplePdf("processing one")).id()
        f.waitFileStatus(processing)
        jdbc.sql("update files set status = 'processing' where id = :id").param("id", processing).update()

        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", member.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].fileId") { value(ready) }
        }
        // 관리자는 처리 중인 자료도 보고, 먼저 올린 자료가 위에 와요
        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", admin.bearer) }.andExpect {
            jsonPath("$[0].fileId") { value(ready) }
            jsonPath("$[1].fileId") { value(processing) }
        }
        mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", member.bearer) }.andExpect {
            jsonPath("$[0].name") { value("mid") }
            jsonPath("$[0].fileCount") { value(1) }
        }
        mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$[0].fileCount") { value(2) } }

        mvc.get("/api/v1/files/$processing") { header("Authorization", member.bearer) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/files/$processing") { header("Authorization", admin.bearer) }.andExpect { status { isOk() } }

        f.upload(member, mid, samplePdf("x")).andExpect { status { isForbidden() } }
        createFolder(member, courseId, "기출").andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/files/$ready") { header("Authorization", member.bearer) }.andExpect { status { isForbidden() } }

        // 멤버가 아닌 사람은 과목이 있는지도 몰라요
        val stranger = f.newUser()
        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", stranger.bearer) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/files/$ready") { header("Authorization", stranger.bearer) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `원본 열람을 끄면 멤버는 403, 관리자 · 공동 관리자는 계속 봐요`() {
        val admin = f.newUser()
        val co = f.newUser()
        val member = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, co, role = "co", coSinceDaysAgo = 1)
        f.join(courseId, member)
        val mid = f.folderId(admin, courseId, "mid")
        val fileId = f.upload(admin, mid, samplePdf("raw")).id()
        f.waitFileStatus(fileId)

        mvc.patch("/api/v1/courses/$courseId") {
            header("Authorization", admin.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"rawAllowed":false}"""
        }.andExpect { status { isOk() } }

        mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", member.bearer) }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", member.bearer) }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/files/$fileId") { header("Authorization", member.bearer) }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", co.bearer) }.andExpect { status { isOk() } }
        mvc.get("/api/v1/files/$fileId") { header("Authorization", co.bearer) }.andExpect { status { isOk() } }
    }

    @Test
    fun `기본 폴더는 이름 변경 · 삭제가 안 되고, 만든 폴더는 파일과 함께 지워져요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val mid = f.folderId(admin, courseId, "mid")

        rename(admin, mid, "중간").andExpect { status { isBadRequest() } }
        mvc.delete("/api/v1/folders/$mid") { header("Authorization", admin.bearer) }.andExpect { status { isBadRequest() } }

        val custom = createFolder(admin, courseId, "기출").andExpect {
            status { isCreated() }
            jsonPath("$.kind") { value("custom") }
        }.andReturn().response.contentAsString.let { json.readTree(it).path("folderId").asLong() }
        rename(admin, custom, "기출 문제").andExpect { status { isOk() }; jsonPath("$.name") { value("기출 문제") } }

        val names = mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", admin.bearer) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
            .let { json.readTree(it).toList().map { n -> n.path("name").asString() } }
        assertThat(names).containsExactly("mid", "final", "기출 문제")

        val fileId = f.upload(admin, custom, samplePdf("in custom")).id()
        f.waitFileStatus(fileId)
        val key = storageKey(fileId)
        assertThat(storageFile(key)).exists()

        mvc.delete("/api/v1/folders/$custom") { header("Authorization", admin.bearer) }.andExpect { status { isNoContent() } }
        mvc.get("/api/v1/files/$fileId") { header("Authorization", admin.bearer) }.andExpect { status { isNotFound() } }
        assertThat(storageFile(key)).doesNotExist()
    }

    @Test
    fun `임시 URL 로 원본을 열 수 있고, 서명이 틀리면 404 예요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val mid = f.folderId(admin, courseId, "mid")
        val fileId = f.upload(admin, mid, samplePdf("view me")).id()
        f.waitFileStatus(fileId)

        val url = mvc.get("/api/v1/files/$fileId") { header("Authorization", admin.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.expiresAt") { isNotEmpty() } }
            .andReturn().response.contentAsString.let { json.readTree(it).path("url").asString() }
        val uri = URI(url)
        val pathAndQuery = uri.rawPath + "?" + uri.rawQuery

        val bytes = mvc.get(pathAndQuery).andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
        mvc.get(pathAndQuery.replace(Regex("sig=[^&]+"), "sig=wrong")).andExpect { status { isNotFound() } }
    }

    @Test
    fun `파일을 지우면 원본도 지워지고, 관리자가 혼자 탈퇴해 과목이 사라지면 원본도 지워져요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val mid = f.folderId(admin, courseId, "mid")
        val first = f.upload(admin, mid, samplePdf("first")).id()
        val second = f.upload(admin, mid, samplePdf("second")).id()
        f.waitFileStatus(first); f.waitFileStatus(second)
        val firstKey = storageKey(first)
        val secondKey = storageKey(second)

        mvc.delete("/api/v1/files/$first") { header("Authorization", admin.bearer) }.andExpect { status { isNoContent() } }
        assertThat(storageFile(firstKey)).doesNotExist()

        mvc.post("/api/v1/courses/$courseId/leave") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$.result") { value("deleted") } }
        assertThat(storageFile(secondKey)).doesNotExist()
    }

    @Test
    fun `활동 없음으로 꺼진 과목은 업로드하면 다시 켜지고, 기말로 꺼진 과목은 그대로예요`() {
        val admin = f.newUser()
        val (inactive, _) = f.createCourse(admin)
        val (finalEnded, _) = f.createCourse(admin)
        jdbc.sql("update courses set state = 'ended', ended_reason = 'inactive', ended_at = now() where id = :id").param("id", inactive).update()
        jdbc.sql("update courses set state = 'ended', ended_reason = 'final_exam', ended_at = now() where id = :id").param("id", finalEnded).update()

        f.upload(admin, f.folderId(admin, inactive, "mid"), samplePdf("back")).andExpect { status { isCreated() } }
        f.upload(admin, f.folderId(admin, finalEnded, "mid"), samplePdf("still ended")).andExpect { status { isCreated() } }

        assertThat(state(inactive)).isEqualTo("active")
        assertThat(state(finalEnded)).isEqualTo("ended")
    }

    @Test
    fun `이름을 비우면 파일 이름을 쓰고, 관리자 · 공동 관리자는 이름을 바꿀 수 있어요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val stranger = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, member)
        val mid = f.folderId(admin, courseId, "mid")

        val fileId = f.upload(admin, mid, samplePdf("named by file"), title = null, filename = "3주차 프로세스.PDF").id()
        f.waitFileStatus(fileId)
        f.upload(admin, mid, samplePdf("x"), title = "가".repeat(81)).andExpect { status { isBadRequest() } }
        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$[0].title") { value("3주차 프로세스") } }

        renameFile(admin, fileId, "  3주차 · 스케줄링  ").andExpect {
            status { isOk() }
            jsonPath("$.title") { value("3주차 · 스케줄링") }
            jsonPath("$.status") { value("ready") }
        }
        renameFile(member, fileId, "멤버는 못 바꿔요").andExpect { status { isForbidden() } }
        renameFile(stranger, fileId, "남의 과목").andExpect { status { isNotFound() } }
        renameFile(admin, fileId, "   ").andExpect { status { isBadRequest() } }
        // 원본은 그대로라 보기 URL 도 그대로 열려요
        mvc.get("/api/v1/files/$fileId") { header("Authorization", admin.bearer) }.andExpect { status { isOk() } }
    }

    // ---------- 도우미 ----------

    private fun renameFile(user: TestUser, fileId: Long, title: String) =
        mvc.patch("/api/v1/files/$fileId") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("title" to title))
        }

    private fun ResultActionsDsl.id(): Long =
        json.readTree(andReturn().response.contentAsString).path("fileId").asLong()

    private fun createFolder(user: TestUser, courseId: Long, name: String) =
        mvc.post("/api/v1/courses/$courseId/folders") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        }

    private fun rename(user: TestUser, folderId: Long, name: String) =
        mvc.patch("/api/v1/folders/$folderId") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        }

    private fun storageKey(fileId: Long) =
        jdbc.sql("select storage_key from files where id = :id").param("id", fileId).query(String::class.java).single()

    private fun storageFile(key: String) = java.nio.file.Path.of("build/test-storage").resolve(key)

    private fun state(courseId: Long) =
        jdbc.sql("select state from courses where id = :id").param("id", courseId).query(String::class.java).single()
}
