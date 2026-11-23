package top.sincs.sinjav.actor

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import top.sincs.sinjav.actor.EmbyActorSyncService.SyncResult
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.Job
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.emby.EmbyStub
import top.sincs.sinjav.job.JobType
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.MovieMetadata
import top.sincs.sinjav.scraper.ScrapeParams
import top.sincs.sinjav.scraper.ScrapeService
import top.sincs.sinjav.scraper.ScraperChain
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 演员管理端到端集成测试（批次 8）：真实 Spring 上下文 + 真实 SQLite 库（Flyway 迁移）+ 真实业务链路，
 * 只把**网络边界**替换为替身：
 *
 * - [HttpClientFactory] → Mockito 替身：Gfriends 索引 JSON 与头像字节由测试提供（不访问 GitHub）；
 * - [ScraperChain] → Mockito 替身：直接返回构造好的 [MovieMetadata]（不访问站点）；
 * - Emby → 本地 [EmbyStub] HTTP 桩服务（真实 EmbyClient + 真实 HTTP 调用链，只是服务端在本地）；
 * - 翻译、封面图片下载关闭（`sinjav.scraper.auto-translate=false` / `sinjav.images.download=false`）。
 *
 * 覆盖四条关键流程：
 * 1. 批次刮削 → 演员落库 → 自动派发 `actor_photo_auto` 任务 → 头像落盘并回写 `photo_path`；
 * 2. Emby 演员同步 → `emby_person_id` 回写 → 新演员触发头像补全 → 任务 completed + 统计日志；
 * 3. 别名归一化：多种写法收敛到规范名（且不会重复建号）；
 * 4. API 端到端：`GET /api/v1/actors?search=...` 返回分页 JSON，未登录 401 / 不存在 404。
 *
 * 数据隔离：每个用例前等待上一轮异步任务收尾，再清空业务表（`settings` 除外——登录凭据存那里）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension::class)
class ActorManagementIntegrationTest {

    companion object {
        /** SQLite 库文件放 build/ 下（连接池持有文件锁，@TempDir 会在上下文关闭前删除而失败） */
        private val dbFile: Path = Path.of("build", "sinjav-actor-it.db")

        /** 演员头像落盘目录（photo_path 的相对前缀取末级目录名，即 `actor-it-photos`） */
        private val photoDir: Path = Path.of("build", "actor-it-photos")

        /** 假 Filetree.json（现行嵌套格式：Content/{上传者}/{演员名}.jpg → 仓库内文件名） */
        private val FILETREE_JSON = """
            {
              "Information": {"Total": "2"},
              "Content": {
                "0-uploader": {
                  "夢乃あいか.jpg": "夢乃あいか.jpg",
                  "三上悠亜.jpg": "AI-Fix-三上悠亜.jpg"
                }
              }
            }
        """.trimIndent()

        /** 测试头像字节：真实可解码的 PNG（GfriendsService 落盘后会走 valid_pic 校验） */
        private val TEST_PHOTO: ByteArray = run {
            val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
            java.io.ByteArrayOutputStream().use { out ->
                javax.imageio.ImageIO.write(image, "png", out)
                out.toByteArray()
            }
        }

        /** Emby 桩服务：只预置用户列表，条目 People 由各用例 route 覆盖 */
        private val embyStub = EmbyStub(
            mapOf("/emby/Users" to """[{"Id": "u1", "Name": "admin", "IsAdministrator": true}]"""),
        )

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Files.deleteIfExists(dbFile)
            deleteRecursively(photoDir)
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbFile.toAbsolutePath()}" }
            registry.add("spring.datasource.hikari.data-source-properties.date_string_format") { "yyyy-MM-dd HH:mm:ss" }
            // Emby 指向本地桩服务（EmbyClient 拼接 /emby 前缀，与桩路由一致）
            registry.add("sinjav.emby.base-url") { embyStub.baseUrl }
            registry.add("sinjav.emby.api-key") { "test-key" }
            registry.add("sinjav.actors.photo-dir") { photoDir.toAbsolutePath().toString() }
            // 批量补全逐个间隔限速：测试里置 0，避免每个演员等 1.5s
            registry.add("sinjav.actors.scrape-interval-ms") { "0" }
            registry.add("sinjav.images.download") { "false" }
            registry.add("sinjav.scraper.auto-translate") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun stopEmbyStub() = embyStub.stop()

        private fun deleteRecursively(dir: Path) {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    // ---------------------------------------------------------------- 网络边界替身

    @MockitoBean
    lateinit var http: HttpClientFactory

    @MockitoBean
    lateinit var scraperChain: ScraperChain

    // ---------------------------------------------------------------- 真实 Bean

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    lateinit var props: AppProperties

    @Autowired
    lateinit var actressRepository: ActressRepository

    @Autowired
    lateinit var actressService: ActressService

    @Autowired
    lateinit var movieRepository: MovieRepository

    @Autowired
    lateinit var jobRepository: JobRepository

    @Autowired
    lateinit var scrapeService: ScrapeService

    @Autowired
    lateinit var embyActorSyncService: EmbyActorSyncService

    @Autowired
    lateinit var rest: TestRestTemplate

    private val authCookie = AtomicReference<String>()

    @BeforeEach
    fun resetState() {
        // 上一用例派发的异步任务（头像补全）可能还在跑：先等它收尾，再清表，避免任务把行写回来
        awaitIdle()
        listOf(
            "actress_aliases", "movie_actresses", "movie_genres", "movies", "actresses", "genres",
            "jobs", "gfriends_index", "app_metadata", "emby_items", "file_records",
        ).forEach { jdbc.update("DELETE FROM $it", emptyMap<String, Any>()) }

        Mockito.reset(http, scraperChain)
        embyStub.failStatus.clear()
        synchronized(embyStub.requests) { embyStub.requests.clear() }
        stubNetwork()
    }

    /**
     * 网络替身：索引下载返回 Filetree.json，头像下载返回合法 PNG 字节（valid_pic 可通过）。
     *
     * 注意：参数匹配器一律用 `anyString()` / `any()`——`Mockito.eq()` 与 `any()` 返回 null，
     * 传给 Kotlin 的非空参数会触发编译器插入的空检查（NPE），这是 Mockito + Kotlin 的经典坑。
     */
    private fun stubNetwork() {
        Mockito.`when`(
            http.get(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()),
        ).thenReturn(FILETREE_JSON)
        Mockito.`when`(
            http.download(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()),
        ).thenReturn(TEST_PHOTO)
    }

    // ---------------------------------------------------------------- 1. 完整刮削流程

    @Test
    fun `批次刮削新建演员并自动补全头像`() {
        // 准备：一条待刮削影片 + 假刮削结果（含两位新演员）
        movieRepository.ensurePending("ABP-123", "ABP-123")
        Mockito.`when`(scraperChain.scrapeWithSource(Mockito.anyString(), Mockito.any())).thenReturn(
            "javbus" to MovieMetadata(
                number = "ABP-123",
                title = "テスト作品",
                releaseDate = LocalDate.of(2024, 5, 1),
                actresses = listOf("夢乃あいか", "三上悠亜"),
                genres = listOf("ドラマ"),
            ),
        )
        val scrapeJob = jobRepository.create(JobType.SCRAPE.code, null, 0)

        // 执行：真实 ScrapeService.execute（落库 → 收尾派发头像补全）
        scrapeService.execute(scrapeJob.id, ScrapeParams(numbers = listOf("ABP-123")))

        // 1) 演员与影片关联落库，刮削任务不受补全影响
        assertEquals("completed", jobRepository.findById(scrapeJob.id)!!.status)
        assertEquals(
            listOf("夢乃あいか", "三上悠亜"),
            jdbc.queryForList("SELECT name FROM actresses ORDER BY id", emptyMap<String, Any>(), String::class.java),
        )
        val movieId = movieRepository.findByNumber("ABP-123")!!.id
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM movie_actresses WHERE movie_id = :id", mapOf("id" to movieId), Long::class.java,
        ))

        // 2) 自动创建 actor_photo_auto 任务（参数含触发原因）
        val photoJob = photoJobs().single()
        assertTrue(
            photoJob.params.orEmpty().contains("批次刮削自动补全"),
            "任务参数应记录触发原因: ${photoJob.params}",
        )

        // 3) 任务完成后演员有 photo_path，且头像文件真实落盘
        awaitJobStatus(photoJob.id, "completed")
        val photoPath = actressRepository.findByName("夢乃あいか")?.photoPath
        assertNotNull(photoPath, "补全完成后应回写 photo_path")
        assertTrue(photoPath!!.endsWith("/夢乃あいか.jpg"), "文件名应为规范名: $photoPath")
        assertTrue(
            Files.isRegularFile(photoDir.resolve(photoPath.substringAfterLast('/'))),
            "头像文件应落在 sinjav.actors.photo-dir: $photoPath",
        )
        assertEquals(0, jobRepository.findById(photoJob.id)!!.failItems)
    }

    @Test
    fun `关闭自动补全开关时只落演员不建头像任务`() {
        props.actors.autoScrapeAfterBatch = false
        try {
            movieRepository.ensurePending("ABP-200", "ABP-200")
            Mockito.`when`(scraperChain.scrapeWithSource(Mockito.anyString(), Mockito.any())).thenReturn(
                "javbus" to MovieMetadata(number = "ABP-200", title = "开关关闭", actresses = listOf("新人女優")),
            )
            val job = jobRepository.create(JobType.SCRAPE.code, null, 0)

            scrapeService.execute(job.id, ScrapeParams(numbers = listOf("ABP-200")))

            assertEquals("completed", jobRepository.findById(job.id)!!.status)
            assertNotNull(actressRepository.findByName("新人女優"), "演员仍正常入库")
            assertTrue(photoJobs().isEmpty(), "开关关闭时不应创建头像任务")
        } finally {
            props.actors.autoScrapeAfterBatch = true
        }
    }

    // ---------------------------------------------------------------- 2. Emby 同步流程

    @Test
    fun `Emby 演员同步回写 person id 并补全新演员头像`(output: CapturedOutput) {
        embyStub.route(
            "/emby/Users/u1/Items/i1",
            """
            {"Id": "i1", "Name": "ABP-123 テスト", "People": [
              {"Id": "p1", "Name": "三上悠亜", "Role": "Actor", "Type": "Actor"},
              {"Id": "p2", "Name": "夢乃あいか", "Role": "Actor", "Type": "Actor"}
            ]}
            """.trimIndent(),
        )
        embyStub.route(
            "/emby/Users/u1/Items/i2",
            """
            {"Id": "i2", "Name": "SSIS-001 テスト", "People": [
              {"Id": "p1", "Name": "三上悠亜", "Role": "Actor", "Type": "Actor"},
              {"Id": "p3", "Name": "新人女優", "Role": "Actor", "Type": "Actor"}
            ]}
            """.trimIndent(),
        )
        insertEmbyItem("i1", "ABP-123")
        insertEmbyItem("i2", "SSIS-001")
        // 既有演员（本地已有）：同步时应「回写 Person Id」而不是新建
        actressRepository.insertIfAbsent("夢乃あいか")

        val job = jobRepository.create(JobType.EMBY_ACTOR_SYNC.code, null, 0)

        val result = embyActorSyncService.syncFromEmby(job.id)

        // 1) 统计与任务状态
        assertEquals(SyncResult.SUCCESS(new = 2, updated = 1), result)
        val finished = jobRepository.findById(job.id)!!
        assertEquals("completed", finished.status)
        assertEquals(2, finished.totalItems, "进度总数 = emby_items 条目数")
        assertEquals(2, finished.doneItems)
        assertEquals(0, finished.failItems)
        assertTrue(output.out.contains("新增演员=2"), "统计日志应含新增/更新数量: ${output.out.takeLast(400)}")

        // 2) emby_person_id 回写（同一 Person 跨条目只落库一次）
        assertEquals("p1", actressRepository.findByCanonicalName("三上悠亜")?.embyPersonId)
        assertEquals("p2", actressRepository.findByCanonicalName("夢乃あいか")?.embyPersonId)
        assertEquals(3, countActresses(), "三上悠亜/新人女優 新建，夢乃あいか 复用")

        // 3) 新演员自动触发头像补全：Gfriends 只有 三上悠亜 → 命中一个，另一个按未找到计失败
        val photoJob = photoJobs().single()
        awaitJobStatus(photoJob.id, "completed")
        val photoPath = actressRepository.findByCanonicalName("三上悠亜")?.photoPath
        assertNotNull(photoPath, "命中 Gfriends 的新演员应有头像: $photoPath")
        assertNull(actressRepository.findByCanonicalName("新人女優")?.photoPath, "Gfriends 无此演员时不应编造头像")
        assertEquals(1, jobRepository.findById(photoJob.id)!!.failItems)
        assertTrue(
            jobRepository.findById(photoJob.id)!!.errorDetail.orEmpty().contains("新人女優"),
            "失败明细应带演员名: ${jobRepository.findById(photoJob.id)!!.errorDetail}",
        )
        // 头像读取走真实 Gfriends 索引（Filetree.json 解析 → gfriends_index 表）
        assertTrue(gfriendsIndexCount() >= 2, "索引同步应把 Filetree.json 落库")
        assertEquals(1, embyStub.requestsTo("/emby/Users/u1/Items/i1").size, "每条目只拉取一次演员列表")
    }

    // ---------------------------------------------------------------- 3. 别名归一化

    @Test
    fun `别名归一化把中文与英文写法收敛到规范名`() {
        val id = actressRepository.insertIfAbsent("夢乃あいか")!!
        actressService.updateAliases(id, "夢乃あいか", listOf("梦乃爱华", "Aika Yumeno"))

        assertEquals("夢乃あいか", actressService.normalize("梦乃爱华"), "简体中文写法应归一")
        assertEquals("夢乃あいか", actressService.normalize(" Aika Yumeno "), "英文写法应归一（含首尾空白）")
        assertEquals("夢乃あいか", actressService.normalize("夢乃あいか"), "规范名本身保持不变")
        assertEquals("完全未知的名字", actressService.normalize("完全未知的名字"), "未登记的名字原样返回")

        // 归一化后再入库不会新建重复演员
        assertTrue(actressService.registerAll(listOf("梦乃爱华", "Aika Yumeno")).isEmpty())
        assertEquals(1, countActresses())
    }

    // ---------------------------------------------------------------- 4. API 端到端

    @Test
    fun `演员列表接口返回分页 JSON 且错误走统一异常处理`() {
        val aiId = actressRepository.insertIfAbsent("夢乃あいか")!!
        actressRepository.updatePhotoPath(aiId, "actor-it-photos/夢乃あいか.jpg")
        // 规范名 + 别名（列表 search 会同时匹配 name / alias / 别名表）
        actressService.updateAliases(aiId, "夢乃あいか", listOf("梦乃爱华"))
        actressRepository.insertIfAbsent("三上悠亜")
        login()

        // 搜索命中一位（日文关键字走 URI 变量编码）
        val resp = rest.getForEntity("/api/v1/actors?search={q}&page=1&size=10", Map::class.java, "あいか")
        assertEquals(HttpStatus.OK, resp.statusCode, "响应体: ${resp.body}")
        val body = resp.body!!
        assertEquals(1, (body["total"] as Number).toInt())
        assertEquals(1, (body["page"] as Number).toInt())
        assertEquals(10, (body["size"] as Number).toInt())
        assertEquals(1, (body["totalPages"] as Number).toInt())
        val item = (body["items"] as List<Map<String, Any?>>).single()
        assertEquals("夢乃あいか", item["name"])
        assertEquals("夢乃あいか", item["alias"])
        assertEquals("actor-it-photos/夢乃あいか.jpg", item["photoPath"])
        assertEquals(0, (item["movieCount"] as Number).toInt())

        // 不过滤时返回全部 + 分页信息
        val all = rest.getForEntity("/api/v1/actors?page=1&size=1", Map::class.java).body!!
        assertEquals(2, (all["total"] as Number).toInt())
        assertEquals(2, (all["totalPages"] as Number).toInt(), "2 条 / 每页 1 → 2 页")
        assertEquals(1, (all["items"] as List<*>).size)

        // 统一异常处理：演员不存在 → 404 ProblemDetail（而不是 500）
        val missing = rest.getForEntity("/api/v1/actors/999999", Map::class.java)
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
        assertEquals(404, (missing.body!!["status"] as Number).toInt())
        assertEquals("Not found", missing.body!!["title"])

        // 统一异常处理：非法分页参数类型 → 400（GlobalExceptionHandler.handleTypeMismatch）
        val badRequest = rest.getForEntity("/api/v1/actors?page=abc", Map::class.java)
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.statusCode)
        assertEquals(400, (badRequest.body!!["status"] as Number).toInt())

        // 统一异常处理：路径不存在 → 404（NoResourceFoundException，而不是兜底 500）
        val noPath = rest.getForEntity("/api/v1/actors/999999/unknown", Map::class.java)
        assertEquals(HttpStatus.NOT_FOUND, noPath.statusCode)
        assertEquals("Not found", noPath.body!!["title"])

        // 未登录访问 → 401（安全配置侧的 ProblemDetail 兼容格式）
        val anonymous = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("${rest.rootUri!!.trimEnd('/')}/api/v1/actors"),
            ).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, anonymous.statusCode())
        assertTrue(anonymous.body().contains("未登录"), "401 响应应为 ProblemDetail 兼容格式: ${anonymous.body()}")
    }

    // ---------------------------------------------------------------- 脚手架

    /** 演员数（actresses 表行数） */
    private fun countActresses(): Long =
        jdbc.queryForObject("SELECT count(*) FROM actresses", emptyMap<String, Any>(), Long::class.java) ?: 0L

    /** Gfriends 索引条目数（验证 Filetree.json 已解析入库） */
    private fun gfriendsIndexCount(): Long =
        jdbc.queryForObject("SELECT count(*) FROM gfriends_index", emptyMap<String, Any>(), Long::class.java) ?: 0L

    /** 头像自动补全任务（actor_photo_auto，按 id 升序） */
    private fun photoJobs(): List<Job> =
        jdbc.queryForList(
            "SELECT id FROM jobs WHERE type = :t ORDER BY id",
            mapOf("t" to JobType.ACTOR_PHOTO_AUTO.code), Long::class.java,
        ).filterNotNull().mapNotNull { jobRepository.findById(it) }

    private fun insertEmbyItem(itemId: String, number: String) {
        jdbc.update(
            "INSERT INTO emby_items (item_id, number, name, path) VALUES (:i, :n, :nm, NULL)",
            mapOf("i" to itemId, "n" to number, "nm" to "$number テスト"),
        )
    }

    /** 等所有异步任务退出 running（清理前置：避免上一用例的任务把数据写回来） */
    private fun awaitIdle() {
        await().atMost(30, TimeUnit.SECONDS).until {
            (jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status = 'running'", emptyMap<String, Any>(), Long::class.java,
            ) ?: 0L) == 0L
        }
    }

    private fun awaitJobStatus(jobId: Long, expected: String): Job {
        await().atMost(30, TimeUnit.SECONDS).until { jobRepository.findById(jobId)?.status == expected }
        return jobRepository.findById(jobId)!!
    }

    /** 首次调用建号并登录，随后给 REST 客户端挂会话 Cookie（安全改造后 /api/v1 全部需要登录） */
    private fun login() {
        if (authCookie.get() != null) return
        val status = rest.getForEntity("/api/v1/auth/status", Map::class.java).body ?: emptyMap<Any, Any>()
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val creds = mapOf("username" to "admin", "password" to "secret66")
        val resp = if (status["initialized"] == true) {
            rest.postForEntity("/api/v1/auth/login", HttpEntity(creds, headers), String::class.java)
        } else {
            rest.postForEntity("/api/v1/auth/init", HttpEntity(creds, headers), String::class.java)
        }
        authCookie.set(resp.headers[HttpHeaders.SET_COOKIE]?.joinToString("; ") { it.split(";").first() })
        rest.restTemplate.interceptors.add(
            ClientHttpRequestInterceptor { request, body, execution ->
                authCookie.get()?.let { request.headers.add(HttpHeaders.COOKIE, it) }
                execution.execute(request, body)
            },
        )
    }
}
