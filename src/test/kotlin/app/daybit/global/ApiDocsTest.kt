package app.daybit.global

import app.daybit.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiDocsTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `명세의 첫 로그인 필드 이름이 실제 응답과 같아요`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.components.schemas.AuthResponse.properties.isFirstLogin") { exists() }
            jsonPath("$.components.schemas.AuthResponse.properties.firstLogin") { doesNotExist() }
            jsonPath("$.components.schemas.GithubAuthResponse.properties.isFirstLogin") { exists() }
            jsonPath("$.components.schemas.GithubAuthResponse.properties.firstLogin") { doesNotExist() }
        }
    }
}
