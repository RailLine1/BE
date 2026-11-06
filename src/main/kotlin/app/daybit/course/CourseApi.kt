package app.daybit.course

import app.daybit.global.common.CourseRole
import app.daybit.global.common.MyAi
import app.daybit.global.security.LoginUser
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

// ---------- DTO ----------

@Suppress("EnumEntryName")
enum class Visibility { public, private }

@Suppress("EnumEntryName")
enum class CourseState { active, ended }

@Suppress("EnumEntryName")
enum class QuizSourceType { default, myai }

@Schema(description = "`{type:\"default\"}`(Gemini Flash) 또는 `{type:\"myai\", ai:\"claude\"|\"chatgpt\"}`(내 AI 출제에 등록한 본인 AI만, P-AI-09 · 10)")
data class QuizSource(
    val type: QuizSourceType = QuizSourceType.default,
    val ai: MyAi? = null,
)

data class CreateCourseRequest(
    @field:NotBlank @field:Size(max = 40) val name: String,
    @field:NotBlank @field:Size(max = 20) val icon: String,
    @field:Size(max = 30) val intro: String? = null,          // P-COURSE-01 한 줄 소개 30자
    @field:Size(max = 20) val term: String? = null,
    val visibility: Visibility,
    val midExamDate: LocalDate? = null,
    val finalExamDate: LocalDate? = null,
    val quizSource: QuizSource = QuizSource(),
)

data class CreateCourseResponse(val id: Long, val code: String)

data class CourseTile(
    val courseId: Long,
    val name: String,
    val icon: String,
    val intro: String?,
)

data class CourseDetail(
    val courseId: Long,
    val name: String,
    val icon: String,
    val intro: String?,
    val term: String?,
    val subscribers: Int,
    val role: CourseRole,
    val rawAllowed: Boolean,
    val archiveCount: Int,
    val state: CourseState,
)

@Schema(description = "멤버는 code · role · notify 만 채워지고 나머지는 null 이에요 (P-ROLE-01)")
data class CourseSettings(
    val code: String,
    val role: CourseRole,
    val notify: Boolean,
    val icon: String? = null,
    val intro: String? = null,
    val visibility: Visibility? = null,
    val midExamDate: LocalDate? = null,
    val finalExamDate: LocalDate? = null,
    val rawAllowed: Boolean? = null,
    val quizSource: QuizSource? = null,
)

@Schema(description = "바꿀 항목만 보내요. intro · midExamDate · finalExamDate 는 null 을 보내면 지워요. 다른 항목의 null 은 변경 없음이에요")
class UpdateCourseRequest {
    /** 본문에 들어 있던 비울 수 있는 칸. 키 없음(변경 없음)과 null(지우기)을 나눠요 (#44) */
    @get:JsonIgnore
    val sent: MutableSet<String> = mutableSetOf()

    @field:Size(max = 20) var icon: String? = null
    @field:Size(max = 30) var intro: String? = null
        set(value) { field = value; sent += "intro" }
    var visibility: Visibility? = null
    var midExamDate: LocalDate? = null
        set(value) { field = value; sent += "midExamDate" }
    var finalExamDate: LocalDate? = null
        set(value) { field = value; sent += "finalExamDate" }
    var rawAllowed: Boolean? = null
    var quizSource: QuizSource? = null
}

@Suppress("EnumEntryName")
enum class LeaveResult { left, promoted, deleted }

data class LeaveResponse(
    val result: LeaveResult,
    @field:Schema(description = "result=promoted 일 때 새 관리자 닉네임 (P-ROLE-03)")
    val newAdmin: String? = null,
)

data class SubscriptionRequest(val notify: Boolean)

// ---------- Controller ----------

@Tag(name = "3. 과목")
@RestController
@RequestMapping("/api/v1/courses")
class CourseController(private val courses: CourseService) {

    @Operation(summary = "과목 만들기 (P-COURSE-01, 02)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@LoginUser userId: Long, @Valid @RequestBody req: CreateCourseRequest): CreateCourseResponse =
        courses.create(userId, req)

    @Operation(summary = "강의실 타일(active) · 지난 강의(ended) (P-COURSE-05)")
    @GetMapping("/mine")
    fun mine(
        @LoginUser userId: Long,
        @RequestParam(defaultValue = "active") state: CourseState,
    ): List<CourseTile> = courses.mine(userId, state)

    @Operation(summary = "과목 상세")
    @GetMapping("/{courseId}")
    fun detail(@LoginUser userId: Long, @PathVariable courseId: Long): CourseDetail =
        courses.detail(userId, courseId)

    @Operation(summary = "환경설정 (P-ROLE-01)")
    @GetMapping("/{courseId}/settings")
    fun settings(@LoginUser userId: Long, @PathVariable courseId: Long): CourseSettings =
        courses.settings(userId, courseId)

    @Operation(summary = "환경설정 변경 (P-COURSE-04, P-BIT-03, 14)")
    @PatchMapping("/{courseId}")
    fun update(
        @LoginUser userId: Long,
        @PathVariable courseId: Long,
        @Valid @RequestBody req: UpdateCourseRequest,
    ): CourseSettings = courses.update(userId, courseId, req)

    @Operation(summary = "탈퇴 (P-ROLE-03, 04)")
    @PostMapping("/{courseId}/leave")
    fun leave(@LoginUser userId: Long, @PathVariable courseId: Long): LeaveResponse =
        courses.leave(userId, courseId)

    @Operation(summary = "과목 알림 켜기·끄기 (P-NOTI-02)")
    @PatchMapping("/{courseId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun subscription(
        @LoginUser userId: Long,
        @PathVariable courseId: Long,
        @RequestBody req: SubscriptionRequest,
    ): Unit = courses.subscription(userId, courseId, req.notify)
}
