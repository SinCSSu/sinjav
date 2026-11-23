package top.sincs.sinjav.actor

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.common.AppMetadataRepository
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.Job
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.job.JobControl
import top.sincs.sinjav.job.JobType
import top.sincs.sinjav.scraper.CoverCropper
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * 演员头像抓取：单演员（成功/跳过/未命中/下载失败/别名兜底/特殊字符）、批量（进度 + 限速 + 取消）、
 * 刮削后自动补全入口。
 *
 * 网络层用本地 HttpServer 桩（Filetree.json + 真实 JPEG 字节），下载链路走真实 HTTP 与 valid_pic 校验，
 * 不依赖外网；真实 Gfriends 端到端验证见 ActorPhotoLiveTest（GFRIENDS_LIVE=true 时运行）。
 */
class ActorPhotoServiceTest {

    // ------------------------------------------------------------ 测试脚手架

    /** 已记录的请求（Emby 推送断言用） */
    private data class RecordedRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        val body: ByteArray,
    )

    /**
     * 本地 HTTP 桩：path → 响应字节（未配置的路径返回空体，valid_pic 必然失败）。
     * [failStatus] 里的路径按错误状态码空响应，用于验证「推送 Emby 失败」分支；所有请求都会被记录。
     */
    private class StubServer(
        private val responses: Map<String, ByteArray>,
        val failStatus: MutableMap<String, Int> = mutableMapOf(),
    ) {
        private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)

        val requests: MutableList<RecordedRequest> =
            java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

        init {
            server.createContext("/") { ex ->
                val body = ex.requestBody.readBytes()
                requests += RecordedRequest(
                    method = ex.requestMethod,
                    path = ex.requestURI.path,
                    contentType = ex.requestHeaders.getFirst("Content-Type"),
                    body = body,
                )
                val status = failStatus[ex.requestURI.path] ?: 200
                if (status >= 400) {
                    ex.sendResponseHeaders(status, -1)
                    ex.close()
                    return@createContext
                }
                val payload = responses[ex.requestURI.path] ?: ByteArray(0)
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)

        /** 所有 POST 请求（Emby 图片上传） */
        fun posts(): List<RecordedRequest> = synchronized(requests) { requests.filter { it.method == "POST" } }
    }

    private fun makeJpeg(w: Int = 120, h: Int = 180): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private lateinit var tempDir: Path
    private lateinit var props: AppProperties
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var actressRepository: ActressRepository
    private lateinit var actressService: ActressService
    private lateinit var jobs: JobRepository
    private lateinit var service: ActorPhotoService
    private lateinit var server: StubServer
    private lateinit var jpeg: ByteArray

    @BeforeEach
    fun setUp() {
        jpeg = makeJpeg()
        tempDir = Files.createTempDirectory("sinjav-actor-photo")
        val filetree = """
            {
              "Content": {
                "z-test": {
                  "夢乃あいか.jpg": "photo.jpg",
                  "梦乃爱华.jpg": "photo.jpg",
                  "三上悠亜.jpg": "photo.jpg",
                  "新人1.jpg": "photo.jpg",
                  "新人2.jpg": "photo.jpg",
                  "新人3.jpg": "photo.jpg",
                  "坏图.jpg": "bad.jpg",
                  "梦乃:爱华.jpg": "photo.jpg"
                }
              }
            }
        """.trimIndent().toByteArray()
        server = StubServer(
            mapOf(
                "/Filetree.json" to filetree,
                "/Content/z-test/photo.jpg" to jpeg,
                "/Content/z-test/bad.jpg" to "<html>404 not an image</html>".toByteArray(),
            ),
        )

        props = AppProperties().apply {
            scraper.requestIntervalMs = 0
            scraper.timeoutMs = 3000
            scraper.proxyHost = ""
            scraper.proxyPort = 0
            gfriends.indexUrl = "${server.baseUrl}/Filetree.json"
            gfriends.syncThresholdDays = 7
            gfriends.enableProxy = false
            images.dir = tempDir.resolve("images").toString()
            // 末级目录名为 actors → photo_path 前缀为 actors/（与设计文档一致）
            actors.photoDir = tempDir.resolve("data/actors").toString()
            actors.scrapeIntervalMs = 1500
            actors.pushToEmby = true
            // Emby 指向同一个桩服务：/emby/Items/{personId}/Images/Primary 的上传请求可被记录断言
            emby.baseUrl = server.baseUrl
            emby.apiKey = "test-key"
        }

        // 临时 SQLite（内存库跨连接不可见）：手工建出本功能用到的表
        val dbFile = Files.createTempFile("sinjav-actor-photo-db", ".sqlite")
        val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        val ds = SimpleDriverDataSource(driver, "jdbc:sqlite:$dbFile")
        val template = JdbcTemplate(ds)
        template.execute(
            """
            CREATE TABLE actresses (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                name           VARCHAR(128) UNIQUE NOT NULL,
                photo_path     TEXT,
                alias          TEXT,
                emby_person_id TEXT,
                updated_at     TIMESTAMP
            )
            """.trimIndent(),
        )
        template.execute(
            "CREATE TABLE actress_aliases (id INTEGER PRIMARY KEY AUTOINCREMENT, actress_id INTEGER NOT NULL, alias TEXT NOT NULL UNIQUE)",
        )
        template.execute("CREATE TABLE gfriends_index (name TEXT PRIMARY KEY, url TEXT NOT NULL)")
        template.execute(
            "CREATE TABLE app_metadata (key TEXT PRIMARY KEY, value TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)",
        )
        // jobs：时间列用 INTEGER epoch（测试直连 SQLite 时没有 Hikari 的 date_string_format 设置，
        // 文本时间会被驱动按 yyyy-MM-dd HH:mm:ss.SSS 解析失败）
        template.execute(
            """
            CREATE TABLE jobs (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                type         VARCHAR(32) NOT NULL,
                status       VARCHAR(16) NOT NULL DEFAULT 'running',
                params       TEXT,
                total_items  INTEGER NOT NULL DEFAULT 0,
                done_items   INTEGER NOT NULL DEFAULT 0,
                fail_items   INTEGER NOT NULL DEFAULT 0,
                error_detail TEXT,
                started_at   INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER) * 1000),
                finished_at  INTEGER
            )
            """.trimIndent(),
        )

        jdbc = NamedParameterJdbcTemplate(ds)
        actressRepository = ActressRepository(jdbc)
        actressService = ActressService(actressRepository, ActressAliasRepository(jdbc))
        jobs = JobRepository(jdbc)

        val http = HttpClientFactory(props)
        val imageStore = ImageStore(http, props, CoverCropper(), MovieRepository(jdbc))
        val gfriends = GfriendsService(http, props, imageStore, GfriendsIndexRepository(jdbc), AppMetadataRepository(jdbc))
        assertTrue(gfriends.syncIndex(force = true), "桩索引应同步成功")

        service = ActorPhotoService(
            actressService, actressRepository, gfriends, jobs, JobControl(), props, ObjectMapper(),
            top.sincs.sinjav.emby.EmbyClient(props, ObjectMapper()),
            // 直接执行的 Executor：把「异步派发」在单测里变成同步调用，断言无需等待
            java.util.concurrent.Executor { command -> command.run() },
        )
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun newActress(name: String, alias: String? = null, photoPath: String? = null): Long {
        jdbc.update(
            "INSERT INTO actresses (name, alias, photo_path) VALUES (:n, :a, :p)",
            mapOf("n" to name, "a" to alias, "p" to photoPath),
        )
        return jdbc.queryForObject("SELECT id FROM actresses WHERE name = :n", mapOf("n" to name), Long::class.java)!!
    }

    private fun addAlias(actressId: Long, alias: String) {
        jdbc.update(
            "INSERT INTO actress_aliases (actress_id, alias) VALUES (:aid, :a)",
            mapOf("aid" to actressId, "a" to alias),
        )
    }

    private fun photoFile(name: String): Path = Path.of(props.actors.photoDir).resolve(name)

    private fun newJob(type: JobType = JobType.ACTOR_PHOTO_BATCH): Job = jobs.create(type.code, null, 0)

    private fun reload(jobId: Long): Job = jobs.findById(jobId)!!

    // ------------------------------------------------------------ 单演员

    @Test
    fun `scrapePhoto 命中后下载头像并回写 photo_path`() {
        val id = newActress("夢乃あいか")

        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id), "应下载成功")

        val actress = actressRepository.findById(id)!!
        assertEquals("actors/夢乃あいか.jpg", actress.photoPath, "photo_path 存相对路径")
        assertNotNull(actress.updatedAt, "应刷新 updated_at")
        val file = photoFile("夢乃あいか.jpg")
        assertTrue(Files.isRegularFile(file), "文件应落盘到 data/actors")
        assertEquals(jpeg.toList(), Files.readAllBytes(file).toList(), "内容应与下载字节一致")
    }

    @Test
    fun `scrapePhoto 已有照片跳过 force 重下 文件丢失则重下`() {
        val id = newActress("夢乃あいか")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id))

        assertEquals(ScrapeResult.SKIPPED, service.scrapePhoto(id), "已有照片且文件存在应跳过")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id, force = true), "force=true 应重下")

        Files.delete(photoFile("夢乃あいか.jpg"))
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id), "photo_path 有值但文件丢失应重下")
        assertTrue(Files.isRegularFile(photoFile("夢乃あいか.jpg")))
    }

    @Test
    fun `scrapePhoto 采用手工放置的本地头像且不覆盖原图`() {
        val id = newActress("夢乃あいか")
        val handPlaced = photoFile("夢乃あいか.jpg")
        Files.createDirectories(handPlaced.parent)
        // 内容不参与校验（该分支不下载、不校验），只需证明文件未被覆盖
        val manualBytes = "hand-placed-avatar".toByteArray()
        Files.write(handPlaced, manualBytes)

        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id), "photo_path 为空但本地已有同名文件 → 登记为头像")
        assertEquals("actors/夢乃あいか.jpg", actressRepository.findById(id)!!.photoPath)
        assertTrue(manualBytes.contentEquals(Files.readAllBytes(handPlaced)), "手工放置的头像不应被下载覆盖")

        // force = true 走正常重下（「换一张」），会覆盖成 Gfriends 的图
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id, force = true))
        assertFalse(manualBytes.contentEquals(Files.readAllBytes(handPlaced)), "force 应重新下载并覆盖")
    }

    @Test
    fun `scrapePhoto 手工头像在 force 下仍优先登记`() {
        val id = newActress("三上悠亜")
        val handPlaced = photoFile("三上悠亜.jpg")
        Files.createDirectories(handPlaced.parent)
        val manualBytes = "hand-placed-avatar-force".toByteArray()
        Files.write(handPlaced, manualBytes)

        // photo_path 为空时若不登记，手工放的文件永远不会出现在页面上（与 force 无关）
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id, force = true))
        assertTrue(manualBytes.contentEquals(Files.readAllBytes(handPlaced)), "手工头像不应被下载覆盖")
        assertEquals("actors/三上悠亜.jpg", actressRepository.findById(id)!!.photoPath)
    }

    @Test
    fun `scrapePhoto 索引未命中返回 NOT_FOUND 且不写库`() {
        val id = newActress("查无此人")
        assertEquals(ScrapeResult.NOT_FOUND, service.scrapePhoto(id))
        assertNull(actressRepository.findById(id)!!.photoPath, "未命中不应写入 photo_path")
    }

    @Test
    fun `scrapePhoto 下载非图片返回 DOWNLOAD_FAILED 且不写库`() {
        val id = newActress("坏图")
        assertEquals(ScrapeResult.DOWNLOAD_FAILED, service.scrapePhoto(id), "valid_pic 未通过应算下载失败")
        assertNull(actressRepository.findById(id)!!.photoPath)
        assertFalse(Files.exists(photoFile("坏图.jpg")), "校验失败的文件应被删除")
    }

    @Test
    fun `scrapePhoto 别名兜底命中 文件名用规范名`() {
        // name = 中文名，alias = 日文规范名 → 索引按 alias 命中，文件按规范名命名
        val id = newActress(name = "梦乃爱华", alias = "夢乃あいか")
        addAlias(id, "梦乃爱华2")

        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id))
        assertEquals("actors/夢乃あいか.jpg", actressRepository.findById(id)!!.photoPath)
        assertTrue(Files.isRegularFile(photoFile("夢乃あいか.jpg")))
    }

    @Test
    fun `scrapePhoto 文件名特殊字符替换为下划线`() {
        val id = newActress("梦乃:爱华")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id))
        assertEquals("actors/梦乃_爱华.jpg", actressRepository.findById(id)!!.photoPath, "冒号应被清洗")
        assertTrue(Files.isRegularFile(photoFile("梦乃_爱华.jpg")))
    }

    // ------------------------------------------------------------ Emby 推送（批次 4）

    @Test
    fun `scrapePhoto 成功后推送头像到 Emby Person 主图`() {
        val id = newActress("夢乃あいか")
        actressRepository.updateEmbyPersonId(id, "person-1")

        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id))

        val push = server.posts().single()
        assertEquals("/emby/Items/person-1/Images/Primary", push.path, "应上传到 Person 主图接口")
        assertEquals("image/jpeg", push.contentType)
        val uploaded = java.util.Base64.getDecoder().decode(String(push.body, Charsets.UTF_8))
        assertEquals(jpeg.toList(), uploaded.toList(), "请求体应是 Base64 编码的头像字节")
    }

    @Test
    fun `scrapePhoto 未缓存 Emby Person Id 不推送`() {
        val id = newActress("夢乃あいか")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id))
        assertTrue(server.posts().isEmpty(), "没有 emby_person_id 时不应请求 Emby")
    }

    @Test
    fun `push-to-emby 关闭时不推送`() {
        props.actors.pushToEmby = false
        val id = newActress("夢乃あいか")
        actressRepository.updateEmbyPersonId(id, "person-1")

        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id))
        assertTrue(server.posts().isEmpty(), "配置关闭时不应请求 Emby")
    }

    @Test
    fun `scrapePhoto Emby 推送失败仍返回成功且本地文件保留`() {
        val id = newActress("夢乃あいか")
        actressRepository.updateEmbyPersonId(id, "person-1")
        server.failStatus["/emby/Items/person-1/Images/Primary"] = 500

        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(id), "推送失败不影响抓取结果")
        assertEquals("actors/夢乃あいか.jpg", actressRepository.findById(id)!!.photoPath)
        assertTrue(Files.isRegularFile(photoFile("夢乃あいか.jpg")), "本地头像应保留")
        assertEquals(1, server.posts().size, "确实尝试过推送")
    }

    // ------------------------------------------------------------ 批量补全

    @Test
    fun `scrapeAllMissing 逐个处理并上报进度 限速间隔生效`() {
        val a = newActress("新人1")
        val b = newActress("新人2")
        val c = newActress("新人3")

        val sleeps = mutableListOf<Long>()
        service.sleeper = { ms -> sleeps += ms }

        val job = newJob()
        service.scrapeAllMissing(jobId = job.id)   // 未传 id → 取全部无 photos 的演员

        val done = reload(job.id)
        assertEquals("completed", done.status)
        assertEquals(3, done.totalItems, "无照片演员全部纳入")
        assertEquals(3, done.doneItems, "每个演员都计入进度")
        assertEquals(0, done.failItems)
        assertEquals(listOf(1500L, 1500L), sleeps, "3 个演员之间应休眠 2 次，间隔取配置值")
        assertTrue(Files.isRegularFile(photoFile("新人1.jpg")))
        assertTrue(Files.isRegularFile(photoFile("新人2.jpg")))
        assertEquals("actors/新人1.jpg", actressRepository.findById(a)!!.photoPath)
        assertEquals("actors/新人2.jpg", actressRepository.findById(b)!!.photoPath)
        assertEquals("actors/新人3.jpg", actressRepository.findById(c)!!.photoPath)
    }

    @Test
    fun `scrapeAllMissing 已有照片的演员计为跳过而非失败`() {
        val a = newActress("新人1")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(a))
        service.sleeper = { }

        val job = newJob()
        service.scrapeAllMissing(listOf(a), job.id)

        val done = reload(job.id)
        assertEquals("completed", done.status)
        assertEquals(1, done.doneItems)
        assertEquals(0, done.failItems, "已存在照片应算跳过，不算失败")
        assertNull(done.errorDetail, "跳过不应写失败明细")
    }

    @Test
    fun `scrapeAllMissing 显式传入 id 列表只处理这些演员`() {
        val a = newActress("新人1")
        val b = newActress("新人2")
        service.sleeper = { }

        val job = newJob()
        service.scrapeAllMissing(listOf(a), job.id)

        assertEquals(1, reload(job.id).totalItems)
        assertNotNull(actressRepository.findById(a)!!.photoPath)
        assertNull(actressRepository.findById(b)!!.photoPath, "未传入的演员不应被处理")
    }

    @Test
    fun `scrapeAllMissing 已取消任务立即退出 不下载任何头像`() {
        val a = newActress("新人1")
        val job = newJob()
        jobs.finish(job.id, "cancelled")   // 模拟任务已被取消
        var slept = 0
        service.sleeper = { slept++ }

        service.scrapeAllMissing(jobId = job.id)

        val after = reload(job.id)
        assertEquals("cancelled", after.status, "取消状态应保持")
        assertEquals(0, after.doneItems, "取消后不应继续处理")
        assertEquals(0, slept, "取消后不应再触发限速等待")
        assertNull(actressRepository.findById(a)!!.photoPath, "取消后不应下载头像")
        assertFalse(Files.exists(photoFile("新人1.jpg")))
    }

    @Test
    fun `scrapeAllMissing 单个演员异常不中断任务 计入失败明细`() {
        val a = newActress("新人1")
        service.sleeper = { }
        val job = newJob()

        // 999999 不存在 → 单项异常，任务应继续并最终 completed
        service.scrapeAllMissing(listOf(a, 999_999L), job.id)

        val after = reload(job.id)
        assertEquals("completed", after.status, "单项异常不应让整个任务失败")
        assertEquals(2, after.doneItems)
        assertEquals(1, after.failItems, "不存在的演员应计入失败")
        assertTrue(after.errorDetail!!.contains("下载失败"), "失败明细应记录原因")
    }

    // ------------------------------------------------------------ 自动补全入口（批次 6）

    /** 已创建的自动补全任务 id（按 id 升序） */
    private fun autoJobs(): List<Long> = jdbc.query(
        "SELECT id FROM jobs WHERE type = :t ORDER BY id",
        mapOf("t" to JobType.ACTOR_PHOTO_AUTO.code),
    ) { rs, _ -> rs.getLong("id") }

    @Test
    fun `scrapePhotosAsync 创建自动任务并补全新演员头像`() {
        val a = newActress("新人1")
        val b = newActress("新人2")
        service.sleeper = { }

        val jobId = service.scrapePhotosAsync(listOf(a, b), reason = "批次刮削自动补全")

        assertNotNull(jobId, "有新演员应返回任务 id")
        val job = reload(jobId!!)
        assertEquals(JobType.ACTOR_PHOTO_AUTO.code, job.type)
        assertEquals("completed", job.status)
        assertEquals(2, job.totalItems, "任务总数 = 待补全演员数")
        assertTrue(job.params!!.contains("批次刮削自动补全"), "任务参数应记录触发原因: ${job.params}")
        assertNotNull(actressRepository.findById(a)!!.photoPath)
        assertNotNull(actressRepository.findById(b)!!.photoPath)
    }

    @Test
    fun `scrapePhotosAsync 已有头像的演员被过滤 全部有头像返回 null 不建任务`() {
        val a = newActress("新人1")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(a))
        service.sleeper = { }

        val jobId = service.scrapePhotosAsync(listOf(a), reason = "批次刮削自动补全")

        assertNull(jobId, "全部已有头像时应返回 null")
        assertTrue(autoJobs().isEmpty(), "不应创建 actor_photo_auto 任务")
    }

    @Test
    fun `scrapePhotosAsync 只补缺失头像的演员`() {
        val a = newActress("新人1")
        val b = newActress("新人2")
        assertEquals(ScrapeResult.SUCCESS, service.scrapePhoto(a))
        service.sleeper = { }

        val jobId = service.scrapePhotosAsync(listOf(a, b), reason = "Emby 重刮自动补全")

        assertNotNull(jobId)
        assertEquals(1, reload(jobId!!).totalItems, "只应保留缺头像的演员")
        assertNotNull(actressRepository.findById(b)!!.photoPath)
    }

    @Test
    fun `scrapePhotosAsync 空列表直接返回 null 不建任务`() {
        assertNull(service.scrapePhotosAsync(emptyList(), reason = "批次刮削自动补全"))
        assertTrue(autoJobs().isEmpty())
    }
}
