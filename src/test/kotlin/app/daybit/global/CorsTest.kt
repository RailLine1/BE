package app.daybit.global

import app.daybit.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["daybit.cors.allowed-origins=https://*.expo.app,http://localhost:8081"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsTest(@Autowired val mvc: MockMvc) {

    private fun preflight(origin: String) = mvc.options("/api/v1/me") {
        header("Origin", origin)
        header("Access-Control-Request-Method", "GET")
        header("Access-Control-Request-Headers", "authorization")
    }

    @Test
    fun `EAS 미리보기 주소처럼 패턴에 맞는 출처는 허용해요`() {
        preflight("https://daybit--a1b2c3.expo.app").andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "https://daybit--a1b2c3.expo.app") }
        }
        preflight("http://localhost:8081").andExpect { status { isOk() } }
    }

    @Test
    fun `목록에 없는 출처는 막아요`() {
        preflight("https://evil.example.com").andExpect { status { isForbidden() } }
    }
}
