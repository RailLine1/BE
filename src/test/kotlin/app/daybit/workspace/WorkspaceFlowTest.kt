package app.daybit.workspace

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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** GitHub 대신 쓰는 가짜. 실행 요청(dispatch)을 모아 두고, 실패하게 만들 수도 있어요 */
class FakeGithubApi : GithubApi {
    val dispatched = CopyOnWriteArrayList<Map<String, String>>()
    val created = CopyOnWriteArrayList<String>()
    @Volatile var dispatchFails = false
    @Volatile var accountFails = false
    private val ids = AtomicLong(7_000_000)

    override fun exchangeToken(code: String, redirectUri: String) = "gho_$code"
    override fun account(token: String) = if (accountFails) error("GitHub 503") else GithubAccount(token.removePrefix("gho_").hashCode().toLong().let { if (it < 0) -it else it } + ids.get(), "owner-" + token.removePrefix("gho_"))
    override fun createFromTemplate(token: String, template: String, owner: String, name: String): String {
        created += "$template -> $owner/$name"
        return "$owner/$name"
    }
    override fun dispatch(token: String, repo: String, workflow: String, inputs: Map<String, String>) {
        if (dispatchFails) error("workflow not found")
        dispatched += inputs + ("repo" to repo) + ("token" to token)
    }
}

/** OIDC 토큰 대신 "repo:{owner/name}" 문자열을 받아요 */
class FakeActionsVerifier : ActionsTokenVerifier {
    override fun repository(token: String): String =
        token.takeIf { it.startsWith("repo:") }?.removePrefix("repo:") ?: error("bad token")
}

@TestConfiguration(proxyBeanMethods = false)
class FakeWorkspaceConfig {
    @Bean @Primary fun fakeGithubApi() = FakeGithubApi()
    @Bean @Primary fun fakeActionsVerifier() = FakeActionsVerifier()
}

@Import(TestcontainersConfiguration::class, FakeWorkspaceConfig::class, FakeGeneratorConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val github: FakeGithubApi,
    @Autowired val gemini: FakeQuestionGenerator,
    @Autowired val workspace: WorkspaceService,
) {
    private val f = Fixtures(mvc, json, jdbc)

    @BeforeEach
    fun reset() {
        github.dispatched.clear()
        github.dispatchFails = false
        github.accountFails = false
        gemini.enabled = true
        gemini.respond = { emptyList() }
        gemini.requests.clear()
    }

    // ---------- 1 · 2단계 ----------

    @Test
    fun `GitHub 권한을 허용하면 내 계정에 비공개 저장소가 생기고, Codespace 주소를 받아요`() {
        val user = f.newUser()
        mvc.post("/api/v1/me/workspace/claudecode/setup") { header("Authorization", user.bearer) }
            .andExpect { status { isConflict() }; jsonPath("$.error.code") { value("WORKSPACE_NOT_LINKED") } }

        val repo = link(user)
        assertThat(repo).startsWith("owner-").endsWith("/daybit-workspace")
        assertThat(github.created.last()).startsWith("RailLine1/AI -> ")

        mvc.get("/api/v1/me/workspace") { header("Authorization", user.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.githubLinked") { value(true) }
            jsonPath("$.repo") { value(repo) }
            jsonPath("$.ais[0].ai") { value("claudecode") }
            jsonPath("$.ais[0].status") { value("none") }
        }
        mvc.get("/api/v1/me") { header("Authorization", user.bearer) }.andExpect { jsonPath("$.githubLinked") { value(true) } }
        // GitHub 토큰은 암호화해서 저장해요
        val stored = jdbc.sql("select github_token_enc from workspaces where user_id = :u").param("u", user.id).query(String::class.java).single()
        assertThat(stored).doesNotContain("gho_")

        mvc.post("/api/v1/me/workspace/codex/setup") { header("Authorization", user.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.url") { value("https://codespaces.new/$repo?quickstart=1") }
            jsonPath("$.command") { value("daybit-setup codex") }
        }
    }

    @Test
    fun `1단계 실패는 앱으로 error 를 붙여 돌려보내요 - 계정 조회 실패, 이미 연결한 GitHub 와 다른 계정`() {
        // GitHub 계정 조회가 실패해도 500 이 아니라 앱으로 돌아가요
        val user = f.newUser()
        github.accountFails = true
        assertThat(callback(user)).isEqualTo("daybit://workspace?error=GITHUB_FAILED")
        github.accountFails = false

        // 로그인용으로 다른 GitHub 를 이미 연결했으면 작업 공간을 만들지 않아요
        val other = f.newUser()
        jdbc.sql("update users set github_id = :g, github_login = 'someone-else' where id = :u")
            .param("g", 900_000_000L + other.id).param("u", other.id).update()
        assertThat(callback(other)).isEqualTo("daybit://workspace?error=GITHUB_ACCOUNT_MISMATCH")
        val repos = jdbc.sql("select count(*) from workspaces where user_id = :u and github_repo is not null").param("u", other.id)
            .query(Int::class.java).single()
        assertThat(repos).isZero()
    }

    // ---------- 3단계 ----------

    @Test
    fun `샘플 테스트는 워크플로를 실행하고, 근거가 맞는 3문항이 돌아오면 ready 가 돼요`() {
        val user = f.newUser()
        val repo = link(user)

        mvc.post("/api/v1/me/workspace/claudecode/test") { header("Authorization", user.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.testing") { value(true) }
            jsonPath("$.status") { value("none") }
        }
        val run = github.dispatched.single()
        assertThat(run).containsEntry("ai", "claudecode").containsEntry("repo", repo).containsEntry("server", "http://localhost:8080")
        assertThat(run["token"]).startsWith("gho_")
        val jobId = run.getValue("job_id")

        // 다른 저장소나 토큰 없이는 받을 수 없어요
        mvc.get("/internal/workspace/jobs/$jobId") { header("Authorization", "Bearer repo:someone/else") }.andExpect { status { isNotFound() } }
        mvc.get("/internal/workspace/jobs/$jobId").andExpect { status { isUnauthorized() } }
        mvc.get("/internal/workspace/jobs/$jobId") { header("Authorization", "Bearer forged") }.andExpect { status { isUnauthorized() } }

        val payload = job(jobId, repo)
        assertThat(payload.path("kind").asString()).isEqualTo("test")
        assertThat(payload.path("count").asInt()).isEqualTo(3)
        assertThat(payload.path("system").asString()).startsWith("너는 대학 강의 자료로")
        assertThat(payload.path("schema").path("properties").has("questions")).isTrue()
        assertThat(payload.path("prompt").asString()).contains("<page number=\"2\">").contains("문맥 교환")

        // 근거가 틀린 문제가 섞이면 실패하고 이유를 남겨요
        result(jobId, repo, mapOf("questions" to listOf(sampleQ(1, "스레드는 프로세스 안에서 실행되는 흐름의 단위"), sampleQ(2, "지어낸 문장이라 페이지에 없어요"))))
        mvc.get("/api/v1/me/workspace") { header("Authorization", user.bearer) }.andExpect {
            jsonPath("$.ais[0].status") { value("none") }
            jsonPath("$.ais[0].testing") { value(false) }
            jsonPath("$.ais[0].lastError") { isNotEmpty() }
        }
        // 끝난 작업에는 다시 보낼 수 없어요
        mvc.post("/internal/workspace/jobs/$jobId/result") {
            header("Authorization", "Bearer repo:$repo"); contentType = MediaType.APPLICATION_JSON; content = """{"error":"x"}"""
        }.andExpect { status { isNotFound() } }

        // 다시 테스트 → 3문항 모두 맞으면 ready
        mvc.post("/api/v1/me/workspace/claudecode/test") { header("Authorization", user.bearer) }
        val second = github.dispatched.last().getValue("job_id")
        job(second, repo)
        result(second, repo, mapOf("questions" to listOf(
            sampleQ(1, "같은 프로세스의 스레드들은 코드, 데이터, 열린 파일을 공유한다"),
            sampleQ(2, "같은 프로세스의 스레드끼리 전환하면 주소 공간을 바꾸지 않아도 되므로"),
            sampleQ(3, "경쟁 조건을 막으려면 뮤텍스 같은 동기화 도구로"),
        )))
        mvc.get("/api/v1/me/workspace") { header("Authorization", user.bearer) }.andExpect {
            jsonPath("$.ais[0].status") { value("ready") }
            jsonPath("$.ais[0].lastTestAt") { isNotEmpty() }
            jsonPath("$.ais[0].lastError") { doesNotExist() }
        }
    }

    // ---------- 생성 ----------

    @Test
    fun `출처가 작업 공간이면 자료가 올라올 때 워크플로가 만들고, 결과를 문제 은행에 넣어요`() {
        val admin = f.newUser()
        val repo = readyWorkspace(admin)
        val courseId = workspaceCourse(admin)

        val fileId = f.uploadReady(admin, courseId, "Semaphore wait and signal operations")
        val run = waitDispatch(1).last()
        assertThat(gemini.requests).isEmpty()
        val payload = job(run.getValue("job_id"), repo)
        assertThat(payload.path("kind").asString()).isEqualTo("generate")
        assertThat(payload.path("prompt").asString()).contains("Semaphore wait and signal")

        result(run.getValue("job_id"), repo, mapOf("questions" to listOf(
            sampleQ(1, "Semaphore wait and signal operations").copyAsMap(),
            sampleQ(1, "made up quote that is not there").copyAsMap(concept = "다른 개념"),
        )))
        val rows = jdbc.sql("select source, verified from questions where evidence_file_id = :f order by id").param("f", fileId)
            .query { rs, _ -> rs.getString(1) to rs.getBoolean(2) }.list()
        assertThat(rows).containsExactly("claudecode" to true, "claudecode" to false)
        assertThat(sourceType(courseId)).isEqualTo("workspace")
    }

    @Test
    fun `작업 공간이 실패하면 Gemini 로 전환해 다시 만들고 관리자에게 알려요`() {
        val admin = f.newUser()
        val repo = readyWorkspace(admin)
        val courseId = workspaceCourse(admin)
        gemini.respond = { listOf(GeneratedQuestion("교착", "Q?", listOf("a", "b", "c", "d"), 1, "E", 1, "Deadlock needs four conditions")) }

        val fileId = f.uploadReady(admin, courseId, "Deadlock needs four conditions")
        val run = waitDispatch(1).last()
        result(run.getValue("job_id"), repo, mapOf("error" to "claude: OAuth token expired"))

        waitGeminiQuestions(fileId)
        assertThat(sourceType(courseId)).isEqualTo("default")
        assertThat(f.notificationTypes(admin)).contains("quiz_source_fallback")
        mvc.get("/api/v1/me/workspace") { header("Authorization", admin.bearer) }.andExpect {
            jsonPath("$.ais[0].status") { value("expired") }
            jsonPath("$.ais[0].lastError") { value("claude: OAuth token expired") }
        }
    }

    @Test
    fun `실행 요청이 실패하거나 시간이 지나도 끝나지 않으면 Gemini 로 전환해요`() {
        val admin = f.newUser()
        readyWorkspace(admin)
        val first = workspaceCourse(admin)
        gemini.respond = { listOf(GeneratedQuestion("교착", "Q?", listOf("a", "b", "c", "d"), 1, "E", 1, "Circular wait breaks with ordering")) }

        github.dispatchFails = true
        val failedFile = f.uploadReady(admin, first, "Circular wait breaks with ordering")
        waitGeminiQuestions(failedFile)
        assertThat(sourceType(first)).isEqualTo("default")
        // 요청이 실패한 작업은 바로 닫혀서, 시간 초과 정리에서 다시 전환 · 재생성하지 않아요
        val open = jdbc.sql("select count(*) from workspace_jobs where file_id = :f and status in ('pending', 'running')")
            .param("f", failedFile).query(Int::class.java).single()
        assertThat(open).isZero()

        // 시간 초과: 다시 ready 로 돌리고 새 과목으로
        github.dispatchFails = false
        jdbc.sql("update workspace_ais set status = 'ready' where user_id = :u").param("u", admin.id).update()
        val second = workspaceCourse(admin)
        val slowFile = f.uploadReady(admin, second, "Circular wait breaks with ordering")
        waitDispatch(1)
        assertThat(workspace.sweep(OffsetDateTime.now().plusHours(1))).isGreaterThanOrEqualTo(1)
        waitGeminiQuestions(slowFile)
        assertThat(sourceType(second)).isEqualTo("default")
        Thread.sleep(500)
        assertThat(geminiCount(failedFile)).isEqualTo(1)
        assertThat(geminiCount(slowFile)).isEqualTo(1)
    }

    private fun geminiCount(fileId: Long) =
        jdbc.sql("select count(*) from questions where evidence_file_id = :f and source = 'gemini'").param("f", fileId)
            .query(Int::class.java).single()

    // ---------- 도우미 ----------

    /** 1단계를 끝까지 돌고 저장소 이름을 돌려줘요 */
    private fun link(user: TestUser): String {
        assertThat(callback(user)).isEqualTo("daybit://workspace?workspace=linked")
        return jdbc.sql("select github_repo from workspaces where user_id = :u").param("u", user.id).query(String::class.java).single()
    }

    /** 1단계 시작 → GitHub 콜백까지 돌고, 앱으로 돌아가는 주소를 돌려줘요 */
    private fun callback(user: TestUser): String {
        val url = mvc.post("/api/v1/me/workspace/github") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"redirect":"daybit://workspace"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let { json.readTree(it).path("url").asString() }
        val query = UriComponentsBuilder.fromUriString(url).build().queryParams
        assertThat(java.net.URLDecoder.decode(query.getFirst("scope"), Charsets.UTF_8)).contains("repo").contains("workflow")
        val back = mvc.get("/api/v1/auth/github/callback") {
            param("code", "c${user.id}"); param("state", java.net.URLDecoder.decode(query.getFirst("state"), Charsets.UTF_8))
        }.andExpect { status { isFound() } }.andReturn().response.getHeader("Location")!!
        return back
    }

    private fun readyWorkspace(user: TestUser): String {
        val repo = link(user)
        jdbc.sql("insert into workspace_ais (user_id, ai, status, last_test_at) values (:u, 'claudecode', 'ready', now())")
            .param("u", user.id).update()
        return repo
    }

    /** 퀴즈 출처를 내 작업 공간 Claude Code 로 고른 과목 */
    private fun workspaceCourse(admin: TestUser): Long {
        val courseId = f.createCourse(admin).first
        mvc.patch("/api/v1/courses/$courseId") {
            header("Authorization", admin.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizSource":{"type":"workspace","ai":"claudecode"}}"""
        }.andExpect { status { isOk() } }
        return courseId
    }

    private fun job(jobId: String, repo: String): JsonNode =
        mvc.get("/internal/workspace/jobs/$jobId") { header("Authorization", "Bearer repo:$repo") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString.let { json.readTree(it) }

    private fun result(jobId: String, repo: String, body: Map<String, Any>) =
        mvc.post("/internal/workspace/jobs/$jobId/result") {
            header("Authorization", "Bearer repo:$repo")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }.andExpect { status { isNoContent() } }

    private fun sampleQ(page: Int, quote: String) = mapOf(
        "concept" to "개념 $page", "stem" to "문제 $page", "choices" to listOf("가", "나", "다", "라"),
        "answerIndex" to 1, "explanation" to "해설", "evidencePage" to page, "evidenceQuote" to quote,
    )

    private fun Map<String, Any>.copyAsMap(concept: String? = null) = if (concept == null) this else this + ("concept" to concept)

    private fun sourceType(courseId: Long) =
        jdbc.sql("select quiz_source_type from courses where id = :c").param("c", courseId).query(String::class.java).single()

    private fun waitDispatch(atLeast: Int): List<Map<String, String>> {
        repeat(100) { if (github.dispatched.size >= atLeast) return github.dispatched.toList(); Thread.sleep(100) }
        error("워크플로 실행 요청이 오지 않았어요")
    }

    private fun waitGeminiQuestions(fileId: Long) {
        repeat(100) {
            val n = jdbc.sql("select count(*) from questions where evidence_file_id = :f and source = 'gemini'").param("f", fileId)
                .query(Int::class.java).single()
            if (n > 0) return
            Thread.sleep(100)
        }
        error("Gemini 로 다시 만들지 않았어요: $fileId")
    }
}
