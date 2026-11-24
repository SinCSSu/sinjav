package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.MovieRepository
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** 刮削成功后下载封面/剧照到本地目录（封面候选重试 + valid_pic 校验 + fanart 原图）。 */
class ImageStoreTest {

    /** 生成真实可解码 JPEG（ImageIO 编码，纯色 w×h） */
    private fun makeJpeg(w: Int = 100, h: Int = 100): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    /** ImageStore 依赖 MovieRepository 回写封面来源：内存 SQLite + 最小 movies 表 */
    private fun newStore(props: AppProperties): Pair<ImageStore, MovieRepository> {
        // 内存库随连接独立，改用临时文件库保证跨连接可见
        val dbFile = Files.createTempFile("sinjav-imgs-test", ".sqlite")
        val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        val ds = SimpleDriverDataSource(driver, "jdbc:sqlite:$dbFile")
        JdbcTemplate(ds).execute(
            """
            CREATE TABLE movies (
                id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT UNIQUE NOT NULL, raw_number TEXT, title TEXT,
                title_zh TEXT, title_orig TEXT, release_date TEXT, director TEXT, maker TEXT, label TEXT,
                series TEXT, duration_min INTEGER, cover_url TEXT, preview_urls TEXT,
                cover_source TEXT, preview_source TEXT, cover_candidates TEXT, preview_candidates TEXT,
                plot TEXT, source TEXT,
                status TEXT, fail_reason TEXT, scraped_at TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP, updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        val repo = MovieRepository(NamedParameterJdbcTemplate(ds))
        return ImageStore(HttpClientFactory(props), props, CoverCropper(), repo) to repo
    }

    private fun newProps(dirPrefix: String): AppProperties = AppProperties().apply {
        scraper.requestIntervalMs = 0
        scraper.timeoutMs = 3000
        images.download = true
        images.dir = Files.createTempDirectory(dirPrefix).toString()
    }

    @Test
    fun `下载封面与剧照到本地目录（含 fanart 原图）`() {
        val server = startServer(mapOf("/a.jpg" to makeJpeg(), "/1.jpg" to makeJpeg(), "/2.jpg" to makeJpeg()))
        try {
            val props = newProps("sinjav-imgs")
            val (store, _) = newStore(props)
            val base = "http://127.0.0.1:${server.address.port}"
            store.downloadAll("ABP-123", coverUrl = "$base/a.jpg", previewUrls = listOf("$base/1.jpg", "$base/2.jpg"))

            val dir = java.nio.file.Path.of(props.images.dir).resolve("ABP-123")
            assertTrue(Files.exists(dir.resolve("poster.jpg")), "poster.jpg 应写入")
            assertTrue(Files.exists(dir.resolve("fanart.jpg")), "fanart.jpg 原图应写入")
            assertEquals(makeJpeg().toList(), Files.readAllBytes(dir.resolve("preview1.jpg")).toList())
            assertEquals(makeJpeg().toList(), Files.readAllBytes(dir.resolve("preview2.jpg")).toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `封面下载失败自动换下一候选并回写实际来源`() {
        val server = startServer(mapOf("/good.jpg" to makeJpeg()))
        try {
            val props = newProps("sinjav-imgs-fallback")
            val (store, repo) = newStore(props)
            val base = "http://127.0.0.1:${server.address.port}"
            repo.ensurePending("ABP-123", "ABP-123")
            // 首候选（javbus）不可达 → 回退第二候选（javdb，测试桩演示用）
            val candidates = listOf(
                CoverCandidate("http://127.0.0.1:1/dead.jpg", "javbus"),
                CoverCandidate("$base/good.jpg", "javdb"),
            )
            store.downloadAll("ABP-123", coverUrl = candidates[0].url, previewUrls = emptyList(), coverCandidates = candidates)

            val dir = java.nio.file.Path.of(props.images.dir).resolve("ABP-123")
            assertTrue(Files.exists(dir.resolve("poster.jpg")), "应回退到第二候选写出 poster.jpg")
            assertTrue(Files.exists(dir.resolve("fanart.jpg")), "应回退到第二候选写出 fanart.jpg")
            assertEquals("javdb", repo.findByNumber("ABP-123")?.coverSource, "实际生效来源应回写")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `无效图片（HTML 错误页）跳过换下一候选`() {
        val html = "<html>error page</html>".toByteArray()
        val server = startServer(mapOf("/bad.jpg" to html, "/good.jpg" to makeJpeg()))
        try {
            val props = newProps("sinjav-imgs-valid")
            val (store, _) = newStore(props)
            val base = "http://127.0.0.1:${server.address.port}"
            val candidates = listOf(
                CoverCandidate("$base/bad.jpg", "javbus"),
                CoverCandidate("$base/good.jpg", "missav"),
            )
            store.downloadCoverOriginal("ABP-123", candidates[0].url, null, candidates) ?: run { assertTrue(false, "应命中第二候选") }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `全部候选失败返回 null 不抛异常`() {
        val props = newProps("sinjav-imgs-allfail")
        val (store, _) = newStore(props)
        val candidates = listOf(
            CoverCandidate("http://127.0.0.1:1/a.jpg", "javbus"),
            CoverCandidate("http://127.0.0.1:1/b.jpg", "javdb"),
        )
        val result = store.downloadCoverOriginal("ABP-123", candidates[0].url, null, candidates)
        assertTrue(result == null, "全部候选失败应返回 null")
    }

    @Test
    fun `开关关闭时不下载`() {
        val props = AppProperties().apply {
            images.download = false
            images.dir = Files.createTempDirectory("sinjav-imgs-off").toString()
        }
        val (store, _) = newStore(props)
        store.downloadAll("ABP-123", coverUrl = "http://127.0.0.1:1/a.jpg", previewUrls = emptyList())
        assertFalse(Files.exists(java.nio.file.Path.of(props.images.dir).resolve("ABP-123")))
    }

    @Test
    fun `下载失败不影响刮削流程（仅记录警告）`() {
        val props = newProps("sinjav-imgs-fail").apply { scraper.timeoutMs = 500 }
        val (store, _) = newStore(props)
        // 无人监听的端口 → 下载失败，但不应抛异常
        store.downloadAll("ABP-123", coverUrl = "http://127.0.0.1:1/a.jpg", previewUrls = listOf("http://127.0.0.1:1/b.jpg"))
        val dir = java.nio.file.Path.of(props.images.dir).resolve("ABP-123")
        assertTrue(Files.isDirectory(dir), "目录应已创建")
        assertTrue(
            Files.list(dir).use { it.count() } == 0L,
            "失败的文件不应写入",
        )
    }

    @Test
    fun `剧照按逐张来源路由（不被单一 previewSource 带错出口）`() {
        val server = startServer(mapOf("/1.jpg" to makeJpeg()))
        try {
            val props = newProps("sinjav-imgs-preview-route").apply {
                // javdb 被配置成过盾源，过盾代理指向无人监听的端口 → 走该通道必失败
                scraper.cfProxyUrl = "http://127.0.0.1:1"
                scraper.cfProxySources = "javdb"
                scraper.cfTimeoutMs = 300
            }
            val (store, _) = newStore(props)
            val url = "http://127.0.0.1:${server.address.port}/1.jpg"
            val dir = java.nio.file.Path.of(props.images.dir).resolve("ABP-123")

            // 只给整体来源：按 javdb（过盾）路由 → 失败，不落盘
            store.downloadAll("ABP-123", coverUrl = null, previewUrls = listOf(url), previewSource = "javdb")
            assertFalse(Files.exists(dir.resolve("preview1.jpg")), "单一 previewSource=javdb 时应下载失败")

            // 逐张来源为 javbus（直连）→ 应成功落盘
            store.downloadAll(
                "ABP-123", coverUrl = null, previewUrls = listOf(url),
                previewSource = "javdb", previewCandidates = listOf(CoverCandidate(url, "javbus")),
            )
            assertTrue(Files.exists(dir.resolve("preview1.jpg")), "逐张来源应覆盖整体来源并下载成功")
            assertEquals(makeJpeg().toList(), Files.readAllBytes(dir.resolve("preview1.jpg")).toList())
        } finally {
            server.stop(0)
        }
    }

    private fun startServer(responses: Map<String, ByteArray>): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = responses[ex.requestURI.path] ?: ByteArray(0)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }
}
