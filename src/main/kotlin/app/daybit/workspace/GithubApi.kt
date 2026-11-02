package app.daybit.workspace

import app.daybit.global.config.DaybitProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.json.JsonMapper

data class GithubAccount(val id: Long, val login: String)

/**
 * 작업 공간에 필요한 GitHub API (#10). 테스트에서는 가짜로 바꿔 끼워요.
 * 토큰은 관리자가 1단계에서 허용한 OAuth 토큰(scope: repo, workflow)이에요.
 */
interface GithubApi {
    /** OAuth code → 액세스 토큰 */
    fun exchangeToken(code: String, redirectUri: String): String
    fun account(token: String): GithubAccount

    /** 템플릿 저장소로 비공개 저장소를 만들어요. 이미 있으면 그 저장소를 써요. "owner/name" 을 돌려줘요 */
    fun createFromTemplate(token: String, template: String, owner: String, name: String): String

    /** workflow_dispatch 실행 요청. 저장소나 워크플로가 없으면 · 토큰이 만료되면 예외 */
    fun dispatch(token: String, repo: String, workflow: String, inputs: Map<String, String>)
}

@Component
class RestGithubApi(
    private val props: DaybitProperties,
    private val json: JsonMapper,
) : GithubApi {
    private val client = RestClient.create()

    override fun exchangeToken(code: String, redirectUri: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", props.github.clientId)
            add("client_secret", props.github.clientSecret)
            add("code", code)
            add("redirect_uri", redirectUri)
        }
        val body = client.post().uri("https://github.com/login/oauth/access_token")
            .accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve().body<String>() ?: error("GitHub 토큰 응답이 비었어요")
        val tree = json.readTree(body)
        return tree.path("access_token").asString("").ifBlank { error("GitHub 토큰 교환 실패: ${tree.path("error").asString("")}") }
    }

    override fun account(token: String): GithubAccount {
        val user = json.readTree(get(token, "https://api.github.com/user"))
        return GithubAccount(user.path("id").asLong(), user.path("login").asString())
    }

    override fun createFromTemplate(token: String, template: String, owner: String, name: String): String {
        val body = mapOf(
            "owner" to owner,
            "name" to name,
            "private" to true,
            "description" to "Daybit AI 작업 공간 — 내 구독 AI로 과목 문제를 만들어요",
        )
        return try {
            val created = client.post().uri("https://api.github.com/repos/$template/generate")
                .headers { it.setBearerAuth(token); it.set("Accept", GITHUB_JSON) }
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve().body<String>() ?: error("저장소 생성 응답이 비었어요")
            json.readTree(created).path("full_name").asString()
        } catch (e: HttpClientErrorException) {
            // 같은 이름이 이미 있으면 422. 예전에 만든 작업 공간을 그대로 써요
            if (e.statusCode != HttpStatus.UNPROCESSABLE_ENTITY) throw e
            json.readTree(get(token, "https://api.github.com/repos/$owner/$name")).path("full_name").asString()
        }
    }

    override fun dispatch(token: String, repo: String, workflow: String, inputs: Map<String, String>) {
        client.post().uri("https://api.github.com/repos/$repo/actions/workflows/$workflow/dispatches")
            .headers { it.setBearerAuth(token); it.set("Accept", GITHUB_JSON) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(json.writeValueAsString(mapOf("ref" to "main", "inputs" to inputs)))
            .retrieve().toBodilessEntity()
    }

    private fun get(token: String, url: String): String =
        client.get().uri(url)
            .headers { it.setBearerAuth(token); it.set("Accept", GITHUB_JSON) }
            .retrieve().body<String>() ?: error("GitHub 응답이 비었어요")

    companion object {
        private const val GITHUB_JSON = "application/vnd.github+json"
    }
}
