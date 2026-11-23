package top.sincs.sinjav.actor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.nio.file.Files

/**
 * 别名归一化（ActressService.normalize）与「演员 + 别名」装载。
 *
 * 用临时 SQLite（内存库跨连接不可见，与 GfriendsServiceTest/ImageStoreTest 同做法），
 * 表结构手工建出 actresses / actress_aliases 的最小列集（V6 迁移后的形态）。
 */
class ActressServiceTest {

    private val jdbc: NamedParameterJdbcTemplate
    private val service: ActressService

    init {
        val dbFile = Files.createTempFile("sinjav-actress-service", ".sqlite")
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
            """
            CREATE TABLE actress_aliases (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                actress_id INTEGER NOT NULL,
                alias      TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
        jdbc = NamedParameterJdbcTemplate(ds)
        service = ActressService(ActressRepository(jdbc), ActressAliasRepository(jdbc))
    }

    private fun insertActress(name: String, alias: String? = null): Long {
        jdbc.update(
            "INSERT INTO actresses (name, alias) VALUES (:n, :a)",
            mapOf("n" to name, "a" to alias),
        )
        return jdbc.queryForObject(
            "SELECT id FROM actresses WHERE name = :n", mapOf("n" to name), Long::class.java,
        )!!
    }

    private fun insertAlias(actressId: Long, alias: String) {
        jdbc.update(
            "INSERT INTO actress_aliases (actress_id, alias) VALUES (:aid, :a)",
            mapOf("aid" to actressId, "a" to alias),
        )
    }

    @Test
    fun `normalize 命中别名返回规范名（alias 优先于 name）`() {
        val id = insertActress(name = "梦乃爱华", alias = "夢乃あいか")
        insertAlias(id, "夢乃あいか")
        insertAlias(id, "夢乃あいか（梦乃爱华）")

        assertEquals("夢乃あいか", service.normalize("夢乃あいか（梦乃爱华）"), "别名应归一化到 alias")
        assertEquals("夢乃あいか", service.normalize("夢乃あいか"), "规范名自身也应命中")
    }

    @Test
    fun `normalize 演员无 alias 时回退 name`() {
        val id = insertActress(name = "三上悠亜")
        insertAlias(id, "三上悠亚")

        assertEquals("三上悠亜", service.normalize("三上悠亚"), "alias 为空应回退 name")
    }

    @Test
    fun `normalize 未命中返回原名（去首尾空白）`() {
        insertActress(name = "新人", alias = null)
        assertEquals("查无此人", service.normalize(" 查无此人 "), "未命中返回 trim 后的原名")
        assertEquals("", service.normalize(""), "空串原样返回，不抛异常")
    }

    @Test
    fun `getActressWithAliases 返回演员与全部别名`() {
        val id = insertActress(name = "夢乃あいか", alias = null)
        insertAlias(id, "梦乃爱华")
        insertAlias(id, "夢乃あいか")

        val (actress, aliases) = service.getActressWithAliases(id)
        assertEquals("夢乃あいか", actress.name)
        assertEquals(listOf("梦乃爱华", "夢乃あいか"), aliases, "别名按录入顺序返回")
        assertEquals("夢乃あいか", service.canonicalName(actress), "无 alias 时规范名回退 name")
    }

    @Test
    fun `getActressWithAliases 演员不存在抛 NoSuchElementException`() {
        val e = assertThrows<NoSuchElementException> { service.getActressWithAliases(999_999) }
        assertTrue(e.message!!.contains("演员不存在"), "异常消息应说明演员不存在")
    }

    @Test
    fun `idsMissingPhoto 只返回无照片的演员`() {
        val noPhoto = insertActress(name = "无照片")
        val hasPhoto = insertActress(name = "有照片")
        jdbc.update(
            "UPDATE actresses SET photo_path = 'actors/有照片.jpg' WHERE id = :id",
            mapOf("id" to hasPhoto),
        )

        val ids = service.idsMissingPhoto()
        assertTrue(noPhoto in ids, "无照片演员应在候选池")
        assertTrue(hasPhoto !in ids, "已有照片演员不应在候选池")
    }
}
