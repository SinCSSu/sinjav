package top.sincs.sinjav.emby

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.api.dto.EmbyMetadataUpdate
import top.sincs.sinjav.api.dto.EmbyRescrapeApplyRequest
import top.sincs.sinjav.api.dto.EmbyRescrapePreviewDto
import top.sincs.sinjav.api.dto.ScrapeFieldChoice
import top.sincs.sinjav.scan.NumberRecognizer
import top.sincs.sinjav.scraper.ScrapeCompareService
import top.sincs.sinjav.scraper.ScraperChain
import top.sincs.sinjav.scraper.TranslationService
import java.nio.file.Path
import java.time.LocalDate

/**
 * Emby 条目「重新刮削」：用本应用的刮削管线刮取元数据，先返回各来源对比供逐字段选择，再回写 Emby。
 *
 * 流程：从条目文件名/原标题/标题提取番号 → 并发抓取全部数据源 → 返回各源原始值 + 合并默认值（不推送）。
 * 应用时按逐字段来源选择组装元数据（标题/简介翻译；manual 值不翻译）后回写 Emby。
 * 同步执行（单次刮削受请求限速影响，可能耗时数十秒）。
 */
@Service
class EmbyRescrapeService(
    private val emby: EmbyClient,
    private val chain: ScraperChain,
    private val translation: TranslationService,
    private val recognizer: NumberRecognizer,
    private val compare: ScrapeCompareService,
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

    /**
     * 按逐字段选择组装 EmbyMetadataUpdate：
     * 标题→名称（译中文，统一为「番号-中文标题」）+ 原标题（原文）；简介→概述（译中文）；日期→发行日期+年份；
     * 厂商/厂牌→工作室；女优/系列/导演/厂牌→标签；类别→类别（过滤画质标签并译名）。
     * 留空的字段不回写；manual 值视为最终文本不翻译。
     */
    private fun toUpdate(number: String, fields: Map<String, ScrapeFieldChoice>): EmbyMetadataUpdate {
        val values = compare.chosenValues(fields)

        val titleVal = values["title"]
        val titleManual = fields["title"]?.source == "manual"
        val titleZh = if (titleManual || titleVal == null) null else translation.translateToZh(titleVal)

        val plotVal = values["plot"]
        val plot = when {
            plotVal == null -> null
            fields["plot"]?.source == "manual" -> plotVal
            else -> translation.translateToZh(plotVal)
        }

        val releaseDate = values["releaseDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val director = values["director"]
        val maker = values["maker"]
        val label = values["label"]
        val series = values["series"]
        val actresses = compare.splitListValues(values["actresses"])
        val genres = compare.splitListValues(values["genres"])
            .filter { !qualityTagRegex.containsMatchIn(it) }
            .map { translation.translateLabel(it) }
            .filter { it.isNotBlank() && !qualityTagRegex.containsMatchIn(it) }
            .distinct()

        val studios = listOfNotNull(maker, label)
            .filter { it.isNotBlank() }
            .distinct()
        val tags = buildList {
            addAll(actresses)
            addAll(listOfNotNull(series, label, director).filter { it.isNotBlank() })
        }.distinct()

        return EmbyMetadataUpdate(
            // 名称统一为「番号-中文标题」
            name = titleVal?.let { "$number-${titleZh ?: it}" },
            originalTitle = if (titleManual) null else titleVal,
            overview = plot,
            genres = genres.ifEmpty { null },
            studios = studios.ifEmpty { null },
            tags = tags.ifEmpty { null },
            premiereDate = releaseDate?.toString(),
            productionYear = releaseDate?.year,
        )
    }
}
