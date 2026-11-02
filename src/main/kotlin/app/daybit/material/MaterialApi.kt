package app.daybit.material

import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.OffsetDateTime

@Suppress("EnumEntryName")
enum class FolderKind { mid, final, custom }

@Suppress("EnumEntryName")
enum class FileStatus { processing, ready, failed }

data class FolderItem(
    val folderId: Long,
    val name: String,
    val kind: FolderKind,
    /** 멤버에게는 ready 파일만 세요 (P-MAT-04) */
    val fileCount: Int,
)

data class FolderRequest(
    @field:NotBlank @field:Size(max = 30) val name: String,
)

data class FileItem(
    val fileId: Long,
    val title: String,
    val status: FileStatus,
    val pages: Int?,
    val uploadedAt: OffsetDateTime,
)

data class UploadResponse(val fileId: Long, val status: FileStatus)

data class FileViewResponse(
    /** 원본 PDF 를 볼 수 있는 임시 URL */
    val url: String,
    val expiresAt: OffsetDateTime,
)

@Tag(name = "6. 학습자료")
@RestController
@RequestMapping("/api/v1")
class MaterialController(private val service: MaterialService) {

    @Operation(summary = "폴더 목록 (P-MAT-04, 05)", description = "원본 열람 off 면 멤버는 403")
    @GetMapping("/courses/{courseId}/folders")
    fun folders(@LoginUser userId: Long, @PathVariable courseId: Long): List<FolderItem> =
        service.folders(userId, courseId)

    @Operation(summary = "폴더 만들기 (P-MAT-01)")
    @PostMapping("/courses/{courseId}/folders")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFolder(
        @LoginUser userId: Long,
        @PathVariable courseId: Long,
        @Valid @RequestBody req: FolderRequest,
    ): FolderItem = service.createFolder(userId, courseId, req)

    @Operation(summary = "폴더 이름 변경 (mid·final 은 400)")
    @PatchMapping("/folders/{folderId}")
    fun renameFolder(
        @LoginUser userId: Long,
        @PathVariable folderId: Long,
        @Valid @RequestBody req: FolderRequest,
    ): FolderItem = service.renameFolder(userId, folderId, req)

    @Operation(summary = "폴더 삭제 (mid·final 은 400)")
    @DeleteMapping("/folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteFolder(@LoginUser userId: Long, @PathVariable folderId: Long) =
        service.deleteFolder(userId, folderId)

    @Operation(summary = "파일 목록 (멤버는 ready 만, P-MAT-04)")
    @GetMapping("/folders/{folderId}/files")
    fun files(@LoginUser userId: Long, @PathVariable folderId: Long): List<FileItem> =
        service.files(userId, folderId)

    @Operation(summary = "PDF 업로드 (P-MAT-02, 03)", description = "PDF만, 50MB 이하(초과 시 413 FILE_TOO_LARGE). consent=true 필수. 응답은 status=processing 이고, 텍스트 추출이 끝나면 ready · failed 로 바뀌어요")
    @PostMapping("/folders/{folderId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @LoginUser userId: Long,
        @PathVariable folderId: Long,
        @RequestPart file: MultipartFile,
        @RequestParam title: String,
        @RequestParam consent: Boolean,
    ): UploadResponse = service.upload(userId, folderId, file, title, consent)

    @Operation(summary = "원본 보기용 임시 URL (원본 열람 off 면 멤버 403, P-MAT-05)", description = "URL 은 10분 동안 로그인 없이 열려요. 멤버는 ready 가 아닌 파일이면 404")
    @GetMapping("/files/{fileId}")
    fun view(@LoginUser userId: Long, @PathVariable fileId: Long): FileViewResponse =
        service.view(userId, fileId)

    @Operation(summary = "파일 삭제")
    @DeleteMapping("/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteFile(@LoginUser userId: Long, @PathVariable fileId: Long) =
        service.deleteFile(userId, fileId)
}
