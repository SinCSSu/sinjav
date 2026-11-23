package top.sincs.sinjav.job

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.domain.Job
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.organize.OrganizeParams
import top.sincs.sinjav.scan.ScanParams
import top.sincs.sinjav.scan.ScanService
import top.sincs.sinjav.scraper.ScrapeParams
import top.sincs.sinjav.scraper.ScrapeService
import tools.jackson.databind.ObjectMapper

class JobConflictException(message: String) : Exception(message)

/**
 * 任务编排入口：创建任务记录并分发到线程池执行。
 * 同类型任务同时只允许一个运行，避免对同一目录/站点的并发冲突。
 */
@Service
class JobService(
    private val jobs: JobRepository,
    private val runner: JobRunner,
    private val scanService: ScanService,
    private val scrapeService: ScrapeService,
    private val jobControl: JobControl,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(JobService::class.java)

    fun triggerScan(params: ScanParams): Job {
        params.sourceDirs.ifEmpty { throw IllegalArgumentException("sourceDirs 不能为空") }
        return createAndDispatch(JobType.SCAN, params) { id, p -> runner.runScan(id, p) }
    }

    fun triggerScrape(params: ScrapeParams): Job =
        createAndDispatch(JobType.SCRAPE, params) { id, p -> runner.runScrape(id, p) }

    fun triggerOrganize(params: OrganizeParams): Job =
        createAndDispatch(JobType.ORGANIZE, params) { id, p -> runner.runOrganize(id, p) }

    /** 组合任务：扫描 → 刮削 → 整理 */
    fun triggerPipeline(params: PipelineParams): Job {
        if (params.scan?.sourceDirs.isNullOrEmpty()) throw IllegalArgumentException("pipeline 需提供 scan.sourceDirs")
        return createAndDispatch(JobType.PIPELINE, params) { id, p -> runner.runPipeline(id, p) }
    }

    // ---------------------------------------------------------------- 演员管理（批次 5）

    /**
     * 演员头像批量补全：创建 [JobType.ACTOR_PHOTO_BATCH] 任务并异步执行
     * （[top.sincs.sinjav.actor.ActorPhotoService.scrapeAllMissing]）。
     *
     * [actorIds] 为空 → 服务层回退到「全部缺头像的演员」；非法 id（<= 0）与重复项在这里剔除，
     * 保证任务参数与进度总数一致。
     *
     * @return jobId（进度/停止复用 /jobs/{id}）
     * @throws JobConflictException 已有同类型任务在运行
     */
    fun triggerActorPhotoScrape(actorIds: List<Long>?): Long {
        val ids = actorIds?.filter { it > 0 }?.distinct()?.takeIf { it.isNotEmpty() }
        return createAndDispatch(JobType.ACTOR_PHOTO_BATCH, ids) { id, p -> runner.runActorPhotoBatch(id, p) }.id
    }

    /**
     * Emby 演员同步：创建 [JobType.EMBY_ACTOR_SYNC] 任务并异步执行
     * （[top.sincs.sinjav.actor.EmbyActorSyncService.syncFromEmby]，Emby 未配置时任务落 failed 并记明细）。
     *
     * @return jobId
     * @throws JobConflictException 已有同类型任务在运行
     */
    fun triggerEmbyActorSync(): Long =
        createAndDispatch(JobType.EMBY_ACTOR_SYNC, emptyMap<String, Any?>()) { id, _ -> runner.runEmbyActorSync(id) }.id

    /**
     * 任务是否已被取消：内存态的停止请求（已点停止、执行侧尚未落库）或已落库的 cancelled 状态。
     * 执行侧（如 ActorPhotoService.scrapeAllMissing）在每个检查点调用它实现协作式取消。
     */
    fun isCancelled(jobId: Long): Boolean = jobControl.isCancelled(jobId) || jobs.isCancelled(jobId)

    /**
     * 停止运行中的任务（协作式取消）：执行侧在处理间隙检查标记，命中即落库 cancelled。
     * 当前正在执行的条目会执行完，不会中途强杀线程。
     */
    fun stop(jobId: Long): Job {
        val job = jobs.findById(jobId) ?: throw NoSuchElementException("任务不存在: $jobId")
        if (job.status != "running") throw IllegalStateException("任务 #${job.id} 不在运行中，无法停止")
        jobControl.requestStop(jobId)
        return job
    }

    private inline fun <T> createAndDispatch(type: JobType, params: T, dispatch: (Long, T) -> Unit): Job {
        if (jobs.existsRunning(type.code)) throw JobConflictException("已有 ${type.code} 任务在运行，请等待其完成")
        val json = runCatching { objectMapper.writeValueAsString(params) }.getOrNull()
        val job = jobs.create(type.code, json, 0)
        log.info("任务触发 type={} jobId={} params={}", type.code, job.id, json ?: "<参数序列化失败>")
        dispatch(job.id, params)
        return job
    }
}

data class PipelineParams(
    val scan: ScanParams? = null,
    val scrape: ScrapeParams? = null,
    val organize: OrganizeParams? = null,
) {
    fun scanParams(): ScanParams = scan ?: throw IllegalArgumentException("pipeline 缺少 scan 参数")
    fun scrapeParams(): ScrapeParams = scrape ?: ScrapeParams()
    fun organizeParams(): OrganizeParams = organize ?: OrganizeParams()
}
