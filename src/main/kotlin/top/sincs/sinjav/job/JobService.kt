package top.sincs.sinjav.job

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
    private val objectMapper: ObjectMapper,
) {

    fun triggerScan(params: ScanParams): Job {
        params.sourceDirs.ifEmpty { throw IllegalArgumentException("sourceDirs 不能为空") }
        return createAndDispatch("scan", params) { id, p -> runner.runScan(id, p) }
    }

    fun triggerScrape(params: ScrapeParams): Job = createAndDispatch("scrape", params) { id, p -> runner.runScrape(id, p) }

    fun triggerOrganize(params: OrganizeParams): Job = createAndDispatch("organize", params) { id, p -> runner.runOrganize(id, p) }

    /** 组合任务：扫描 → 刮削 → 整理 */
    fun triggerPipeline(params: PipelineParams): Job {
        if (params.scan?.sourceDirs.isNullOrEmpty()) throw IllegalArgumentException("pipeline 需提供 scan.sourceDirs")
        return createAndDispatch("pipeline", params) { id, p -> runner.runPipeline(id, p) }
    }

    private inline fun <T> createAndDispatch(type: String, params: T, dispatch: (Long, T) -> Unit): Job {
        if (jobs.existsRunning(type)) throw JobConflictException("已有 $type 任务在运行，请等待其完成")
        val json = runCatching { objectMapper.writeValueAsString(params) }.getOrNull()
        val job = jobs.create(type, json, 0)
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
