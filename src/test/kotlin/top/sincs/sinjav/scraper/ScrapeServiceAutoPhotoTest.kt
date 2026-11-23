package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.actor.ActorPhotoService
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.job.JobControl
import top.sincs.sinjav.job.JobType
import top.sincs.sinjav.scan.NumberRecognizer
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files

/**
 * 批次 6 入口 1：批次刮削收尾自动补齐新演员头像（ScrapeService → ActorPhotoService.scrapePhotosAsync）。
 *
 * 覆盖验收点：
 * - 刮削结束后按「本次新建的演员 id」触发一次异步补全，且刮削任务本身仍落 completed；
 * - `sinjav.actors.auto-scrape-after-batch=false` 时完全不触发；
 * - 没有新演员（演员库已存在这些名字）时不触发。
 *
 * 网络与 AI 侧不需要：数据源用内存假 [Scraper]（真实 [ScraperChain] 负责选链与合并），
 * 图片下载关闭，翻译关闭；只有 [ActorPhotoService] 用 Mockito 替身以便断言调用参数。
 */
class ScrapeServiceAutoPhotoTest {

    /** 假数据源：按番号返回固定女优列表；id 取 javbus，保证落在默认 NORMAL 刮削链里 */
    private class FakeScraper(private val actressesByNumber: Map<String, List<String>>) : Scraper {
        override fun id(): String = "javbus"

        override fun scrape(number: String): MovieMetadata = MovieMetadata(
            number = number,
            title = "测试标题",
            actresses = actressesByNumber[number].orEmpty(),
            genres = listOf("剧情"),
        )
    }

    private lateinit var props: AppProperties
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var movies: MovieRepository
    private lateinit var jobs: JobRepository
    private lateinit var actorPhotoService: ActorPhotoService

    @BeforeEach
    fun setUp() {
        props = AppProperties().apply {
            scraper.concurrency = 1          // 顺序执行，断言调用参数时顺序确定
            scraper.requestIntervalMs = 0
            scraper.proxyHost = ""
            scraper.proxyPort = 0
            scraper.autoTranslate = false    // 不触发任何翻译 API
            images.download = false          // 不下载封面/剧照
            images.dir = Files.createTempDirectory("sinjav-scrape-auto-photo-images").toString()
            actors.autoScrapeAfterBatch = true
        }

        // 临时 SQLite：只建本链路用到的表（与生产迁移的列保持一致）
        val dbFile = Files.createTempFile("sinjav-scrape-auto-photo", ".sqlite")
        val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        val ds = SimpleDriverDataSource(driver, "jdbc:sqlite:$dbFile")
        val template = JdbcTemplate(ds)
        template.execute(
            """
            CREATE TABLE movies (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                number          VARCHAR(64) UNIQUE NOT NULL,
                raw_number      VARCHAR(128),
                title           TEXT,
                title_zh        TEXT,
                title_orig      TEXT,
                release_date    DATE,
                director        VARCHAR(128),
                maker           VARCHAR(128),
                label           VARCHAR(128),
                series          VARCHAR(128),
                duration_min    INTEGER,
                cover_url       TEXT,
                preview_urls    TEXT,
                cover_source    VARCHAR(32),
                preview_source  VARCHAR(32),
                cover_candidates TEXT,
                plot            TEXT,
                source          VARCHAR(16),
                status          VARCHAR(16) NOT NULL DEFAULT 'pending',
                fail_reason     TEXT,
                scraped_at      TIMESTAMP,
                created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        template.execute("CREATE TABLE actresses (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(128) UNIQUE NOT NULL)")
        template.execute("CREATE TABLE genres (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(128) UNIQUE NOT NULL)")
        template.execute(
            "CREATE TABLE movie_actresses (movie_id INTEGER NOT NULL, actress_id INTEGER NOT NULL, PRIMARY KEY (movie_id, actress_id))",
        )
        template.execute(
            "CREATE TABLE movie_genres (movie_id INTEGER NOT NULL, genre_id INTEGER NOT NULL, PRIMARY KEY (movie_id, genre_id))",
        )
        // jobs：时间列用 INTEGER epoch（测试直连 SQLite 时没有 Hikari 的 date_string_format 设置）
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
        movies = MovieRepository(jdbc)
        jobs = JobRepository(jdbc)
        actorPhotoService = Mockito.mock(ActorPhotoService::class.java)
    }

    private fun newService(actressesByNumber: Map<String, List<String>>): ScrapeService {
        val chain = ScraperChain(listOf(FakeScraper(actressesByNumber)), props)
        val http = HttpClientFactory(props)
        val imageStore = ImageStore(http, props, CoverCropper(), movies)
        val translation = TranslationService(http, props, ObjectMapper())
        return ScrapeService(
            chain, movies, jobs, NumberRecognizer(), imageStore, translation, props, JobControl(), actorPhotoService,
        )
    }

    /** 建 pending 影片记录 → 建刮削任务 → 执行（返回 jobId） */
    private fun runScrape(service: ScrapeService, numbers: List<String>): Long {
        numbers.forEach { movies.ensurePending(it, it) }
        val job = jobs.create(JobType.SCRAPE.code, null, 0)
        service.execute(job.id, ScrapeParams(numbers = numbers))
        return job.id
    }

    /** actresses 表的全部 id（升序） */
    private fun actressIds(): List<Long> =
        jdbc.queryForList("SELECT id FROM actresses ORDER BY id", emptyMap<String, Any>(), Long::class.java)
            .filterNotNull()

    @Test
    fun `刮削收尾触发新演员头像补全 参数为本次新增的演员 id`() {
        val service = newService(
            mapOf(
                "ABP-123" to listOf("新人A", "新人B"),
                "ABP-124" to listOf("新人C"),
            ),
        )

        val jobId = runScrape(service, listOf("ABP-123", "ABP-124"))

        assertEquals("completed", jobs.findById(jobId)!!.status, "刮削任务不受补全影响，仍应 completed")
        val ids = actressIds()
        assertEquals(3, ids.size, "三次新建应各产生一位演员")
        Mockito.verify(actorPhotoService).scrapePhotosAsync(ids, "批次刮削自动补全")
        Mockito.verifyNoMoreInteractions(actorPhotoService)
    }

    @Test
    fun `配置关闭时不触发自动补全`() {
        props.actors.autoScrapeAfterBatch = false
        val service = newService(mapOf("ABP-123" to listOf("新人A")))

        val jobId = runScrape(service, listOf("ABP-123"))

        assertEquals("completed", jobs.findById(jobId)!!.status)
        assertEquals(1, actressIds().size, "演员仍正常入库，只是不补头像")
        Mockito.verify(actorPhotoService, Mockito.never()).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }

    @Test
    fun `无新演员时不触发自动补全`() {
        // 演员库里已存在同名演员 → markScraped 不会再「新建」，应视为无新演员
        jdbc.update("INSERT INTO actresses (name) VALUES (:n)", mapOf("n" to "老演员"))
        val service = newService(mapOf("ABP-123" to listOf("老演员")))

        val jobId = runScrape(service, listOf("ABP-123"))

        assertEquals("completed", jobs.findById(jobId)!!.status)
        assertEquals(1, actressIds().size, "已存在的演员不应重复建号")
        Mockito.verify(actorPhotoService, Mockito.never()).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }

    @Test
    fun `重复刮削同一番号只在首次触发补全`() {
        val service = newService(mapOf("ABP-123" to listOf("新人A")))

        val firstJob = runScrape(service, listOf("ABP-123"))
        assertEquals("completed", jobs.findById(firstJob)!!.status)
        Mockito.verify(actorPhotoService, Mockito.times(1)).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())

        // 第二次重刮：演员已入库 → 不新增 → 不再触发
        val secondJob = runScrape(service, listOf("ABP-123"))
        assertEquals("completed", jobs.findById(secondJob)!!.status)
        Mockito.verify(actorPhotoService, Mockito.times(1)).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }
}
