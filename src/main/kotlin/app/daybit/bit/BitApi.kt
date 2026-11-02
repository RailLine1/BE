package app.daybit.bit

import app.daybit.global.common.Evidence
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime

@Suppress("EnumEntryName")
enum class TicketStatus { open, done }

@Suppress("EnumEntryName")
enum class RecordDay { today, yesterday }

@Schema(description = "Bit 탭 티켓 (P-BIT-07). 안 푼 티켓이 앞에 와요.")
data class BitTicket(
    val bitId: Long,
    val courseId: Long,
    val courseName: String,
    val icon: String,
    val intro: String?,
    val no: Int,
    val status: TicketStatus,
    /** status=done 일 때 걸린 시간 */
    val elapsedSec: Int?,
    val streak: Int,
    @field:Schema(description = "이번 주 7칸 (월~일), 1=푼 날")
    val week: List<Int>,
)

data class BitQuestion(val stem: String, val choices: List<String>)

@Schema(description = "정답·해설은 제출 후에만 공개 (P-BIT-09)")
data class StartResponse(
    val startedAt: OffsetDateTime,
    val no: Int,
    val question: BitQuestion,
)

data class SubmitRequest(
    @field:Min(0) @field:Max(3) val choice: Int,
)

@Schema(description = "결과 티켓 (P-BIT-10, 11)")
data class ResultTicket(
    val courseName: String,
    val no: Int,
    val correct: Boolean,
    val answer: Int,
    val myChoice: Int,
    val elapsedSec: Int,
    val averageSec: Int,
    val topPercent: Int,
    val streak: Int,
    val week: List<Int>,
    val score: Int,
    val explanation: String,
    val evidence: Evidence,
)

data class PersonRecord(
    val userId: Long,
    val nickname: String,
    val correct: Boolean,
    val elapsedSec: Int,
    val me: Boolean,
)

data class MyBit(
    val streak: Int,
    val best: Int,
    val accuracy: Int,
    @field:Schema(description = "5주 격자, 날짜별 푼 Bit 수 (P-BIT-13, 0~4+)")
    val grid: List<Int>,
)

data class Explanation(
    val stem: String,
    val choices: List<String>,
    val answer: Int,
    val myChoice: Int?,
    val text: String,
    val evidence: Evidence,
)

data class RecordsResponse(
    val day: RecordDay,
    val bitNo: Int,
    val people: List<PersonRecord>,
    val mine: MyBit,
    val notify: Boolean,
    val explanation: Explanation?,
)

data class StreakDay(val date: LocalDate, val count: Int)

@Schema(description = "학습 기록 격자. 과목 구분 없음, 누구에게나 공개 (P-BIT-13)")
data class StreakResponse(
    val current: Int,
    val best: Int,
    val thisMonth: Int,
    val days: List<StreakDay>,
)

data class ProfileResponse(
    val userId: Long,
    val nickname: String,
    val streak: StreakResponse,
)

data class WelcomeResponse(
    val nickname: String,
    val newMaterials: List<NewMaterials>,
    val bitsLeft: Int,
    val streak: Int,
) {
    data class NewMaterials(val courseId: Long, val courseName: String, val count: Int)
}

@Tag(name = "7. Bit")
@RestController
@RequestMapping("/api/v1")
class BitController {

    @Operation(summary = "오늘의 Bit 티켓 (P-BIT-07) · 준비 중")
    @GetMapping("/bits/today")
    fun today(@LoginUser userId: Long): List<BitTicket> = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "풀기 시작: 문제 + 서버 시작 시각 (P-BIT-08) · 준비 중")
    @PostMapping("/bits/{bitId}/start")
    fun start(@LoginUser userId: Long, @PathVariable bitId: Long): StartResponse =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "제출 → 결과 티켓 (P-BIT-09, 10) · 준비 중", description = "두 번째 제출은 409 ALREADY_ATTEMPTED")
    @PostMapping("/bits/{bitId}/submit")
    fun submit(@LoginUser userId: Long, @PathVariable bitId: Long, @RequestBody req: SubmitRequest): ResultTicket =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "결과 티켓 다시 보기 (P-BIT-11) · 준비 중")
    @GetMapping("/bits/{bitId}/ticket")
    fun ticket(@LoginUser userId: Long, @PathVariable bitId: Long): ResultTicket =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "오늘의 결과: 함께 듣는 사람 + 내 비트 + 해설 (P-BIT-12) · 준비 중")
    @GetMapping("/courses/{courseId}/records")
    fun records(
        @LoginUser userId: Long,
        @PathVariable courseId: Long,
        @RequestParam(defaultValue = "today") day: RecordDay,
    ): RecordsResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "다른 사람 프로필: 닉네임 + 학습 기록 격자 (P-BIT-13) · 준비 중")
    @GetMapping("/users/{targetUserId}/profile")
    fun profile(@LoginUser userId: Long, @PathVariable targetUserId: Long): ProfileResponse =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "내 학습 기록 격자 (P-BIT-13) · 준비 중")
    @GetMapping("/me/streak")
    fun myStreak(
        @LoginUser userId: Long,
        @RequestParam(defaultValue = "15") @Min(1) @Max(53) weeks: Int,
    ): StreakResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "환영 화면 데이터 (P-ACC-05) · 준비 중")
    @GetMapping("/me/welcome")
    fun welcome(@LoginUser userId: Long): WelcomeResponse = throw ApiException(ErrorCode.NOT_IMPLEMENTED)
}
