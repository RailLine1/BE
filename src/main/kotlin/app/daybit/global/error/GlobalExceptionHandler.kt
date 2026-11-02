package app.daybit.global.error

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ErrorResponse> = respond(e.code, e.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidBody(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val first = e.bindingResult.fieldErrors.firstOrNull()
        val message = first?.let { "${it.field}: ${it.defaultMessage}" } ?: ErrorCode.VALIDATION_FAILED.defaultMessage
        return respond(ErrorCode.VALIDATION_FAILED, message)
    }

    @ExceptionHandler(
        HandlerMethodValidationException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleBadRequest(e: Exception): ResponseEntity<ErrorResponse> = respond(ErrorCode.VALIDATION_FAILED)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> = respond(ErrorCode.FORBIDDEN)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = respond(ErrorCode.NOT_FOUND)

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return respond(ErrorCode.INTERNAL_ERROR)
    }

    private fun respond(code: ErrorCode, message: String = code.defaultMessage) =
        ResponseEntity.status(code.status).body(ErrorResponse.of(code, message))
}
