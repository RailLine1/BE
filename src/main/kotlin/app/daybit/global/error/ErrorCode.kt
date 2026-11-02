package app.daybit.global.error

import org.springframework.http.HttpStatus

/**
 * API 명세서 "0. 공통 · 오류 응답"의 code 목록.
 * 새 코드를 추가하면 프론트와 공유해요.
 */
enum class ErrorCode(val status: HttpStatus, val defaultMessage: String) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력 형식이 올바르지 않아요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않아요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임이에요."),
    LOGIN_ID_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 아이디예요."),
    QUIZ_SOURCE_UNAVAILABLE(HttpStatus.BAD_REQUEST, "작업 공간에 연결된 내 AI만 퀴즈 출처로 고를 수 있어요."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 커요. 50MB 이하로 올려 주세요."),
    ALREADY_ATTEMPTED(HttpStatus.CONFLICT, "이미 푼 문제예요."),
    COURSE_ENDED(HttpStatus.CONFLICT, "수강 기간이 끝난 과목이에요."),
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "아직 준비 중인 기능이에요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해 주세요."),
}
