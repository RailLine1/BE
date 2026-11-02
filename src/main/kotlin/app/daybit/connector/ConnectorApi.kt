package app.daybit.connector

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@Suppress("EnumEntryName")
enum class ConnectorClient { claude, chatgpt, claudecode, codex }

data class ConnectorClientStatus(
    val client: ConnectorClient,
    /** null 이면 아직 등록 안 됨 (P-AI-04: 첫 호출이 오면 등록됨) */
    val lastCalledAt: OffsetDateTime?,
)

data class ConnectorResponse(
    @field:Schema(description = "전체 주소는 발급 직후에만 보여요. 이후엔 끝부분만 가린 주소 (P-AI-02)")
    val url: String,
    val clients: List<ConnectorClientStatus>,
)

@Tag(name = "10. 커넥터")
@RestController
@RequestMapping("/api/v1/me/connector")
class ConnectorController {

    @Operation(summary = "내 커넥터 주소 · 등록 상태 (P-AI-02, 04) · 준비 중")
    @GetMapping
    fun get(@LoginUser userId: Long): ConnectorResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "주소 재발급 — 이전 주소 즉시 무효 (P-AI-02) · 준비 중")
    @PostMapping("/reissue")
    fun reissue(@LoginUser userId: Long): ConnectorResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)
}
