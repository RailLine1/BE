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
@SpringBootTest(properties = ["RAILWAY_GIT_COMMIT_SHA=49df34e0000"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeployInfoTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `로그인 없이 지금 떠 있는 커밋을 확인해요`() {
        mvc.get("/actuator/info").andExpect {
            status { isOk() }
            jsonPath("$.commit") { value("49df34e0000") }
        }
    }
}
