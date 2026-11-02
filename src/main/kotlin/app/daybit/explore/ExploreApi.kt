package app.daybit.explore

import app.daybit.global.common.PageResponse
import app.daybit.global.security.LoginUser
import app.daybit.course.Visibility
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Suppress("EnumEntryName")
enum class ExploreSort { subscribers, newest }

@Suppress("EnumEntryName")
enum class MyStatus { subscribed, pending, none }

@Schema(description = "탐색 목록 한 줄: 아이콘 · 과목명 · 관리자만 (구독자 수 없음, P-EXP-05)")
data class ExploreItem(
    val code: String,
    val name: String,
    val icon: String,
    val admin: String,
    val ended: Boolean,
    val myStatus: MyStatus,
)

data class CourseInfo(
    val code: String,
    val name: String,
    val icon: String,
    val admin: String,
    val term: String?,
    val visibility: Visibility,
    val intro: String?,
    val ended: Boolean,
    val myStatus: MyStatus,
)

@Suppress("EnumEntryName")
enum class SubscribeResult { subscribed, pending }

data class SubscribeResponse(val result: SubscribeResult, val courseId: Long)

@Tag(name = "4. 탐색 · 구독")
@RestController
@RequestMapping("/api/v1/explore")
class ExploreController(private val explore: ExploreService) {

    @Operation(summary = "공개 과목 목록 (P-EXP-01, 02, 05)")
    @GetMapping("/courses")
    fun list(
        @LoginUser userId: Long,
        @RequestParam(defaultValue = "subscribers") sort: ExploreSort,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "8") @Min(1) @Max(50) size: Int,
    ): PageResponse<ExploreItem> = explore.list(userId, sort, page, size)

    @Operation(summary = "검색: 과목명 부분 일치 + 코드 전체 일치 (P-EXP-03)", description = "코드 일치가 맨 앞, 최대 50개")
    @GetMapping("/search")
    fun search(@LoginUser userId: Long, @RequestParam q: String): List<ExploreItem> =
        explore.search(userId, q)

    @Operation(summary = "과목 정보 (P-COURSE-06)")
    @GetMapping("/courses/{code}")
    fun info(@LoginUser userId: Long, @PathVariable code: String): CourseInfo =
        explore.info(userId, code)

    @Operation(summary = "구독 · 가입 요청 (P-COURSE-03, P-EXP-04)", description = "종료된 과목은 409 COURSE_ENDED. 이미 구독·요청했다면 지금 상태를 그대로 돌려줘요.")
    @PostMapping("/courses/{code}/subscribe")
    fun subscribe(@LoginUser userId: Long, @PathVariable code: String): SubscribeResponse =
        explore.subscribe(userId, code)
}
