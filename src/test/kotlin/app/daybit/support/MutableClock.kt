package app.daybit.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 테스트에서 시각을 마음대로 옮기는 Clock */
class MutableClock(private var instant: Instant = Instant.now(), private val zone: ZoneId = ZoneId.of("Asia/Seoul")) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClockView(this, zone)
    override fun instant(): Instant = instant

    /** 서울 시각으로 맞춰요 */
    fun set(seoul: LocalDateTime) { instant = seoul.atZone(ZoneId.of("Asia/Seoul")).toInstant() }
    fun advance(d: Duration) { instant = instant.plus(d) }

    private class MutableClockView(private val base: MutableClock, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClockView(base, zone)
        override fun instant(): Instant = base.instant()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MutableClockConfig {
    @Bean
    @Primary
    fun mutableClock() = MutableClock()
}
