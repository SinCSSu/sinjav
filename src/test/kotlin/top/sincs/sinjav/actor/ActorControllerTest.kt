package top.sincs.sinjav.actor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.sincs.sinjav.api.dto.ScrapePhotosRequest
import top.sincs.sinjav.api.dto.UpdateActorDto
import top.sincs.sinjav.job.JobService
import java.time.Instant

/**
 * 演员管理接口（批次 5）：列表 / 详情 / 编辑 / 单个刮削 / 批量任务 / Emby 同步 / Gfriends 索引。
 *
 * Service 层全部用 Mockito 桩替（Controller 只做参数校验、DTO 映射与任务分发），
 * 真实 SQL 由 ActressRepositoryPagingTest 覆盖，任务编排出 JobServiceActorTest 覆盖。
 */
class ActorControllerTest {

    private val actressService = mock(ActressService::class.java)
    private val actorPhotoService = mock(ActorPhotoService::class.java)
    private val jobService = mock(JobService::class.java)
    private val gfriendsService = mock(GfriendsService::class.java)

    private val controller = ActorController(actressService, actorPhotoService, jobService)
    private val gfriends = GfriendsController(gfriendsService)

    private fun actress(
        id: Long,
        name: String,
        alias: String? = null,
        photo: String? = null,
        embyPersonId: String? = null,
    ) = ActressEntity(id = id, name = name, photoPath = photo, alias = alias, embyPersonId = embyPersonId)

    private fun row(
        id: Long,
        name: String,
        alias: String? = null,
        photo: String? = null,
        movieCount: Long = 0,
    ) = ActressRepository.ActressWithCount(actress(id, name, alias, photo), movieCount)

    // ---------------------------------------------------------------- 列表

    @Test
    fun `列表分页透传过滤条件并映射影片数`() {
        `when`(actressService.listPaged(2, 50, "夢乃", true)).thenReturn(
            ActressRepository.Page(listOf(row(7, "夢乃あいか", "夢乃", "actors/夢乃あいか.jpg", 12)), 51),
        )

        val page = controller.list(page = 2, size = 50, search = " 夢乃 ", hasPhoto = true)

        assertEquals(2, page.page)
        assertEquals(50, page.size)
        assertEquals(51, page.total)
        assertEquals(2, page.totalPages, "51 条 / 每页 50 → 2 页")
        val item = page.items.single()
        assertEquals(7, item.id)
        assertEquals("夢乃あいか", item.name)
        assertEquals("夢乃", item.alias)
        assertEquals("actors/夢乃あいか.jpg", item.photoPath)
        assertEquals(12, item.movieCount)
        verify(actressService).listPaged(2, 50, "夢乃", true)
    }

    @Test
    fun `列表夹紧非法分页参数且空关键字与 hasPhoto 缺省不过滤`() {
        `when`(actressService.listPaged(1, 200, null, null)).thenReturn(ActressRepository.Page(emptyList(), 0))

        val page = controller.list(page = 0, size = 9999, search = "   ", hasPhoto = null)

        assertEquals(1, page.page, "page < 1 归 1")
        assertEquals(200, page.size, "size 超上限夹到 200")
        assertEquals(0, page.total)
        assertTrue(page.items.isEmpty())
        verify(actressService).listPaged(1, 200, null, null)
    }

    // ---------------------------------------------------------------- 详情

    @Test
    fun `详情返回演员信息别名与所属影片`() {
        val detail = ActressRepository.ActressDetail(
            actress = actress(3, "夢乃あいか", "夢乃", "actors/夢乃あいか.jpg", "person-1"),
            movies = listOf(
                ActressRepository.ActressMovie(11, "ABP-123", "标题A"),
                ActressRepository.ActressMovie(9, "SSIS-001", null),
            ),
            movieCount = 2,
        )
        `when`(actressService.getDetail(3)).thenReturn(detail to listOf("梦乃爱华", "夢乃あいか"))

        val dto = controller.detail(3)

        assertEquals(3, dto.id)
        assertEquals("夢乃あいか", dto.name)
        assertEquals("夢乃", dto.alias)
        assertEquals(listOf("梦乃爱华", "夢乃あいか"), dto.aliases)
        assertEquals("actors/夢乃あいか.jpg", dto.photoPath)
        assertEquals("person-1", dto.embyPersonId)
        assertEquals(2, dto.movieCount)
        assertEquals(2, dto.movies.size)
        assertEquals("/local-images/ABP-123/poster.jpg", dto.movies.first().coverPath)
        assertNull(dto.movies[1].title)
    }

    @Test
    fun `详情演员不存在抛 NoSuchElementException`() {
        `when`(actressService.getDetail(99)).thenThrow(NoSuchElementException("演员不存在: 99"))

        val e = assertThrows<NoSuchElementException> { controller.detail(99) }
        assertTrue(e.message!!.contains("演员不存在"))
    }

    // ---------------------------------------------------------------- 编辑

    @Test
    fun `编辑别名后返回最新详情`() {
        val updated = actress(3, "夢乃あいか", "夢乃あいか")
        `when`(actressService.updateAliases(3, "夢乃あいか", listOf("梦乃爱华"))).thenReturn(updated)
        `when`(actressService.getDetail(3)).thenReturn(
            ActressRepository.ActressDetail(updated, emptyList(), 0) to listOf("梦乃爱华"),
        )

        val dto = controller.update(3, UpdateActorDto(alias = "夢乃あいか", aliases = listOf("梦乃爱华")))

        assertEquals("夢乃あいか", dto.alias)
        assertEquals(listOf("梦乃爱华"), dto.aliases)
        assertTrue(dto.movies.isEmpty())
        verify(actressService).updateAliases(3, "夢乃あいか", listOf("梦乃爱华"))
    }

    @Test
    fun `编辑别名冲突透出 IllegalArgumentException`() {
        `when`(actressService.updateAliases(3, null, listOf("已占用"))).thenThrow(
            IllegalArgumentException("别名已被其他演员占用: 已占用"),
        )

        val e = assertThrows<IllegalArgumentException> {
            controller.update(3, UpdateActorDto(aliases = listOf("已占用")))
        }
        assertTrue(e.message!!.contains("占用"))
    }

    // ---------------------------------------------------------------- 刮削

    @Test
    fun `单个补全同步返回刮削结果`() {
        `when`(actorPhotoService.scrapePhoto(5)).thenReturn(ScrapeResult.SUCCESS)
        `when`(actorPhotoService.scrapePhoto(6)).thenReturn(ScrapeResult.NOT_FOUND)

        assertEquals(ScrapeResult.SUCCESS, controller.scrapePhoto(5))
        assertEquals(ScrapeResult.NOT_FOUND, controller.scrapePhoto(6))
        verify(actorPhotoService).scrapePhoto(5)
        verify(actorPhotoService).scrapePhoto(6)
    }

    @Test
    fun `单个补全透传 force 参数`() {
        `when`(actorPhotoService.scrapePhoto(7, true)).thenReturn(ScrapeResult.SUCCESS)

        assertEquals(ScrapeResult.SUCCESS, controller.scrapePhoto(7, force = true))
        verify(actorPhotoService).scrapePhoto(7, true)
    }

    @Test
    fun `批量补全返回 202 与 jobId 并透传演员 id`() {
        `when`(jobService.triggerActorPhotoScrape(listOf(1L, 2L))).thenReturn(42L)

        val resp = controller.scrapePhotos(ScrapePhotosRequest(listOf(1L, 2L)))

        assertEquals(202, resp.statusCode.value())
        assertEquals(42L, resp.body?.jobId)
        verify(jobService).triggerActorPhotoScrape(listOf(1L, 2L))
    }

    @Test
    fun `批量补全缺省请求体按全量缺头像处理`() {
        `when`(jobService.triggerActorPhotoScrape(null)).thenReturn(7L)

        val resp = controller.scrapePhotos(null)

        assertEquals(202, resp.statusCode.value())
        assertEquals(7L, resp.body?.jobId)
        verify(jobService).triggerActorPhotoScrape(null)
    }

    @Test
    fun `同步 Emby 演员返回 202 与 jobId`() {
        `when`(jobService.triggerEmbyActorSync()).thenReturn(9L)

        val resp = controller.syncEmby()

        assertEquals(202, resp.statusCode.value())
        assertEquals(9L, resp.body?.jobId)
        verify(jobService).triggerEmbyActorSync()
    }

    // ---------------------------------------------------------------- Gfriends 索引

    @Test
    fun `Gfriends 状态返回最后同步时间与条目数`() {
        val at = Instant.parse("2026-11-23T10:15:30Z")
        `when`(gfriendsService.lastSyncAt()).thenReturn(at)
        `when`(gfriendsService.indexCount()).thenReturn(16_000L)

        val dto = gfriends.status()

        assertEquals(at.toString(), dto.lastSyncAt)
        assertEquals(16_000L, dto.indexCount)
    }

    @Test
    fun `Gfriends 从未同步时状态时间为空`() {
        `when`(gfriendsService.lastSyncAt()).thenReturn(null)
        `when`(gfriendsService.indexCount()).thenReturn(0L)

        val dto = gfriends.status()

        assertNull(dto.lastSyncAt)
        assertEquals(0L, dto.indexCount)
    }

    @Test
    fun `Gfriends 强制同步成功返回 success 与最新状态`() {
        val at = Instant.parse("2026-11-23T11:00:00Z")
        `when`(gfriendsService.syncIndex(force = true)).thenReturn(true)
        `when`(gfriendsService.lastSyncAt()).thenReturn(at)
        `when`(gfriendsService.indexCount()).thenReturn(100L)

        val dto = gfriends.sync()

        assertTrue(dto.success)
        assertNull(dto.message)
        assertEquals(at.toString(), dto.lastSyncAt)
        assertEquals(100L, dto.indexCount)
        verify(gfriendsService).syncIndex(force = true)
    }

    @Test
    fun `Gfriends 强制同步失败保留旧索引并说明原因`() {
        `when`(gfriendsService.syncIndex(force = true)).thenReturn(false)
        `when`(gfriendsService.lastSyncAt()).thenReturn(null)
        `when`(gfriendsService.indexCount()).thenReturn(99L)

        val dto = gfriends.sync()

        assertEquals(false, dto.success)
        assertTrue(dto.message!!.contains("保留旧索引"))
        assertEquals(99L, dto.indexCount, "失败时仍返回旧索引条目数")
    }

    @Test
    fun `Gfriends 首次同步异常收敛为失败结果而非 500`() {
        `when`(gfriendsService.syncIndex(force = true)).thenThrow(IllegalStateException("无法下载 Filetree.json"))
        `when`(gfriendsService.lastSyncAt()).thenReturn(null)
        `when`(gfriendsService.indexCount()).thenReturn(0L)

        val dto = gfriends.sync()

        assertEquals(false, dto.success)
        assertTrue(dto.message!!.contains("无法下载 Filetree.json"))
    }
}
