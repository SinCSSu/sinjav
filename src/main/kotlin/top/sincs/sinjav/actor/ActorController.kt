package top.sincs.sinjav.actor

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.sincs.sinjav.api.dto.ActorDetailDto
import top.sincs.sinjav.api.dto.ActorJobAccepted
import top.sincs.sinjav.api.dto.ActorListDto
import top.sincs.sinjav.api.dto.GfriendsStatusDto
import top.sincs.sinjav.api.dto.GfriendsSyncResultDto
import top.sincs.sinjav.api.dto.PageDto
import top.sincs.sinjav.api.dto.ScrapePhotosRequest
import top.sincs.sinjav.api.dto.UpdateActorDto
import top.sincs.sinjav.job.JobService

/**
 * 演员管理接口（批次 5）：列表/详情/别名编辑 + 头像刮削（单个同步、批量异步）+ Emby 演员同步。
 *
 * 返回类型约定：分页统一用项目既有的 [PageDto]（Spring Data `Page` 不在本项目依赖内，
 * 且前端 `request()` 已按 `PageDto{page,size,total,totalPages,items}` 解析）。
 *
 * 任务类端点（批量补全 / Emby 同步）返回 202 + jobId，进度复用 `GET /api/v1/jobs/{jobId}`
 * 与 `POST /api/v1/jobs/{jobId}/stop`（协作式取消由服务层自行检查）。
 */
@RestController
@RequestMapping("/api/v1/actors")
class ActorController(
    private val actressService: ActressService,
    private val actorPhotoService: ActorPhotoService,
    private val jobService: JobService,
) {

    /**
     * 分页列表。
     *
     * @param page 页码（从 1 开始，非法值归 1）
     * @param size 每页条数（1..200，超界夹紧）
     * @param search 关键字：匹配 name / alias / 别名表（actress_aliases），空则不过滤
     * @param hasPhoto 头像三态过滤：true 有头像、false 无头像、缺省（null）不过滤
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) hasPhoto: Boolean?,
    ): PageDto<ActorListDto> {
        val pg = page.coerceAtLeast(1)
        val sz = size.coerceIn(1, MAX_PAGE_SIZE)
        val p = actressService.listPaged(pg, sz, search?.trim()?.takeIf { it.isNotEmpty() }, hasPhoto)
        return PageDto.of(pg, sz, p.total, p.items.map { ActorListDto.of(it) })
    }

    /** 详情：演员信息 + 别名列表 + 所属影片（movie_actresses JOIN movies，按影片 id 倒序） */
    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): ActorDetailDto {
        val (detail, aliases) = actressService.getDetail(id)
        return ActorDetailDto.of(detail, aliases)
    }

    /**
     * 编辑别名：更新 `actresses.alias`（null 不改 / 空串清空）+ 全量替换 `actress_aliases`。
     * 返回编辑后的详情（含实际落库的别名列表，便于前端回显）。
     */
    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody req: UpdateActorDto): ActorDetailDto {
        actressService.updateAliases(id, req.alias, req.aliases)
        val (detail, aliases) = actressService.getDetail(id)
        return ActorDetailDto.of(detail, aliases)
    }

    /**
     * 单个补全头像（同步）：命中 Gfriends 并下载成功返回 `SUCCESS`，未命中 `NOT_FOUND`，
     * 已有照片 `SKIPPED`，下载/校验失败 `DOWNLOAD_FAILED`（失败不抛异常，便于前端标注哪一类）。
     *
     * @param force true = 忽略本地已有文件强制重新下载（前端「重新抓取头像」即用该模式；
     *   规范名改过之后重抓才能让头像文件名跟随新规范名，旧文件由服务层清理）
     */
    @PostMapping("/{id}/scrape-photo")
    fun scrapePhoto(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") force: Boolean = false,
    ): ScrapeResult = actorPhotoService.scrapePhoto(id, force)

    /**
     * 批量补全头像（异步任务）：请求体 `{actorIds?}` 为空/缺省 → 全部缺头像的演员。
     * 返回 202 + jobId；同类型任务已在运行时抛 409（JobConflictException）。
     */
    @PostMapping("/scrape-photos")
    fun scrapePhotos(@RequestBody(required = false) req: ScrapePhotosRequest?): ResponseEntity<ActorJobAccepted> {
        val jobId = jobService.triggerActorPhotoScrape(req?.actorIds)
        return ResponseEntity.accepted().body(ActorJobAccepted(jobId))
    }

    /** 同步 Emby 演员（异步任务）：遍历 emby_items 快照拉条目演员并入本地演员库，返回 202 + jobId */
    @PostMapping("/sync-emby")
    fun syncEmby(): ResponseEntity<ActorJobAccepted> =
        ResponseEntity.accepted().body(ActorJobAccepted(jobService.triggerEmbyActorSync()))

    private companion object {
        /** 分页上限（与 /movies、/file-records 等列表接口一致） */
        const val MAX_PAGE_SIZE = 200
    }
}

/**
 * Gfriends 头像库索引管理（状态 / 强制同步）。
 *
 * 同时注册 `/api/v1/gfriends` 与 `/api/v1/actors/gfriends` 两个前缀：设计文档写前者、
 * 批次任务书写「前缀 /api/v1/actors」，双写避免前端返工。
 */
@RestController
@RequestMapping(value = ["/api/v1/gfriends", "/api/v1/actors/gfriends"])
class GfriendsController(private val gfriends: GfriendsService) {

    private val log = LoggerFactory.getLogger(GfriendsController::class.java)

    /** 索引状态：最后同步时间（null = 从未成功同步）+ 条目数 */
    @GetMapping("/status")
    fun status(): GfriendsStatusDto = GfriendsStatusDto.of(gfriends.lastSyncAt(), gfriends.indexCount())

    /**
     * 强制同步索引（同步执行，可能耗时数十秒）：`syncIndex(force = true)`。
     * 失败不抛 500——返回 `success=false` + 原因，旧索引仍可继续用（首次同步失败时索引为空，
     * `syncIndex` 内部抛 IllegalStateException，这里同样收敛为 success=false）。
     */
    @PostMapping("/sync")
    fun sync(): GfriendsSyncResultDto {
        val message = try {
            if (gfriends.syncIndex(force = true)) null else "索引同步失败（下载或解析异常），已保留旧索引"
        } catch (e: Exception) {
            log.warn("Gfriends 索引强制同步失败: {}", e.message)
            "索引同步失败：${e.message ?: "未知错误"}"
        }
        if (message != null) log.warn("Gfriends 索引同步未成功: {}", message)
        return GfriendsSyncResultDto(
            success = message == null,
            message = message,
            lastSyncAt = gfriends.lastSyncAt()?.toString(),
            indexCount = gfriends.indexCount(),
        )
    }
}
