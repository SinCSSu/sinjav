package top.sincs.sinjav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * 防爆破锁定集成测试（独立上下文/独立数据库：
 * 锁定计数是全局内存状态，与 AuthFlowTests 分开避免互相污染）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AuthLockoutTests {

    companion object {
        private val dbFile: Path = Path.of("build", "sinjav-auth-lock-test.db")

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            Files.deleteIfExists(dbFile)
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbFile.toAbsolutePath()}" }
            registry.add("spring.datasource.hikari.data-source-properties.date_string_format") { "yyyy-MM-dd HH:mm:ss" }
        }
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private fun post(path: String, body: String): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    @Test
    fun `连续5次失败锁定15分钟`() {
        val st = rest.getForEntity("/api/v1/auth/status", Map::class.java).body!!
        if (st["initialized"] != true) {
            post("/api/v1/auth/init", """{"username":"admin","password":"secret66"}""")
        }
        repeat(4) {
            assertEquals(
                HttpStatus.UNAUTHORIZED,
                post("/api/v1/auth/login", """{"username":"admin","password":"wrong-pass"}""").statusCode,
            )
        }
        // 第 5 次失败即触发锁定（该次仍返回 401）
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/v1/auth/login", """{"username":"admin","password":"wrong-pass"}""").statusCode)
        // 锁定期间正确密码也 429
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, post("/api/v1/auth/login", """{"username":"admin","password":"secret66"}""").statusCode)
    }
}
