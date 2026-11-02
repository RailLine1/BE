package app.daybit.course

import app.daybit.global.common.CourseRole
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class MemberItem(
    val userId: Long,
    val nickname: String,
    val role: CourseRole,
    val coSince: OffsetDateTime?,
)

@Suppress("EnumEntryName")
enum class AssignableRole { co, member }

data class ChangeRoleRequest(
    @field:Schema(description = "관리자만 바꿀 수 있어요")
    val role: AssignableRole,
)

data class JoinRequestItem(
    @field:Schema(description = "요청한 사람의 userId 예요")
    val requestId: Long,
    val nickname: String,
    val requestedAt: OffsetDateTime,
)

data class ApproveAllResponse(val approved: Int)

@Tag(name = "5. 멤버 · 가입 요청")
@RestController
@RequestMapping("/api/v1/courses/{courseId}")
class MemberController(private val members: MemberService) {

    @Operation(summary = "멤버 목록: 관리자 → 공동 관리자(날짜순) → 멤버 (P-ROLE-02)")
    @GetMapping("/members")
    fun members(@LoginUser userId: Long, @PathVariable courseId: Long): List<MemberItem> =
        members.members(userId, courseId)

    @Operation(summary = "멤버 ↔ 공동 관리자 (관리자만)")
    @PatchMapping("/members/{memberId}")  // memberId = 대상 userId
    fun changeRole(
        @LoginUser userId: Long,
        @PathVariable courseId: Long,
        @PathVariable memberId: Long,
        @RequestBody req: ChangeRoleRequest,
    ): MemberItem = members.changeRole(userId, courseId, memberId, req.role)

    @Operation(summary = "추방 (공동 관리자는 멤버만, P-ROLE-05)")
    @DeleteMapping("/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun kick(@LoginUser userId: Long, @PathVariable courseId: Long, @PathVariable memberId: Long): Unit =
        members.kick(userId, courseId, memberId)

    @Operation(summary = "가입 요청 목록")
    @GetMapping("/join-requests")
    fun joinRequests(@LoginUser userId: Long, @PathVariable courseId: Long): List<JoinRequestItem> =
        members.joinRequests(userId, courseId)

    @Operation(summary = "가입 요청 승인 → 요청자 알림 (P-NOTI-03)")
    @PostMapping("/join-requests/{requestId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approve(@LoginUser userId: Long, @PathVariable courseId: Long, @PathVariable requestId: Long): Unit =
        members.approve(userId, courseId, requestId)

    @Operation(summary = "가입 요청 거절")
    @PostMapping("/join-requests/{requestId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@LoginUser userId: Long, @PathVariable courseId: Long, @PathVariable requestId: Long): Unit =
        members.reject(userId, courseId, requestId)

    @Operation(summary = "가입 요청 모두 승인")
    @PostMapping("/join-requests/approve-all")
    fun approveAll(@LoginUser userId: Long, @PathVariable courseId: Long): ApproveAllResponse =
        members.approveAll(userId, courseId)
}
