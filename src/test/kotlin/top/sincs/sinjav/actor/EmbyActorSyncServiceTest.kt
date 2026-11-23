package top.sincs.sinjav.actor

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import top.sincs.sinjav.actor.EmbyActorSyncService.SyncResult
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.EmbyItemRepository
import top.sincs.sinjav.domain.Job
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.emby.EmbyClient
import top.sincs.sinjav.emby.EmbyStub
import top.sincs.sinjav.job.JobControl
import top.sincs.sinjav.job.JobType
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files

/**
 * Emby 演员同步（批次 4）：emby_items 快照 → 条目 People → 归一化 → actresses 落库 + emby_person_id 回写。
 *
 * Emby 侧用本地 [EmbyStub] 桩服务（不依赖真实环境），数据库用临时 SQLite（手工建本功能用到的表）。
 */
class EmbyActorSyncServiceTest {

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var actressRepository: ActressRepository
    private lateinit var actressService: ActressService
    private lateinit var jobs: JobRepository
    private lateinit var embyItems: EmbyItemRepository

    /** 头像自动补全（批次 8 收尾）用替身：只断言触发参数，真实抓取由集成测试覆盖 */
    private lateinit var actorPhotoService: ActorPhotoService
    private lateinit var props: AppProperties
    private val stubs = mutableListOf<EmbyStub>()

    @BeforeEach
    fun setUp() {
        val dbFile = Files.createTempFile("sinjav-emby-actor-sync", ".sqlite")
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
            """
            CREATE TABLE emby_items (
                item_id   VARCHAR(64) PRIMARY KEY,
                number    VARCHAR(64) NOT NULL,
                name      TEXT,
                path      TEXT,
                synced_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER) * 1000)
            )
            """.trimIndent(),
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
        actressRepository = ActressRepository(jdbc)
        actressService = ActressService(actressRepository, ActressAliasRepository(jdbc))
        jobs = JobRepository(jdbc)
        embyItems = EmbyItemRepository(jdbc)
        actorPhotoService = Mockito.mock(ActorPhotoService::class.java)
        props = AppProperties().apply { actors.autoScrapeAfterBatch = true }
    }

    @AfterEach
    fun tearDown() {
        stubs.forEach { runCatching { it.stop() } }
    }

    // ------------------------------------------------------------ 脚手架

    /** 桩服务：默认带上管理员用户与两个条目，返回 People（各测试可再 route 覆盖） */
    private fun newStub(withItems: Boolean = true): EmbyStub {
        val stub = EmbyStub(
            mapOf(
                "/emby/Users" to """[{"Id": "u1", "Name": "admin", "IsAdministrator": true}]""",
                "/emby/Users/u1/Items/i1" to """
                    {
                      "Id": "i1",
                      "Name": "ABP-123 テスト",
                      "People": [
                        {"Id": "p1", "Name": "三上悠亜", "Role": "Actor", "Type": "Actor"},
                        {"Id": "p2", "Name": "夢乃あいか", "Role": "Actor", "Type": "Actor"}
                      ]
                    }
                """.trimIndent(),
                "/emby/Users/u1/Items/i2" to """
                    {
                      "Id": "i2",
                      "Name": "SSIS-001 テスト",
                      "People": [
                        {"Id": "p1", "Name": "三上悠亜", "Role": "Actor", "Type": "Actor"},
                        {"Id": "p3", "Name": "新人女優", "Role": "Actor", "Type": "Actor"}
                      ]
                    }
                """.trimIndent(),
            ),
        ).also { stubs += it }
        if (withItems) {
            addEmbyItem("i1", "ABP-123")
            addEmbyItem("i2", "SSIS-001")
        }
        return stub
    }

    private fun newService(client: EmbyClient): EmbyActorSyncService =
        EmbyActorSyncService(
            client, embyItems, actressService, actressRepository, jobs, JobControl(),
            actorPhotoService, props,
        )

    private fun newJob(): Job = jobs.create(JobType.EMBY_ACTOR_SYNC.code, null, 0)

    private fun reload(jobId: Long): Job = jobs.findById(jobId)!!

    private fun addEmbyItem(itemId: String, number: String, name: String? = null) {
        jdbc.update(
            "INSERT INTO emby_items (item_id, number, name, path) VALUES (:i, :n, :nm, NULL)",
            mapOf("i" to itemId, "n" to number, "nm" to name),
        )
    }

    private fun newActress(name: String, alias: String? = null): Long {
        jdbc.update(
            "INSERT INTO actresses (name, alias) VALUES (:n, :a)",
            mapOf("n" to name, "a" to alias),
        )
        return jdbc.queryForObject("SELECT id FROM actresses WHERE name = :n", mapOf("n" to name), Long::class.java)!!
    }

    private fun addAlias(actressId: Long, alias: String) {
        jdbc.update(
            "INSERT INTO actress_aliases (actress_id, alias) VALUES (:aid, :a)",
            mapOf("aid" to actressId, "a" to alias),
        )
    }

    private fun count(): Long =
        jdbc.queryForObject("SELECT count(*) FROM actresses", emptyMap<String, Any>(), Long::class.java) ?: 0L

    // ------------------------------------------------------------ 用例

    @Test
    fun `syncFromEmby 未配置 Emby 返回 NOT_CONFIGURED 且任务落失败`() {
        val job = newJob()
        // baseUrl / apiKey 均为空 = 未配置
        val result = newService(EmbyClient(AppProperties(), ObjectMapper())).syncFromEmby(job.id)

        assertEquals(SyncResult.NOT_CONFIGURED, result)
        assertEquals("failed", reload(job.id).status, "未配置时任务应落 failed 而不是一直 running")
        assertEquals(0L, count(), "未配置时不应写库")
    }

    @Test
    fun `syncFromEmby 新建演员并回写 emby_person_id`() {
        val stub = newStub()
        // 已存在的本地演员：应被"更新"而不是重复插入
        val existing = newActress("夢乃あいか")
        val job = newJob()

        val result = newService(stub.client()).syncFromEmby(job.id)

        assertEquals(SyncResult.SUCCESS(new = 2, updated = 1), result, "三上悠亜/新人女優新建，夢乃あいか 回写 Id")
        assertEquals(3L, count(), "同一 Person 跨条目只落库一次，且不新建重复行")
        assertEquals("p1", actressRepository.findByCanonicalName("三上悠亜")?.embyPersonId)
        assertEquals("p2", actressRepository.findById(existing)?.embyPersonId, "既有演员应回写 Emby Person Id")
        assertEquals("p3", actressRepository.findByCanonicalName("新人女優")?.embyPersonId)

        val done = reload(job.id)
        assertEquals("completed", done.status)
        assertEquals(2, done.totalItems, "进度总数 = emby_items 条目数")
        assertEquals(2, done.doneItems)
        assertEquals(0, done.failItems)
        assertEquals(1, stub.requestsTo("/emby/Users/u1/Items/i1").size)
        assertEquals(1, stub.requestsTo("/emby/Users/u1/Items/i2").size)
    }

    @Test
    fun `syncFromEmby 按别名归一化合并到既有演员`() {
        val stub = newStub()
        // 本地有「梦乃爱华」（规范名 alias=夢乃あいか），Emby 侧写作「梦乃爱华」→ 应更新同一行
        val id = newActress(name = "梦乃爱华", alias = "夢乃あいか")
        addAlias(id, "梦乃爱华")
        stub.route(
            "/emby/Users/u1/Items/i1",
            """{"Id": "i1", "People": [{"Id": "p2", "Name": "梦乃爱华", "Type": "Actor"}]}""",
        )
        stub.route("/emby/Users/u1/Items/i2", """{"Id": "i2", "People": []}""")
        val job = newJob()

        val result = newService(stub.client()).syncFromEmby(job.id)

        assertEquals(SyncResult.SUCCESS(new = 0, updated = 1), result)
        assertEquals(1L, count(), "归一化命中既有演员，不应新建重复行")
        assertEquals("p2", actressRepository.findById(id)?.embyPersonId)
        assertEquals("夢乃あいか", actressRepository.findById(id)?.alias, "规范名保持不变")
    }

    @Test
    fun `syncFromEmby 重复同步不重复计数`() {
        val stub = newStub()
        newActress("夢乃あいか")
        newService(stub.client()).syncFromEmby(newJob().id)

        val second = newService(stub.client()).syncFromEmby(newJob().id)

        assertEquals(SyncResult.SUCCESS(new = 0, updated = 0), second, "第二轮演员与 Person Id 均已存在，应全部跳过")
        assertEquals(3L, count())
    }

    @Test
    fun `syncFromEmby 单条目失败计入失败明细且不影响其他条目`() {
        val stub = newStub()
        stub.fail("/emby/Users/u1/Items/i2", 500)
        val job = newJob()

        val result = newService(stub.client()).syncFromEmby(job.id)

        assertEquals(SyncResult.SUCCESS(new = 2, updated = 0), result, "i1 的演员仍应同步成功")
        val done = reload(job.id)
        assertEquals("completed", done.status, "单条目失败不应让整轮同步失败")
        assertEquals(1, done.failItems)
        assertTrue(done.errorDetail.orEmpty().contains("SSIS-001"), "失败明细应带番号: ${done.errorDetail}")
        assertNull(actressRepository.findByCanonicalName("新人女優"), "失败条目的演员不应落库")
    }

    @Test
    fun `syncFromEmby Emby 已删除的条目按跳过处理不计失败`() {
        val stub = newStub()
        stub.fail("/emby/Users/u1/Items/i2", 404)
        val job = newJob()

        val result = newService(stub.client()).syncFromEmby(job.id)

        assertEquals(SyncResult.SUCCESS(new = 2, updated = 0), result)
        val done = reload(job.id)
        assertEquals("completed", done.status)
        assertEquals(0, done.failItems, "快照过期导致的 404 不算失败")
        assertNull(done.errorDetail, "404 不应写失败明细")
    }

    @Test
    fun `syncFromEmby 已取消任务立即退出且不请求 Emby`() {
        val stub = newStub()
        val job = newJob()
        jobs.finish(job.id, "cancelled")

        val result = newService(stub.client()).syncFromEmby(job.id)

        assertEquals(SyncResult.SUCCESS(new = 0, updated = 0), result)
        assertEquals("cancelled", reload(job.id).status, "取消状态应保持")
        assertEquals(0L, count(), "取消后不应落库")
        assertTrue(stub.requestsTo("/emby/Users/u1/Items/i1").isEmpty(), "取消后不应再请求 Emby")
    }

    @Test
    fun `syncFromEmby 新建演员后触发头像自动补全`() {
        val stub = newStub()
        val existing = newActress("夢乃あいか")
        val job = newJob()

        newService(stub.client()).syncFromEmby(job.id)

        // 只有新建的两位触发补全（既有演员回写 Person Id 不算新演员）
        val newIds = listOf("三上悠亜", "新人女優").map { actressRepository.findByCanonicalName(it)!!.id }
        Mockito.verify(actorPhotoService).scrapePhotosAsync(newIds, "Emby 演员同步自动补全")
        Mockito.verifyNoMoreInteractions(actorPhotoService)
        assertEquals("p2", actressRepository.findById(existing)?.embyPersonId, "既有演员只回写 Person Id，不触发补全")
    }

    @Test
    fun `syncFromEmby 自动补全关闭时不触发头像补全`() {
        val stub = newStub()
        props.actors.autoScrapeAfterBatch = false
        val job = newJob()

        newService(stub.client()).syncFromEmby(job.id)

        assertEquals("completed", reload(job.id).status, "开关只控制补全，同步照常完成")
        assertEquals(3L, count(), "演员仍正常入库")
        Mockito.verify(actorPhotoService, Mockito.never()).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }

    @Test
    fun `syncFromEmby 没有新演员时不触发头像补全`() {
        val stub = newStub()
        stub.route(
            "/emby/Users/u1/Items/i1",
            """{"Id": "i1", "People": [{"Id": "p2", "Name": "夢乃あいか", "Type": "Actor"}]}""",
        )
        stub.route("/emby/Users/u1/Items/i2", """{"Id": "i2", "People": []}""")
        newActress("夢乃あいか")

        newService(stub.client()).syncFromEmby(newJob().id)

        Mockito.verify(actorPhotoService, Mockito.never()).scrapePhotosAsync(Mockito.anyList(), Mockito.anyString())
    }

    @Test
    fun `syncFromEmby emby_items 为空时直接完成`() {
        val stub = newStub(withItems = false)
        val job = newJob()

        val result = newService(stub.client()).syncFromEmby(job.id)

        assertEquals(SyncResult.SUCCESS(new = 0, updated = 0), result)
        assertEquals("completed", reload(job.id).status)
        assertEquals(0, reload(job.id).totalItems)
        assertNotNull(jobs.findById(job.id)?.finishedAt, "任务应落结束时间")
        assertTrue(stub.requestsTo("/emby/Persons").isEmpty(), "演员同步不走 /Persons 全量拉取")
    }
}
