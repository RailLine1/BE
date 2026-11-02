package app.daybit.connector

import app.daybit.global.storage.FileStorage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** 도구에서 사용자에게 보여 줄 오류 (MCP 결과의 isError = true) */
class ToolError(message: String) : RuntimeException(message)

/** 도구 결과: 텍스트(JSON) 또는 이미지 */
sealed interface ToolResult {
    data class Json(val value: Any) : ToolResult
    data class Image(val base64: String, val mimeType: String) : ToolResult
}

/**
 * MCP 도구 5개 (docs/api.md "MCP 서버", P-AI-03).
 * 모두 읽기 전용이고, 토큰 주인이 구독 중(승인된 멤버)인 과목의 ready 자료만 다뤄요.
 * 원본 열람 off(P-MAT-05)여도 막지 않아요 (P-MAT-06).
 */
@Component
class ConnectorTools(
    private val jdbc: JdbcClient,
    private val storage: FileStorage,
) {

    fun listCourses(userId: Long): ToolResult =
        ToolResult.Json(
            jdbc.sql(
                """
                select c.id, c.name, c.code, c.term
                  from course_members m join courses c on c.id = m.course_id
                 where m.user_id = :u and m.status = 'active'
                 order by c.name, c.id
                """.trimIndent(),
            ).param("u", userId)
                .query { rs, _ -> mapOf("courseId" to rs.getLong("id"), "name" to rs.getString("name"), "code" to rs.getString("code"), "term" to rs.getString("term")) }
                .list(),
        )

    fun listMaterials(userId: Long, courseId: Long): ToolResult {
        requireMember(userId, courseId)
        return ToolResult.Json(
            jdbc.sql(
                """
                select d.name as folder, f.id, f.title, f.pages
                  from files f join folders d on d.id = f.folder_id
                 where f.course_id = :c and f.status = 'ready'
                 order by d.sort, d.id, f.created_at, f.id
                """.trimIndent(),
            ).param("c", courseId)
                .query { rs, _ -> mapOf("folder" to rs.getString("folder"), "fileId" to rs.getLong("id"), "title" to rs.getString("title"), "pages" to rs.getInt("pages")) }
                .list(),
        )
    }

    /**
     * 키워드 검색: 검색어를 공백으로 나눠 하나라도 들어 있는 페이지를 찾고, 많이 맞은 페이지부터 돌려줘요.
     * 대소문자는 무시하고 `%` · `_`는 글자 그대로 찾아요.
     */
    fun search(userId: Long, courseId: Long, query: String, limit: Int?): ToolResult {
        requireMember(userId, courseId)
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct().take(MAX_TERMS)
        if (terms.isEmpty()) throw ToolError("query 가 비었어요.")
        val size = (limit ?: DEFAULT_SEARCH).coerceIn(1, MAX_SEARCH)
        val patterns = terms.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }

        data class Hit(val fileId: Long, val title: String, val page: Int, val text: String)
        val hits = jdbc.sql(
            """
            select f.id, f.title, p.page_no, p.text
              from file_pages p join files f on f.id = p.file_id
             where f.course_id = :c and f.status = 'ready' and p.text ilike any (:patterns)
             order by f.created_at desc, f.id, p.page_no
             limit 300
            """.trimIndent(),
        ).param("c", courseId).param("patterns", patterns.toTypedArray())
            .query { rs, _ -> Hit(rs.getLong("id"), rs.getString("title"), rs.getInt("page_no"), rs.getString("text") ?: "") }
            .list()

        val ranked = hits
            .map { h -> h to terms.count { h.text.contains(it, ignoreCase = true) } }
            .sortedByDescending { it.second }
            .take(size)
            .map { (h, _) -> mapOf("fileId" to h.fileId, "title" to h.title, "page" to h.page, "snippet" to snippet(h.text, terms)) }
        return ToolResult.Json(ranked)
    }

    fun readPages(userId: Long, fileId: Long, pages: List<Int>): ToolResult {
        if (pages.isEmpty()) throw ToolError("pages 가 비었어요.")
        if (pages.distinct().size > MAX_PAGES) throw ToolError("한 번에 ${MAX_PAGES}쪽까지 읽을 수 있어요.")
        readyFile(userId, fileId)
        val wanted = pages.distinct().sorted()
        return ToolResult.Json(
            jdbc.sql("select page_no, text from file_pages where file_id = :f and page_no in (:pages) order by page_no")
                .param("f", fileId).param("pages", wanted)
                .query { rs, _ -> mapOf("page" to rs.getInt("page_no"), "text" to (rs.getString("text") ?: "")) }
                .list(),
        )
    }

    /** 원본 PDF 에서 그 페이지를 PNG 로 그려요. 페이지 이미지는 미리 저장하지 않아요 */
    fun pageImage(userId: Long, fileId: Long, page: Int): ToolResult {
        val file = readyFile(userId, fileId)
        if (page < 1 || page > file.pages) throw ToolError("1~${file.pages}쪽 사이로 골라 주세요.")
        val png = Loader.loadPDF(storage.get(file.storageKey)).use { doc ->
            val image = PDFRenderer(doc).renderImageWithDPI(page - 1, IMAGE_DPI, ImageType.RGB)
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
        return ToolResult.Image(Base64.getEncoder().encodeToString(png), "image/png")
    }

    // ---------- 공통 ----------

    private fun requireMember(userId: Long, courseId: Long) {
        val ok = jdbc.sql("select count(*) from course_members where course_id = :c and user_id = :u and status = 'active'")
            .param("c", courseId).param("u", userId).query(Int::class.java).single() > 0
        if (!ok) throw ToolError("구독 중인 과목이 아니에요. list_courses 로 과목을 확인해 주세요.")
    }

    private data class FileRow(val courseId: Long, val storageKey: String, val pages: Int)

    private fun readyFile(userId: Long, fileId: Long): FileRow {
        val file = jdbc.sql("select course_id, storage_key, pages from files where id = :f and status = 'ready'").param("f", fileId)
            .query { rs, _ -> FileRow(rs.getLong("course_id"), rs.getString("storage_key"), rs.getInt("pages")) }
            .optional().orElse(null) ?: throw ToolError("자료를 찾을 수 없어요. list_materials 로 자료를 확인해 주세요.")
        requireMember(userId, file.courseId)
        return file
    }

    /** 처음 맞은 검색어 주변 앞뒤로 잘라요 */
    private fun snippet(text: String, terms: List<String>): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val at = terms.map { flat.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = (at - SNIPPET_SIDE).coerceAtLeast(0)
        val to = (at + SNIPPET_SIDE * 2).coerceAtMost(flat.length)
        return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
    }

    companion object {
        const val MAX_PAGES = 5
        private const val DEFAULT_SEARCH = 5
        private const val MAX_SEARCH = 20
        private const val MAX_TERMS = 8
        private const val SNIPPET_SIDE = 80
        private const val IMAGE_DPI = 110f
    }
}
