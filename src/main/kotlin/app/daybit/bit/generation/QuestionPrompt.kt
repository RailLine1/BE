package app.daybit.bit.generation

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 문제 생성 프롬프트 (resources/prompts). Gemini 와 내 AI 출제(#48)가 같은 규칙 · 같은 응답 형식을 써요.
 * 품질 실험 결과는 프롬프트 파일만 고쳐서 반영해요.
 */
@Component
class QuestionPrompt(private val json: JsonMapper) {

    /** 출제 규칙 (시스템 프롬프트) */
    val system: String = read("prompts/question-generation.md").trim()

    /** 응답 JSON 스키마 (표준 JSON Schema, 소문자 type) */
    val schema: JsonNode = json.readTree(read("prompts/question-schema.json"))

    /** 정답 숨긴 검수 규칙과 응답 스키마 (#52) */
    val checkSystem: String = read("prompts/question-check.md").trim()
    private val checkSchema: JsonNode = json.readTree(read("prompts/question-check-schema.json"))

    /** Gemini responseSchema 는 type 을 대문자(OBJECT, STRING …)로 받아요 */
    fun geminiSchema(): Any = upperTypes(json.treeToValue(schema, Map::class.java))!!

    fun geminiCheckSchema(): Any = upperTypes(json.treeToValue(checkSchema, Map::class.java))!!

    /** 과목 · 자료 · 문제 수 · 페이지 본문 */
    fun user(r: GenerationRequest): String = buildString {
        appendLine("과목: ${r.courseName}")
        appendLine("자료: ${r.fileTitle}")
        appendLine("만들 문제 수: ${r.count}")
        appendLine()
        appendPages(r.pages)
    }

    private fun upperTypes(node: Any?): Any? = when (node) {
        is Map<*, *> -> node.entries.associate { (k, v) ->
            k to if (k == "type" && v is String) v.uppercase() else upperTypes(v)
        }
        is List<*> -> node.map { upperTypes(it) }
        else -> node
    }

    private fun read(path: String): String =
        ClassPathResource(path).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
}
