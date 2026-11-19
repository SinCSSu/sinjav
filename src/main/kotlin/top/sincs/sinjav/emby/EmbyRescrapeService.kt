package top.sincs.sinjav.emby

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.api.dto.EmbyMetadataUpdate
import top.sincs.sinjav.api.dto.EmbyRescrapePreviewDto
import top.sincs.sinjav.scan.NumberRecognizer
import top.sincs.sinjav.scraper.ScraperChain
import top.sincs.sinjav.scraper.TranslationService
import java.nio.file.Path

/**
 * Emby 条目「重新刮削」：用本应用的刮削管线刮取元数据，先返回前后对比供确认，再回写 Emby。
 *
 * 流程：从条目文件名/原标题/标题提取番号 → 本地刮削（多数据源）→ 翻译 → 组装待应用的元数据。
 * 不直接推送；应用由前端调 PUT /emby/items/{id}/metadata 完成。
 * 同步执行（单次刮削受请求限速影响，可能耗时数十秒）。
 */
@Service
class EmbyRescrapeService(
    private val emby: EmbyClient,
    private val chain: ScraperChain,
    private val translation: TranslationService,
    private val recognizer: NumberRecognizer,
) {
    private val log = LoggerFactory.getLogger(EmbyRescrapeService::class.java)

    /** 画质/尺寸类标签（高清、4K、1080P 等，简繁+英文写法），刮削时过滤不入库（与 ScrapeService 一致） */
    private val qualityTagRegex = Regex(
        """(?i)(4\s*k|8\s*k|2\s*k|\d{3,4}\s*p|fhd|uhd|高清|超清|藍光|蓝光|高畫質|高画質|畫質|画质)""",
    )

    /** 刮削预览：识别番号（或用手动指定番号）→ 刮削 → 翻译 → 返回「当前 Emby 元数据 + 刮削结果」，不推送 */
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

        // 2. 本地刮削（全部数据源失败会抛 ScrapeException → 500）
        val (source, meta) = chain.scrapeWithSource(number)
        log.info("Emby 刮削预览 {}: 番号={} 来源={}", itemId, number, source)

        // 3. 翻译（与 ScrapeService 的策略一致：标题译文失败回退源站繁体标题；简介失败保留原文）
        val titleZh = meta.title?.let { translation.translateToZh(it) }
            ?: meta.titleZh?.takeIf { it.isNotBlank() }?.let { translation.toSimplified(it) }
        val plot = meta.plot?.let { translation.translateToZh(it) ?: it }
        val genres = meta.genres
            .filter { !qualityTagRegex.containsMatchIn(it) }
            .map { translation.translateLabel(it) }
            .filter { it.isNotBlank() && !qualityTagRegex.containsMatchIn(it) }
            .distinct()

        // 4. 组装待应用元数据：maker/厂牌 → 工作室；演员/系列/厂牌/导演 → 标签
        val studios = listOfNotNull(meta.maker, meta.label)
            .filter { it.isNotBlank() }
            .distinct()
        val tags = buildList {
            addAll(meta.actresses)
            addAll(listOfNotNull(meta.series, meta.label, meta.director).filter { it.isNotBlank() })
        }.distinct()

        val proposed = EmbyMetadataUpdate(
            name = titleZh ?: meta.title,
            originalTitle = meta.title,
            overview = plot,
            genres = genres.ifEmpty { null },
            studios = studios.ifEmpty { null },
            tags = tags.ifEmpty { null },
            premiereDate = meta.releaseDate?.toString(),
            productionYear = meta.releaseDate?.year,
        )
        return EmbyRescrapePreviewDto(
            itemId = itemId,
            matchedNumber = number,
            source = source,
            current = current,
            proposed = proposed,
        )
    }
}
