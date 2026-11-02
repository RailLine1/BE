package app.daybit.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

/** 자료 텍스트 추출 같은 뒷단 작업을 @Async 로 돌려요. 가상 스레드 실행기를 써요(spring.threads.virtual.enabled) */
@Configuration
@EnableAsync
class AsyncConfig
