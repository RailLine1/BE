package app.daybit.push

import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Suppress("EnumEntryName")
enum class PushPlatform { android, ios, web }

data class PushTokenRequest(
    @field:NotBlank val token: String,
    val platform: PushPlatform,
)

data class PushTokenDeleteRequest(@field:NotBlank val token: String)

/** 알림은 FCM 으로 보내요 (결정 D4) */
@Tag(name = "9. 알림")
@RestController
@RequestMapping("/api/v1/push/tokens")
class PushController(private val service: PushService) {

    @Operation(summary = "FCM 기기 토큰 등록", description = "앱 시작·로그인 때마다 보내도 돼요. 같은 토큰은 갱신하고, 다른 계정에 있던 토큰이면 지금 계정으로 옮겨요")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun register(@LoginUser userId: Long, @Valid @RequestBody req: PushTokenRequest) =
        service.register(userId, req.token, req.platform)

    @Operation(summary = "FCM 기기 토큰 삭제 (로그아웃 시)", description = "로그아웃 API 를 부르기 전에 보내요. 내 토큰이 아니거나 없어도 204")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unregister(@LoginUser userId: Long, @Valid @RequestBody req: PushTokenDeleteRequest) =
        service.unregister(userId, req.token)
}
