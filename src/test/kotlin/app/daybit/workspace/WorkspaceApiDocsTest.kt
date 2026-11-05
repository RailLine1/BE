package app.daybit.workspace

import app.daybit.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
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
class WorkspaceApiDocsTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `작업 공간 API는 Swagger에 변경 예정으로 보여요`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.tags[?(@.name == '11. AI 작업 공간')].description") { value(hasItem(containsString("변경 예정"))) }
            jsonPath("$.paths['/api/v1/me/workspace'].get.deprecated") { value(true) }
            jsonPath("$.paths['/api/v1/me/workspace/{ai}/test'].post.deprecated") { value(true) }
            jsonPath("$.paths['/api/v1/me/github/link'].post.deprecated") { value(true) }
        }
    }
}
