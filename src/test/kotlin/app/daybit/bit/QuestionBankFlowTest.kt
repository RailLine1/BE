package app.daybit.bit

import app.daybit.TestcontainersConfiguration
import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.bit.generation.GenerationRequest
import app.daybit.bit.generation.QuestionBankService
import app.daybit.bit.generation.QuestionGenerator
import app.daybit.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/** Gemini 대신 쓰는 가짜 생성기. 테스트마다 응답을 바꿔 끼워요 */
class FakeQuestionGenerator : QuestionGenerator {
    override var enabled = true
    var respond: (GenerationRequest) -> List<GeneratedQuestion> = { emptyList() }
    val requests = CopyOnWriteArrayList<GenerationRequest>()

    override fun generate(request: GenerationRequest): List<GeneratedQuestion> {
        requests += request
        return respond(request)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeGeneratorConfig {
    @Bean
    @Primary
    fun fakeQuestionGenerator() = FakeQuestionGenerator()
}

@Import(TestcontainersConfiguration::class, FakeGeneratorConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionBankFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val fake: FakeQuestionGenerator,
    @Autowired val bank: QuestionBankService,
) {
    private val f = Fixtures(mvc, json, jdbc)

    @BeforeEach
    fun reset() {
        fake.enabled = true
        fake.respond = { emptyList() }
        fake.requests.clear()
    }

    private fun q(
        concept: String = "Scheduling",
        quote: String = "Round robin gives each process a time quantum",
        page: Int = 1,
        choices: List<String> = listOf("FIFO", "Round robin", "SJF", "Priority"),
        answer: Int = 1,
    ) = GeneratedQuestion(concept, "Which algorithm uses a time quantum?", choices, answer, "Round robin uses a quantum.", page, quote)

    @Test
    fun `자료가 ready 가 되면 문제를 만들고, 근거 문장이 페이지에 있는 문제만 verified 예요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        fake.respond = {
            listOf(
                q(quote = "Round robin gives each   process\na time quantum"),     // 공백 · 줄바꿈이 달라도 통과
                q(concept = "Paging", quote = "This sentence is not in the material at all", page = 2),
                q(concept = "Broken", choices = listOf("A", "A", "B", "C")),       // 보기 중복 → 버림
                q(concept = "Broken", page = 9),                                     // 없는 페이지 → 버림
                q(concept = "Broken", answer = 4),                                   // 정답 번호 범위 밖 → 버림
            )
        }

        val fileId = f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.", "Paging splits memory into pages.")
        val rows = waitQuestions(courseId, 2)

        assertThat(rows.map { it.verified }).containsExactlyInAnyOrder(true, false)
        assertThat(rows).allSatisfy {
            assertThat(it.scope).isEqualTo("mid")
            assertThat(it.fileId).isEqualTo(fileId)
        }
        val concepts = jdbc.sql("select name from concepts where course_id = :c order by name").param("c", courseId)
            .query(String::class.java).list()
        assertThat(concepts).containsExactly("Paging", "Scheduling")

        val request = fake.requests.single()
        assertThat(request.pages.map { it.page }).containsExactly(1, 2)
        // 시험일이 없으면 14일 × 1.5 = 21개가 목표지만, 한 번에 최대 10개까지만 요청해요
        assertThat(request.count).isEqualTo(10)
    }

    @Test
    fun `시험일까지 남은 일수로 목표 개수를 정하고, 적어도 3개는 요청해요`() {
        val admin = f.newUser()
        val mid = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(4)
        val (courseId, _) = f.createCourse(admin, extra = ""","midExamDate":"$mid"""")
        fake.respond = { listOf(q()) }

        f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.")
        waitQuestions(courseId, 1)
        assertThat(fake.requests.single().count).isEqualTo(6)       // 4일 × 1.5

        // 지금 은행에 1개 → 목표 6 − 1 = 5
        f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.")
        waitQuestions(courseId, 2)
        assertThat(fake.requests.last().count).isEqualTo(5)
    }

    @Test
    fun `출처가 출제 AI 면 자료가 ready 여도 기다리고, Gemini 키가 없으면 만들지 않아요`() {
        val admin = f.newUser()
        val (myAiCourse, _) = f.createCourse(admin)
        jdbc.sql("update courses set quiz_source_type = 'myai', quiz_source_user_id = :u, quiz_source_ai = 'claude' where id = :c")
            .param("u", admin.id).param("c", myAiCourse).update()
        fake.respond = { listOf(q()) }
        fake.requests.clear()
        val waiting = f.uploadReady(admin, myAiCourse, "Round robin gives each process a time quantum.")
        // P-BIT-20: 예약 실행을 기다려요. force 면(24시간 대체) Gemini 로 만들어요
        assertThat(bank.generateForFile(waiting)).isZero()
        assertThat(fake.requests).isEmpty()
        assertThat(bank.generateForFile(waiting, force = true)).isEqualTo(1)

        val (course, _) = f.createCourse(admin)
        fake.enabled = false
        fake.requests.clear()
        val second = f.uploadReady(admin, course, "Round robin gives each process a time quantum.")
        assertThat(bank.generateForFile(second)).isZero()
        assertThat(fake.requests).isEmpty()
    }

    @Test
    fun `생성이 실패해도 자료는 ready 이고, 보충할 때 다시 만들어요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        fake.respond = { error("Gemini 503") }

        val fileId = f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.")
        Thread.sleep(500)
        assertThat(questions(courseId)).isEmpty()
        assertThat(f.waitFileStatus(fileId)).isEqualTo("ready")

        fake.respond = { listOf(q(), q(concept = "Quantum")) }
        assertThat(bank.replenish(courseId, "mid")).isEqualTo(2)
        assertThat(questions(courseId)).hasSize(2)
        // final 폴더에는 자료가 없어서 아무것도 하지 않아요
        assertThat(bank.replenish(courseId, "final")).isZero()
    }

    @Test
    fun `안 낸 문제가 3일치 이상이면 보충하지 않아요`() {
        val admin = f.newUser()
        val (courseId, _) = f.createCourse(admin)
        fake.respond = { listOf(q(), q(), q()) }
        f.uploadReady(admin, courseId, "Round robin gives each process a time quantum.")
        waitQuestions(courseId, 3)

        val before = fake.requests.size
        assertThat(bank.replenish(courseId, "mid")).isZero()
        assertThat(fake.requests).hasSize(before)
    }

    // ---------- 도우미 ----------

    data class Row(val verified: Boolean, val scope: String?, val fileId: Long)

    private fun questions(courseId: Long) =
        jdbc.sql("select verified, scope, evidence_file_id from questions where course_id = :c order by id").param("c", courseId)
            .query { rs, _ -> Row(rs.getBoolean("verified"), rs.getString("scope"), rs.getLong("evidence_file_id")) }.list()

    /** 문제 생성은 별도 스레드에서 돌아요 */
    private fun waitQuestions(courseId: Long, atLeast: Int): List<Row> {
        repeat(100) {
            val rows = questions(courseId)
            if (rows.size >= atLeast) return rows
            Thread.sleep(100)
        }
        error("문제가 만들어지지 않았어요: course=$courseId")
    }
}
