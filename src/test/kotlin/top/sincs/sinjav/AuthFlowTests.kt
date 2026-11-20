package top.sincs.sinjav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * 认证链路集成测试：初始化 → 登录 → 会话可用 → 登出 → 401；改凭据验证当前密码。
 * 防爆破锁定在 AuthLockoutTests（独立上下文，避免全局失败计数污染本类）。
 * 两个用例共享同一数据库，显式固定执行顺序（改用户名会影响登录凭据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class AuthFlowTests {

    companion object {
        private val dbFile: Path = Path.of("build", "sinjav-auth-test.db")

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

    private fun post(path: String, body: String, cookie: String? = null) =
        exchange(path, HttpMethod.POST, body, cookie)

    private fun put(path: String, body: String, cookie: String? = null) =
        exchange(path, HttpMethod.PUT, body, cookie)

    private fun exchange(path: String, method: HttpMethod, body: String?, cookie: String?): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        cookie?.let { headers.add(HttpHeaders.COOKIE, it) }
        return rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private fun cookieOf(resp: org.springframework.http.ResponseEntity<String>): String? =
        resp.headers[HttpHeaders.SET_COOKIE]?.joinToString("; ") { it.split(";").first() }

    @org.junit.jupiter.api.Order(1)
    @Test
    fun `初始化-登录-受保护接口-登出-401`() {
        // 未登录：受保护接口 401
        assertEquals(HttpStatus.UNAUTHORIZED, rest.getForEntity("/api/v1/movies", String::class.java).statusCode)
        val status0 = rest.getForEntity("/api/v1/auth/status", Map::class.java).body!!
        val init = if (status0["initialized"] == true) {
            // 已被本类其他测试初始化：直接登录获取会话
            post("/api/v1/auth/login", """{"username":"admin","password":"secret66"}""")
        } else {
            // 弱密码被拒
            assertEquals(HttpStatus.BAD_REQUEST, post("/api/v1/auth/init", """{"username":"admin","password":"123"}""").statusCode)
            post("/api/v1/auth/init", """{"username":"admin","password":"secret66"}""")
        }
        assertEquals(HttpStatus.OK, init.statusCode, "init/login 响应: ${init.body}")
        val sessionCookie = cookieOf(init)
        assertNotNull(sessionCookie)
        // 重复初始化 409
        assertEquals(HttpStatus.CONFLICT, post("/api/v1/auth/init", """{"username":"a","password":"secret66"}""").statusCode)
        // 带会话访问受保护接口 200
        assertEquals(HttpStatus.OK, exchange("/api/v1/movies", HttpMethod.GET, null, sessionCookie).statusCode)
        // 登出后 401
        post("/api/v1/auth/logout", "", sessionCookie)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("/api/v1/movies", HttpMethod.GET, null, sessionCookie).statusCode)
    }

    @org.junit.jupiter.api.Order(2)
    @Test
    fun `验证当前密码后可改用户名`() {
        val st = rest.getForEntity("/api/v1/auth/status", Map::class.java).body!!
        if (st["initialized"] != true) {
            post("/api/v1/auth/init", """{"username":"admin","password":"secret66"}""")
        }
        // 当前密码错误 → 400
        assertEquals(HttpStatus.BAD_REQUEST, put("/api/v1/auth/credentials", """{"currentPassword":"nope","username":"root"}""").statusCode)
        // 正确 → 200，status 显示新用户名
        assertEquals(HttpStatus.OK, put("/api/v1/auth/credentials", """{"currentPassword":"secret66","username":"root"}""").statusCode)
        val status = rest.getForEntity("/api/v1/auth/status", Map::class.java).body!!
        assertEquals("root", status["username"])
    }
}
