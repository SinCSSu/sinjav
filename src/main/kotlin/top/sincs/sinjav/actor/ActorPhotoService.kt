package top.sincs.sinjav.actor

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.emby.EmbyClient
import top.sincs.sinjav.job.JobControl
import top.sincs.sinjav.job.JobType
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

/** 单个演员的头像抓取结果 */
enum class ScrapeResult {
    /** 命中并下载成功，已回写 photo_path */
    SUCCESS,

    /** 已有照片且文件仍在（force=true 才会重下） */
    SKIPPED,

    /** Gfriends 索引里没有该演员（含别名逐一尝试） */
    NOT_FOUND,

    /** 命中但下载失败 / valid_pic 校验不通过 */
    DOWNLOAD_FAILED,
    ;

    /** 中文描述（进度日志与任务失败明细用） */
    val label: String
        get() = when (this) {
            SUCCESS -> "下载成功"
            SKIPPED -> "已存在，跳过"
            NOT_FOUND -> "未找到"
            DOWNLOAD_FAILED -> "下载失败"
        }
}

/**
 * 演员头像抓取（Gfriends → 本地 data/actors）。
 *
 * 约定：
 * - 规范名 = `actress.alias ?: actress.name`（[ActressService.canonicalName]），同时用作文件名；
 * - `photo_path` 只存**相对路径**（`actors/{规范名}.jpg`），便于整体迁移 data 目录；
 * - 抓取结果落本地 + 数据库；`sinjav.actors.push-to-emby=true` 且演员已缓存 `emby_person_id` 时
 *   顺带推送到 Emby Person 主图（尽力而为，失败只记日志）。
 *
 * 批量补全（[scrapeAllMissing]）自带进度上报与限速：逐个演员间隔 `sinjav.actors.scrape-interval-ms`
 * （默认 1.5s，对齐 MDCx 的 `scrap_interval`），每 5 个检查一次取消标记（协作式取消）。
 */
@Service
class ActorPhotoService(
    private val actressService: ActressService,
    private val actressRepository: ActressRepository,
    private val gfriends: GfriendsService,
    private val jobs: JobRepository,
    private val jobControl: JobControl,
    private val props: AppProperties,
    private val objectMapper: ObjectMapper,
    private val emby: EmbyClient,
    /** 自动补全的派发线程池（与任务执行同一个池）：建好任务后立刻返回，抓取在池里跑 */
    @Qualifier("jobExecutor")
    private val autoScrapeExecutor: Executor,
) {

    private val log = LoggerFactory.getLogger(ActorPhotoService::class.java)

    /**
     * 限速休眠（测试接缝：单测替换成记录器即可断言调用次数与间隔，无需真实等待）。
     * 生产实现就是 [Thread.sleep]——批量任务是后台线程，阻塞无副作用。
     */
    internal var sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }

    // ---------------------------------------------------------------- 单个演员

    /**
     * 抓取单个演员头像（同步）：
     * 查演员 + 别名 → 已有照片且文件存在则 [ScrapeResult.SKIPPED]（[force] 覆盖）
     * → 本地已有手工放置的 `{规范名}.jpg` 则直接登记为 [ScrapeResult.SUCCESS]（不覆盖用户的图）
     * → Gfriends 查 URL（主名优先，别名兜底）→ 下载落盘 → 回写 photo_path/updated_at。
     *
     * 下载失败不抛异常（返回 [ScrapeResult.DOWNLOAD_FAILED]），避免批量任务被单个演员中断。
     *
     * @param force true = 忽略本地已有文件强制重新下载（用于「换一张头像」）
     * @throws NoSuchElementException 演员不存在
     */
    fun scrapePhoto(actressId: Long, force: Boolean = false): ScrapeResult {
        val (actress, aliases) = actressService.getActressWithAliases(actressId)
        val canonical = actressService.canonicalName(actress)
        val existing = actress.photoPath?.takeIf { it.isNotBlank() }
        val relative = relativePhotoPath(canonical, actressId)

        // 已有照片且文件仍在：默认跳过（force 强制重下，如规范名/别名变更后）
        if (!force && existing != null && runCatching { Files.isRegularFile(resolvePhoto(existing)) }.getOrDefault(false)) {
            log.debug("演员 {} 已有头像 {}，跳过", canonical, existing)
            return ScrapeResult.SKIPPED
        }

        // 手工放置的头像（用户把图丢进 data/actors/{规范名}.jpg）：只登记 photo_path，不访问网络也不覆盖用户的图。
        // 该分支**不受 force 影响**——photo_path 为空时若不登记，手工放的文件永远不会被识别；
        // 想换成 Gfriends 的图，删掉该文件后再重抓即可（此时文件不存在，正常走下载）。
        if (existing == null && runCatching { Files.isRegularFile(resolvePhoto(relative)) }.getOrDefault(false)) {
            actressRepository.updatePhotoPath(actressId, relative)
            log.info("演员 {} 采用手工放置的本地头像 {}", canonical, relative)
            return ScrapeResult.SUCCESS
        }

        val url = gfriends.findPhoto(canonical, aliases) ?: run {
            log.info("演员 {} 未在 Gfriends 命中（别名={}）", canonical, aliases)
            return ScrapeResult.NOT_FOUND
        }

        val target = resolvePhoto(relative)
        val ok = runCatching { gfriends.downloadPhoto(url, target) }
            .onFailure { log.warn("演员 {} 头像下载异常 {}: {}", canonical, url, it.message) }
            .getOrDefault(false)
        if (!ok) return ScrapeResult.DOWNLOAD_FAILED

        actressRepository.updatePhotoPath(actressId, relative)
        // 规范名变更导致文件名变化时清掉旧文件，避免 data/actors 里堆积孤儿照片
        if (existing != null && existing != relative) {
            runCatching { Files.deleteIfExists(resolvePhoto(existing)) }
                .onFailure { log.debug("旧头像删除失败 {}: {}", existing, it.message) }
        }
        log.info("演员 {} 头像已保存 {} ← {}", canonical, relative, url)
        pushToEmby(actress, target, canonical)
        return ScrapeResult.SUCCESS
    }

    /**
     * 把刚落盘的头像推送到 Emby Person 主图（批次 4）。
     *
     * 触发条件：`sinjav.actors.push-to-emby=true` 且演员已缓存 `emby_person_id`
     * （未同步过 Emby 演员的本地演员没有该值，静默跳过）。
     * Emby 未配置 / HTTP 失败 / 图片格式不支持都只记日志：头像已落本地，
     * 推送属于尽力而为，不能把抓取结果改成失败。
     */
    private fun pushToEmby(actress: ActressEntity, file: Path, canonical: String) {
        if (!props.actors.pushToEmby) return
        val personId = actress.embyPersonId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!emby.isConfigured()) {
            log.debug("Emby 未配置，跳过演员 {} 头像推送（本地已保存）", canonical)
            return
        }
        runCatching { emby.uploadActorPhoto(personId, Files.readAllBytes(file)) }
            .onSuccess { log.info("演员 {} 头像已推送 Emby personId={}", canonical, personId) }
            .onFailure { log.warn("演员 {} 头像推送 Emby 失败（本地已保存）personId={}: {}", canonical, personId, it.message) }
    }

    // ---------------------------------------------------------------- 批量补全

    /**
     * 批量补全缺失头像（同步，由任务调度方提供 jobId）。
     *
     * - [actressIds] 为空 → 取全部 `photo_path IS NULL/空` 的演员；
     * - 每个演员一项进度（`jobs.updateProgress`），日志形如 `[1/10] 夢乃あいか: 下载成功`；
     * - 逐个间隔 `scrapeIntervalMs`；每 [CANCEL_CHECK_INTERVAL] 个检查一次取消（首个处理前也检查，
     *   覆盖「刚提交就取消」的场景），命中则提前退出并把任务落库为 cancelled；
     * - 正常结束落 completed，未捕获异常落 failed（异常本身不再上抛，任务结果已记录）。
     */
    fun scrapeAllMissing(actressIds: List<Long>? = null, jobId: Long) {
        val ids = actressIds?.takeIf { it.isNotEmpty() } ?: actressService.idsMissingPhoto()
        // 目录不存在时自动创建（downloadPhoto 落盘前也会补建，这里提前确保批量任务起手即有目录）
        runCatching { Files.createDirectories(Path.of(props.actors.photoDir)) }
            .onFailure { log.warn("创建演员头像目录失败 {}: {}", props.actors.photoDir, it.message) }
        jobs.setTotalItems(jobId, ids.size)
        log.info("演员头像补全开始 jobId={} 演员数={}", jobId, ids.size)

        var done = 0
        var succeeded = 0
        var skipped = 0
        var failed = 0
        try {
            for ((index, actressId) in ids.withIndex()) {
                if (done % CANCEL_CHECK_INTERVAL == 0 && isCancelled(jobId)) {
                    log.info("演员头像补全已取消 jobId={}，已处理 {}/{}", jobId, done, ids.size)
                    jobs.finish(jobId, STATUS_CANCELLED)
                    return
                }
                // 限速：首个演员不等待，之后每个之前等一个间隔
                if (index > 0) throttle()

                val (name, result) = scrapeOneLogged(actressId)
                done++
                when (result) {
                    ScrapeResult.SUCCESS -> succeeded++
                    ScrapeResult.SKIPPED -> skipped++
                    ScrapeResult.NOT_FOUND, ScrapeResult.DOWNLOAD_FAILED -> {
                        failed++
                        jobs.appendError(jobId, name, result.label)
                    }
                }
                jobs.updateProgress(jobId, done, failed)
                log.info("[{}/{}] {}: {}", done, ids.size, name, result.label)
            }
            jobs.finish(jobId, STATUS_COMPLETED)
            log.info(
                "演员头像补全完成 jobId={} 总数={} 成功={} 跳过={} 失败={}",
                jobId, ids.size, succeeded, skipped, failed,
            )
        } catch (e: Exception) {
            log.error("演员头像补全任务异常 jobId={}", jobId, e)
            jobs.appendError(jobId, "job", e.message ?: "unknown")
            jobs.finish(jobId, STATUS_FAILED)
        }
    }

    /** 单个演员的抓取 + 异常兜底，返回 (展示名, 结果)；单个演员异常按下载失败计入并继续后续演员 */
    private fun scrapeOneLogged(actressId: Long): Pair<String, ScrapeResult> {
        val name = runCatching { actressService.canonicalName(actressService.getActress(actressId)) }
            .getOrDefault("#$actressId")
        val result = runCatching { scrapePhoto(actressId) }
            .onFailure { log.warn("演员 {} 头像抓取异常: {}", name, it.message) }
            .getOrDefault(ScrapeResult.DOWNLOAD_FAILED)
        return name to result
    }

    /**
     * 异步补全指定演员头像（自动触发场景，批次 6 双入口共用：批次刮削 / Emby 重刮收尾）。
     *
     * 语义：**建任务后立刻返回**，抓取在 [autoScrapeExecutor] 里跑，绝不阻塞调用方
     * （刮削任务照常收尾，头像补全失败与否都不影响刮削结果）。
     *
     * 处理顺序：剔除非法 id/重复项 → 过滤已有 `photo_path` 的演员（无需重复补全）
     * → 剩余为空返回 null → 否则创建 [JobType.ACTOR_PHOTO_AUTO] 任务（[reason] 写入任务参数，
     * 供任务列表回溯）并派发 [scrapeAllMissing]。
     *
     * @param actressIds 演员 ID 列表
     * @param reason 触发原因（记录到任务参数）
     * @return 任务 ID；null 表示无需补全（全部已有头像 / 入参为空）
     */
    fun scrapePhotosAsync(actressIds: List<Long>, reason: String): Long? {
        val ids = actressIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return null
        val missing = actressRepository.findIdsMissingPhoto(ids)
        if (missing.isEmpty()) {
            log.debug("演员头像自动补全跳过：{} 位演员均已有头像（{}）", ids.size, reason)
            return null
        }
        val job = jobs.create(JobType.ACTOR_PHOTO_AUTO.code, encodeParams(missing, reason), missing.size)
        log.info("演员头像自动补全任务已创建 jobId={} 演员数={} 原因={}", job.id, missing.size, reason)
        dispatchAutoScrape(missing, job.id)
        return job.id
    }

    /**
     * 把批量补全派发到任务线程池。
     *
     * 先确保 Gfriends 索引可用（超过 `sinjav.gfriends.sync-threshold-days` 才真的下载，通常直接命中缓存）：
     * 索引不可用时只记警告并把任务落 failed —— 头像不是主流程的必需产物，不能让它污染刮削结果。
     * 后台线程抛出的异常无人接收，这里全部就地兜底并落任务状态，避免任务永远停在 running。
     */
    private fun dispatchAutoScrape(ids: List<Long>, jobId: Long) {
        val task = Runnable {
            try {
                if (!runCatching { gfriends.syncIndex() }.getOrDefault(false)) {
                    log.warn("Gfriends 索引不可用，跳过新演员头像自动补全（{} 人）jobId={}", ids.size, jobId)
                    jobs.appendError(jobId, "gfriends", "Gfriends 索引不可用，已跳过本轮头像补全")
                    jobs.finish(jobId, STATUS_FAILED)
                    return@Runnable
                }
                scrapeAllMissing(ids, jobId)
            } catch (e: Exception) {
                log.warn("新演员头像自动补全失败（不影响主流程）jobId={}: {}", jobId, e.message, e)
                runCatching { jobs.finish(jobId, STATUS_FAILED) }
            }
        }
        try {
            autoScrapeExecutor.execute(task)
        } catch (e: Exception) {
            // 线程池拒绝（队列满/已关闭）：任务已落库，标记失败而不是让它卡在 running
            log.warn("演员头像补全任务提交线程池失败 jobId={}: {}", jobId, e.message)
            runCatching { jobs.finish(jobId, STATUS_FAILED) }
        }
    }

    // ---------------------------------------------------------------- 内部工具

    /** 是否已取消：内存态停止请求（已点停止、尚未落库）或已落库的 cancelled 状态 */
    private fun isCancelled(jobId: Long): Boolean = jobControl.isCancelled(jobId) || jobs.isCancelled(jobId)

    private fun throttle() {
        val interval = props.actors.scrapeIntervalMs
        if (interval <= 0) return
        runCatching { sleeper(interval) }
    }

    /** 照片相对路径（落 photo_path）：`actors/{规范名}.jpg`，文件名做路径安全清洗 */
    private fun relativePhotoPath(canonicalName: String, actressId: Long): String {
        val safe = safeFileName(canonicalName).ifBlank { "actor_$actressId" }
        return "${relativePrefix()}/$safe.jpg"
    }

    /**
     * 相对路径 → 实际文件（`data/actors/{文件名}`）。
     * 只取最后一段，天然规避 `../` 之类的目录穿越；与 [relativePhotoPath] 互逆。
     */
    private fun resolvePhoto(relative: String): Path =
        Path.of(props.actors.photoDir).resolve(relative.substringAfterLast('/'))

    /**
     * photo_path 的相对前缀：取 photoDir 末级目录名（默认 ./data/actors → `actors`）。
     * 与设计文档的 `actors/{规范名}.jpg` 一致，同时自定义目录时也自洽。
     */
    private fun relativePrefix(): String =
        runCatching { Path.of(props.actors.photoDir).fileName?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_RELATIVE_PREFIX

    /** 文件名清洗：路径分隔符与 Windows 非法字符 → `_`，去首尾点/空格并限长（超出文件系统上限） */
    private fun safeFileName(raw: String): String =
        raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .replace("..", "_")
            .trim('.', ' ')
            .take(MAX_FILE_NAME_LENGTH)

    /** 任务参数 JSON（失败不影响任务创建：params 仅用于回溯，含触发原因） */
    private fun encodeParams(ids: List<Long>, reason: String): String? =
        runCatching {
            objectMapper.writeValueAsString(mapOf("reason" to reason, "actressIds" to ids))
        }.getOrNull()

    private companion object {
        /** 取消检查间隔：每处理 N 个演员检查一次（首个处理前也会检查一次） */
        const val CANCEL_CHECK_INTERVAL = 5

        /** photo_path 缺省相对前缀（photoDir 无法取末级目录名时兜底） */
        const val DEFAULT_RELATIVE_PREFIX = "actors"

        /** 文件名长度上限（保留扩展名余量，远低于 255 字节的常见上限） */
        const val MAX_FILE_NAME_LENGTH = 120

        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}
