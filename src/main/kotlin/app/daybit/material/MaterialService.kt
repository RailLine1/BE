package app.daybit.material

import app.daybit.course.CourseService
import app.daybit.global.common.CourseRole
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.storage.FileStorage
import app.daybit.global.storage.deleteAfterCommit
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** 업로드가 커밋되면 MaterialProcessor 가 텍스트를 추출해요 */
data class MaterialUploadedEvent(val fileId: Long)

@Service
class MaterialService(
    private val jdbc: JdbcClient,
    private val courses: CourseService,
    private val storage: FileStorage,
    private val events: ApplicationEventPublisher,
) {

    // ---------- 폴더 ----------

    /** P-MAT-04, 05: 멤버는 원본 열람 off 면 403, 개수는 ready 만 세요 */
    @Transactional(readOnly = true)
    fun folders(userId: Long, courseId: Long): List<FolderItem> {
        val manager = readAccess(userId, courseId)
        return jdbc.sql(
            """
            select f.id, f.name, f.kind,
                   (select count(*) from files x
                     where x.folder_id = f.id and (:manager or x.status = 'ready')) as file_count
              from folders f
             where f.course_id = :courseId
             order by f.sort, f.id
            """.trimIndent(),
        ).param("courseId", courseId).param("manager", manager)
            .query { rs, _ ->
                FolderItem(rs.getLong("id"), rs.getString("name"), FolderKind.valueOf(rs.getString("kind")), rs.getInt("file_count"))
            }.list()
    }

    /** P-MAT-01: 관리자 · 공동 관리자만 폴더를 더 만들어요 */
    @Transactional
    fun createFolder(userId: Long, courseId: Long, req: FolderRequest): FolderItem {
        requireManager(userId, courseId)
        val name = req.name.trim().ifEmpty { throw ApiException(ErrorCode.VALIDATION_FAILED, "name: 폴더 이름을 입력해 주세요.") }
        val id = jdbc.sql(
            """
            insert into folders (course_id, name, kind, sort)
            values (:courseId, :name, 'custom', (select coalesce(max(sort), 0) + 1 from folders where course_id = :courseId))
            returning id
            """.trimIndent(),
        ).param("courseId", courseId).param("name", name).query(Long::class.java).single()
        return FolderItem(id, name, FolderKind.custom, 0)
    }

    /** P-MAT-01: 기본 폴더 mid · final 은 이름을 바꿀 수 없어요 */
    @Transactional
    fun renameFolder(userId: Long, folderId: Long, req: FolderRequest): FolderItem {
        val folder = folder(folderId)
        requireManager(userId, folder.courseId)
        if (folder.kind != FolderKind.custom) throw ApiException(ErrorCode.VALIDATION_FAILED, "기본 폴더는 이름을 바꿀 수 없어요.")
        val name = req.name.trim().ifEmpty { throw ApiException(ErrorCode.VALIDATION_FAILED, "name: 폴더 이름을 입력해 주세요.") }
        jdbc.sql("update folders set name = :name where id = :id").param("name", name).param("id", folderId).update()
        val count = jdbc.sql("select count(*) from files where folder_id = :id").param("id", folderId).query(Int::class.java).single()
        return FolderItem(folderId, name, folder.kind, count)
    }

    /** P-MAT-01: 기본 폴더는 지울 수 없어요. 안의 파일과 원본도 함께 지워요 */
    @Transactional
    fun deleteFolder(userId: Long, folderId: Long) {
        val folder = folder(folderId)
        requireManager(userId, folder.courseId)
        if (folder.kind != FolderKind.custom) throw ApiException(ErrorCode.VALIDATION_FAILED, "기본 폴더는 지울 수 없어요.")
        val keys = jdbc.sql("select storage_key from files where folder_id = :id").param("id", folderId)
            .query(String::class.java).list().filterNotNull()
        jdbc.sql("delete from folders where id = :id").param("id", folderId).update()
        storage.deleteAfterCommit(keys)
    }

    // ---------- 파일 ----------

    /** P-MAT-04: 멤버는 ready 만, 최근 업로드 순 */
    @Transactional(readOnly = true)
    fun files(userId: Long, folderId: Long): List<FileItem> {
        val folder = folder(folderId)
        val manager = readAccess(userId, folder.courseId)
        return jdbc.sql(
            """
            select id, title, status, pages, created_at
              from files
             where folder_id = :folderId and (:manager or status = 'ready')
             order by created_at, id   -- 올린 순서. 강의 자료는 보통 1강부터 차례로 올린다
            """.trimIndent(),
        ).param("folderId", folderId).param("manager", manager)
            .query { rs, _ ->
                FileItem(
                    fileId = rs.getLong("id"),
                    title = rs.getString("title"),
                    status = FileStatus.valueOf(rs.getString("status")),
                    pages = rs.getObject("pages") as Int?,
                    uploadedAt = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            }.list()
    }

    /** P-MAT-02, 03 · P-BIT-18 */
    @Transactional
    fun upload(userId: Long, folderId: Long, file: MultipartFile, title: String?, consent: Boolean): UploadResponse {
        val folder = folder(folderId)
        requireManager(userId, folder.courseId, lock = true)
        if (!consent) throw ApiException(ErrorCode.VALIDATION_FAILED, "consent: 자료 사용 동의가 필요해요.")
        val cleanTitle = resolveTitle(title, file.originalFilename)
        val bytes = file.bytes
        if (!isPdf(bytes)) throw ApiException(ErrorCode.VALIDATION_FAILED, "file: PDF 파일만 올릴 수 있어요.")

        val key = "courses/${folder.courseId}/files/${UUID.randomUUID()}.pdf"
        storage.put(key, bytes, "application/pdf")
        // 원본은 올렸는데 DB 가 롤백되면 원본이 고아가 되므로 지워요
        registerRollbackCleanup(key)

        val fileId = jdbc.sql(
            """
            insert into files (folder_id, course_id, title, storage_key, status, uploaded_by, consent_at)
            values (:folderId, :courseId, :title, :key, 'processing', :userId, now())
            returning id
            """.trimIndent(),
        ).param("folderId", folderId).param("courseId", folder.courseId).param("title", cleanTitle)
            .param("key", key).param("userId", userId).query(Long::class.java).single()

        // P-BIT-18: 새 자료가 올라오면 활동 없음으로 꺼진 과목을 다시 켜요. 기말 OFF 는 그대로예요
        jdbc.sql(
            """
            update courses
               set last_upload_at = now(),
                   state        = case when state = 'ended' and ended_reason = 'inactive' then 'active' else state end,
                   ended_reason = case when state = 'ended' and ended_reason = 'inactive' then null else ended_reason end,
                   ended_at     = case when state = 'ended' and ended_reason = 'inactive' then null else ended_at end
             where id = :courseId
            """.trimIndent(),
        ).param("courseId", folder.courseId).update()

        events.publishEvent(MaterialUploadedEvent(fileId))
        return UploadResponse(fileId, FileStatus.processing)
    }

    /** P-MAT-05: 멤버는 원본 열람 off 면 403, ready 가 아니면 404 */
    @Transactional(readOnly = true)
    fun view(userId: Long, fileId: Long): FileViewResponse {
        val file = file(fileId)
        val manager = readAccess(userId, file.courseId)
        if (!manager && file.status != FileStatus.ready) throw ApiException(ErrorCode.NOT_FOUND)
        val temp = storage.temporaryUrl(file.storageKey, VIEW_TTL)
        return FileViewResponse(temp.url, temp.expiresAt)
    }

    /** 이름만 바꿔요. 원본 · 추출 텍스트 · 문제의 근거는 파일 id 로 이어져 있어서 그대로예요 */
    @Transactional
    fun renameFile(userId: Long, fileId: Long, title: String): FileItem {
        val file = file(fileId)
        requireManager(userId, file.courseId)
        val clean = title.trim().ifEmpty { throw ApiException(ErrorCode.VALIDATION_FAILED, "title: 이름을 입력해 주세요.") }
        return jdbc.sql(
            "update files set title = :title where id = :id returning id, title, status, pages, created_at",
        ).param("title", clean).param("id", fileId)
            .query { rs, _ ->
                FileItem(
                    fileId = rs.getLong("id"),
                    title = rs.getString("title"),
                    status = FileStatus.valueOf(rs.getString("status")),
                    pages = rs.getObject("pages") as Int?,
                    uploadedAt = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            }.single()
    }

    @Transactional
    fun deleteFile(userId: Long, fileId: Long) {
        val file = file(fileId)
        requireManager(userId, file.courseId)
        jdbc.sql("delete from files where id = :id").param("id", fileId).update()
        storage.deleteAfterCommit(listOf(file.storageKey))
    }

    // ---------- 권한 ----------

    /** 읽기 권한을 확인하고 관리자 · 공동 관리자인지 돌려줘요. 멤버는 원본 열람 off 면 403 (P-MAT-05) */
    private fun readAccess(userId: Long, courseId: Long): Boolean {
        val m = courses.membership(userId, courseId)
        if (m.role != CourseRole.member) return true
        val rawAllowed = jdbc.sql("select raw_allowed from courses where id = :id").param("id", courseId)
            .query(Boolean::class.java).single()
        if (!rawAllowed) throw ApiException(ErrorCode.FORBIDDEN)
        return false
    }

    private fun requireManager(userId: Long, courseId: Long, lock: Boolean = false) {
        if (courses.membership(userId, courseId, lock).role == CourseRole.member) throw ApiException(ErrorCode.FORBIDDEN)
    }

    private data class FolderRow(val courseId: Long, val kind: FolderKind)

    private fun folder(folderId: Long): FolderRow =
        jdbc.sql("select course_id, kind from folders where id = :id").param("id", folderId)
            .query { rs, _ -> FolderRow(rs.getLong("course_id"), FolderKind.valueOf(rs.getString("kind"))) }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }

    private data class FileRow(val courseId: Long, val storageKey: String, val status: FileStatus)

    private fun file(fileId: Long): FileRow =
        jdbc.sql("select course_id, storage_key, status from files where id = :id").param("id", fileId)
            .query { rs, _ -> FileRow(rs.getLong("course_id"), rs.getString("storage_key"), FileStatus.valueOf(rs.getString("status"))) }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }

    private fun registerRollbackCleanup(key: String) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK) runCatching { storage.delete(key) }
                }
            },
        )
    }

    /** 보낸 이름이 있으면 그 이름, 없으면 파일 이름에서 .pdf 를 뺀 값 (최대 80자) */
    private fun resolveTitle(title: String?, filename: String?): String {
        val given = title?.trim().orEmpty()
        if (given.length > TITLE_MAX) throw ApiException(ErrorCode.VALIDATION_FAILED, "title: 이름은 ${TITLE_MAX}자까지예요.")
        if (given.isNotEmpty()) return given
        val fromFile = filename.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("(?i)\\.pdf$"), "").trim().take(TITLE_MAX)
        return fromFile.ifEmpty { "자료" }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= PDF_MAGIC.size && PDF_MAGIC.indices.all { bytes[it] == PDF_MAGIC[it] }

    companion object {
        private const val TITLE_MAX = 80
        private val VIEW_TTL: Duration = Duration.ofMinutes(10)
        private val PDF_MAGIC = "%PDF-".toByteArray()
    }
}
