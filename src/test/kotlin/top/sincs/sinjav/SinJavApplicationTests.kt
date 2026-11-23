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

    private val authCookie = java.util.concurrent.atomic.AtomicReference<String>()

    /** 安全改造后所有 /api/v1 需登录：每个测试前确保已初始化凭据，并给底层 RestTemplate 挂会话 Cookie 拦截器 */
    @org.junit.jupiter.api.BeforeEach
    fun authSetup() {
        if (authCookie.get() != null) return
        val status = rest.getForEntity("/api/v1/auth/status", Map::class.java).body ?: emptyMap<Any, Any>()
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        val creds = mapOf("username" to "admin", "password" to "secret66")
        val resp = if (status["initialized"] == true) {
            rest.postForEntity("/api/v1/auth/login", org.springframework.http.HttpEntity(creds, headers), String::class.java)
        } else {
            rest.postForEntity("/api/v1/auth/init", org.springframework.http.HttpEntity(creds, headers), String::class.java)
        }
        authCookie.set(
            resp.headers[org.springframework.http.HttpHeaders.SET_COOKIE]
                ?.joinToString("; ") { it.split(";").first() },
        )
        rest.restTemplate.interceptors.add(
            org.springframework.http.client.ClientHttpRequestInterceptor { request, body, execution ->
                authCookie.get()?.let { request.headers.add(org.springframework.http.HttpHeaders.COOKIE, it) }
                execution.execute(request, body)
            },
        )
    }

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
        assertEquals("javbus, javdb, missav", resp.body?.get("sourceOrder"))
        // 扫描全局配置（供扫描弹窗高级选项回填默认值）
        assertEquals("mp4, mkv, avi, wmv, iso, rmvb, ts, m2ts, mov, flv", resp.body?.get("videoExtensions"))
        assertEquals(50L, ((resp.body?.get("minFileSizeMb") as Number).toLong()))
    }

    @Test
    fun `设置更新与持久化`(@TempDir tmp: Path) {
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        val body = mapOf("namingTemplate" to "{number}/{date} - {title}")
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

    @Test
    fun `扫描结果落库端点`() {
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        val items = listOf(
            mapOf(
                "sourcePath" to "C:/videos/MIDV-999.mp4", "fileName" to "MIDV-999.mp4",
                "fileSize" to 1024L, "fileMtime" to "2024-01-01T00:00:00Z",
            ),
            mapOf(
                "sourcePath" to "C:/videos/test.mp4", "fileName" to "test.mp4",
                "fileSize" to 2048L, "fileMtime" to null,
            ),
        )
        val resp = rest.postForEntity(
            "/api/v1/scans/commit",
            org.springframework.http.HttpEntity(mapOf("items" to items), headers),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, resp.statusCode, "commit 响应: ${resp.body}")
        assertEquals(2L, (resp.body?.get("total") as Number).toLong())
        assertEquals(1L, (resp.body?.get("recognized") as Number).toLong())
        assertEquals(1L, (resp.body?.get("unrecognized") as Number).toLong())

        // 文件记录与影片记录均落库
        val records = rest.getForEntity("/api/v1/file-records?number=MIDV-999", Map::class.java).body!!
        assertEquals(1L, (records["total"] as Number).toLong())
        val record = (records["items"] as List<Map<*, *>>).first()
        assertEquals("recognized", record["recognizeStatus"])
        val movies = rest.getForEntity("/api/v1/movies?keyword=MIDV-999", Map::class.java).body!!
        assertEquals(1L, (movies["total"] as Number).toLong())
        assertEquals("pending", (movies["items"] as List<Map<*, *>>).first()["status"])

        // 重复提交幂等（同指纹不重复）
        val resp2 = rest.postForEntity(
            "/api/v1/scans/commit",
            org.springframework.http.HttpEntity(mapOf("items" to items), headers),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, resp2.statusCode)
        val records2 = rest.getForEntity("/api/v1/file-records?number=MIDV-999", Map::class.java).body!!
        assertEquals(1L, (records2["total"] as Number).toLong())

        // 清理本测试写入的数据，避免污染其他测试（共享 SQLite 库）
        jdbc.update("DELETE FROM file_records WHERE source_path LIKE 'C:/videos/%'", emptyMap<String, Any>())
        jdbc.update("DELETE FROM movies WHERE number = 'MIDV-999'", emptyMap<String, Any>())
    }

    @Test
    fun `整理预览与重整`(@TempDir sourceDir: Path, @TempDir targetDir: Path, @TempDir targetDir2: Path) {
        val video = sourceDir.resolve("ABP-777.mp4")
        java.io.RandomAccessFile(video.toFile(), "rw").use { it.setLength(51L * 1024 * 1024) }
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }

        // 扫描识别
        val scanResp = rest.postForEntity(
            "/api/v1/scans",
            org.springframework.http.HttpEntity(mapOf("sourceDirs" to listOf(sourceDir.toString())), headers),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, scanResp.statusCode)
        awaitJob((scanResp.body?.get("jobId") as Number).toLong())
        val record = (rest.getForEntity("/api/v1/file-records?number=ABP-777", Map::class.java).body!!["items"]
            as List<Map<*, *>>).first()
        val recordId = (record["id"] as Number).toLong()
        insertFakeScrape("ABP-777")

        // 整理弹窗列表接口（pending）
        val pendingList = rest.getForEntity("/api/v1/organize/records?group=pending&keyword=ABP-777", Map::class.java).body!!
        assertEquals(1L, (pendingList["total"] as Number).toLong())
        assertEquals("Fake Title", (pendingList["items"] as List<Map<*, *>>).first()["title"])

        // 复制整理
        val orgResp = rest.postForEntity(
            "/api/v1/organize",
            org.springframework.http.HttpEntity(
                mapOf("mode" to "copy", "targetDir" to targetDir.toString(), "namingTemplate" to "{number}/{number} - {title}"),
                headers,
            ),
            Map::class.java,
        )
        val orgJob = awaitJob((orgResp.body?.get("jobId") as Number).toLong())
        assertEquals("completed", orgJob["status"])

        // 已整理分组出现该记录（带标题与原目标路径）
        val organizedList = rest.getForEntity("/api/v1/organize/records?group=organized&keyword=ABP-777", Map::class.java).body!!
        assertEquals(1L, (organizedList["total"] as Number).toLong())
        val row = (organizedList["items"] as List<Map<*, *>>).first()
        assertEquals("organized", row["organizeStatus"])
        assertEquals("copy", row["linkMode"])
        assertNotNull(row["targetPath"])

        // 预览：已整理记录标记重整，并按参数计算出新目标路径
        val preview = rest.postForEntity(
            "/api/v1/organize/preview",
            org.springframework.http.HttpEntity(
                mapOf("recordIds" to listOf(recordId), "targetDir" to targetDir2.toString(), "mode" to "copy", "namingTemplate" to "{number}/{number} - {title}"),
                headers,
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, preview.statusCode, "preview 响应: ${preview.body}")
        val item = (preview.body?.get("items") as List<Map<*, *>>).first()
        assertEquals(true, item["reorganize"])
        assertEquals("copy", item["currentLinkMode"])
        assertNotNull(item["targetPath"])

        // 重整：覆盖目标路径，旧整理结果应被删除
        val override = targetDir2.resolve("custom").resolve("renamed.mp4").toString()
        val reorgResp = rest.postForEntity(
            "/api/v1/organize",
            org.springframework.http.HttpEntity(
                mapOf("targetDir" to targetDir2.toString(), "mode" to "copy", "items" to listOf(mapOf("recordId" to recordId, "targetPath" to override))),
                headers,
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, reorgResp.statusCode)
        val reorgJob = awaitJob((reorgResp.body?.get("jobId") as Number).toLong())
        assertEquals("completed", reorgJob["status"], "reorg job: ${reorgJob["errors"]}")
        assertEquals(0, (reorgJob["failItems"] as Number).toInt())

        // 新目标存在且生成 nfo；旧整理目录整体清理
        assertTrue(Files.exists(Path.of(override)), "重整后的文件应存在")
        assertTrue(Files.exists(Path.of(override).parent.resolve("movie.nfo")), "重整后应生成 movie.nfo")
        assertTrue(Files.notExists(targetDir.resolve("ABP-777")), "旧整理目录应被清理")

        // 记录指向新目标
        val after = (rest.getForEntity("/api/v1/file-records?number=ABP-777", Map::class.java).body!!["items"]
            as List<Map<*, *>>).first()
        assertEquals("organized", after["organizeStatus"])
        assertEquals(override, after["targetPath"])

        // 清理，避免污染其他测试（共享 SQLite 库）
        jdbc.update("DELETE FROM file_records WHERE number = 'ABP-777'", emptyMap<String, Any>())
        jdbc.update("DELETE FROM movies WHERE number = 'ABP-777'", emptyMap<String, Any>())
    }

    @Test
    fun `删除影片记录`() {
        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        // 通过扫描落库创建影片 + 文件记录
        val resp = rest.postForEntity(
            "/api/v1/scans/commit",
            org.springframework.http.HttpEntity(
                mapOf(
                    "items" to listOf(
                        mapOf(
                            "sourcePath" to "C:/del/TEST-901.mp4", "fileName" to "TEST-901.mp4",
                            "fileSize" to 1024L, "fileMtime" to null,
                        ),
                    ),
                ),
                headers,
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals(1L, (rest.getForEntity("/api/v1/movies?keyword=TEST-901", Map::class.java).body!!["total"] as Number).toLong())
        assertEquals(1L, (rest.getForEntity("/api/v1/file-records?number=TEST-901", Map::class.java).body!!["total"] as Number).toLong())

        // 删除
        val delResp = rest.exchange(
            "/api/v1/movies/TEST-901",
            org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(null, headers),
            Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, delResp.statusCode)
        assertEquals(0L, (rest.getForEntity("/api/v1/movies?keyword=TEST-901", Map::class.java).body!!["total"] as Number).toLong())
        assertEquals(0L, (rest.getForEntity("/api/v1/file-records?number=TEST-901", Map::class.java).body!!["total"] as Number).toLong())

        // 重复删除 → 404
        val delAgain = rest.exchange(
            "/api/v1/movies/TEST-901",
            org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(null, headers),
            Map::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, delAgain.statusCode)
    }

    /** 配置绑定：application.yaml 的 gfriends 段应正确读入 AppProperties（GfriendsService 构造日志可复核） */
    @Autowired
    lateinit var appProps: top.sincs.sinjav.config.AppProperties

    @Test
    fun `Gfriends 配置从 application yaml 正确绑定`() {
        assertEquals(
            "https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json",
            appProps.gfriends.indexUrl,
        )
        assertEquals(7, appProps.gfriends.syncThresholdDays)
        assertTrue(appProps.gfriends.enableProxy)
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
