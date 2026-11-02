package app.daybit.material

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
import app.daybit.support.TestUser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
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
        val mid = folderId(admin, courseId, "mid")

        val fileId = upload(admin, mid, samplePdf("Process scheduling", "Round robin quantum")).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("processing") }
        }.id()

        assertThat(waitStatus(fileId)).isEqualTo("ready")
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
        val mid = folderId(admin, courseId, "mid")

        upload(admin, mid, "hello".toByteArray()).andExpect { status { isBadRequest() } }
        upload(admin, mid, samplePdf("a"), consent = false).andExpect { status { isBadRequest() } }
        upload(admin, mid, samplePdf("a"), title = "  ").andExpect { status { isBadRequest() } }

        val broken = upload(admin, mid, "%PDF-1.7 broken".toByteArray()).andExpect { status { isCreated() } }.id()
        assertThat(waitStatus(broken)).isEqualTo("failed")
    }

    @Test
    fun `멤버는 ready 자료만 보고 개수도 ready 만 세며, 업로드 · 폴더 관리는 못 해요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, member)
        val mid = folderId(admin, courseId, "mid")

        val ready = upload(admin, mid, samplePdf("ready one")).id()
        assertThat(waitStatus(ready)).isEqualTo("ready")
        // 처리 중인 파일을 흉내 내요
        val processing = upload(admin, mid, samplePdf("processing one")).id()
        waitStatus(processing)
        jdbc.sql("update files set status = 'processing' where id = :id").param("id", processing).update()

        mvc.get("/api/v1/folders/$mid/files") { header("Authorization", member.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].fileId") { value(ready) }
        }
        mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", member.bearer) }.andExpect {
            jsonPath("$[0].name") { value("mid") }
            jsonPath("$[0].fileCount") { value(1) }
        }
        mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", admin.bearer) }
            .andExpect { jsonPath("$[0].fileCount") { value(2) } }

        mvc.get("/api/v1/files/$processing") { header("Authorization", member.bearer) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/files/$processing") { header("Authorization", admin.bearer) }.andExpect { status { isOk() } }

        upload(member, mid, samplePdf("x")).andExpect { status { isForbidden() } }
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
        val mid = folderId(admin, courseId, "mid")
        val fileId = upload(admin, mid, samplePdf("raw")).id()
        waitStatus(fileId)

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
        val mid = folderId(admin, courseId, "mid")

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

        val fileId = upload(admin, custom, samplePdf("in custom")).id()
        waitStatus(fileId)
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
        val mid = folderId(admin, courseId, "mid")
        val fileId = upload(admin, mid, samplePdf("view me")).id()
        waitStatus(fileId)

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
        val mid = folderId(admin, courseId, "mid")
        val first = upload(admin, mid, samplePdf("first")).id()
        val second = upload(admin, mid, samplePdf("second")).id()
        waitStatus(first); waitStatus(second)
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

        upload(admin, folderId(admin, inactive, "mid"), samplePdf("back")).andExpect { status { isCreated() } }
        upload(admin, folderId(admin, finalEnded, "mid"), samplePdf("still ended")).andExpect { status { isCreated() } }

        assertThat(state(inactive)).isEqualTo("active")
        assertThat(state(finalEnded)).isEqualTo("ended")
    }

    // ---------- 도우미 ----------

    private fun upload(user: TestUser, folderId: Long, bytes: ByteArray, title: String = "강의 자료", consent: Boolean = true) =
        mvc.multipart("/api/v1/folders/$folderId/files") {
            header("Authorization", user.bearer)
            file(MockMultipartFile("file", "lecture.pdf", "application/pdf", bytes))
            param("title", title)
            param("consent", consent.toString())
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

    private fun folderId(user: TestUser, courseId: Long, name: String): Long {
        val body = mvc.get("/api/v1/courses/$courseId/folders") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(body).toList().first { it.path("name").asString() == name }.path("folderId").asLong()
    }

    /** 추출은 별도 스레드에서 돌아요. 끝날 때까지 기다려요 */
    private fun waitStatus(fileId: Long): String {
        repeat(100) {
            val status = jdbc.sql("select status from files where id = :id").param("id", fileId).query(String::class.java).single()
            if (status != "processing") return status
            Thread.sleep(100)
        }
        error("10초 안에 처리되지 않았어요: $fileId")
    }

    private fun storageKey(fileId: Long) =
        jdbc.sql("select storage_key from files where id = :id").param("id", fileId).query(String::class.java).single()

    private fun storageFile(key: String) = java.nio.file.Path.of("build/test-storage").resolve(key)

    private fun state(courseId: Long) =
        jdbc.sql("select state from courses where id = :id").param("id", courseId).query(String::class.java).single()

    private fun samplePdf(vararg pageTexts: String): ByteArray = PDDocument().use { doc ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        pageTexts.forEach { text ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(font, 14f); cs.newLineAtOffset(72f, 700f); cs.showText(text); cs.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
}
