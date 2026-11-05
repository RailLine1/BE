package app.daybit.dev

import app.daybit.TestcontainersConfiguration
import app.daybit.support.Fixtures
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/** 기본값(DAYBIT_DEV_TOOLS 없음)에서는 개발자 전용 API 가 없어요 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevToolsOffTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
) {
    @Test
    fun `꺼져 있으면 경로가 없고 Swagger에도 안 보여요`() {
        val admin = Fixtures(mvc, json, jdbc).newUser()
        val courseId = Fixtures(mvc, json, jdbc).createCourse(admin).first
        mvc.post("/api/v1/dev/courses/$courseId/bit/open-now") { header("Authorization", admin.bearer) }
            .andExpect { status { isNotFound() } }
        mvc.post("/api/v1/dev/bits/1/reset-me") { header("Authorization", admin.bearer) }
            .andExpect { status { isNotFound() } }
        mvc.delete("/api/v1/dev/bits/1") { header("Authorization", admin.bearer) }
            .andExpect { status { isNotFound() } }
        mvc.get("/v3/api-docs").andExpect { jsonPath("$.paths['/api/v1/dev/bits/{bitId}/reset-me']") { doesNotExist() } }
    }
}
