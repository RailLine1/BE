package app.daybit.auth

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "1. 인증")
@SecurityRequirements // 🔓 로그인 불필요
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val auth: AuthService) {

    @Operation(summary = "아이디 가입 (P-ACC-02~04)")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody req: SignupRequest): AuthResponse = auth.signup(req)

    @Operation(summary = "아이디 로그인")
    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): AuthResponse = auth.login(req)

    @Operation(summary = "토큰 갱신", description = "리프레시 토큰은 한 번 쓰면 폐기되고 새로 발급돼요.")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest): AuthResponse = auth.refresh(req)

    @Operation(summary = "로그아웃", description = "리프레시 토큰을 폐기해요. 액세스 토큰은 앱에서 지워요.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody req: RefreshRequest) = auth.logout(req)

    @Operation(
        summary = "GitHub 로그인 시작 (P-ACC-06) · 준비 중",
        description = "앱은 외부 브라우저로 이 주소를 열고, 로그인 후 `redirect` 딥링크로 일회용 code 를 받아요.",
    )
    @GetMapping("/github/start")
    fun githubStart(
        @RequestParam redirect: String,
        @RequestParam(required = false, defaultValue = "false") link: Boolean,
    ): Nothing = throw ApiException(ErrorCode.NOT_IMPLEMENTED)

    @Operation(summary = "GitHub 일회용 code → 토큰 교환 · 준비 중", description = "신규 사용자면 `needsNickname` 이 true 예요.")
    @PostMapping("/github/exchange")
    fun githubExchange(@RequestParam code: String): Nothing = throw ApiException(ErrorCode.NOT_IMPLEMENTED)
}
