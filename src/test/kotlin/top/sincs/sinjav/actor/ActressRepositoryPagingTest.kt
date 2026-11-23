package top.sincs.sinjav.actor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.nio.file.Files

/**
 * 演员列表/详情的 SQL 语义（批次 5）：相关子查询统计影片数、search 覆盖别名表、
 * hasPhoto 三态过滤、分页 offset，以及 `movie_actresses JOIN movies` 的详情查询。
 *
 * 用临时 SQLite（与 ActressServiceTest/EmbyActorSyncServiceTest 同做法），表结构手工建出本功能用到的列集。
 */
class ActressRepositoryPagingTest {

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repo: ActressRepository

    @BeforeEach
    fun setUp() {
        val dbFile = Files.createTempFile("sinjav-actress-paging", ".sqlite")
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
        template.execute(
            "CREATE TABLE movies (id INTEGER PRIMARY KEY AUTOINCREMENT, number VARCHAR(64) UNIQUE NOT NULL, title TEXT)",
        )
        template.execute(
            "CREATE TABLE movie_actresses (movie_id INTEGER NOT NULL, actress_id INTEGER NOT NULL, PRIMARY KEY (movie_id, actress_id))",
        )
        jdbc = NamedParameterJdbcTemplate(ds)
        repo = ActressRepository(jdbc)
    }

    private fun insertActress(name: String, alias: String? = null, photo: String? = null): Long {
        jdbc.update(
            "INSERT INTO actresses (name, alias, photo_path) VALUES (:n, :a, :p)",
            mapOf("n" to name, "a" to alias, "p" to photo),
        )
        return jdbc.queryForObject("SELECT id FROM actresses WHERE name = :n", mapOf("n" to name), Long::class.java)!!
    }

    private fun insertAlias(actressId: Long, alias: String) {
        jdbc.update(
            "INSERT INTO actress_aliases (actress_id, alias) VALUES (:aid, :a)",
            mapOf("aid" to actressId, "a" to alias),
        )
    }

    private fun insertMovie(number: String, title: String?): Long {
        jdbc.update("INSERT INTO movies (number, title) VALUES (:n, :t)", mapOf("n" to number, "t" to title))
        return jdbc.queryForObject("SELECT id FROM movies WHERE number = :n", mapOf("n" to number), Long::class.java)!!
    }

    private fun link(movieId: Long, actressId: Long) {
        jdbc.update(
            "INSERT INTO movie_actresses (movie_id, actress_id) VALUES (:mid, :aid)",
            mapOf("mid" to movieId, "aid" to actressId),
        )
    }

    @Test
    fun `分页列表按 id 倒序并统计每个演员的影片数`() {
        val a1 = insertActress("夢乃あいか", photo = "actors/夢乃あいか.jpg")
        val a2 = insertActress("三上悠亜")
        insertActress("新人")
        val m1 = insertMovie("ABP-123", "标题A")
        val m2 = insertMovie("ABP-124", "标题B")
        link(m1, a1)
        link(m2, a1)
        link(m1, a2)

        val page = repo.findAllPaged(page = 1, size = 10, search = null, hasPhoto = null)

        assertEquals(3, page.total)
        assertEquals(listOf("新人", "三上悠亜", "夢乃あいか"), page.items.map { it.actress.name }, "id 倒序")
        assertEquals(listOf(0L, 1L, 2L), page.items.map { it.movieCount })
    }

    @Test
    fun `分页 offset 生效且 total 为过滤后总数`() {
        (1..5).forEach { insertActress("演员$it") }

        val first = repo.findAllPaged(page = 1, size = 2, search = null, hasPhoto = null)
        val third = repo.findAllPaged(page = 3, size = 2, search = null, hasPhoto = null)

        assertEquals(5, first.total)
        assertEquals(5, third.total, "total 与页码无关")
        assertEquals(2, first.items.size)
        assertEquals(1, third.items.size, "第 3 页只剩 1 条")
        assertTrue(first.items.map { it.actress.id }.intersect(third.items.map { it.actress.id }.toSet()).isEmpty())
    }

    @Test
    fun `search 覆盖 name alias 与别名表`() {
        val a1 = insertActress("夢乃あいか", alias = "夢乃")
        insertAlias(a1, "梦乃爱华")
        insertActress("三上悠亜", alias = "三上")

        assertEquals(listOf("夢乃あいか"), repo.findAllPaged(1, 10, "梦乃", null).items.map { it.actress.name }, "命中别名表")
        assertEquals(listOf("夢乃あいか"), repo.findAllPaged(1, 10, "夢乃", null).items.map { it.actress.name }, "命中 name/alias")
        assertEquals(listOf("三上悠亜"), repo.findAllPaged(1, 10, "三上", null).items.map { it.actress.name })
        assertEquals(0, repo.findAllPaged(1, 10, "查无此人", null).total)
    }

    @Test
    fun `hasPhoto 三态过滤（NULL 与空串都算无头像）`() {
        insertActress("有照片", photo = "actors/有照片.jpg")
        insertActress("无照片")
        insertActress("空路径", photo = "")

        val all = repo.findAllPaged(1, 10, null, null)
        val withPhoto = repo.findAllPaged(1, 10, null, true)
        val withoutPhoto = repo.findAllPaged(1, 10, null, false)

        assertEquals(3, all.total, "null 不过滤")
        assertEquals(listOf("有照片"), withPhoto.items.map { it.actress.name })
        assertEquals(setOf("无照片", "空路径"), withoutPhoto.items.map { it.actress.name }.toSet())
        assertEquals(2, withoutPhoto.total)
    }

    @Test
    fun `详情联出所属影片并统计影片数`() {
        val actressId = insertActress("夢乃あいか", photo = "actors/夢乃あいか.jpg")
        val m1 = insertMovie("ABP-123", "标题A")
        val m2 = insertMovie("SSIS-001", null)
        link(m1, actressId)
        link(m2, actressId)

        val detail = repo.findByIdWithMovies(actressId)!!
        assertEquals("夢乃あいか", detail.actress.name)
        assertEquals(2, detail.movieCount)
        assertEquals(listOf("SSIS-001", "ABP-123"), detail.movies.map { it.number }, "影片按 id 倒序")
        assertNull(detail.movies.first().title)
        assertEquals(2, repo.countMovies(actressId))
    }

    @Test
    fun `详情演员不存在返回 null 且影片数为 0`() {
        assertNull(repo.findByIdWithMovies(999_999))

        val actressId = insertActress("无作品")
        val detail = repo.findByIdWithMovies(actressId)!!
        assertTrue(detail.movies.isEmpty())
        assertEquals(0, detail.movieCount)
        assertEquals(0, repo.countMovies(actressId))
    }
}
