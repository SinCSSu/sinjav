package top.sincs.sinjav

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class SinJavApplication

fun main(args: Array<String>) {
    runApplication<SinJavApplication>(*args)
}
