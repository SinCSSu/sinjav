package top.sincs.sinjav.job

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import top.sincs.sinjav.actor.ActorPhotoService
import top.sincs.sinjav.actor.EmbyActorSyncService
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.emby.EmbyClient
import top.sincs.sinjav.organize.OrganizeParams
import top.sincs.sinjav.organize.Organizer
import top.sincs.sinjav.scan.ScanParams
import top.sincs.sinjav.scan.ScanService
import top.sincs.sinjav.scraper.ScrapeParams
import top.sincs.sinjav.scraper.ScrapeService
import top.sincs.sinjav.domain.JobRepository

/**
 * 异步执行体（独立 Bean，避免 @Async 自调用失效）。
 * 所有方法 public，由 JobService 通过 Spring 代理调用。
 */
@Component
class JobRunner(
    private val jobs: JobRepository,
    private val scanService: ScanService,
    private val scrapeService: ScrapeService,
    private val organizer: Organizer,
    private val fileRecords: FileRecordRepository,
    private val emby: EmbyClient,
    private val jobControl: JobControl,
    private val actorPhotoService: ActorPhotoService,
    private val embyActorSyncService: EmbyActorSyncService,
) {

    private val log = LoggerFactory.getLogger(JobRunner::class.java)

    @Async("jobExecutor")
    open fun runScan(jobId: Long, params: ScanParams) = wrap(jobId) { scanService.execute(jobId, params) }

    @Async("jobExecutor")
    open fun runScrape(jobId: Long, params: ScrapeParams) = wrap(jobId) { scrapeService.execute(jobId, params) }

    @Async("jobExecutor")
    open fun runOrganize(jobId: Long, params: OrganizeParams) = wrap(jobId) {
        executeOrganize(jobId, params)
        jobs.finish(jobId, "completed")
        refreshEmby(jobId)
    }

    @Async("jobExecutor")
    open fun runPipeline(jobId: Long, params: PipelineParams) = wrap(jobId) {
        scanService.execute(jobId, params.scanParams())
        scrapeService.execute(jobId, params.scrapeParams())
        executeOrganize(jobId, params.organizeParams())
        jobs.finish(jobId, "completed")
        refreshEmby(jobId)
    }

    /**
     * 演员头像批量补全（批次 5）：任务状态由 [ActorPhotoService.scrapeAllMissing] 自行落库
     * （completed / failed / cancelled），这里只兜底未捕获异常。
     * [actorIds] 为空 → 服务层回退到全部缺头像的演员。
     */
    @Async("jobExecutor")
    open fun runActorPhotoBatch(jobId: Long, actorIds: List<Long>?) = wrap(jobId) {
        actorPhotoService.scrapeAllMissing(actorIds, jobId)
    }

    /** Emby 演员同步（批次 5）：任务状态同样由 [EmbyActorSyncService.syncFromEmby] 落库 */
    @Async("jobExecutor")
    open fun runEmbyActorSync(jobId: Long) = wrap(jobId) { embyActorSyncService.syncFromEmby(jobId) }

    /** 整理完成后触发 Emby 媒体库刷新（未配置 Emby 时跳过；刷新失败不影响整理结果） */
    private fun refreshEmby(jobId: Long) {
        if (!emby.isConfigured()) return
        try {
            emby.refreshLibrary()
            log.info("任务 {} 整理完成，已触发 Emby 媒体库刷新", jobId)
        } catch (e: Exception) {
            log.warn("任务 {} 触发 Emby 刷新失败（不影响整理结果）: {}", jobId, e.message)
            jobs.appendError(jobId, "Emby刷新", e.message ?: "unknown")
        }
    }

    private fun executeOrganize(jobId: Long, params: OrganizeParams) {
        log.info(
            "整理开始 jobId={} 目标目录={} 模式={} 模板={} 冲突策略={}",
            jobId,
            params.targetDir ?: "<未指定>", params.mode ?: "<未指定>",
            params.namingTemplate ?: "<全局设置>", params.conflictPolicy ?: "<全局设置>",
        )
        // 任务涉及的记录先标记为「整理中」，逐条处理完成后再落到最终状态；
        // finally 兜底复位（异常中断时避免记录永久卡在「整理中」）
        var marked: List<Long> = emptyList()
        try {
            if (!params.items.isNullOrEmpty()) {
                // 按文件记录逐条整理（支持覆盖目标路径/整理方式与已整理记录重整）
                marked = params.items.map { it.recordId }
                fileRecords.markOrganizing(marked)
                jobs.setTotalItems(jobId, params.items.size)
                var done = 0
                var failed = 0
                params.items.forEach { item ->
                    if (jobControl.isCancelled(jobId)) throw JobCancelledException()
                    val result = organizer.executeItem(item, params)
                    if (!result.ok && !result.skipped) {
                        failed++
                        jobs.appendError(jobId, "记录#${item.recordId}", result.message ?: "unknown")
                    }
                    done++
                    jobs.updateProgress(jobId, done, failed)
                }
                log.info("整理完成 jobId={} 共 {} 条记录，失败 {}", jobId, params.items.size, failed)
                return
            }
            val records = if (!params.numbers.isNullOrEmpty()) {
                params.numbers.flatMap { fileRecords.findByNumber(it) }
            } else {
                val all = mutableListOf<top.sincs.sinjav.domain.FileRecord>()
                var page = 1
                while (true) {
                    val p = fileRecords.list(page, 200, null, null, "pending")
                    if (p.items.isEmpty()) break
                    all += p.items
                    if (all.size >= p.total) break
                    page++
                }
                all
            }
            val filtered = records.filter { it.recognizeStatus == "recognized" && it.organizeStatus == "pending" }
            marked = filtered.map { it.id }
            fileRecords.markOrganizing(marked)
            jobs.setTotalItems(jobId, filtered.size)
            var done = 0
            var failed = 0
            filtered.forEach { record ->
                if (jobControl.isCancelled(jobId)) throw JobCancelledException()
                val result = organizer.executeOne(record, params)
                if (!result.ok && !result.skipped) {
                    failed++
                    jobs.appendError(jobId, record.sourcePath, result.message ?: "unknown")
                }
                done++
                jobs.updateProgress(jobId, done, failed)
            }
            log.info("整理完成 jobId={} 共 {} 个文件，失败 {}", jobId, filtered.size, failed)
        } finally {
            fileRecords.resetOrganizingToPending(marked)
        }
    }

    private inline fun wrap(jobId: Long, body: () -> Unit) {
        try {
            body()
        } catch (e: JobCancelledException) {
            log.info("任务 {} 已被用户停止", jobId)
            jobs.finish(jobId, "cancelled")
        } catch (e: Exception) {
            log.error("任务 {} 执行异常", jobId, e)
            jobs.appendError(jobId, "job", e.message ?: "unknown")
            jobs.finish(jobId, "failed")
        } finally {
            jobControl.clear(jobId)
        }
    }
}
