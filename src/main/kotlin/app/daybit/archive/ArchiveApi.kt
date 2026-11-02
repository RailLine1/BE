package app.daybit.archive

import app.daybit.global.common.Evidence
import app.daybit.global.common.PageResponse
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class ArchiveStats(val attempts: Int, val accuracy: Double, val averageSec: Int)

@Schema(description = "기록 게시물 (P-ARC-01, 02). 작성자는 항상 'Daybit 퀴즈 · 자동 게시'")
data class ArchivePost(
    val bitId: Long,
    val no: Int,
    val archivedAt: OffsetDateTime,
    val stem: String,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
    val evidence: Evidence,
    val stats: ArchiveStats,
    val memoCount: Int,
)

@Schema(description = "작성자 정보 없음 (P-ARC-03). mine 은 수정·삭제 버튼 표시용")
data class MemoItem(val memoId: Long, val body: String, val mine: Boolean)

data class MemoRequest(
    @field:NotBlank @field:Size(max = 500) val body: String,
)

@Tag(name = "8. 기록 · 메모")
@RestController
@RequestMapping("/api/v1")
class ArchiveController {

    @Operation(summary = "기록 목록 (P-ARC-01, 02) · 준비 중")
    @GetMapping("/courses/{courseId}/archive")
    fun archive(
        @LoginUser userId: Long,
        @PathVariable courseId: Long,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) size: Int,
    ): PageResponse<ArchivePost> = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "메모 목록 · 준비 중")
    @GetMapping("/archive/{bitId}/memos")
    fun memos(@LoginUser userId: Long, @PathVariable bitId: Long): List<MemoItem> =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "메모 남기기 (P-ARC-03) · 준비 중")
    @PostMapping("/archive/{bitId}/memos")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMemo(@LoginUser userId: Long, @PathVariable bitId: Long, @Valid @RequestBody req: MemoRequest): MemoItem =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "메모 수정 (작성자, P-ARC-04) · 준비 중")
    @PatchMapping("/memos/{memoId}")
    fun editMemo(@LoginUser userId: Long, @PathVariable memoId: Long, @Valid @RequestBody req: MemoRequest): MemoItem =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "메모 삭제 (작성자 · 관리자 · 공동 관리자, P-ARC-04) · 준비 중")
    @DeleteMapping("/memos/{memoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMemo(@LoginUser userId: Long, @PathVariable memoId: Long): Unit =
        throw ApiException(ErrorCode.NOT_IMPLEMENTED)
}
