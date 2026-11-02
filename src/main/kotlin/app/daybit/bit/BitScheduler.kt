package app.daybit.bit

import app.daybit.global.config.ClockConfig.Companion.SEOUL
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 서울 시각 기준 배치 (docs/api.md 배치 작업). 서버 1대를 전제로 해서 ShedLock 은 쓰지 않아요(결정 D9).
 * 테스트에서는 daybit.batch.enabled=false 로 꺼 두고 BitBatchService 를 직접 불러요.
 */
@Component
@ConditionalOnProperty(prefix = "daybit.batch", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BitScheduler(
    private val batch: BitBatchService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 16:30 출제 준비: 오늘 17:00 에 열 Bit 를 만들어요 */
    @Scheduled(cron = "0 30 16 * * *", zone = "Asia/Seoul")
    fun prepare() = run("prepare") { batch.prepare(LocalDate.now(clock.withZone(SEOUL)), now().toOffsetDateTime()) }

    /** 17:00 어제 Bit 마감 · 아카이브, 오늘 Bit 열림 알림 */
    @Scheduled(cron = "0 0 17 * * *", zone = "Asia/Seoul")
    fun open() = run("open") { batch.open(LocalDate.now(clock.withZone(SEOUL)), now().toOffsetDateTime()) }

    /** 00:10 과목 상태 점검 · 문제 은행 보충 */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    fun dailyCheck() = run("dailyCheck") { batch.dailyCheck(LocalDate.now(clock.withZone(SEOUL)), now().toOffsetDateTime()) }

    /** 서버가 배치 시각에 꺼져 있었으면 시작할 때 따라잡아요 */
    @EventListener(ApplicationReadyEvent::class)
    fun catchUp() = run("catchUp") { batch.catchUp(now()) }

    private fun now() = ZonedDateTime.now(clock.withZone(SEOUL))

    private fun run(name: String, block: () -> Unit) {
        runCatching(block).onFailure { log.error("배치 실패: {}", name, it) }
    }
}
