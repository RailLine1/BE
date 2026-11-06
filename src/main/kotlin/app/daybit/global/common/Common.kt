package app.daybit.global.common

/** 목록 응답: `?page=1&size=8` → `{ items, total, page, size }` (API 명세서 0. 공통) */
data class PageResponse<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val size: Int,
)

/** 내 AI 출제 · 퀴즈 출처에서 쓰는 AI 종류 (P-AI-07, P-BIT-14) */
@Suppress("EnumEntryName")
enum class MyAi { claude, chatgpt }

/** 과목 권한 (P-ROLE) */
@Suppress("EnumEntryName")
enum class CourseRole { admin, co, member }

/** 근거 페이지 `{ file: "lec07", page: 12 }` */
data class Evidence(val fileId: Long?, val file: String, val page: Int)
