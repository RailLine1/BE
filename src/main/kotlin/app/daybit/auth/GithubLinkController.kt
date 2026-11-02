package app.daybit.auth

import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "2. 내 정보")
@RestController
@RequestMapping("/api/v1/me/github")
class GithubLinkController(private val github: GithubAuthService) {

    @Operation(
        summary = "GitHub 계정 연결 주소 (P-ACC-06)",
        description = "받은 url 을 외부 브라우저로 열어요. 연결이 끝나면 redirect 로 `?code=` 가 붙어 돌아오고, " +
            "exchange 로 바꾼 토큰은 같은 계정이에요. 다른 계정에 연결된 GitHub 면 `?error=GITHUB_ALREADY_LINKED`",
    )
    @PostMapping("/link")
    fun link(@LoginUser userId: Long, @Valid @RequestBody req: GithubLinkRequest): GithubLinkResponse =
        GithubLinkResponse(github.authorizeUrl(req.redirect, linkUserId = userId))
}
