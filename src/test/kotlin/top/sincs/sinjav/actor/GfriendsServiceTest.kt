package top.sincs.sinjav.actor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.common.AppMetadataRepository
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scraper.CoverCropper
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Gfriends 索引同步 / 按名查头像 / 头像下载校验。
 *
 * 索引源用本地 HttpServer 桩（嵌套与扁平两种 Filetree 格式都覆盖），不依赖外网；
 * 真实网络的端到端验证见 GfriendsLiveTest（GFRIENDS_LIVE=true 时运行）。
 */
class GfriendsServiceTest {

    // ------------------------------------------------------------ 测试脚手架

    private data class Fixture(val service: GfriendsService, val index: GfriendsIndexRepository)

    private fun newProps(indexUrl: String, thresholdDays: Int = 7): AppProperties = AppProperties().apply {
        scraper.requestIntervalMs = 0
        scraper.timeoutMs = 3000
        scraper.proxyHost = ""
        scraper.proxyPort = 0
        gfriends.indexUrl = indexUrl
        gfriends.syncThresholdDays = thresholdDays
        gfriends.enableProxy = false
        images.dir = Files.createTempDirectory("sinjav-gfriends-imgs").toString()
    }

    /** 临时 SQLite（内存库跨连接不可见，与 ImageStoreTest 同做法）+ 手工建 gfriends_index / app_metadata */
    private fun newService(props: AppProperties): Fixture {
        val dbFile = Files.createTempFile("sinjav-gfriends-test", ".sqlite")
        val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        val ds = SimpleDriverDataSource(driver, "jdbc:sqlite:$dbFile")
        val template = JdbcTemplate(ds)
        template.execute("CREATE TABLE gfriends_index (name TEXT PRIMARY KEY, url TEXT NOT NULL)")
        template.execute(
            "CREATE TABLE app_metadata (key TEXT PRIMARY KEY, value TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)",
        )
        val jdbc = NamedParameterJdbcTemplate(ds)
        val index = GfriendsIndexRepository(jdbc)
        val http = HttpClientFactory(props)
        val imageStore = ImageStore(http, props, CoverCropper(), MovieRepository(jdbc))
        return Fixture(GfriendsService(http, props, imageStore, index, AppMetadataRepository(jdbc)), index)
    }

    /** 记录命中次数的本地 HTTP 桩：path → 响应字节 */
    private class StubServer(responses: Map<String, ByteArray>) {
        val hits = AtomicInteger()
        private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { ex ->
                hits.incrementAndGet()
                val body = responses[ex.requestURI.path] ?: ByteArray(0)
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }

    /** 现行嵌套格式的 Filetree 桩（含同名重复条目，第二个上传者应被忽略） */
    private val nestedFiletree = """
        {
          "Content": {
            "z-テスト": {
              "夢乃あいか.jpg": "AI-Fix-夢乃あいか.jpg?t=1",
              "Rino.jpg": "Rino.jpg",
              "るり.png": "るり.png"
            },
            "0-Hand-Storage": {
              "夢乃あいか.jpg": "dup.jpg"
            }
          },
          "Information": { "TotalNum": 3, "TotalSize": 100 }
        }
    """.trimIndent().toByteArray()

    private fun makeJpeg(w: Int = 80, h: Int = 80): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun decode(url: String): String = URLDecoder.decode(url, StandardCharsets.UTF_8)

    // ------------------------------------------------------------ 索引同步

    @Test
    fun `首次同步解析嵌套 Filetree 并写库（同名取先出现者）`() {
        val server = StubServer(mapOf("/Filetree.json" to nestedFiletree))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, index) = newService(props)

            assertTrue(service.syncIndex(), "首次同步应成功")
            assertEquals(3L, index.count(), "3 条唯一演员名（重复条目按先出现者去重）")
            assertEquals(1, server.hits.get(), "应只下载一次索引")
            assertNotNull(service.lastSyncAt(), "应记录最后同步时间")

            val url = service.findPhoto("夢乃あいか")
            assertNotNull(url, "按名应命中")
            assertEquals(
                "${server.baseUrl}/Content/z-テスト/AI-Fix-夢乃あいか.jpg",
                decode(url!!),
                "URL 应由索引 URL 所在目录拼出，且用条目值里的真实文件名",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `索引未过期时跳过下载 强制同步才重新下载`() {
        val server = StubServer(mapOf("/Filetree.json" to nestedFiletree))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json", thresholdDays = 7)
            val (service, _) = newService(props)

            assertTrue(service.syncIndex())
            assertTrue(service.syncIndex(), "阈值内应跳过下载但仍返回可用")
            assertEquals(1, server.hits.get(), "过期前不应重复下载")

            assertTrue(service.syncIndex(force = true), "强制同步应成功")
            assertEquals(2, server.hits.get(), "force=true 必须重新下载")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `阈值为 0 时每次都重新同步`() {
        val server = StubServer(mapOf("/Filetree.json" to nestedFiletree))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json", thresholdDays = 0)
            val (service, _) = newService(props)
            service.syncIndex()
            service.syncIndex()
            assertEquals(2, server.hits.get(), "阈值 <= 0 表示每次都同步")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `首次同步下载失败抛异常`() {
        val props = newProps("http://127.0.0.1:1/Filetree.json").apply { scraper.timeoutMs = 500 }
        val (service, _) = newService(props)
        assertThrows<IllegalStateException>("无可用索引时首次同步失败必须让调用方感知") {
            service.syncIndex()
        }
    }

    @Test
    fun `已有索引时下载失败保留旧索引并返回 false`() {
        val server = StubServer(mapOf("/Filetree.json" to nestedFiletree))
        val props = newProps("${server.baseUrl}/Filetree.json")
        val (service, index) = newService(props)
        try {
            assertTrue(service.syncIndex())
            assertEquals(3L, index.count())
        } finally {
            server.stop()
        }
        // 服务已停：强制同步失败，但旧索引仍可用
        assertFalse(service.syncIndex(force = true), "下载失败应返回 false")
        assertEquals(3L, index.count(), "旧索引条数不变")
        assertNotNull(service.findPhoto("Rino"), "旧索引应继续可用")
    }

    @Test
    fun `解析失败返回 false 不抛异常`() {
        val server = StubServer(mapOf("/Filetree.json" to "这不是 JSON".toByteArray()))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, index) = newService(props)
            assertFalse(service.syncIndex(), "解析失败应返回 false")
            assertEquals(0L, index.count())
            assertNull(service.lastSyncAt(), "解析失败不应记录同步时间")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `兼容扁平格式 Filetree（键含路径 值为完整 URL）`() {
        val flat = """
            {
              "Content/someone/名前.jpg": "https://raw.githubusercontent.com/gfriends/gfriends/master/Content/someone/名前.jpg",
              "Content/someone/別名.png": "https://raw.githubusercontent.com/gfriends/gfriends/master/Content/someone/別名.png"
            }
        """.trimIndent().toByteArray()
        val server = StubServer(mapOf("/Filetree.json" to flat))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, index) = newService(props)
            assertTrue(service.syncIndex())
            assertEquals(2L, index.count())
            assertEquals(
                "https://raw.githubusercontent.com/gfriends/gfriends/master/Content/someone/名前.jpg",
                service.findPhoto("名前"),
                "完整 URL 应原样入库",
            )
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------ 查询

    @Test
    fun `findPhoto 别名兜底 大小写敏感 支持 png`() {
        val server = StubServer(mapOf("/Filetree.json" to nestedFiletree))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, _) = newService(props)
            service.syncIndex()

            assertNull(service.findPhoto("RINO"), "精确匹配且大小写敏感，RINO 不应命中 Rino.jpg")
            assertNotNull(service.findPhoto("Rino"), "Rino.jpg 应命中")
            assertEquals(
                "${server.baseUrl}/Content/z-テスト/るり.png",
                decode(service.findPhoto("るり")!!),
                "png 条目也应命中",
            )
            // 中文名未命中 → 别名（日文名）兜底
            val byAlias = service.findPhoto("梦乃爱华", listOf("夢乃あいか"))
            assertNotNull(byAlias, "应按别名命中")
            assertEquals("${server.baseUrl}/Content/z-テスト/AI-Fix-夢乃あいか.jpg", decode(byAlias!!))
            assertNull(service.findPhoto("查无此人"), "全部未命中返回 null")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `findPhoto 主名优先于别名`() {
        val payload = """
            {
              "Content": {
                "z-a": { "本名.jpg": "本名.jpg" },
                "z-b": { "别名.jpg": "别名.jpg" }
              }
            }
        """.trimIndent().toByteArray()
        val server = StubServer(mapOf("/Filetree.json" to payload))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, _) = newService(props)
            service.syncIndex()
            val hit = service.findPhoto("本名", listOf("别名"))
            assertEquals("${server.baseUrl}/Content/z-a/本名.jpg", decode(hit!!), "主名命中时不应回退别名")
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------ 下载

    @Test
    fun `downloadPhoto 下载有效图片并落盘`() {
        val jpeg = makeJpeg()
        val server = StubServer(mapOf("/a.jpg" to jpeg))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, _) = newService(props)
            val savePath = Files.createTempDirectory("sinjav-actors").resolve("actors/夢乃あいか.jpg")

            assertTrue(service.downloadPhoto("${server.baseUrl}/a.jpg", savePath), "有效 JPEG 应下载成功")
            assertTrue(Files.isRegularFile(savePath), "文件应已落盘（父目录自动创建）")
            assertEquals(jpeg.toList(), Files.readAllBytes(savePath).toList(), "内容应与响应一致")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `downloadPhoto 非图片内容校验失败并删除文件`() {
        val server = StubServer(mapOf("/bad.jpg" to "<html>404</html>".toByteArray()))
        try {
            val props = newProps("${server.baseUrl}/Filetree.json")
            val (service, _) = newService(props)
            val dir: Path = Files.createTempDirectory("sinjav-actors-bad")
            val savePath = dir.resolve("bad.jpg")

            assertFalse(service.downloadPhoto("${server.baseUrl}/bad.jpg", savePath), "非图片应校验失败")
            assertFalse(Files.exists(savePath), "校验失败的文件应被删除")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `downloadPhoto 网络失败返回 false 不抛异常`() {
        val props = newProps("http://127.0.0.1:1/Filetree.json").apply { scraper.timeoutMs = 500 }
        val (service, _) = newService(props)
        val savePath = Files.createTempDirectory("sinjav-actors-net").resolve("x.jpg")
        assertFalse(service.downloadPhoto("http://127.0.0.1:1/a.jpg", savePath))
        assertFalse(Files.exists(savePath))
    }
}
