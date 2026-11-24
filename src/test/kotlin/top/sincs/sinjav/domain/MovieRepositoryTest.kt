package top.sincs.sinjav.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.scraper.CoverCandidate
import java.nio.file.Files

/** 剧照逐张来源（preview_candidates）落库/读回：多源合并后需要按图路由下载。 */
class MovieRepositoryTest {

    private fun newRepo(): MovieRepository {
        val dbFile = Files.createTempFile("sinjav-movies-test", ".sqlite")
        val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        val ds = SimpleDriverDataSource(driver, "jdbc:sqlite:$dbFile")
        val template = JdbcTemplate(ds)
        // 只建本链路用到的表（列与生产迁移保持一致）
        template.execute(
            """
            CREATE TABLE movies (
                id INTEGER PRIMARY KEY AUTOINCREMENT, number VARCHAR(64) UNIQUE NOT NULL, raw_number VARCHAR(128),
                title TEXT, title_zh TEXT, title_orig TEXT, release_date DATE, director VARCHAR(128),
                maker VARCHAR(128), label VARCHAR(128), series VARCHAR(128), duration_min INTEGER,
                cover_url TEXT, preview_urls TEXT, cover_source VARCHAR(32), preview_source VARCHAR(32),
                cover_candidates TEXT, preview_candidates TEXT, plot TEXT, source VARCHAR(16),
                status VARCHAR(16) NOT NULL DEFAULT 'pending', fail_reason TEXT, scraped_at TIMESTAMP,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
        return MovieRepository(NamedParameterJdbcTemplate(ds))
    }

    private fun MovieRepository.markScrapedWithCandidates(candidates: List<CoverCandidate>) = markScraped(
        number = "ABP-123", title = "ABP-123-t", titleZh = null, titleOrig = null,
        releaseDate = null, director = null, maker = null, label = null, series = null,
        durationMin = null, coverUrl = null,
        previewUrls = candidates.map { it.url }, plot = null, source = "javbus",
        actresses = emptyList(), genres = emptyList(),
        previewSource = "javbus", previewCandidates = candidates,
    )

    @Test
    fun `剧照逐张来源落库并能读回`() {
        val repo = newRepo()
        repo.ensurePending("ABP-123", "ABP-123")

        repo.markScrapedWithCandidates(
            listOf(
                CoverCandidate("https://pics.dmm.co.jp/hmn00607jp-1.jpg", "javbus"),
                CoverCandidate("https://c0.jdbstatic.com/nKKYEM_l_0.jpg", "javdb"),
            ),
        )

        val movie = repo.findByNumber("ABP-123")!!
        assertEquals(listOf("javbus", "javdb"), movie.previewCandidates.map { it.source })
        assertEquals(movie.previewUrls, movie.previewCandidates.map { it.url })
        assertEquals("javbus", movie.previewSource)
    }

    @Test
    fun `未提供逐张来源时读回空列表（旧记录回退 preview_source）`() {
        val repo = newRepo()
        repo.ensurePending("ABP-123", "ABP-123")
        repo.markScrapedWithCandidates(emptyList())

        val movie = repo.findByNumber("ABP-123")!!
        assertTrue(movie.previewCandidates.isEmpty())
        assertEquals("javbus", movie.previewSource)
    }
}
