package app.daybit

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<DaybitServerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
