package app.daybit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@SpringBootApplication
@ConfigurationPropertiesScan
class DaybitServerApplication

fun main(args: Array<String>) {
	// 모든 시간 계산은 서울 기준이에요 (API 명세서 0. 공통 · 17:00 출제 등)
	TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
	runApplication<DaybitServerApplication>(*args)
}
