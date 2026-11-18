package top.sincs.sinjav.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.scraper.HttpClientFactory
import java.net.InetSocketAddress

/** 图片代理端点：白名单校验 + 自动携带源站点 Referer（绕过防盗链）。 */
class ImagesControllerTest {

    private fun props() = AppProperties().apply {
        scraper.requestIntervalMs = 0
        scraper.timeoutMs = 3000
    }

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handler(ex); ex.close() }
        server.start()
        return server
    }

    @Test
    fun `代理图片并自动携带源站点Referer`() {
        var seenReferer: String? = null
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x01)
        val server = startServer { ex ->
            seenReferer = ex.requestHeaders.getFirst("Referer")
            ex.responseHeaders.add("Content-Type", "image/jpeg")
            ex.sendResponseHeaders(200, jpeg.size.toLong())
            ex.responseBody.use { it.write(jpeg) }
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/pics/a.jpg"
            // 测试专用白名单允许本地夹具
            val controller = ImagesController(HttpClientFactory(props()), props(), allowedHostSuffixes = setOf("127.0.0.1"))
            val resp = controller.image(url)
            assertEquals(200, resp.statusCode.value())
            assertTrue(resp.headers.getFirst("Content-Type")!!.startsWith("image/"))
            assertTrue(resp.body!!.contentEquals(jpeg))
            // Referer 应按图片 URL 的源站点推导
            assertEquals("http://127.0.0.1/", seenReferer)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `非白名单域名拒绝代理`() {
        val controller = ImagesController(HttpClientFactory(props()), props())
        val resp = controller.image("http://evil.example.com/a.jpg")
        assertEquals(400, resp.statusCode.value())
    }
}
