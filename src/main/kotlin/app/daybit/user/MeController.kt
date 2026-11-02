package app.daybit.user

import app.daybit.auth.AuthRules
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class MeResponse(
    val id: Long,
    val nickname: String,
    val githubLinked: Boolean,
    val subscribedCount: Int,
    val managedCount: Int,
    val onboarded: Boolean,
)

data class UpdateMeRequest(
    @field:Pattern(regexp = AuthRules.NICKNAME, message = "닉네임은 2~12자 한글·영문·숫자예요")
    val nickname: String,
)

@Service
class MeService(
    private val users: UserRepository,
    private val memberships: MembershipCounter,
) {
    @Transactional(readOnly = true)
    fun me(userId: Long): MeResponse {
        val user = load(userId)
        val (subscribed, managed) = memberships.count(userId)
        return MeResponse(user.id, user.nickname, user.githubLinked, subscribed, managed, user.onboardedAt != null)
    }

    /** P-ACC-03 · GitHub 첫 로그인 닉네임 설정(P-ACC-04)에도 써요 */
    @Transactional
    fun updateNickname(userId: Long, nickname: String): MeResponse {
        val user = load(userId)
        if (user.nickname != nickname && users.existsByNickname(nickname)) throw ApiException(ErrorCode.NICKNAME_TAKEN)
        user.nickname = nickname
        return me(userId)
    }

    /** P-ACC-05 서비스 소개를 끝까지 보거나 건너뛰면 호출 */
    @Transactional
    fun completeOnboarding(userId: Long) {
        val user = load(userId)
        if (user.onboardedAt == null) user.onboardedAt = OffsetDateTime.now()
    }

    private fun load(userId: Long) = users.findById(userId).orElseThrow { ApiException(ErrorCode.UNAUTHORIZED) }
}

/** 과목 기능이 생기기 전까지는 0을 돌려줘요. course 패키지가 실제 구현으로 대체해요. */
fun interface MembershipCounter {
    /** (구독 중인 과목 수, 관리자·공동 관리자인 과목 수) */
    fun count(userId: Long): Pair<Int, Int>
}

@Tag(name = "2. 내 정보")
@RestController
@RequestMapping("/api/v1/me")
class MeController(private val me: MeService) {

    @Operation(summary = "내 정보")
    @GetMapping
    fun get(@LoginUser userId: Long): MeResponse = me.me(userId)

    @Operation(summary = "닉네임 변경 (P-ACC-03)")
    @PatchMapping
    fun update(@LoginUser userId: Long, @Valid @RequestBody req: UpdateMeRequest): MeResponse =
        me.updateNickname(userId, req.nickname)

    @Operation(summary = "서비스 소개 완료 (P-ACC-05)", description = "이후 로그인부터 isFirstLogin 이 false 가 돼요.")
    @PostMapping("/onboarding/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun completeOnboarding(@LoginUser userId: Long) = me.completeOnboarding(userId)
}
