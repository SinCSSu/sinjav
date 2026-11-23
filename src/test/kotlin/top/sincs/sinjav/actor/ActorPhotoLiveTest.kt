package top.sincs.sinjav.actor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.common.AppMetadataRepository
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.job.JobControl
import top.sincs.sinjav.scraper.CoverCropper
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * 演员头像**真实网络**端到端验证（默认跳过，不进 CI）：真同步 Gfriends 索引 →
 * 真实下载 2 个演员的头像到 data/actors → 回写 photo_path。
 *
 * 开启方式（GitHub raw 通常需代理，默认取本地 127.0.0.1:10808）：
 * ```
 * $env:GFRIENDS_LIVE="true"; .\gradlew.bat test --tests "top.sincs.sinjav.actor.ActorPhotoLiveTest"
 * ```
 * 可选环境变量：GFRIENDS_PROXY_HOST / GFRIENDS_PROXY_PORT（代理）、GFRIENDS_PHOTO_DIR（头像落盘目录，默认 data/actors）
 */
@EnabledIfEnvironmentVariable(named = "GFRIENDS_LIVE", matches = "true")
class ActorPhotoLiveTest {

    @Test
    fun `真实下载两个演员头像并回写 photo_path`() {
        val photoDir = Path.of(System.getenv("GFRIENDS_PHOTO_DIR") ?: "data/actors")
        val props = AppProperties().apply {
            scraper.requestIntervalMs = 0
            scraper.timeoutMs = 60_000
            scraper.proxyHost = System.getenv("GFRIENDS_PROXY_HOST") ?: "127.0.0.1"
            scraper.proxyPort = (System.getenv("GFRIENDS_PROXY_PORT") ?: "10808").toInt()
            gfriends.indexUrl = "https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json"
            gfriends.syncThresholdDays = 7
            gfriends.enableProxy = true
            images.dir = Files.createTempDirectory("sinjav-actor-live-imgs").toString()
            actors.photoDir = photoDir.toString()
        }

        val dbFile = Files.createTempFile("sinjav-actor-live", ".sqlite")
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
        template.execute("CREATE TABLE actress_aliases (id INTEGER PRIMARY KEY AUTOINCREMENT, actress_id INTEGER NOT NULL, alias TEXT NOT NULL UNIQUE)")
        template.execute("CREATE TABLE gfriends_index (name TEXT PRIMARY KEY, url TEXT NOT NULL)")
        template.execute("CREATE TABLE app_metadata (key TEXT PRIMARY KEY, value TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)")

        val jdbc = NamedParameterJdbcTemplate(ds)
        val actressRepository = ActressRepository(jdbc)
        val actressService = ActressService(actressRepository, ActressAliasRepository(jdbc))
        val http = HttpClientFactory(props)
        val imageStore = ImageStore(http, props, CoverCropper(), MovieRepository(jdbc))
        val gfriends = GfriendsService(http, props, imageStore, GfriendsIndexRepository(jdbc), AppMetadataRepository(jdbc))
        val service = ActorPhotoService(
            actressService, actressRepository, gfriends, JobRepository(jdbc), JobControl(), props, ObjectMapper(),
            top.sincs.sinjav.emby.EmbyClient(props, ObjectMapper()),
            java.util.concurrent.Executor { command -> command.run() },
        )

        assertTrue(gfriends.syncIndex(force = true), "Gfriends 索引真实同步应成功")
        println("[live] Gfriends 索引条目数 = ${gfriends.indexCount()}")

        // 两个真实存在的演员：日文名 / 中文名 + 日文别名
        val targets = listOf("夢乃あいか" to emptyList<String>(), "三上悠亜" to listOf("三上悠亚"))
        var success = 0
        targets.forEach { (name, aliases) ->
            jdbc.update("INSERT INTO actresses (name) VALUES (:n)", mapOf("n" to name))
            val id = jdbc.queryForObject("SELECT id FROM actresses WHERE name = :n", mapOf("n" to name), Long::class.java)!!
            aliases.forEach { a -> jdbc.update("INSERT INTO actress_aliases (actress_id, alias) VALUES (:i, :a)", mapOf("i" to id, "a" to a)) }

            val result = service.scrapePhoto(id)
            val path = actressRepository.findById(id)!!.photoPath
            println("[live] 演员 $name → $result, photo_path=$path")
            if (result == ScrapeResult.SUCCESS) {
                success++
                val file = photoDir.resolve(path!!.substringAfterLast('/'))
                assertTrue(Files.isRegularFile(file), "头像文件应落盘: $file")
                assertTrue(Files.size(file) > 1024, "下载的头像应是有内容的图片")
            }
        }
        assertEquals(2, success, "两个演员都应抓取成功（Gfriends 索引变更时需人工复核）")
    }
}
