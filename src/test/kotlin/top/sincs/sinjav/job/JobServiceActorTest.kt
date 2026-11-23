package top.sincs.sinjav.job

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import top.sincs.sinjav.domain.Job
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.scan.ScanService
import top.sincs.sinjav.scraper.ScrapeService
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * 演员任务编排（批次 5）：批量补全 / Emby 演员同步要「创建任务 + 返回 jobId + 异步分发」，
 * 并在同类型任务已在运行时抛 [JobConflictException]（与既有 scan/scrape/organize 一致）。
 */
class JobServiceActorTest {

    private val jobs = mock(JobRepository::class.java)
    private val runner = mock(JobRunner::class.java)
    private val service = JobService(
        jobs = jobs,
        runner = runner,
        scanService = mock(ScanService::class.java),
        scrapeService = mock(ScrapeService::class.java),
        jobControl = mock(JobControl::class.java),
        objectMapper = ObjectMapper(),
    )

    private fun runningJob(id: Long, type: String) = Job(
        id = id, type = type, status = "running", params = null, totalItems = 0, doneItems = 0,
        failItems = 0, errorDetail = null, startedAt = Instant.now(), finishedAt = null,
    )

    @Test
    fun `批量补全创建 ACTOR_PHOTO_BATCH 任务并异步分发`() {
        `when`(jobs.existsRunning("actor_photo_batch")).thenReturn(false)
        `when`(jobs.create("actor_photo_batch", "[1,2]", 0)).thenReturn(runningJob(42, "actor_photo_batch"))

        val jobId = service.triggerActorPhotoScrape(listOf(1L, 2L))

        assertEquals(42L, jobId)
        verify(jobs).create("actor_photo_batch", "[1,2]", 0)
        verify(runner).runActorPhotoBatch(42L, listOf(1L, 2L))
    }

    @Test
    fun `批量补全剔除非法 id 与重复项`() {
        `when`(jobs.existsRunning("actor_photo_batch")).thenReturn(false)
        `when`(jobs.create("actor_photo_batch", "[3]", 0)).thenReturn(runningJob(43, "actor_photo_batch"))

        val jobId = service.triggerActorPhotoScrape(listOf(0L, -1L, 3L, 3L))

        assertEquals(43L, jobId)
        verify(runner).runActorPhotoBatch(43L, listOf(3L))
    }

    @Test
    fun `批量补全未指定演员时按全量缺头像分发`() {
        `when`(jobs.existsRunning("actor_photo_batch")).thenReturn(false)
        `when`(jobs.create("actor_photo_batch", "null", 0)).thenReturn(runningJob(44, "actor_photo_batch"))

        val jobId = service.triggerActorPhotoScrape(null)

        assertEquals(44L, jobId)
        verify(runner).runActorPhotoBatch(44L, null)
    }

    @Test
    fun `批量补全空集合也按全量处理`() {
        `when`(jobs.existsRunning("actor_photo_batch")).thenReturn(false)
        `when`(jobs.create("actor_photo_batch", "null", 0)).thenReturn(runningJob(45, "actor_photo_batch"))

        assertEquals(45L, service.triggerActorPhotoScrape(emptyList()))
        verify(runner).runActorPhotoBatch(45L, null)
    }

    @Test
    fun `批量补全同类型任务在运行时抛冲突且不分发`() {
        `when`(jobs.existsRunning("actor_photo_batch")).thenReturn(true)

        assertThrows<JobConflictException> { service.triggerActorPhotoScrape(listOf(1L)) }
        verifyNoInteractions(runner)
    }

    @Test
    fun `Emby 同步创建 EMBY_ACTOR_SYNC 任务并异步分发`() {
        `when`(jobs.existsRunning("emby_actor_sync")).thenReturn(false)
        `when`(jobs.create("emby_actor_sync", "{}", 0)).thenReturn(runningJob(7, "emby_actor_sync"))

        val jobId = service.triggerEmbyActorSync()

        assertEquals(7L, jobId)
        verify(jobs).create("emby_actor_sync", "{}", 0)
        verify(runner).runEmbyActorSync(7L)
    }

    @Test
    fun `Emby 同步同类型任务在运行时抛冲突`() {
        `when`(jobs.existsRunning("emby_actor_sync")).thenReturn(true)

        assertThrows<JobConflictException> { service.triggerEmbyActorSync() }
        verifyNoInteractions(runner)
    }
}
