package app.daybit.booth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class BoothStartRequest(
    @field:NotBlank @field:Size(max = 30) val nickname: String,
)

data class BoothQuestion(val stem: String, val choices: List<String>)

data class BoothStartResponse(
    @field:Schema(description = "제출할 때 쓰는 풀이 id")
    val playId: UUID,
    val questions: List<BoothQuestion>,
)

data class BoothSubmitRequest(
    @field:Schema(description = "문제 순서대로 고른 보기 번호(0~3)")
    @field:Size(min = 1, max = 20) val choices: List<@Min(0) @Max(3) Int>,
)

data class BoothAnswer(val answer: Int, val myChoice: Int, val correct: Boolean, val explanation: String)

data class BoothResult(
    val nickname: String,
    val correct: Int,
    val total: Int,
    val elapsedMs: Int,
    val rank: Int,
    val players: Int,
    val answers: List<BoothAnswer>,
)

data class BoothRankRow(val rank: Int, val nickname: String, val correct: Int, val elapsedMs: Int)

data class BoothRanking(
    val total: Int,
    val players: Int,
    val rows: List<BoothRankRow>,
)

/** 행사 부스 운영용 퀴즈. 앱 기능과 별개이고 로그인 없이 써요 */
@Tag(name = "9. 부스 퀴즈", description = "행사 부스용. 로그인 없이 닉네임 하나당 한 번 풀고, 모두의 기록이 랭킹에 공개돼요.")
@RestController
@RequestMapping("/api/v1/booth")
class BoothController(private val service: BoothService) {

    @Operation(summary = "🔓 풀기 시작", description = "닉네임(앞뒤 공백 제거, 1~10자)을 받아 문제를 돌려줘요. 쓴 적 있는 닉네임이면 409 NICKNAME_TAKEN. 시간은 이때부터 재요")
    @PostMapping("/plays")
    fun start(@Valid @RequestBody req: BoothStartRequest): BoothStartResponse = service.start(req.nickname)

    @Operation(summary = "🔓 제출 → 결과와 내 순위", description = "보기 수가 문제 수와 다르면 400, 두 번째 제출은 409 ALREADY_ATTEMPTED, 없는 풀이 id 는 404")
    @PostMapping("/plays/{playId}/submit")
    fun submit(@PathVariable playId: UUID, @Valid @RequestBody req: BoothSubmitRequest): BoothResult =
        service.submit(playId, req.choices)

    @Operation(summary = "🔓 랭킹", description = "맞힌 수가 많은 순, 같으면 빨리 푼 순. 제출한 사람만 나와요")
    @GetMapping("/ranking")
    fun ranking(@RequestParam(defaultValue = "100") @Min(1) @Max(500) limit: Int): BoothRanking = service.ranking(limit)
}
