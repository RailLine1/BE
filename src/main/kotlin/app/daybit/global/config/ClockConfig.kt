package app.daybit.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/** 날짜 계산은 이 Clock 으로 해요. 테스트에서 시각을 바꿔 끼울 수 있어요 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(SEOUL)

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
