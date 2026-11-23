package top.sincs.sinjav.actor

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import top.sincs.sinjav.common.AppMetadataRepository
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scraper.CoverCropper
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gfriends 真实网络端到端验证（默认跳过，不进 CI）：
 * 真下载 Filetree.json（约 6.5MB / 10 万+ 条）→ 落库 → 按名查 URL → 下载一张头像到本地目录并 valid_pic 校验。
 *
 * 开启方式（GitHub raw 通常需代理，默认取本地 127.0.0.1:10808）：
 * ```
 * $env:GFRIENDS_LIVE="true"; .\gradlew.bat test --tests "top.sincs.sinjav.actor.GfriendsLiveTest"
 * ```
 * 可选环境变量：GFRIENDS_PROXY_HOST / GFRIENDS_PROXY_PORT（代理）、GFRIENDS_PHOTO_DIR（头像落盘目录，默认 data/actors）
 */
@EnabledIfEnvironmentVariable(named = "GFRIENDS_LIVE", matches = "true")
class GfriendsLiveTest {

    private fun newService(props: AppProperties): Pair<GfriendsService, GfriendsIndexRepository> {
        val dbFile = Files.createTempFile("sinjav-gfriends-live", ".sqlite")
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
        // 无 Spring 上下文时 @Transactional 不生效（SQLite 逐行自动提交会让 3.4 万行 upsert 从 ~0.1s 变 ~4min），
        // 故测试里用 TransactionTemplate 显式包一层事务，模拟生产经代理调用的行为
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(ds))
        return GfriendsService(http, props, imageStore, index, AppMetadataRepository(jdbc)) to index
    }

    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `真实同步索引 查头像并下载到本地`() {
        val props = AppProperties().apply {
            scraper.requestIntervalMs = 0
            scraper.timeoutMs = 60_000
            scraper.proxyHost = System.getenv("GFRIENDS_PROXY_HOST") ?: "127.0.0.1"
            scraper.proxyPort = (System.getenv("GFRIENDS_PROXY_PORT") ?: "10808").toInt()
            gfriends.indexUrl = "https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json"
            gfriends.syncThresholdDays = 7
            gfriends.enableProxy = true
            images.dir = Files.createTempDirectory("sinjav-gfriends-live-imgs").toString()
        }
        val (service, index) = newService(props)

        // 1) 真实下载 + 解析 + 落库（外层事务模拟生产 @Transactional 代理）
        assertTrue(transactionTemplate.execute { service.syncIndex(force = true) } == true, "索引同步应成功")
        val count = index.count()
        println("[live] Gfriends 索引条目数 = $count，最后同步 = ${service.lastSyncAt()}")
        // Filetree.json 约 10.7 万条，按演员名去重后约 3.4 万条（同名多上传者取先出现者）
        assertTrue(count > 30_000, "去重后应有 3 万+ 条演员头像（实际 $count）")

        // 2) 按名查头像（中文别名 → 日文名兜底）
        val url = service.findPhoto("夢乃あいか", listOf("梦乃爱华"))
        println("[live] findPhoto(夢乃あいか) = $url")
        assertNotNull(url, "夢乃あいか 应有头像")

        // 3) 下载头像并校验（默认落 data/actors/）
        val dir = Path.of(System.getenv("GFRIENDS_PHOTO_DIR") ?: "data/actors")
        val savePath = dir.resolve("夢乃あいか.jpg")
        assertTrue(service.downloadPhoto(url!!, savePath), "头像应下载并通过 valid_pic 校验")
        val size = Files.size(savePath)
        println("[live] 头像已保存 ${savePath.toAbsolutePath()}（$size B）")
        assertTrue(size > 1024, "下载的头像应是有内容的图片（实际 $size B）")
    }
}
