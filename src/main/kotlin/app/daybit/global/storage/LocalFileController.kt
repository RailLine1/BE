package app.daybit.global.storage

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 로컬 저장소의 임시 URL 을 열어 주는 곳. R2 를 쓰면 R2 가 직접 내려주므로 여기는 404 예요. */
@Hidden
@RestController
class LocalFileController(private val storage: FileStorage) {

    @GetMapping("/local-files/**")
    fun read(request: HttpServletRequest, @RequestParam exp: Long, @RequestParam sig: String): ResponseEntity<ByteArray> {
        val local = storage as? LocalFileStorage ?: throw ApiException(ErrorCode.NOT_FOUND)
        val key = URLDecoder.decode(request.requestURI.removePrefix("/local-files/"), StandardCharsets.UTF_8)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(local.readSigned(key, exp, sig))
    }
}
