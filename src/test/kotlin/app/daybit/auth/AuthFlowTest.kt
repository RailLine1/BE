package app.daybit.auth

import app.daybit.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
) {

    private fun signup(loginId: String, nickname: String) = mvc.post("/api/v1/auth/signup") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"loginId":"$loginId","password":"password1","nickname":"$nickname"}"""
    }

    @Test
    fun `가입하면 토큰과 첫 로그인 표시가 와요`() {
        signup("alice01", "앨리스").andExpect {
            status { isCreated() }
            jsonPath("$.user.nickname") { value("앨리스") }
            jsonPath("$.isFirstLogin") { value(true) }
            jsonPath("$.tokens.accessToken") { isNotEmpty() }
            jsonPath("$.tokens.refreshToken") { isNotEmpty() }
        }
    }

    @Test
    fun `같은 아이디·닉네임은 409`() {
        signup("bob0001", "바비").andExpect { status { isCreated() } }
        signup("bob0001", "다른밥").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("LOGIN_ID_TAKEN") }
        }
        signup("bob0002", "바비").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("NICKNAME_TAKEN") }
        }
    }

    @Test
    fun `규칙에 안 맞는 입력은 400 VALIDATION_FAILED`() {
        signup("AB", "x").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `로그인 실패는 401, 리프레시는 한 번만 쓸 수 있어요`() {
        signup("carol01", "캐롤").andExpect { status { isCreated() } }

        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"carol01","password":"wrong-password"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("INVALID_CREDENTIALS") }
        }

        val body = mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"carol01","password":"password1"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val refresh = json.readTree(body).path("tokens").path("refreshToken").asString()

        val refreshBody = """{"refreshToken":"$refresh"}"""
        mvc.post("/api/v1/auth/refresh") { contentType = MediaType.APPLICATION_JSON; content = refreshBody }
            .andExpect { status { isOk() } }
        mvc.post("/api/v1/auth/refresh") { contentType = MediaType.APPLICATION_JSON; content = refreshBody }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.error.code") { value("INVALID_REFRESH_TOKEN") }
            }
    }

    @Test
    fun `토큰 없이 보호된 API 는 401 JSON`() {
        mvc.post("/api/v1/courses").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("UNAUTHORIZED") }
        }
    }
}
