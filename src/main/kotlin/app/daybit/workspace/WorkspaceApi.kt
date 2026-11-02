package app.daybit.workspace

import app.daybit.global.common.WorkspaceAi
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@Suppress("EnumEntryName")
enum class WorkspaceAiStatus { none, ready, expired }

data class WorkspaceAiItem(
    val ai: WorkspaceAi,
    val status: WorkspaceAiStatus,
    val lastTestAt: OffsetDateTime?,
)

data class WorkspaceResponse(
    val githubLinked: Boolean,
    val repo: String?,
    val ais: List<WorkspaceAiItem>,
)

data class RedirectResponse(val url: String)

/** [검증 필요] P-AI-07~10. PoC 결과에 따라 바뀔 수 있어요. */
@Tag(name = "11. AI 작업 공간")
@RestController
@RequestMapping("/api/v1/me/workspace")
class WorkspaceController {

    @Operation(summary = "작업 공간 상태 (P-AI-07) · 준비 중")
    @GetMapping
    fun get(@LoginUser userId: Long): WorkspaceResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "1단계: GitHub 권한 요청 URL (P-AI-08) · 준비 중")
    @PostMapping("/github")
    fun github(@LoginUser userId: Long): RedirectResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "2단계: Codespace 열기 URL (P-AI-08) · 준비 중")
    @PostMapping("/{ai}/setup")
    fun setup(@LoginUser userId: Long, @PathVariable ai: WorkspaceAi): RedirectResponse =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "3단계: 샘플 3문항 테스트 실행 (P-AI-08) · 준비 중")
    @PostMapping("/{ai}/test")
    fun test(@LoginUser userId: Long, @PathVariable ai: WorkspaceAi): WorkspaceAiItem =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)
}
