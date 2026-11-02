package app.daybit.bit

import app.daybit.global.config.ClockConfig.Companion.SEOUL
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Bit 하루의 경계 (P-BIT-05): 17:00 에 열리고 다음 날 16:59 에 마감돼요.
 * "Bit 날짜"는 그 Bit 가 열린 서울 날짜예요. 16:59 까지는 어제 날짜의 Bit 가 열려 있어요.
 */
object BitDays {
    val OPEN_TIME: LocalTime = LocalTime.of(17, 0)

    /** 지금 열려 있는 Bit 의 날짜 */
    fun current(now: ZonedDateTime): LocalDate {
        val seoul = now.withZoneSameInstant(SEOUL)
        return if (seoul.toLocalTime() >= OPEN_TIME) seoul.toLocalDate() else seoul.toLocalDate().minusDays(1)
    }

    fun openAt(date: LocalDate): OffsetDateTime = date.atTime(OPEN_TIME).atZone(SEOUL).toOffsetDateTime()

    /** 다음 날 16:59:59 */
    fun closeAt(date: LocalDate): OffsetDateTime = openAt(date).plusDays(1).minusSeconds(1)
}
