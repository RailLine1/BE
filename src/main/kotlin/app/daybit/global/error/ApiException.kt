package app.daybit.global.error

class ApiException(
    val code: ErrorCode,
    override val message: String = code.defaultMessage,
) : RuntimeException(message)

/** `{ "error": { "code": "...", "message": "..." } }` */
data class ErrorResponse(val error: Body) {
    data class Body(val code: String, val message: String)

    companion object {
        fun of(code: ErrorCode, message: String = code.defaultMessage) =
            ErrorResponse(Body(code.name, message))
    }
}
