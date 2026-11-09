package app.daybit.connector

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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectorFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    private fun connector(user: TestUser): JsonNode =
        mvc.get("/api/v1/me/connector") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString.let { json.readTree(it) }

    private fun tokenOf(url: String) = url.substringAfterLast("/mcp/u/")

    private fun rpc(token: String, method: String, params: Map<String, Any?> = emptyMap(), session: String? = null) =
        mvc.post("/mcp/u/$token") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            session?.let { header("Mcp-Session-Id", it) }
            content = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        }

    /** tools/call 결과의 첫 content (텍스트면 JSON 으로 풀어서) */
    private fun call(token: String, tool: String, args: Map<String, Any?>): Pair<Boolean, JsonNode> {
        val body = rpc(token, "tools/call", mapOf("name" to tool, "arguments" to args))
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val result = json.readTree(body).path("result")
        val first = result.path("content").path(0)
        val payload = if (first.path("type").asString() == "text" && !result.path("isError").asBoolean())
            json.readTree(first.path("text").asString()) else first
        return result.path("isError").asBoolean() to payload
    }

    @Test
    fun `처음 조회하면 주소를 발급하고, 이후에는 가리고, 재발급하면 이전 주소는 바로 무효예요`() {
        val user = f.newUser()
        val first = connector(user)
        assertThat(first.path("fullUrlShown").asBoolean()).isTrue()
        val oldToken = tokenOf(first.path("url").asString())
        assertThat(first.path("url").asString()).startsWith("http://localhost:8080/mcp/u/")
        assertThat(first.path("clients").toList().map { it.path("client").asString() }).containsExactly("claude", "chatgpt", "claudecode", "codex")

        val again = connector(user)
        assertThat(again.path("fullUrlShown").asBoolean()).isFalse()
        assertThat(again.path("url").asString()).doesNotContain(oldToken)
        rpc(oldToken, "ping").andExpect { status { isOk() } }

        val reissued = mvc.post("/api/v1/me/connector/reissue") { header("Authorization", user.bearer) }
            .andExpect { status { isOk() }; jsonPath("$.fullUrlShown") { value(true) } }
            .andReturn().response.contentAsString.let { json.readTree(it) }
        rpc(oldToken, "ping").andExpect { status { isNotFound() } }
        rpc(tokenOf(reissued.path("url").asString()), "ping").andExpect { status { isOk() } }
        rpc("not-a-token", "ping").andExpect { status { isNotFound() } }
    }

    @Test
    fun `initialize 하면 그 AI 가 등록되고 도구 5개를 알려 줘요`() {
        val user = f.newUser()
        val token = tokenOf(connector(user).path("url").asString())

        val init = rpc(token, "initialize", mapOf(
            "protocolVersion" to "2025-06-18",
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "claude-ai", "version" to "1.0"),
        )).andExpect {
            status { isOk() }
            header { exists("Mcp-Session-Id") }
            jsonPath("$.result.protocolVersion") { value("2025-06-18") }
            jsonPath("$.result.serverInfo.name") { value("daybit") }
            jsonPath("$.result.capabilities.tools") { exists() }
        }.andReturn()
        val session = init.response.getHeader("Mcp-Session-Id")!!

        // 알림은 본문 없이 202
        mvc.post("/mcp/u/$token") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        }.andExpect { status { isAccepted() } }

        val toolList = rpc(token, "tools/list", session = session).andExpect { status { isOk() } }
            .andReturn().response.contentAsString.let { json.readTree(it).path("result").path("tools").toList() }
        val tools = toolList.map { t -> t.path("name").asString() }
        assertThat(tools).containsExactly("list_courses", "list_materials", "search", "read_pages", "get_page_image", "get_jobs", "submit_questions")
        // 읽기 도구는 호출마다 승인을 묻지 않게 읽기 전용으로 표시해요
        val readOnly = toolList.associate { t -> t.path("name").asString() to t.path("annotations").path("readOnlyHint").asBoolean(false) }
        assertThat(readOnly.filterValues { it }.keys)
            .containsExactlyInAnyOrder("list_courses", "list_materials", "search", "read_pages", "get_page_image", "get_jobs")
        assertThat(toolList.first { it.path("name").asString() == "submit_questions" }.path("annotations").has("readOnlyHint")).isTrue()
        assertThat(readOnly["submit_questions"]).isFalse()
        assertThat(toolList.first { it.path("name").asString() == "read_pages" }.path("annotations").path("destructiveHint").asBoolean(true)).isFalse()
        rpc(token, "resources/list").andExpect { jsonPath("$.error.code") { value(-32601) } }

        val claude = connector(user).path("clients").toList().first { it.path("client").asString() == "claude" }
        assertThat(claude.path("lastCalledAt").isNull).isFalse()
        val codex = connector(user).path("clients").toList().first { it.path("client").asString() == "codex" }
        assertThat(codex.path("lastCalledAt").isNull).isTrue()

        // 클라이언트 구분
        assertThat(McpController.clientOf("claude-code", null)).isEqualTo(ConnectorClientType.claudecode)
        assertThat(McpController.clientOf("codex-mcp-client", null)).isEqualTo(ConnectorClientType.codex)
        assertThat(McpController.clientOf("", "openai-mcp/1.0")).isEqualTo(ConnectorClientType.chatgpt)
        assertThat(McpController.clientOf("mcp-inspector", null)).isEqualTo(ConnectorClientType.unknown)
    }

    @Test
    fun `구독한 과목의 ready 자료만 목록 · 검색 · 읽기 · 이미지로 줘요`() {
        val admin = f.newUser()
        val user = f.newUser()
        val (courseId, _) = f.createCourse(admin, name = "운영체제")
        val (otherCourse, _) = f.createCourse(admin, name = "남의과목")
        f.join(courseId, user)
        val fileId = f.uploadReady(admin, courseId, "Semaphore wait and signal", "Deadlock needs four conditions", "Paging and 50% rule")
        val otherFile = f.uploadReady(admin, otherCourse, "Secret material")
        // 원본 열람을 꺼도 커넥터는 막지 않아요 (P-MAT-06)
        jdbc.sql("update courses set raw_allowed = false where id = :c").param("c", courseId).update()
        val token = tokenOf(connector(user).path("url").asString())

        val (_, courses) = call(token, "list_courses", emptyMap())
        assertThat(courses.toList().map { it.path("courseId").asLong() }).containsExactly(courseId)

        val (_, materials) = call(token, "list_materials", mapOf("courseId" to courseId))
        assertThat(materials.path(0).path("fileId").asLong()).isEqualTo(fileId)
        assertThat(materials.path(0).path("folder").asString()).isEqualTo("mid")
        assertThat(materials.path(0).path("pages").asInt()).isEqualTo(3)

        val (_, found) = call(token, "search", mapOf("courseId" to courseId, "query" to "deadlock conditions"))
        assertThat(found.path(0).path("page").asInt()).isEqualTo(2)
        assertThat(found.path(0).path("snippet").asString()).contains("Deadlock")
        val (_, percent) = call(token, "search", mapOf("courseId" to courseId, "query" to "50%"))
        assertThat(percent.toList().map { it.path("page").asInt() }).containsExactly(3)

        val (_, pages) = call(token, "read_pages", mapOf("fileId" to fileId, "pages" to listOf(3, 1, 9)))
        assertThat(pages.toList().map { it.path("page").asInt() }).containsExactly(1, 3)
        assertThat(pages.path(0).path("text").asString()).contains("Semaphore")

        val (imageError, image) = call(token, "get_page_image", mapOf("fileId" to fileId, "page" to 1))
        assertThat(imageError).isFalse()
        assertThat(image.path("type").asString()).isEqualTo("image")
        assertThat(image.path("mimeType").asString()).isEqualTo("image/png")
        assertThat(image.path("data").asString()).isNotEmpty()

        // 범위 밖은 도구 오류(isError)로 답해요
        assertThat(call(token, "list_materials", mapOf("courseId" to otherCourse)).first).isTrue()
        assertThat(call(token, "read_pages", mapOf("fileId" to otherFile, "pages" to listOf(1))).first).isTrue()
        assertThat(call(token, "read_pages", mapOf("fileId" to fileId, "pages" to listOf(1, 2, 3, 4, 5, 6))).first).isTrue()
        assertThat(call(token, "get_page_image", mapOf("fileId" to fileId, "page" to 4)).first).isTrue()
        assertThat(call(token, "search", mapOf("courseId" to courseId, "query" to "  ")).first).isTrue()

        // 처리 중인 자료는 안 보여요
        jdbc.sql("update files set status = 'processing' where id = :f").param("f", fileId).update()
        assertThat(call(token, "list_materials", mapOf("courseId" to courseId)).second.size()).isZero()
        assertThat(call(token, "read_pages", mapOf("fileId" to fileId, "pages" to listOf(1))).first).isTrue()

        val tools = jdbc.sql("select tool from connector_calls where user_id = :u order by id").param("u", user.id)
            .query(String::class.java).list()
        assertThat(tools).contains("list_courses", "search", "read_pages", "get_page_image")
    }

    @Test
    fun `MCP 스트림(GET)은 지원하지 않아요`() {
        val user = f.newUser()
        val token = tokenOf(connector(user).path("url").asString())
        mvc.get("/mcp/u/$token").andExpect { status { isMethodNotAllowed() } }
    }
}
