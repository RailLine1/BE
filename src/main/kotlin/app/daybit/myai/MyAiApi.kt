package app.daybit.myai

import app.daybit.global.common.MyAi
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class MyAiItem(
    val ai: MyAi,
    @field:Schema(description = "예약 실행이 한 번이라도 들어왔으면 true. 그때부터 퀴즈 출처로 고를 수 있어요 (P-AI-08, 10)")
    val registered: Boolean,
    @field:Schema(description = "마지막 예약 실행 시각. 48시간 동안 없으면 이 AI 가 출처인 과목은 Gemini 로 바뀌어요 (P-BIT-15)")
    val lastRunAt: OffsetDateTime?,
    @field:Schema(description = "[예약 문장 복사]로 AI 앱에 붙여 넣을 문장 (P-AI-08, 11)")
    val schedulePrompt: String,
)

data class MyAiResponse(val ais: List<MyAiItem>)

@Tag(name = "11. 내 AI 출제", description = "관리자 AI 앱의 예약 작업이 커넥터로 새 자료를 받아 문제를 만들어 보내요 (P-AI-07~11). 등록은 ① 커넥터 등록 ② 예약 문장 붙여 넣기")
@RestController
@RequestMapping("/api/v1/me/myai")
class MyAiController(private val service: MyAiService) {

    @Operation(summary = "내 AI 출제 등록 상태 · 예약 문장 (P-AI-08, 10)", description = "Claude · ChatGPT 순서로 줘요")
    @GetMapping
    fun get(@LoginUser userId: Long): MyAiResponse = service.status(userId)
}
