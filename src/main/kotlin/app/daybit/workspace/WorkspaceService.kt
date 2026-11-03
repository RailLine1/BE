package app.daybit.workspace

import app.daybit.auth.WorkspaceLinker
import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.bit.generation.Material
import app.daybit.bit.generation.PageText
import app.daybit.bit.generation.QuestionPrompt
import app.daybit.bit.generation.QuestionStore
import app.daybit.bit.generation.WorkspaceFallbackEvent
import app.daybit.bit.generation.WorkspaceGeneration
import app.daybit.course.CourseService
import app.daybit.global.common.WorkspaceAi
import app.daybit.global.config.DaybitProperties
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** 워크플로가 받아 가는 작업 내용 */
data class WorkspaceJobPayload(
    val jobId: UUID,
    val kind: String,
    val ai: String,
    val count: Int,
    /** 출제 규칙 (BE resources/prompts 와 같은 파일) */
    val system: String,
    /** 응답 JSON 스키마 */
    val schema: JsonNode,
    /** 과목 · 자료 · 페이지 본문 */
    val prompt: String,
)

/**
 * AI 작업 공간 PoC (P-AI-07~09, 결정 D11). [검증 필요]
 * 1단계 GitHub 연결 → 관리자 계정에 템플릿으로 비공개 저장소 생성
 * 2단계 Codespace 에서 daybit-setup 으로 구독 로그인(구독 토큰은 저장소 Secret 에만, 서버는 모름)
 * 3단계 샘플 3문항 테스트 → 성공하면 ready
 * 이후 퀴즈 출처가 작업 공간인 과목은 자료가 ready 가 될 때마다 워크플로로 문제를 만들어요.
 * 실패 · 만료 · 시간 초과면 즉시 Gemini 로 전환하고 관리자 · 공동 관리자에게 알려요 (P-BIT-15).
 */
@Service
class WorkspaceService(
    private val jdbc: JdbcClient,
    private val props: DaybitProperties,
    private val github: GithubApi,
    private val cipher: TokenCipher,
    private val prompt: QuestionPrompt,
    private val store: QuestionStore,
    private val courses: CourseService,
    private val events: ApplicationEventPublisher,
    private val json: JsonMapper,
) : WorkspaceLinker, WorkspaceGeneration {
    private val log = LoggerFactory.getLogger(javaClass)

    private val samplePages: List<PageText> by lazy {
        val text = ClassPathResource("prompts/sample-pages.json").inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        json.readTree(text).toList().map { PageText(it.path("page").asInt(), it.path("text").asString()) }
    }

    // ---------- 상태 ----------

    @Transactional(readOnly = true)
    fun status(userId: Long): WorkspaceResponse {
        val repo = repoOf(userId)
        val rows = jdbc.sql("select ai, status, last_test_at, last_error from workspace_ais where user_id = :u").param("u", userId)
            .query { rs, _ ->
                rs.getString("ai") to Triple(rs.getString("status"), rs.getObject("last_test_at", OffsetDateTime::class.java), rs.getString("last_error"))
            }.list().toMap()
        val testing = jdbc.sql(
            "select distinct ai from workspace_jobs where user_id = :u and kind = 'test' and status in ('pending', 'running')",
        ).param("u", userId).query(String::class.java).list().toSet()
        val ais = WorkspaceAi.entries.map { ai ->
            val row = rows[ai.name]
            WorkspaceAiItem(
                ai = ai,
                status = row?.first?.let { WorkspaceAiStatus.valueOf(it) } ?: WorkspaceAiStatus.none,
                lastTestAt = row?.second,
                lastError = row?.third,
                testing = ai.name in testing,
            )
        }
        return WorkspaceResponse(githubLinked = repo != null, repo = repo, ais = ais)
    }

    // ---------- 1단계: GitHub ----------

    /** GitHub 콜백에서 불러요. 저장소를 만들고 토큰을 암호화해 저장해요. 실패하면 앱으로 돌려보낼 error 값 */
    @Transactional
    override fun link(userId: Long, code: String, redirectUri: String): String? {
        val (token, account) = try {
            val token = github.exchangeToken(code, redirectUri)
            token to github.account(token)
        } catch (e: Exception) {
            log.warn("작업 공간 GitHub 토큰 교환 · 계정 조회 실패", e)
            return "GITHUB_FAILED"
        }
        val owner = jdbc.sql("select id from users where github_id = :g").param("g", account.id)
            .query(Long::class.java).optional().orElse(null)
        if (owner != null && owner != userId) return ErrorCode.GITHUB_ALREADY_LINKED.name
        // 이미 다른 GitHub 계정을 연결했으면 그 계정으로만 작업 공간을 만들어요 (로그인 계정과 저장소 주인이 달라지지 않게)
        val otherAccount = jdbc.sql("select count(*) from users where id = :u and github_id is not null and github_id <> :g")
            .param("u", userId).param("g", account.id).query(Int::class.java).single() > 0
        if (otherAccount) return GITHUB_ACCOUNT_MISMATCH
        // 작업 공간은 GitHub 연결이 전제예요 (P-ACC-06). 아직 연결 안 했으면 같이 연결해요
        jdbc.sql("update users set github_id = :g, github_login = :l where id = :u and github_id is null")
            .param("g", account.id).param("l", account.login).param("u", userId).update()

        val repo = try {
            github.createFromTemplate(token, props.workspace.templateRepo, account.login, props.workspace.repoName)
        } catch (e: Exception) {
            log.warn("작업 공간 저장소 생성 실패", e)
            return "WORKSPACE_REPO_FAILED"
        }
        jdbc.sql(
            """
            insert into workspaces (user_id, github_repo, github_login, github_token_enc, linked_at)
            values (:u, :repo, :login, :token, now())
            on conflict (user_id) do update
               set github_repo = :repo, github_login = :login, github_token_enc = :token, linked_at = now()
            """.trimIndent(),
        ).param("u", userId).param("repo", repo).param("login", account.login).param("token", cipher.encrypt(token)).update()
        return null
    }

    // ---------- 2단계: Codespace ----------

    fun setup(userId: Long, ai: WorkspaceAi): RedirectResponse {
        val repo = repoOf(userId) ?: throw ApiException(ErrorCode.WORKSPACE_NOT_LINKED)
        return RedirectResponse(url = "https://codespaces.new/$repo?quickstart=1", command = "daybit-setup ${ai.name}")
    }

    // ---------- 3단계: 샘플 테스트 ----------

    @Transactional
    fun test(userId: Long, ai: WorkspaceAi): WorkspaceAiItem {
        repoOf(userId) ?: throw ApiException(ErrorCode.WORKSPACE_NOT_LINKED)
        jdbc.sql("insert into workspace_ais (user_id, ai) values (:u, :ai) on conflict do nothing")
            .param("u", userId).param("ai", ai.name).update()
        val error = runCatching { createAndDispatch(userId, ai.name, "test", null, null, SAMPLE_COUNT) }
            .exceptionOrNull()?.let { "실행 요청 실패: ${it.message?.take(200)}" }
        if (error != null) {
            jdbc.sql("update workspace_ais set last_error = :e where user_id = :u and ai = :ai")
                .param("e", error).param("u", userId).param("ai", ai.name).update()
        }
        return status(userId).ais.first { it.ai == ai }
    }

    // ---------- 문제 생성 (퀴즈 출처 = 작업 공간) ----------

    override fun request(courseId: Long, fileId: Long, count: Int): Boolean {
        val source = jdbc.sql("select quiz_source_user_id, quiz_source_ai from courses where id = :c and quiz_source_type = 'workspace'")
            .param("c", courseId)
            .query { rs, _ -> rs.getLong("quiz_source_user_id") to rs.getString("quiz_source_ai") }
            .optional().orElse(null) ?: return false
        val (userId, ai) = source
        return try {
            val ready = jdbc.sql("select count(*) from workspace_ais where user_id = :u and ai = :ai and status = 'ready'")
                .param("u", userId).param("ai", ai).query(Int::class.java).single() > 0
            if (!ready) error("작업 공간 AI가 준비되지 않았어요")
            createAndDispatch(userId, ai, "generate", courseId, fileId, count)
            true
        } catch (e: Exception) {
            log.warn("작업 공간 생성 요청 실패, Gemini 로 전환: course={}", courseId, e)
            expire(userId, ai, "실행 요청 실패: ${e.message?.take(200)}")
            courses.releaseQuizSource(courseId, userId)
            false
        }
    }

    // ---------- 워크플로가 부르는 곳 (/internal) ----------

    /** 워크플로가 작업 내용을 받아 가요. repository 는 OIDC 토큰에서 확인한 저장소 */
    @Transactional(noRollbackFor = [ApiException::class])
    fun job(jobId: UUID, repository: String): WorkspaceJobPayload {
        val job = openJob(jobId, repository)
        jdbc.sql("update workspace_jobs set status = 'running' where id = :id").param("id", jobId).update()
        val material = materialOf(job) ?: run {
            fail(job, "자료를 찾을 수 없어요")
            throw ApiException(ErrorCode.NOT_FOUND)
        }
        return WorkspaceJobPayload(
            jobId = jobId,
            kind = job.kind,
            ai = job.ai,
            count = job.count,
            system = prompt.system,
            schema = prompt.schema,
            prompt = prompt.user(material.request(job.count)),
        )
    }

    /** 워크플로가 결과(문제 목록) 또는 오류를 돌려줘요 */
    @Transactional
    fun result(jobId: UUID, repository: String, questions: List<GeneratedQuestion>?, error: String?) {
        val job = openJob(jobId, repository)
        if (error != null || questions.isNullOrEmpty()) {
            fail(job, error?.take(500) ?: "문제를 받지 못했어요")
            return
        }
        val material = materialOf(job) ?: return fail(job, "자료를 찾을 수 없어요")
        when (job.kind) {
            "test" -> {
                val pages = material.pages.associate { it.page to it.text }
                val good = questions.count { store.isWellFormed(it, pages.keys) && store.isGrounded(it, pages.getValue(it.evidencePage)) }
                if (good < SAMPLE_COUNT) return fail(job, "형식과 근거가 맞는 문제가 ${SAMPLE_COUNT}개 미만이에요 ($good/${questions.size})")
                jdbc.sql(
                    """
                    update workspace_ais set status = 'ready', last_test_at = now(), last_error = null
                     where user_id = :u and ai = :ai
                    """.trimIndent(),
                ).param("u", job.userId).param("ai", job.ai).update()
            }
            else -> {
                val saved = store.save(material, questions, job.ai)
                if (saved == 0) return fail(job, "형식이 맞는 문제가 없어요")
            }
        }
        jdbc.sql("update workspace_jobs set status = 'done', finished_at = now() where id = :id").param("id", jobId).update()
    }

    /** 오래 끝나지 않은 작업을 실패로 처리해요 (BitScheduler 와 같이 10분마다) */
    @Transactional
    fun sweep(now: OffsetDateTime): Int {
        val stale = jdbc.sql("select id from workspace_jobs where status in ('pending', 'running') and created_at < :cutoff")
            .param("cutoff", now.minus(props.workspace.jobTimeout))
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }.list()
        stale.forEach { id -> loadJob(id)?.let { fail(it, "시간 초과 (${props.workspace.jobTimeout.toMinutes()}분)") } }
        return stale.size
    }

    // ---------- 공통 ----------

    private data class Job(val id: UUID, val userId: Long, val ai: String, val kind: String, val courseId: Long?, val fileId: Long?, val count: Int)

    private fun createAndDispatch(userId: Long, ai: String, kind: String, courseId: Long?, fileId: Long?, count: Int) {
        val (repo, tokenEnc) = jdbc.sql("select github_repo, github_token_enc from workspaces where user_id = :u").param("u", userId)
            .query { rs, _ -> rs.getString("github_repo") to rs.getString("github_token_enc") }
            .optional().orElseThrow { ApiException(ErrorCode.WORKSPACE_NOT_LINKED) }
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into workspace_jobs (id, user_id, ai, kind, course_id, file_id, count)
            values (:id, :u, :ai, :kind, :c, :f, :count)
            """.trimIndent(),
        ).param("id", id).param("u", userId).param("ai", ai).param("kind", kind)
            .param("c", courseId).param("f", fileId).param("count", count).update()
        try {
            github.dispatch(
                cipher.decrypt(tokenEnc), repo, props.workspace.workflow,
                mapOf("job_id" to id.toString(), "ai" to ai, "server" to props.publicBaseUrl.trimEnd('/')),
            )
        } catch (e: Exception) {
            // 요청 자체가 실패한 작업은 바로 닫아요. 남겨 두면 시간 초과 정리에서 한 번 더 실패 처리돼요
            jdbc.sql("update workspace_jobs set status = 'failed', error = :e, finished_at = now() where id = :id")
                .param("e", "실행 요청 실패: ${e.message?.take(200)}").param("id", id).update()
            throw e
        }
    }

    /** 실패 처리. 테스트면 오류만 남기고, 생성이면 Gemini 로 전환하고 그 자료를 Gemini 로 다시 만들어요 (P-BIT-15) */
    private fun fail(job: Job, reason: String) {
        jdbc.sql("update workspace_jobs set status = 'failed', error = :e, finished_at = now() where id = :id")
            .param("e", reason).param("id", job.id).update()
        if (job.kind == "test") {
            jdbc.sql("update workspace_ais set last_error = :e where user_id = :u and ai = :ai")
                .param("e", reason).param("u", job.userId).param("ai", job.ai).update()
            return
        }
        expire(job.userId, job.ai, reason)
        job.courseId?.let { courses.releaseQuizSource(it, job.userId) }
        job.fileId?.let { events.publishEvent(WorkspaceFallbackEvent(it)) }
    }

    private fun expire(userId: Long, ai: String, reason: String) {
        jdbc.sql("update workspace_ais set status = 'expired', last_error = :e where user_id = :u and ai = :ai")
            .param("e", reason).param("u", userId).param("ai", ai).update()
    }

    /** 진행 중인 작업이고, 요청한 저장소가 작업 주인의 작업 공간이어야 해요. 아니면 404 */
    private fun openJob(jobId: UUID, repository: String): Job {
        val job = loadJob(jobId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        val repo = repoOf(job.userId)
        val status = jdbc.sql("select status from workspace_jobs where id = :id").param("id", jobId).query(String::class.java).single()
        if (repo == null || !repo.equals(repository, ignoreCase = true) || status !in setOf("pending", "running")) {
            throw ApiException(ErrorCode.NOT_FOUND)
        }
        return job
    }

    private fun loadJob(jobId: UUID): Job? =
        jdbc.sql("select id, user_id, ai, kind, course_id, file_id, count from workspace_jobs where id = :id").param("id", jobId)
            .query { rs, _ ->
                Job(
                    rs.getObject("id", UUID::class.java), rs.getLong("user_id"), rs.getString("ai"), rs.getString("kind"),
                    rs.getObject("course_id")?.let { (it as Number).toLong() }, rs.getObject("file_id")?.let { (it as Number).toLong() },
                    rs.getInt("count"),
                )
            }.optional().orElse(null)

    private fun materialOf(job: Job): Material? =
        if (job.kind == "test") Material(0, "샘플 과목", 0, "샘플 자료", null, samplePages)
        else job.fileId?.let { store.material(it) }

    private fun repoOf(userId: Long): String? =
        jdbc.sql("select github_repo from workspaces where user_id = :u and github_repo is not null").param("u", userId)
            .query(String::class.java).optional().orElse(null)

    companion object {
        private const val SAMPLE_COUNT = 3
        /** 1단계: 이미 연결한 GitHub 와 다른 계정으로 허용했어요 (`?error=` 값) */
        const val GITHUB_ACCOUNT_MISMATCH = "GITHUB_ACCOUNT_MISMATCH"
    }
}
