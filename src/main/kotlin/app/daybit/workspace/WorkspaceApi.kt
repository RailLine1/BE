package app.daybit.workspace

import app.daybit.global.common.WorkspaceAi
import app.daybit.auth.GithubAuthService
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@Suppress("EnumEntryName")
enum class WorkspaceAiStatus { none, ready, expired }

data class WorkspaceAiItem(
    val ai: WorkspaceAi,
    val status: WorkspaceAiStatus,
    val lastTestAt: OffsetDateTime?,
    @field:Schema(description = "마지막 실패 이유 (테스트 실패, 실행 요청 실패, 시간 초과 등). 성공하면 null")
    val lastError: String? = null,
    @field:Schema(description = "샘플 테스트가 진행 중이면 true. 끝날 때까지 몇 초마다 다시 조회해요")
    val testing: Boolean = false,
)

data class WorkspaceResponse(
    val githubLinked: Boolean,
    val repo: String?,
    val ais: List<WorkspaceAiItem>,
)

data class RedirectResponse(
    val url: String,
    @field:Schema(description = "2단계: Codespace 터미널에서 실행할 명령")
    val command: String? = null,
)

data class WorkspaceGithubRequest(
    @field:Schema(description = "GitHub 권한 허용 뒤 돌아갈 앱 주소. 성공하면 ?workspace=linked, 실패하면 ?error= 가 붙어요")
    @field:NotBlank val redirect: String,
)

/** [검증 필요] P-AI-07~09, 결정 D11. PoC 결과에 따라 바뀔 수 있어요. */
@Tag(name = "11. AI 작업 공간")
@RestController
@RequestMapping("/api/v1/me/workspace")
class WorkspaceController(private val service: WorkspaceService, private val github: GithubAuthService) {

    @Operation(summary = "작업 공간 상태 (P-AI-07)")
    @GetMapping
    fun get(@LoginUser userId: Long): WorkspaceResponse = service.status(userId)

    @Operation(summary = "1단계: GitHub 권한 요청 URL (P-AI-08)", description = "받은 url 을 외부 브라우저로 열어요. 허용하면 내 GitHub 에 비공개 저장소 daybit-workspace 가 생겨요")
    @PostMapping("/github")
    fun github(@LoginUser userId: Long, @Valid @RequestBody req: WorkspaceGithubRequest): RedirectResponse =
        RedirectResponse(github.authorizeUrl(req.redirect, linkUserId = userId, workspace = true))

    @Operation(summary = "2단계: Codespace 열기 URL (P-AI-08)", description = "url 을 열고 터미널에서 command 를 실행해 구독 로그인해요. 1단계 전이면 409 WORKSPACE_NOT_LINKED")
    @PostMapping("/{ai}/setup")
    fun setup(@LoginUser userId: Long, @PathVariable ai: WorkspaceAi): RedirectResponse =
        service.setup(userId, ai)

    @Operation(summary = "3단계: 샘플 3문항 테스트 실행 (P-AI-08)", description = "워크플로를 실행하고 바로 돌아와요(testing=true). GET /me/workspace 로 결과를 확인해요. 성공하면 status=ready")
    @PostMapping("/{ai}/test")
    fun test(@LoginUser userId: Long, @PathVariable ai: WorkspaceAi): WorkspaceAiItem =
        service.test(userId, ai)
}
