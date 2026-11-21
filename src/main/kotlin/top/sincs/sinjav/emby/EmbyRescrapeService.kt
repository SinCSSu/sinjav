package top.sincs.sinjav.emby

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.api.dto.EmbyImagePushRequest
import top.sincs.sinjav.api.dto.EmbyImagePushResult
import top.sincs.sinjav.api.dto.EmbyMetadataUpdate
import top.sincs.sinjav.api.dto.EmbyRescrapeApplyRequest
import top.sincs.sinjav.api.dto.EmbyRescrapePreviewDto
import top.sincs.sinjav.api.dto.ScrapeFieldChoice
import top.sincs.sinjav.scan.NumberRecognizer
import top.sincs.sinjav.scraper.CoverCropper
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import top.sincs.sinjav.scraper.ScrapeCompareService
import top.sincs.sinjav.scraper.ScraperChain
import java.nio.file.Path
import java.time.LocalDate

/**
 * Emby 条目「重新刮削」：用本应用的刮削管线刮取元数据，先返回各来源对比供逐字段选择，再回写 Emby。
 *
 * 流程：从条目文件名/原标题/标题提取番号 → 并发抓取全部数据源 → 返回各源原始值 + 合并默认值（不推送）。
 * 应用时按输入框当前内容回写 Emby（不自动翻译：翻译/繁→简转换由前端「翻译」按钮调用
 * /scrape/translate 预先回填，保存即所见）。
 * 同步执行（单次刮削受请求限速影响，可能耗时数十秒）。
 */
@Service
class EmbyRescrapeService(
    private val emby: EmbyClient,
    private val chain: ScraperChain,
    private val recognizer: NumberRecognizer,
    private val compare: ScrapeCompareService,
    private val http: HttpClientFactory,
    private val imageStore: ImageStore,
    private val coverCropper: CoverCropper,
) {
    private val log = LoggerFactory.getLogger(EmbyRescrapeService::class.java)

    /** 画质/尺寸类标签（高清、4K、1080P 等，简繁+英文写法），刮削时过滤不入库（与 ScrapeService 一致） */
    private val qualityTagRegex = Regex(
        """(?i)(4\s*k|8\s*k|2\s*k|\d{3,4}\s*p|fhd|uhd|高清|超清|藍光|蓝光|高畫質|高画質|畫質|画质)""",
    )

    /** 刮削预览：识别番号（或用手动指定番号）→ 并发抓全源 → 返回各源原始值、合并默认值与合并后的待应用元数据，不推送 */
    fun preview(itemId: String, manualNumber: String? = null): EmbyRescrapePreviewDto {
        val current = emby.getItem(itemId)

        // 1. 番号：手动指定优先；否则走识别链 文件名 → 原标题 → 标题
        //    Emby 上的媒体大多是整理好的：文件名通常含番号；若无番号则是手动录入了标题
        val number = manualNumber?.let {
            NumberRecognizer.normalize(it)
                ?: throw IllegalArgumentException("手动输入的番号格式无法识别：$it")
        } ?: run {
            val fileName = current.path?.let { Path.of(it).fileName.toString() } ?: ""
            val candidates = listOf(fileName, current.originalTitle ?: "", current.name)
            candidates.firstNotNullOfOrNull { recognizer.recognize(it)?.normalized }
                ?: throw IllegalArgumentException(
                    "无法从文件名、原标题或标题识别番号：${current.name.ifBlank { itemId }}",
                )
        }

        // 2. 并发抓取全部数据源（全部失败会抛 ScrapeException → 500）
        val results = chain.scrapeAllSources(number)
        val merged = chain.mergeSources(results)
        val source = if (results.size > 1) "merged" else results.first().first
        log.info("Emby 刮削预览 {}: 番号={} 来源={}", itemId, number, source)

        // 3. 各源原始值与合并默认值（与刮削弹窗的多源对比同一套字段键）
        val (fields, defaults) = compare.fieldMaps(results)

        val proposed = toUpdate(number, mapOf(
            "title" to ScrapeFieldChoice(source = "merged", value = merged.title),
            "plot" to ScrapeFieldChoice(source = "merged", value = merged.plot),
            "releaseDate" to ScrapeFieldChoice(source = "merged", value = merged.releaseDate?.toString()),
            "director" to ScrapeFieldChoice(source = "merged", value = merged.director),
            "maker" to ScrapeFieldChoice(source = "merged", value = merged.maker),
            "label" to ScrapeFieldChoice(source = "merged", value = merged.label),
            "series" to ScrapeFieldChoice(source = "merged", value = merged.series),
            "actresses" to ScrapeFieldChoice(source = "merged", value = merged.actresses.joinToString(" / ")),
            "genres" to ScrapeFieldChoice(source = "merged", value = merged.genres.joinToString(" / ")),
        ))
        return EmbyRescrapePreviewDto(
            itemId = itemId,
            matchedNumber = number,
            source = source,
            current = current,
            proposed = proposed,
            fields = fields,
            defaults = defaults,
            sources = results.map { it.first },
        )
    }

    /** 多源对比应用：按逐字段来源选择组装元数据并回写 Emby，返回更新后的条目 */
    fun apply(itemId: String, req: EmbyRescrapeApplyRequest): EmbyMetadataUpdate {
        val number = NumberRecognizer.normalize(req.number.trim()) ?: req.number.trim()
        val update = toUpdate(number, req.fields)
        log.info(
            "Emby 重新刮削应用 {}: 番号={} 字段来源={}",
            itemId, req.number,
            req.fields.filterValues { !it.source.isNullOrBlank() }
                .mapValues { it.value.source },
        )
        emby.updateMetadata(itemId, update)
        return update
    }

    // ---------- 图片推送（重新刮削界面「推送封面/推送剧照」） ----------

    /**
     * 推送封面到 Emby（Primary 图）：
     * 预览提供了封面 URL 时优先下载（横版全封套扫描自动裁为竖版，FC2 排除）；
     * 否则回退本地图片缓存（data/images/{番号}/poster.jpg，已是竖版）。
     */
    fun pushCover(itemId: String, req: EmbyImagePushRequest): EmbyImagePushResult {
        val number = normalizeNumber(req)
        val urlBytes = req.coverUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching { coverCropper.cropFrontCover(number, http.download(url)) }
                .onFailure { log.warn("下载封面失败 {}（回退本地缓存）: {}", url, it.message) }
                .getOrNull()
        }
        val bytes = urlBytes ?: imageStore.readPoster(number)
            ?: throw IllegalArgumentException(
                "无法获取封面：封面 URL 下载失败或未提供，且本地无 $number 的图片缓存（可先在影片库刮削一次）",
            )
        // 归一化为 JPEG：WebP/AVIF 等格式 Emby 上传会 500（normalizeJpeg 抛出可读错误）
        val normalized = coverCropper.normalizeJpeg(bytes)
        emby.uploadImage(itemId, "Primary", normalized)
        log.info("Emby 封面已推送 itemId={} 番号={} bytes={}", itemId, number, bytes.size)
        return EmbyImagePushResult(pushed = 1)
    }

    /**
     * 推送剧照到 Emby（Backdrop）：先清空条目已有 Backdrop 再逐张上传，保证重复推送不叠加。
     * 剧照来源：预览提供的 previewUrls（下载失败的跳过）→ 回退本地图片缓存 preview1..N.jpg。
     */
    fun pushBackdrops(itemId: String, req: EmbyImagePushRequest): EmbyImagePushResult {
        val number = normalizeNumber(req)
        val urls = req.previewUrls.orEmpty().mapNotNull { it.trim().takeIf { u -> u.isNotBlank() } }
        val images: List<ByteArray> = if (urls.isNotEmpty()) {
            urls.mapNotNull { url ->
                runCatching { http.download(url) }
                    .onFailure { log.warn("下载剧照失败 {}（跳过）: {}", url, it.message) }
                    .getOrNull()
            }
        } else {
            imageStore.readPreviews(number)
        }
        if (images.isEmpty()) {
            throw IllegalArgumentException(
                "无法获取剧照：剧照 URL 下载失败或未提供，且本地无 $number 的剧照缓存（可先在影片库刮削一次）",
            )
        }
        // 先归一化为 JPEG（WebP/AVIF 等单张跳过，不影响其余上传；全部不支持时直接报错，不动 Emby 数据）
        val uploaded = images.mapNotNull { bytes ->
            runCatching { coverCropper.normalizeJpeg(bytes) }
                .onFailure { log.warn("剧照格式不支持上传，已跳过: {}", it.message) }
                .getOrNull()
        }.ifEmpty {
            throw IllegalArgumentException("全部剧照均为 Emby 不支持的格式（WebP/AVIF 等），未推送任何图片")
        }
        // 从尾到头删除已有 Backdrop（删除会令后续索引前移，倒序删除避免漏删）
        for (i in emby.backdropCount(itemId) - 1 downTo 0) {
            runCatching { emby.deleteImage(itemId, "Backdrop", i) }
                .onFailure { log.warn("删除 Emby 旧背景图失败 index={}: {}", i, it.message) }
        }
        uploaded.forEach { emby.uploadImage(itemId, "Backdrop", it) }
        log.info("Emby 剧照已推送 itemId={} 番号={} 共 {} 张", itemId, number, uploaded.size)
        return EmbyImagePushResult(pushed = uploaded.size)
    }

    private fun normalizeNumber(req: EmbyImagePushRequest): String =
        NumberRecognizer.normalize(req.number.trim()) ?: req.number.trim()

    /**
     * 按逐字段选择组装 EmbyMetadataUpdate（不自动翻译，保存输入框当前内容）：
     * 标题→名称（统一为「番号-标题」）+ 原标题；简介→概述；日期→发行日期+年份；
     * 厂商/厂牌→工作室；女优/系列/导演/厂牌→标签；类别→类别（过滤画质标签）。
     * 留空的字段不回写；manual 值为用户自定义文本。
     */
    private fun toUpdate(number: String, fields: Map<String, ScrapeFieldChoice>): EmbyMetadataUpdate {
        val values = compare.chosenValues(fields)

        val titleVal = values["title"]
        val titleManual = fields["title"]?.source == "manual"

        val plotVal = values["plot"]

        val releaseDate = values["releaseDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val director = values["director"]
        val maker = values["maker"]
        val label = values["label"]
        val series = values["series"]
        val actresses = compare.splitListValues(values["actresses"])
        val genres = compare.splitListValues(values["genres"])
            .filter { !qualityTagRegex.containsMatchIn(it) }
            .filter { it.isNotBlank() }
            .distinct()

        val studios = listOfNotNull(maker, label)
            .filter { it.isNotBlank() }
            .distinct()
        val tags = buildList {
            addAll(actresses)
            addAll(listOfNotNull(series, label, director).filter { it.isNotBlank() })
        }.distinct()

        return EmbyMetadataUpdate(
            // 名称统一为「番号-标题」
            name = titleVal?.let { "$number-$it" },
            originalTitle = if (titleManual) null else titleVal,
            overview = plotVal,
            genres = genres.ifEmpty { null },
            studios = studios.ifEmpty { null },
            tags = tags.ifEmpty { null },
            premiereDate = releaseDate?.toString(),
            productionYear = releaseDate?.year,
        )
    }
}
