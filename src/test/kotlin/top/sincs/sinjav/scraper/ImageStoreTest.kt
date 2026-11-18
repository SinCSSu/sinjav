package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Files

/** 刮削成功后下载封面/剧照到本地目录。 */
class ImageStoreTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0x02)

    private fun startServer(): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.sendResponseHeaders(200, jpeg.size.toLong())
            ex.responseBody.use { it.write(jpeg) }
        }
        server.start()
        return server
    }

    @Test
    fun `下载封面与剧照到本地目录`() {
        val server = startServer()
        try {
            val props = AppProperties().apply {
                scraper.requestIntervalMs = 0
                scraper.timeoutMs = 3000
                images.download = true
                images.dir = Files.createTempDirectory("sinjav-imgs").toString()
            }
            val store = ImageStore(HttpClientFactory(props), props)
            val base = "http://127.0.0.1:${server.address.port}"
            store.downloadAll("ABP-123", coverUrl = "$base/pics/cover/a.jpg", previewUrls = listOf("$base/pics/1.jpg", "$base/pics/2.jpg"))

            val dir = java.nio.file.Path.of(props.images.dir).resolve("ABP-123")
            assertEquals(jpeg.toList(), Files.readAllBytes(dir.resolve("poster.jpg")).toList())
            assertEquals(jpeg.toList(), Files.readAllBytes(dir.resolve("preview1.jpg")).toList())
            assertEquals(jpeg.toList(), Files.readAllBytes(dir.resolve("preview2.jpg")).toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `开关关闭时不下载`() {
        val props = AppProperties().apply {
            images.download = false
            images.dir = Files.createTempDirectory("sinjav-imgs-off").toString()
        }
        val store = ImageStore(HttpClientFactory(props), props)
        store.downloadAll("ABP-123", coverUrl = "http://127.0.0.1:1/a.jpg", previewUrls = emptyList())
        assertFalse(Files.exists(java.nio.file.Path.of(props.images.dir).resolve("ABP-123")))
    }

    @Test
    fun `下载失败不影响刮削流程（仅记录警告）`() {
        val props = AppProperties().apply {
            scraper.requestIntervalMs = 0
            scraper.timeoutMs = 500
            images.download = true
            images.dir = Files.createTempDirectory("sinjav-imgs-fail").toString()
        }
        val store = ImageStore(HttpClientFactory(props), props)
        // 无人监听的端口 → 下载失败，但不应抛异常
        store.downloadAll("ABP-123", coverUrl = "http://127.0.0.1:1/a.jpg", previewUrls = listOf("http://127.0.0.1:1/b.jpg"))
        val dir = java.nio.file.Path.of(props.images.dir).resolve("ABP-123")
        assertTrue(Files.isDirectory(dir), "目录应已创建")
        assertTrue(
            Files.list(dir).use { it.count() } == 0L,
            "失败的文件不应写入",
        )
    }
}
