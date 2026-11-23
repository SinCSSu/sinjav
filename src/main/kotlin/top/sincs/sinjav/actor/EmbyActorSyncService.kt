package top.sincs.sinjav.actor

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.api.dto.EmbyActor
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.EmbyItemRepository
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.emby.EmbyClient
import top.sincs.sinjav.emby.EmbyException
import top.sincs.sinjav.job.JobControl

/**
 * Emby 演员同步（批次 4）：把 Emby 侧「已识别出番号」的条目演员并入本地 `actresses` 表。
 *
 * 数据流：
 * `emby_items`（Emby 媒体库同步快照，含番号）→ 逐条目 `GET /Users/{uid}/Items/{id}` 取 `People`
 * → 名字经 [ActressService.normalize] 归一化（别名表收敛到规范名）→ 插入/更新 `actresses`
 * （顺带回写 `emby_person_id`，供头像推送使用）。
 *
 * 边界约定：
 * - 只扫 `emby_items` 里已识别番号的条目，不做 `/Persons` 全量拉取——避免把非 AV 库演员混进来；
 * - 同一 Person 在多部影片里重复出现时只落库一次（按 Person Id 去重）；
 * - 单个条目拉取失败只计入任务失败明细，不中断整轮同步；
 * - 任务可协作式取消（[JobControl] 内存停表 + jobs.status 双判），取消时已同步的演员保留；
 * - 同步完成后，本次**新建**的演员交给 [ActorPhotoService.scrapePhotosAsync] 异步补头像
 *   （批次 8 收尾：与批次刮削 / Emby 重刮同一套自动补全语义，不阻塞本任务落 completed）。
 */
@Service
class EmbyActorSyncService(
    private val emby: EmbyClient,
    private val embyItems: EmbyItemRepository,
    private val actressService: ActressService,
    private val actressRepository: ActressRepository,
    private val jobs: JobRepository,
    private val jobControl: JobControl,
    private val actorPhotoService: ActorPhotoService,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(EmbyActorSyncService::class.java)

    /** 同步结果：Emby 未配置时调用方应提示用户先配置，而不是当成同步失败 */
    sealed interface SyncResult {
        /** 同步完成（或取消后提前结束）：[new] 新建演员数，[updated] 回写 Emby Person Id 的演员数 */
        data class SUCCESS(val new: Int, val updated: Int) : SyncResult

        /** Emby 服务地址 / API Key 未配置，未做任何同步 */
        data object NOT_CONFIGURED : SyncResult
    }

    /** 单个演员的落库结论（仅用于内部计数与日志） */
    private enum class Outcome { NEW, UPDATED, SKIPPED }

    /**
     * 同步执行（由任务调度方提供 jobId，进度写入 jobs 表）。
     *
     * @return [SyncResult.SUCCESS] 完成（含中途取消，任务已落 cancelled）；[SyncResult.NOT_CONFIGURED] Emby 未配置
     */
    fun syncFromEmby(jobId: Long): SyncResult {
        if (!emby.isConfigured()) {
            log.warn("Emby 未配置，跳过演员同步 jobId={}", jobId)
            jobs.appendError(jobId, "emby", "未配置 Emby 服务地址或 API Key")
            jobs.finish(jobId, STATUS_FAILED)
            return SyncResult.NOT_CONFIGURED
        }

        val items = embyItems.findAll()
        jobs.setTotalItems(jobId, items.size)
        log.info("Emby 演员同步开始 jobId={} 条目数={}", jobId, items.size)

        // 同一 Person 在多部影片里重复出现：按 Person Id 去重，只落库一次
        val seenPersons = HashSet<String>()
        // 本次新建的演员 id（收尾触发头像自动补全用）
        val newActressIds = LinkedHashSet<Long>()
        var done = 0
        var failed = 0
        var newCount = 0
        var updatedCount = 0

        for (item in items) {
            if (isCancelled(jobId)) {
                log.info("Emby 演员同步已取消 jobId={}，已处理 {}/{}", jobId, done, items.size)
                jobs.finish(jobId, STATUS_CANCELLED)
                return SyncResult.SUCCESS(new = newCount, updated = updatedCount)
            }

            var itemFailed = false
            val actors = runCatching { emby.getItem(item.itemId).actors }
                .getOrElse { e ->
                    // 快照可能已过期：条目被 Emby 删掉时是 404，按「无演员」跳过而不是任务失败
                    if (e is EmbyException && e.status == 404) {
                        log.debug("条目已不在 Emby，跳过 itemId={} number={}", item.itemId, item.number)
                    } else {
                        itemFailed = true
                        log.warn("条目演员拉取失败 itemId={} number={}: {}", item.itemId, item.number, e.message)
                        jobs.appendError(jobId, item.number, e.message ?: "演员拉取失败")
                    }
                    emptyList()
                }
            if (itemFailed) failed++

            var itemNew = 0
            var itemUpdated = 0
            for (actor in actors) {
                if (!seenPersons.add(actor.id)) continue
                when (upsert(actor, newActressIds)) {
                    Outcome.NEW -> { newCount++; itemNew++ }
                    Outcome.UPDATED -> { updatedCount++; itemUpdated++ }
                    Outcome.SKIPPED -> Unit
                }
            }

            done++
            jobs.updateProgress(jobId, done, failed)
            log.info(
                "[{}/{}] {}: {} 位演员（新增 {} / 更新 {}）",
                done, items.size, item.number, actors.size, itemNew, itemUpdated,
            )
        }

        jobs.finish(jobId, STATUS_COMPLETED)
        log.info(
            "Emby 演员同步完成 jobId={} 条目={} 新增演员={} 回写PersonId={} 失败条目={}",
            jobId, items.size, newCount, updatedCount, failed,
        )
        triggerActorPhotoScrape(jobId, newActressIds)
        return SyncResult.SUCCESS(new = newCount, updated = updatedCount)
    }

    /**
     * 收尾（批次 8）：本次同步**新建**的演员交给头像自动补全。
     *
     * 与批次刮削（ScrapeService）/Emby 重刮（EmbyRescrapeService）同一套语义：
     * `scrapePhotosAsync` 建好 [top.sincs.sinjav.job.JobType.ACTOR_PHOTO_AUTO] 任务后立刻返回，
     * 抓取在任务线程池里跑，绝不阻塞本任务（同步结果不受头像抓取成败影响）。
     *
     * - 无新演员 → 什么都不做；
     * - `sinjav.actors.auto-scrape-after-batch=false` → 跳过并记日志（新演员留待演员页手动补全）；
     * - 触发失败只记 WARN。
     */
    private fun triggerActorPhotoScrape(jobId: Long, newActressIds: Collection<Long>) {
        if (newActressIds.isEmpty()) return
        if (!props.actors.autoScrapeAfterBatch) {
            log.info(
                "Emby 演员同步完成，{} 位新演员头像补全已跳过（sinjav.actors.auto-scrape-after-batch=false，任务 jobId={}）",
                newActressIds.size, jobId,
            )
            return
        }
        val photoJobId = runCatching {
            actorPhotoService.scrapePhotosAsync(newActressIds.toList(), AUTO_PHOTO_REASON)
        }.onFailure {
            log.warn("新演员头像补全触发失败（不影响同步结果）jobId={}: {}", jobId, it.message, it)
        }.getOrNull()
        if (photoJobId != null) {
            log.info("Emby 演员同步完成，触发 {} 位新演员头像补全（任务 jobId={}）", newActressIds.size, photoJobId)
        } else {
            log.info("Emby 演员同步完成，{} 位新演员均已有头像，无需补全", newActressIds.size)
        }
    }

    /**
     * 单个 Emby 演员 → 本地演员：
     * 名字归一化（别名表 → 规范名）→ 按规范名查重（alias 列也算）→ 不存在则新建、存在且 Person Id 不同则回写。
     *
     * @param newActressIds 新建演员时把 id 收进该集合（供收尾触发头像补全）
     */
    private fun upsert(actor: EmbyActor, newActressIds: MutableSet<Long>): Outcome {
        val name = actressService.normalize(actor.name)
        if (name.isBlank()) return Outcome.SKIPPED

        val existing = actressRepository.findByCanonicalName(name)
        if (existing == null) {
            val newId = actressRepository.insertIfAbsent(name, actor.id)
            if (newId != null) {
                newActressIds += newId
                return Outcome.NEW
            }
            // 竞态兜底：插入被 name 唯一约束挡下（同名行刚被其他入口写入），退回更新分支
            val again = actressRepository.findByCanonicalName(name) ?: return Outcome.SKIPPED
            return updatePersonId(again, actor)
        }
        return updatePersonId(existing, actor)
    }

    private fun updatePersonId(existing: ActressEntity, actor: EmbyActor): Outcome {
        if (existing.embyPersonId == actor.id) return Outcome.SKIPPED
        actressRepository.updateEmbyPersonId(existing.id, actor.id)
        return Outcome.UPDATED
    }

    /** 是否已取消：内存态停止请求（已点停止、尚未落库）或已落库的 cancelled 状态 */
    private fun isCancelled(jobId: Long): Boolean = jobControl.isCancelled(jobId) || jobs.isCancelled(jobId)

    private companion object {
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        /** 自动补全任务参数里的触发原因（任务列表回溯用） */
        const val AUTO_PHOTO_REASON = "Emby 演员同步自动补全"
    }
}
