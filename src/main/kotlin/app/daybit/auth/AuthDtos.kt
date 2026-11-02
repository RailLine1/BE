package app.daybit.auth

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

object AuthRules {
    /** P-ACC-02: 4~20자, 영문 소문자와 숫자 */
    const val LOGIN_ID = "^[a-z0-9]{4,20}$"

    /** P-ACC-03: 2~12자, 한글·영문·숫자 */
    const val NICKNAME = "^[가-힣a-zA-Z0-9]{2,12}$"
}

data class SignupRequest(
    @field:Pattern(regexp = AuthRules.LOGIN_ID, message = "아이디는 4~20자 영문 소문자와 숫자예요")
    val loginId: String,

    // P-ACC-02: 8자 이상. bcrypt 입력 한계(72바이트) 때문에 상한을 둬요.
    @field:Size(min = 8, max = 64, message = "비밀번호는 8자 이상이에요")
    val password: String,

    @field:Pattern(regexp = AuthRules.NICKNAME, message = "닉네임은 2~12자 한글·영문·숫자예요")
    val nickname: String,
)

data class LoginRequest(
    @field:NotBlank val loginId: String,
    @field:NotBlank val password: String,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class AuthUser(
    val id: Long,
    val nickname: String,
)

data class AuthResponse(
    val user: AuthUser,
    @field:Schema(description = "true 면 서비스 소개(onboarding)를 보여줘요 (P-ACC-05)")
    val isFirstLogin: Boolean,
    val tokens: TokenPair,
)
