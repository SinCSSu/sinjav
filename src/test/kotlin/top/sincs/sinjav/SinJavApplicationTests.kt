package top.sincs.sinjav

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 基于 SQLite（零配置）的完整上下文集成测试：
 * 验证 API 契约与核心链路（扫描识别 → 任务跟踪 → 复制整理 → NFO 生成）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
class SinJavApplicationTests {

    companion object {
        // SQLite 测试库放在 build/ 下（连接池缓存持有文件锁，@TempDir 会在上下文关闭前删除而失败）
        private val dbFile: Path = Path.of("build", "sinjav-test.db")

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

    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

    private fun getMap(url: String): Map<*, *> =
        rest.getForEntity(url, Map::class.java).body ?: mapOf<String, Any>()

    private fun awaitJob(jobId: Number): Map<*, *> {
        await().atMost(30, TimeUnit.SECONDS).until {
            val status = getMap("/api/v1/jobs/$jobId")["status"]
            status == "completed" || status == "failed"
        }
        return getMap("/api/v1/jobs/$jobId")
    }

    @Test
    fun `设置接口可用`() {
        val resp = rest.getForEntity("/api/v1/settings", Map::class.java)
        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("javbus, javdb", resp.body?.get("sourceOrder"))
    }

    @Test
    fun `设置更新与持久化`(@TempDir tmp: Path) {
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        val body = mapOf("namingTemplate" to "{number}/{date} - {title}", "targetDir" to tmp.toString())
        val resp = rest.exchange("/api/v1/settings", org.springframework.http.HttpMethod.PUT,
            org.springframework.http.HttpEntity(body, headers), Map::class.java)
        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("{number}/{date} - {title}", resp.body?.get("namingTemplate"))
    }

    @Test
    fun `端到端-扫描识别并复制整理`(@TempDir sourceDir: Path, @TempDir targetDir: Path) {
        // 构造 >50MB 的测试视频文件（触发 min-file-size 过滤），以及一个会被过滤的小文件
        val video = sourceDir.resolve("ABP-123.mp4")
        java.io.RandomAccessFile(video.toFile(), "rw").use { it.setLength(51L * 1024 * 1024) }
        Files.writeString(sourceDir.resolve("no_number_small.txt"), "skip me")

        // 触发扫描
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        val scanResp = rest.postForEntity(
            "/api/v1/scans",
            org.springframework.http.HttpEntity(mapOf("sourceDirs" to listOf(sourceDir.toString())), headers),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, scanResp.statusCode, "scan 响应: ${scanResp.body}")
        val jobId = (scanResp.body?.get("jobId") as Number).toLong()
        assertEquals("scan", scanResp.body?.get("type"))

        val job = awaitJob(jobId)
        assertEquals("completed", job["status"])
        assertEquals(1, (job["totalItems"] as Number).toInt())

        // 识别结果
        val records = rest.getForEntity("/api/v1/file-records?number=ABP-123", Map::class.java).body!!
        assertEquals(1L, (records["total"] as Number).toLong())
        val record = (records["items"] as List<Map<*, *>>).first()
        assertEquals("recognized", record["recognizeStatus"])
        assertEquals("ABP-123", record["number"])

        // 影片记录创建（未刮削 → pending）
        val movies = rest.getForEntity("/api/v1/movies?keyword=ABP-123", Map::class.java).body!!
        assertEquals(1L, (movies["total"] as Number).toLong())

        // 直接入库一条刮削结果以驱动整理（刮削依赖外网，链路单测不覆盖）
        insertFakeScrape("ABP-123")

        // 复制整理（不下载图片，避免外网依赖）
        val orgResp = rest.postForEntity(
            "/api/v1/organize",
            org.springframework.http.HttpEntity(
                mapOf(
                    "mode" to "copy", "targetDir" to targetDir.toString(),
                    "namingTemplate" to "{number}/{number} - {title}",
                    "generateNfo" to true, "downloadImages" to false,
                ),
                headers,
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, orgResp.statusCode)
        val orgJobId = (orgResp.body?.get("jobId") as Number).toLong()
        val orgJob = awaitJob(orgJobId)
        assertEquals("completed", orgJob["status"])
        assertEquals(0, (orgJob["failItems"] as Number).toInt())

        // 目标目录结构：ABP-123/{文件} + movie.nfo
        val subDir = targetDir.resolve("ABP-123")
        assertTrue(Files.exists(subDir.resolve("movie.nfo")))
        val placed = Files.list(subDir).filter { it.fileName.toString().endsWith(".mp4") }.findFirst()
        assertNotNull(placed, "整理后的视频文件应存在")
        assertTrue(Files.size(placed.get()) == 51L * 1024 * 1024)

        // 源文件保持不动（零侵入）
        assertTrue(Files.exists(video))

        // 文件记录状态更新
        val after = (rest.getForEntity("/api/v1/file-records?number=ABP-123", Map::class.java).body!!["items"] as List<Map<*, *>>).first()
        assertEquals("organized", after["organizeStatus"])
        assertEquals("copy", after["linkMode"])
        assertNotNull(after["targetPath"])

        // 影片详情含文件记录
        val detail = rest.getForEntity("/api/v1/movies/ABP-123", Map::class.java)
        assertEquals(HttpStatus.OK, detail.statusCode)
        assertTrue(((detail.body?.get("fileRecords") as List<*>)).isNotEmpty())

        // 任务列表
        val jobList = rest.getForEntity("/api/v1/jobs?type=organize", Map::class.java).body!!
        assertTrue((jobList["total"] as Number).toLong() >= 1)
    }

    private fun insertFakeScrape(number: String) {
        // 通过应用内 jdbcTemplate 直接写入，模拟刮削结果
        jdbc.update(
            """
            UPDATE movies SET title = 'Fake Title', release_date = :rd, maker = 'M',
                status = 'scraped', cover_url = '', scraped_at = :now, updated_at = :now
            WHERE number = :n
            """,
            mapOf(
                "n" to number, "rd" to java.sql.Date.valueOf("2024-05-01"),
                "now" to java.sql.Timestamp(System.currentTimeMillis()),
            ),
        )
    }
}
