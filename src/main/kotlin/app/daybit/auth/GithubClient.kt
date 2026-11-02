package app.daybit.auth

import app.daybit.global.config.DaybitProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.json.JsonMapper

data class GithubUser(val id: Long, val login: String)

/** GitHub OAuth code → 사용자 정보. 테스트에서는 가짜로 바꿔 끼워요 */
fun interface GithubClient {
    /** 실패하면 예외를 던져요 */
    fun fetchUser(code: String, redirectUri: String): GithubUser
}

@Component
class RestGithubClient(
    private val props: DaybitProperties,
    private val json: JsonMapper,
) : GithubClient {
    private val client = RestClient.create()

    override fun fetchUser(code: String, redirectUri: String): GithubUser {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", props.github.clientId)
            add("client_secret", props.github.clientSecret)
            add("code", code)
            add("redirect_uri", redirectUri)
        }
        val tokenBody = client.post().uri("https://github.com/login/oauth/access_token")
            .accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve().body<String>() ?: error("GitHub 토큰 응답이 비었어요")
        val token = json.readTree(tokenBody).path("access_token").asString("")
        if (token.isBlank()) error("GitHub 토큰 교환 실패: ${json.readTree(tokenBody).path("error").asString("")}")

        val userBody = client.get().uri("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .accept(MediaType.parseMediaType("application/vnd.github+json"))
            .retrieve().body<String>() ?: error("GitHub 사용자 응답이 비었어요")
        val user = json.readTree(userBody)
        return GithubUser(user.path("id").asLong(), user.path("login").asString())
    }
}
