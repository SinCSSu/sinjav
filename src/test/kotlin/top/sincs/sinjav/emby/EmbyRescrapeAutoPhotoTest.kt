package top.sincs.sinjav.emby

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.actor.ActorPhotoService
import top.sincs.sinjav.actor.ActressAliasRepository
import top.sincs.sinjav.actor.ActressRepository
import top.sincs.sinjav.actor.ActressService
import top.sincs.sinjav.api.dto.EmbyRescrapeApplyRequest
import top.sincs.sinjav.api.dto.ScrapeFieldChoice
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scan.NumberRecognizer
import top.sincs.sinjav.scraper.CoverCropper
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import top.sincs.sinjav.scraper.ScrapeCompareService
import top.sincs.sinjav.scraper.ScraperChain
import top.sincs.sinjav.scraper.TranslationService
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files

/**
 * 批次 6 入口 2：Emby 重刮收尾自动补齐新演员头像
 * （EmbyRescrapeService.apply → ActressService.registerAll → ActorPhotoService.scrapePhotosAsync）。
 *
 * 覆盖验收点：
 * - 重刮落库的演员并入本地演员库，且**新演员**触发一次异步补全（参数为新建 id + 触发原因）；
 * - 同一批演员再次重刮（已入库）不再触发；
 * - `sinjav.actors.auto-scrape-after-batch=false` 时整段跳过（不写库、不触发）。
 *
 * Emby 网络侧用 Mockito 替身（只断言 updateMetadata 被调用，不发请求），头像服务同样用替身。
 */
class EmbyRescrapeAutoPhotoTest {

    private lateinit var props: AppProperties
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var actressRepository: ActressRepository
    private lateinit var actorPhotoService: ActorPhotoService
    private lateinit var emby: EmbyClient
    private lateinit var service: EmbyRescrapeService

    @BeforeEach
    fun setUp() {
        props = AppProperties().apply {
            actors.autoScrapeAfterBatch = true
            images.download = false
            images.dir = Files.createTempDirectory("sinjav-emby-auto-photo-images").toString()
            scraper.autoTranslate = false
        }

        val dbFile = Files.createTempFile("sinjav-emby-auto-photo", ".sqlite")
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

        jdbc = NamedParameterJdbcTemplate(ds)
        actressRepository = ActressRepository(jdbc)
        val actressService = ActressService(actressRepository, ActressAliasRepository(jdbc))
        actorPhotoService = Mockito.mock(ActorPhotoService::class.java)
        emby = Mockito.mock(EmbyClient::class.java)

        val chain = ScraperChain(emptyList(), props)
        val http = HttpClientFactory(props)
        val movies = MovieRepository(jdbc)
        val imageStore = ImageStore(http, props, CoverCropper(), movies)
        val compare = ScrapeCompareService(chain, movies, imageStore, TranslationService(http, props, ObjectMapper()))
        service = EmbyRescrapeService(
            emby, chain, NumberRecognizer(), compare, http, imageStore, CoverCropper(),
            actressService, actorPhotoService, props,
        )
    }

    private fun request(vararg actresses: String, number: String = "ABP-123"): EmbyRescrapeApplyRequest =
        EmbyRescrapeApplyRequest(
            number = number,
            fields = mapOf(
                "title" to ScrapeFieldChoice(source = "merged", value = "测试标题"),
                "actresses" to ScrapeFieldChoice(source = "merged", value = actresses.joinToString(" / ")),
            ),
        )

    private fun actressIds(): List<Long> =
        jdbc.queryForList("SELECT id FROM actresses ORDER BY id", emptyMap<String, Any>(), Long::class.java)
            .filterNotNull()

    /**
     * 回写 Emby 的次数。这里不用 `Mockito.verify(emby).updateMetadata(eq(...), any())`：
     * `any()` 对 Kotlin 非空参数会返回 null，触发被调方的空检查（Mockito 的经典坑），
     * 数 invocation 更稳且同样能证明「重刮照样回写了 Emby」。
     */
    private fun embyUpdateCount(): Int =
        Mockito.mockingDetails(emby).invocations.count { it.method.name == "updateMetadata" }

    @Test
    fun `Emby 重刮收尾把新演员并入演员库并触发头像补全`() {
        service.apply("item-1", request("新人A", "新人B"))

        assertEquals(1, embyUpdateCount(), "重刮仍应回写 Emby 元数据")
        val ids = actressIds()
        assertEquals(2, ids.size, "重刮落库的演员应并入本地演员库")
        Mockito.verify(actorPhotoService).scrapePhotosAsync(ids, "Emby 重刮自动补全")
        Mockito.verifyNoMoreInteractions(actorPhotoService)
    }

    @Test
    fun `同一批演员再次重刮不重复触发补全`() {
        service.apply("item-1", request("新人A"))
        assertEquals(1, actressIds().size)

        service.apply("item-1", request("新人A"))

        assertTrue(actressIds().size == 1, "已入库的演员不应重复建号")
        Mockito.verify(actorPhotoService, Mockito.times(1)).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }

    @Test
    fun `配置关闭时不写演员库也不触发补全`() {
        props.actors.autoScrapeAfterBatch = false

        service.apply("item-1", request("新人A"))

        assertEquals(1, embyUpdateCount(), "开关只控制补全，重刮回写照常")
        assertTrue(actressIds().isEmpty(), "开关关闭时不应改动本地演员库")
        Mockito.verify(actorPhotoService, Mockito.never()).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }

    @Test
    fun `未提交演员字段时不触发补全`() {
        service.apply("item-1", EmbyRescrapeApplyRequest(number = "ABP-123", fields = emptyMap()))

        assertTrue(actressIds().isEmpty())
        Mockito.verify(actorPhotoService, Mockito.never()).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }
}
