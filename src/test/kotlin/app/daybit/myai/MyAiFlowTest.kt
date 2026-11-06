package app.daybit.myai

import app.daybit.TestcontainersConfiguration
import app.daybit.bit.FakeGeneratorConfig
import app.daybit.bit.FakeQuestionGenerator
import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.support.Fixtures
import app.daybit.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

@Import(TestcontainersConfiguration::class, FakeGeneratorConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyAiFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val fake: FakeQuestionGenerator,
    @Autowired val service: MyAiService,
) {
    private val f = Fixtures(mvc, json, jdbc)
    private val text = "Round robin gives each process a time quantum."

    @BeforeEach
    fun reset() {
        fake.enabled = true
        fake.respond = { emptyList() }
        fake.requests.clear()
    }

    private fun token(user: TestUser): String =
        mvc.get("/api/v1/me/connector") { header("Authorization", user.bearer) }
            .andReturn().response.contentAsString.let { json.readTree(it).path("url").asString().substringAfterLast("/mcp/u/") }

    /** tools/call → (isError, 결과 JSON 또는 오류 문장) */
    private fun call(token: String, tool: String, args: Map<String, Any?>): Pair<Boolean, JsonNode> {
        val body = mvc.post("/mcp/u/$token") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to args)),
            )
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val result = json.readTree(body).path("result")
        val text = result.path("content").path(0).path("text").asString()
        val isError = result.path("isError").asBoolean()
        return isError to json.readTree(if (isError) json.writeValueAsString(text) else text)
    }

    private fun q(quote: String = "Round robin gives each process a time quantum", page: Int = 1, choices: Int = 4) = mapOf(
        "concept" to "Scheduling",
        "stem" to "Which algorithm uses a time quantum?",
        "choices" to listOf("FIFO", "Round robin", "SJF", "Priority").take(choices),
        "answerIndex" to 1,
        "explanation" to "Round robin uses a quantum.",
        "evidencePage" to page,
        "evidenceQuote" to quote,
    )

    private fun setSource(user: TestUser, courseId: Long, ai: String) =
        mvc.patch("/api/v1/courses/$courseId") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizSource":{"type":"myai","ai":"$ai"}}"""
        }

    private fun questionSources(fileId: Long): List<String> =
        jdbc.sql("select source from questions where evidence_file_id = :f order by id").param("f", fileId).query(String::class.java).list().filterNotNull()

    @Test
    fun `예약 실행이 들어오면 등록되고, 받은 자료로 만든 문제를 검사해서 저장해요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        val t = token(admin)

        // 등록 전에는 출처로 고를 수 없어요 (P-AI-09)
        setSource(admin, courseId, "claude").andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("QUIZ_SOURCE_UNAVAILABLE") } }
        mvc.get("/api/v1/me/myai") { header("Authorization", admin.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.ais[0].ai") { value("claude") }
            jsonPath("$.ais[0].registered") { value(false) }
            jsonPath("$.ais[1].ai") { value("chatgpt") }
        }

        // 첫 실행 = 등록 (P-AI-08). 아직 출처인 과목이 없어 job 은 없어요
        val (err0, first) = call(t, "get_jobs", mapOf("ai" to "claude"))
        assertThat(err0).isFalse()
        assertThat(first.path("job").isNull).isTrue()
        mvc.get("/api/v1/me/myai") { header("Authorization", admin.bearer) }.andExpect {
            jsonPath("$.ais[0].registered") { value(true) }
            jsonPath("$.ais[0].lastRunAt") { exists() }
            jsonPath("$.ais[1].registered") { value(false) }
        }
        val prompt = service.status(admin.id).ais.first().schedulePrompt
        assertThat(prompt).contains("ai=\"claude\"").contains("get_jobs").contains("submit_questions")

        setSource(admin, courseId, "claude").andExpect { status { isOk() }; jsonPath("$.quizSource.type") { value("myai") }; jsonPath("$.quizSource.ai") { value("claude") } }
        setSource(admin, courseId, "chatgpt").andExpect { status { isBadRequest() } }

        // 새 자료는 Gemini 가 만들지 않고 기다려요 (P-BIT-20)
        val fileId = f.uploadReady(admin, courseId, text)
        Thread.sleep(300)
        assertThat(fake.requests).isEmpty()

        val (err1, jobs) = call(t, "get_jobs", mapOf("ai" to "claude"))
        assertThat(err1).isFalse()
        val job = jobs.path("job")
        assertThat(job.path("fileId").asLong()).isEqualTo(fileId)
        assertThat(job.path("count").asInt()).isGreaterThan(0)
        assertThat(job.path("prompt").asString()).contains(text).contains("<page number=\"1\">")
        assertThat(jobs.path("rules").asString()).isNotBlank()
        assertThat(jobs.path("schema").path("properties").has("questions")).isTrue()
        assertThat(jobs.path("remaining").asInt()).isZero()

        // 근거가 맞는 문제 1 · 근거가 틀린 문제 1(저장하되 verified 아님) · 형식이 틀린 문제 1(버림)
        val (err2, saved) = call(t, "submit_questions", mapOf(
            "ai" to "claude", "fileId" to fileId,
            "questions" to listOf(q(), q(quote = "This sentence is not in the material at all"), q(choices = 3)),
        ))
        assertThat(err2).isFalse()
        assertThat(saved.path("saved").asInt()).isEqualTo(2)
        assertThat(saved.path("verified").asInt()).isEqualTo(1)
        assertThat(saved.path("rejected").asInt()).isEqualTo(1)
        assertThat(questionSources(fileId)).containsExactly("claude", "claude")

        // 한 자료 한 출처: 다시 받으면 job 이 없고, 다시 보내면 거절해요
        assertThat(call(t, "get_jobs", mapOf("ai" to "claude")).second.path("job").isNull).isTrue()
        val (err3, again) = call(t, "submit_questions", mapOf("ai" to "claude", "fileId" to fileId, "questions" to listOf(q())))
        assertThat(err3).isTrue()
        assertThat(again.asString()).contains("이미 문제가 있는 자료")
    }

    @Test
    fun `출처가 아닌 사람 · 다른 AI · 형식이 모두 틀린 제출은 거절해요`() {
        val admin = f.newUser()
        val member = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        f.join(courseId, member)
        val t = token(admin)
        call(t, "get_jobs", mapOf("ai" to "claude"))
        setSource(admin, courseId, "claude").andExpect { status { isOk() } }
        val fileId = f.uploadReady(admin, courseId, text)

        // 멤버는 출처가 아니라 job 도 없고 제출도 못 해요
        val m = token(member)
        assertThat(call(m, "get_jobs", mapOf("ai" to "claude")).second.path("job").isNull).isTrue()
        assertThat(call(m, "submit_questions", mapOf("ai" to "claude", "fileId" to fileId, "questions" to listOf(q()))).first).isTrue()
        // 같은 사람이라도 출처가 아닌 AI 로는 안 돼요
        assertThat(call(t, "get_jobs", mapOf("ai" to "chatgpt")).second.path("job").isNull).isTrue()
        assertThat(call(t, "submit_questions", mapOf("ai" to "chatgpt", "fileId" to fileId, "questions" to listOf(q()))).first).isTrue()
        // 잘못된 ai 값 · 형식이 모두 틀린 문제
        assertThat(call(t, "get_jobs", mapOf("ai" to "gemini")).first).isTrue()
        val (err, msg) = call(t, "submit_questions", mapOf("ai" to "claude", "fileId" to fileId, "questions" to listOf(q(page = 99))))
        assertThat(err).isTrue()
        assertThat(msg.asString()).contains("형식이 맞는 문제가 없어요")
        assertThat(questionSources(fileId)).isEmpty()
    }

    @Test
    fun `24시간 안에 안 들어온 자료는 Gemini 가 만들고, 48시간 실행이 없으면 출처를 되돌려요`() {
        fake.respond = { listOf(GeneratedQuestion("Scheduling", "Which algorithm uses a time quantum?", listOf("FIFO", "Round robin", "SJF", "Priority"), 1, "Round robin uses a quantum.", 1, "Round robin gives each process a time quantum")) }
        val admin = f.newUser()
        val (late, _) = f.createCourse(admin)
        val (stale, _) = f.createCourse(admin, name = "알고리즘")
        call(token(admin), "get_jobs", mapOf("ai" to "claude"))
        setSource(admin, late, "claude").andExpect { status { isOk() } }
        setSource(admin, stale, "claude").andExpect { status { isOk() } }
        val lateFile = f.uploadReady(admin, late, text)
        val staleFile = f.uploadReady(admin, stale, text)
        val now = OffsetDateTime.now()

        // 아직 24시간 전 · 실행도 최근이면 아무것도 안 해요
        service.sweep(now)
        assertThat(questionSources(lateFile)).isEmpty()

        // P-BIT-20: 올린 지 25시간 → Gemini 로 만들어요. 출처는 그대로예요
        jdbc.sql("update files set created_at = :t where id = :f").param("t", now.minusHours(25)).param("f", lateFile).update()
        service.sweep(now)
        assertThat(questionSources(lateFile)).containsExactly("gemini")
        assertThat(questionSources(staleFile)).isEmpty()

        // P-BIT-15: 마지막 실행이 49시간 전 → 두 과목 모두 Gemini 로 되돌리고 알려요. 기다리던 자료는 바로 만들어요
        jdbc.sql("update myai_runs set last_run_at = :t where user_id = :u").param("t", now.minusHours(49)).param("u", admin.id).update()
        val result = service.sweep(now)
        assertThat(result.released).isEqualTo(2)
        assertThat(questionSources(staleFile)).containsExactly("gemini")
        val types = jdbc.sql("select quiz_source_type from courses where id in (:ids)").param("ids", listOf(late, stale)).query(String::class.java).list()
        assertThat(types).containsOnly("default")
        assertThat(f.notificationTypes(admin).count { it == "quiz_source_fallback" }).isEqualTo(2)
    }
}
