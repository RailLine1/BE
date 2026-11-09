package app.daybit.connector

import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.global.common.MyAi
import app.daybit.myai.MyAiService
import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * 원격 MCP 서버 (Streamable HTTP, JSON 응답만). 결정 D10.
 * - POST /mcp/u/{token}: JSON-RPC 요청 1개 → JSON 응답 1개. 알림(id 없음)은 202
 * - GET: 서버 → 클라이언트 스트림(SSE)은 쓰지 않아서 405
 * 인증은 주소 안의 토큰이에요 (P-AI-02). 틀린 토큰은 404 로 답해요(401 이면 클라이언트가 OAuth 를 시도해요).
 */
@Hidden
@RestController
class McpController(
    private val connectors: ConnectorService,
    private val tools: ConnectorTools,
    private val myAi: MyAiService,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 세션 id → 클라이언트 종류. initialize 의 clientInfo 로 정해요. 서버를 재시작하면 비워져요(그때는 User-Agent 로 추정) */
    private val sessions = ConcurrentHashMap<String, ConnectorClientType>()

    @GetMapping("/mcp/u/{token}")
    fun stream(@PathVariable token: String): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()

    @PostMapping("/mcp/u/{token}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun handle(
        @PathVariable token: String,
        @RequestBody body: JsonNode,
        @RequestHeader("Mcp-Session-Id", required = false) sessionId: String?,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
    ): ResponseEntity<Any> {
        val userId = connectors.userOf(token) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        if (!body.isObject) return ok(error(null, -32600, "요청은 JSON-RPC 객체 하나여야 해요."))

        val id = body.get("id")
        val method = body.path("method").asString("")
        val params = body.path("params")
        if (id == null || id.isNull) return ResponseEntity.status(HttpStatus.ACCEPTED).build()   // 알림 (notifications/initialized 등)

        return when (method) {
            "initialize" -> {
                val client = clientOf(params.path("clientInfo").path("name").asString(""), userAgent)
                val newSession = java.util.UUID.randomUUID().toString()
                sessions[newSession] = client
                connectors.record(userId, client, "initialize", null)
                val requested = params.path("protocolVersion").asString("")
                ResponseEntity.ok().header("Mcp-Session-Id", newSession).body(
                    result(id, mapOf(
                        "protocolVersion" to (requested.takeIf { it in SUPPORTED_VERSIONS } ?: SUPPORTED_VERSIONS.first()),
                        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                        "serverInfo" to mapOf("name" to "daybit", "version" to "1.0.0"),
                        "instructions" to INSTRUCTIONS,
                    )),
                )
            }
            "ping" -> ok(result(id, emptyMap<String, Any>()))
            "tools/list" -> ok(result(id, mapOf("tools" to TOOL_DEFINITIONS)))
            "tools/call" -> {
                val client = sessionId?.let { sessions[it] } ?: clientOf("", userAgent)
                ok(callTool(userId, client, id, params))
            }
            else -> ok(error(id, -32601, "지원하지 않는 메서드예요: $method"))
        }
    }

    private fun callTool(userId: Long, client: ConnectorClientType, id: JsonNode, params: JsonNode): Map<String, Any?> {
        val name = params.path("name").asString("")
        val args = params.path("arguments")
        val courseId = args.path("courseId").takeIf { it.isNumber }?.asLong()
        return try {
            val output = when (name) {
                "list_courses" -> tools.listCourses(userId)
                "list_materials" -> tools.listMaterials(userId, requiredLong(args, "courseId"))
                "search" -> tools.search(userId, requiredLong(args, "courseId"), args.path("query").asString(""),
                    args.path("limit").takeIf { it.isNumber }?.asInt())
                "read_pages" -> tools.readPages(userId, requiredLong(args, "fileId"),
                    args.path("pages").toList().filter { it.isNumber }.map { it.asInt() })
                "get_page_image" -> tools.pageImage(userId, requiredLong(args, "fileId"), requiredLong(args, "page").toInt())
                "get_jobs" -> ToolResult.Json(myAi.jobs(userId, ai(args)))
                "submit_questions" -> ToolResult.Json(myAi.submit(userId, ai(args), requiredLong(args, "fileId"), questions(args)))
                else -> return error(id, -32602, "없는 도구예요: $name")
            }
            connectors.record(userId, client, name, courseId)
            result(id, mapOf("content" to listOf(content(output)), "isError" to false))
        } catch (e: ToolError) {
            result(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to e.message)), "isError" to true))
        } catch (e: Exception) {
            log.warn("MCP 도구 실패: {}", name, e)
            result(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to "잠시 후 다시 시도해 주세요.")), "isError" to true))
        }
    }

    private fun content(output: ToolResult): Map<String, Any> = when (output) {
        is ToolResult.Json -> mapOf("type" to "text", "text" to json.writeValueAsString(output.value))
        is ToolResult.Image -> mapOf("type" to "image", "data" to output.base64, "mimeType" to output.mimeType)
    }

    private fun requiredLong(args: JsonNode, name: String): Long =
        args.path(name).takeIf { it.isNumber }?.asLong() ?: throw ToolError("$name 이 필요해요.")

    private fun ai(args: JsonNode): MyAi {
        val name = args.path("ai").asString("")
        return MyAi.entries.firstOrNull { it.name == name } ?: throw ToolError("ai 는 claude 또는 chatgpt 여야 해요.")
    }

    /** submit_questions 의 questions. 형식이 틀린 항목은 빈 값으로 읽고, 형식 검사에서 걸러요 */
    private fun questions(args: JsonNode): List<GeneratedQuestion> {
        val items = args.path("questions").takeIf { it.isArray }?.toList() ?: throw ToolError("questions 배열이 필요해요.")
        if (items.size > MAX_SUBMIT) throw ToolError("한 번에 ${MAX_SUBMIT}개까지 보낼 수 있어요.")
        return items.map {
            GeneratedQuestion(
                concept = it.path("concept").asString(""),
                stem = it.path("stem").asString(""),
                choices = it.path("choices").toList().map { c -> c.asString("") },
                answerIdx = it.path("answerIndex").asInt(-1),
                explanation = it.path("explanation").asString(""),
                evidencePage = it.path("evidencePage").asInt(-1),
                evidenceQuote = it.path("evidenceQuote").asString(""),
            )
        }
    }

    private fun ok(body: Any) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body)

    private fun result(id: JsonNode?, result: Any) = mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)

    private fun error(id: JsonNode?, code: Int, message: String) =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

    companion object {
        private val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        /** P-AI-04 클라이언트 구분. clientInfo.name 을 먼저 보고, 없으면 User-Agent 를 봐요 */
        internal fun clientOf(clientName: String, userAgent: String?): ConnectorClientType {
            val s = (clientName + " " + (userAgent ?: "")).lowercase()
            return when {
                "claude-code" in s || "claude code" in s || "claude_code" in s -> ConnectorClientType.claudecode
                "codex" in s -> ConnectorClientType.codex
                "chatgpt" in s || "openai" in s -> ConnectorClientType.chatgpt
                "claude" in s || "anthropic" in s -> ConnectorClientType.claude
                else -> ConnectorClientType.unknown
            }
        }

        private const val MAX_SUBMIT = 50

        private const val INSTRUCTIONS =
            "Daybit 에서 내가 구독한 과목의 강의자료를 읽어요. list_courses → list_materials 또는 search 로 관련 페이지를 찾고, " +
                "read_pages 로 그 페이지만 읽어요. 답할 때는 자료 이름과 쪽 번호를 함께 알려 주세요. " +
                "get_jobs · submit_questions 는 내 AI 출제 예약 작업용이에요. 예약 문장에서 시킬 때만 써요."

        private val AI_PROPERTY = mapOf("ai" to mapOf("type" to "string", "enum" to MyAi.entries.map { it.name }, "description" to "예약 문장에 적힌 값"))

        // 자료 읽기 도구: AI 앱이 호출마다 승인을 묻지 않게 읽기 전용으로 표시한다
        private val READ_ONLY = mapOf("readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false)

        private fun schema(properties: Map<String, Any>, required: List<String>) =
            mapOf("type" to "object", "properties" to properties, "required" to required)

        private val TOOL_DEFINITIONS = listOf(
            mapOf(
                "name" to "list_courses",
                "description" to "내가 구독한 과목 목록 [{courseId, name, code, term}]",
                "inputSchema" to schema(emptyMap(), emptyList()),
                "annotations" to READ_ONLY,
            ),
            mapOf(
                "name" to "list_materials",
                "description" to "과목의 폴더와 자료 목록 [{folder, fileId, title, pages}]",
                "inputSchema" to schema(mapOf("courseId" to mapOf("type" to "integer")), listOf("courseId")),
                "annotations" to READ_ONLY,
            ),
            mapOf(
                "name" to "search",
                "description" to "과목 자료에서 키워드가 들어 있는 페이지를 찾아요 [{fileId, title, page, snippet}]. 여러 단어는 공백으로 나눠요",
                "inputSchema" to schema(
                    mapOf(
                        "courseId" to mapOf("type" to "integer"),
                        "query" to mapOf("type" to "string"),
                        "limit" to mapOf("type" to "integer", "description" to "기본 5, 최대 20"),
                    ),
                    listOf("courseId", "query"),
                ),
                "annotations" to READ_ONLY,
            ),
            mapOf(
                "name" to "read_pages",
                "description" to "자료의 페이지 텍스트를 읽어요 [{page, text}]. 한 번에 최대 ${ConnectorTools.MAX_PAGES}쪽",
                "inputSchema" to schema(
                    mapOf(
                        "fileId" to mapOf("type" to "integer"),
                        "pages" to mapOf("type" to "array", "items" to mapOf("type" to "integer"), "maxItems" to ConnectorTools.MAX_PAGES),
                    ),
                    listOf("fileId", "pages"),
                ),
                "annotations" to READ_ONLY,
            ),
            mapOf(
                "name" to "get_page_image",
                "description" to "도표 · 수식이 필요할 때 그 페이지를 이미지로 받아요",
                "inputSchema" to schema(
                    mapOf("fileId" to mapOf("type" to "integer"), "page" to mapOf("type" to "integer")),
                    listOf("fileId", "page"),
                ),
                "annotations" to READ_ONLY,
            ),
            mapOf(
                "name" to "get_jobs",
                "description" to "내 AI 출제: 문제를 만들 새 자료를 하나 받아요 {job: {fileId, course, title, count, prompt} | null, remaining, rules, schema}. " +
                    "rules 대로 job.prompt 의 자료로 문제 count 개를 schema 형식으로 만들어 submit_questions 로 보내요. job 이 null 이면 끝이에요",
                "inputSchema" to schema(AI_PROPERTY, listOf("ai")),
                "annotations" to mapOf("readOnlyHint" to true, "openWorldHint" to false),
            ),
            mapOf(
                "name" to "submit_questions",
                "description" to "내 AI 출제: get_jobs 로 받은 자료의 문제를 보내요 {saved, verified, rejected}. " +
                    "evidenceQuote 가 근거 페이지에 글자 그대로 있어야 출제돼요",
                "inputSchema" to schema(
                    AI_PROPERTY + mapOf(
                        "fileId" to mapOf("type" to "integer"),
                        "questions" to mapOf(
                            "type" to "array",
                            "maxItems" to MAX_SUBMIT,
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "concept" to mapOf("type" to "string"),
                                    "stem" to mapOf("type" to "string"),
                                    "choices" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "minItems" to 4, "maxItems" to 4),
                                    "answerIndex" to mapOf("type" to "integer", "description" to "0~3"),
                                    "explanation" to mapOf("type" to "string"),
                                    "evidencePage" to mapOf("type" to "integer"),
                                    "evidenceQuote" to mapOf("type" to "string"),
                                ),
                                "required" to listOf("concept", "stem", "choices", "answerIndex", "explanation", "evidencePage", "evidenceQuote"),
                            ),
                        ),
                    ),
                    listOf("ai", "fileId", "questions"),
                ),
                "annotations" to mapOf("readOnlyHint" to false, "destructiveHint" to false, "idempotentHint" to false, "openWorldHint" to false),
            ),
        )
    }
}
