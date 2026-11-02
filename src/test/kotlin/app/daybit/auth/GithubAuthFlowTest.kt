package app.daybit.auth

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
import app.daybit.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** GitHub 대신 code → 사용자를 돌려주는 가짜. 등록 안 된 code 는 실패해요 */
class FakeGithubClient : GithubClient {
    val users = ConcurrentHashMap<String, GithubUser>()
    override fun fetchUser(code: String, redirectUri: String): GithubUser =
        users[code] ?: error("bad code")
}

@TestConfiguration(proxyBeanMethods = false)
class FakeGithubConfig {
    @Bean
    @Primary
    fun fakeGithubClient() = FakeGithubClient()
}

@Import(TestcontainersConfiguration::class, FakeGithubConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GithubAuthFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val fake: FakeGithubClient,
) {
    private val f = Fixtures(mvc, json, jdbc)

    /** GitHub 사용자 하나를 만들고, 그 사람이 GitHub 에서 받을 code 를 돌려줘요 */
    private fun githubUser(login: String = "octo"): Pair<String, Long> {
        val id = ids.incrementAndGet()
        val code = "gh-code-$id"
        fake.users[code] = GithubUser(id, "$login$id")
        return code to id
    }

    private fun query(url: String, name: String): String? =
        UriComponentsBuilder.fromUriString(url).build().queryParams.getFirst(name)

    /** start → (GitHub) → callback 까지 돌고 앱으로 돌아온 주소를 돌려줘요 */
    private fun loginRedirect(githubCode: String, redirect: String = "daybit://github"): String {
        val authorize = mvc.get("/api/v1/auth/github/start?redirect=$redirect")
            .andExpect { status { isFound() } }.andReturn().response.getHeader("Location")!!
        assertThat(authorize).startsWith("https://github.com/login/oauth/authorize")
        return callback(githubCode, query(authorize, "state")!!)
    }

    private fun callback(githubCode: String, state: String): String =
        mvc.get("/api/v1/auth/github/callback") { param("code", githubCode); param("state", state) }
            .andExpect { status { isFound() } }.andReturn().response.getHeader("Location")!!

    private fun exchange(code: String) =
        mvc.post("/api/v1/auth/github/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code"}"""
        }

    @Test
    fun `처음 오는 GitHub 사용자는 가입되고, 닉네임 입력이 필요하다고 알려 줘요`() {
        val (ghCode, ghId) = githubUser()
        val back = loginRedirect(ghCode)
        assertThat(back).startsWith("daybit://github?code=")
        val oneTime = query(back, "code")!!

        val body = exchange(oneTime).andExpect {
            status { isOk() }
            jsonPath("$.needsNickname") { value(true) }
            jsonPath("$.isFirstLogin") { value(true) }
            jsonPath("$.githubLogin") { value("octo$ghId") }
            jsonPath("$.tokens.accessToken") { isNotEmpty() }
        }.andReturn().response.contentAsString
        val token = json.readTree(body).path("tokens").path("accessToken").asString()

        // 일회용이에요
        exchange(oneTime).andExpect { status { isUnauthorized() }; jsonPath("$.error.code") { value("GITHUB_CODE_INVALID") } }

        mvc.patch("/api/v1/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"깃허브$ghId"}""".take(40)
        }.andExpect { status { isOk() } }
        mvc.get("/api/v1/me") { header("Authorization", "Bearer $token") }
            .andExpect { jsonPath("$.githubLinked") { value(true) } }

        // 두 번째 로그인은 같은 계정이고 닉네임 입력이 필요 없어요
        val again = query(loginRedirect(ghCode), "code")!!
        exchange(again).andExpect {
            jsonPath("$.needsNickname") { value(false) }
            jsonPath("$.user.id") { value(json.readTree(body).path("user").path("id").asLong()) }
        }
    }

    @Test
    fun `아이디 계정에 GitHub 를 연결하고, 다른 계정이 같은 GitHub 를 연결하면 막아요`() {
        val me = f.newUser()
        val other = f.newUser()
        val (ghCode, _) = githubUser("linker")

        val back = linkRedirect(me, ghCode)
        exchange(query(back, "code")!!).andExpect {
            status { isOk() }
            jsonPath("$.user.id") { value(me.id) }
            jsonPath("$.needsNickname") { value(false) }
        }
        mvc.get("/api/v1/me") { header("Authorization", me.bearer) }.andExpect { jsonPath("$.githubLinked") { value(true) } }

        val denied = linkRedirect(other, ghCode)
        assertThat(query(denied, "error")).isEqualTo("GITHUB_ALREADY_LINKED")
        assertThat(query(denied, "code")).isNull()
        mvc.get("/api/v1/me") { header("Authorization", other.bearer) }.andExpect { jsonPath("$.githubLinked") { value(false) } }

        // 연결한 GitHub 로 로그인하면 원래 계정이에요
        exchange(query(loginRedirect(ghCode), "code")!!).andExpect { jsonPath("$.user.id") { value(me.id) } }
    }

    @Test
    fun `허용되지 않은 앱 주소, 위조된 state, GitHub 실패와 만료된 code 를 막아요`() {
        mvc.get("/api/v1/auth/github/start?redirect=https://evil.example.com/steal").andExpect { status { isBadRequest() } }
        mvc.get("/api/v1/auth/github/callback") { param("code", "x"); param("state", "forged.state") }
            .andExpect { status { isBadRequest() } }

        // GitHub 가 code 를 거절하면 앱으로 error 를 붙여 돌려보내요
        assertThat(query(loginRedirect("unknown-code"), "error")).isEqualTo("GITHUB_FAILED")
        // 사용자가 GitHub 화면에서 취소
        val authorize = mvc.get("/api/v1/auth/github/start?redirect=exp://192.168.0.2:8081/--/github")
            .andReturn().response.getHeader("Location")!!
        val cancelled = mvc.get("/api/v1/auth/github/callback") {
            param("state", query(authorize, "state")!!); param("error", "access_denied")
        }.andReturn().response.getHeader("Location")!!
        assertThat(cancelled).startsWith("exp://192.168.0.2:8081/--/github?error=GITHUB_CANCELLED")

        val (ghCode, _) = githubUser()
        val oneTime = query(loginRedirect(ghCode), "code")!!
        jdbc.sql("update github_login_codes set expires_at = now() - interval '1 second' where used_at is null").update()
        exchange(oneTime).andExpect { status { isUnauthorized() } }
    }

    private fun linkRedirect(user: TestUser, ghCode: String): String {
        val url = mvc.post("/api/v1/me/github/link") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"redirect":"daybit://settings/github"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let { json.readTree(it).path("url").asString() }
        return callback(ghCode, query(url, "state")!!)
    }

    companion object {
        private val ids = AtomicLong(9_000_000)
    }
}
