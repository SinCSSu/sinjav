package top.sincs.sinjav.scraper

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties
import java.net.InetSocketAddress

/** 验证代理配置修改后立即生效（HttpClient 随配置重建），无需重启应用。 */
class HttpClientFactoryTest {

    @Test
    fun `修改代理配置后立即生效`() {
        val props = AppProperties()
        props.scraper.requestIntervalMs = 0
        props.scraper.timeoutMs = 1000

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            val factory = HttpClientFactory(props)

            // 1. 指向一个无人监听的代理端口：请求必须失败（证明请求确实走了代理）
            props.scraper.proxyHost = "127.0.0.1"
            props.scraper.proxyPort = 1
            assertThrows(Exception::class.java) { factory.get(url) }

            // 2. 清空代理：必须立即恢复直连成功（证明配置变更触发了客户端重建）
            props.scraper.proxyHost = ""
            props.scraper.proxyPort = 0
            assertEquals("ok", factory.get(url))

            // 3. 再换一个有效代理语义（指向本地服务端口作代理会失败），再清空后仍能恢复
            props.scraper.proxyHost = "127.0.0.1"
            props.scraper.proxyPort = 1
            assertThrows(Exception::class.java) { factory.get(url) }
            props.scraper.proxyHost = ""
            props.scraper.proxyPort = 0
            assertEquals("ok", factory.get(url))
        } finally {
            server.stop(0)
        }
    }
}
