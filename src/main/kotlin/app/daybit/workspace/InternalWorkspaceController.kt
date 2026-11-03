package app.daybit.workspace

import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 관리자 저장소의 GitHub Actions 워크플로가 부르는 곳 (앱은 쓰지 않아요, Swagger 에도 없어요).
 * Authorization: Bearer {GitHub Actions OIDC 토큰, audience=daybit}. 토큰의 repository 가 작업 주인의 저장소여야 해요.
 */
@Hidden
@RestController
class InternalWorkspaceController(
    private val service: WorkspaceService,
    private val verifier: ActionsTokenVerifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/internal/workspace/jobs/{jobId}")
    fun job(@PathVariable jobId: UUID, @RequestHeader("Authorization", required = false) auth: String?): WorkspaceJobPayload =
        service.job(jobId, repository(auth))

    /** 본문: {"questions":[...]} 또는 {"error":"..."} */
    @PostMapping("/internal/workspace/jobs/{jobId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun result(@PathVariable jobId: UUID, @RequestHeader("Authorization", required = false) auth: String?, @RequestBody body: JsonNode) {
        val questions = body.path("questions").takeIf { it.isArray }?.toList()?.map {
            GeneratedQuestion(
                concept = it.path("concept").asString(""),
                stem = it.path("stem").asString(""),
                choices = it.path("choices").toList().map { c -> c.asString("") },
                answerIdx = it.path("answerIndex").asInt(-1),
                explanation = it.path("explanation").asString(""),
                evidencePage = it.path("evidencePage").asInt(-1),
                evidenceQuote = it.path("evidenceQuote").asString(""),
            )
        }
        val error = body.path("error").asString("").ifBlank { null }
        service.result(jobId, repository(auth), questions, error)
    }

    private fun repository(auth: String?): String {
        val token = auth?.removePrefix("Bearer ")?.trim().orEmpty()
        if (token.isEmpty()) throw ApiException(ErrorCode.UNAUTHORIZED)
        return try {
            verifier.repository(token)
        } catch (e: Exception) {
            log.warn("작업 공간 OIDC 토큰 검증 실패: {}", e.message)
            throw ApiException(ErrorCode.UNAUTHORIZED)
        }
    }
}

/** 끝나지 않은 작업 공간 작업을 10분마다 정리해요. 테스트에서는 daybit.batch.enabled=false 로 꺼요 */
@Component
@ConditionalOnProperty(prefix = "daybit.batch", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WorkspaceSweeper(private val service: WorkspaceService, private val clock: Clock) {
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    fun sweep() {
        runCatching { service.sweep(OffsetDateTime.now(clock)) }
    }
}
