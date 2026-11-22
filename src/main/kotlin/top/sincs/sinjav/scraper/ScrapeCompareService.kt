package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.api.dto.ScrapeApplyRequest
import top.sincs.sinjav.api.dto.ScrapePreviewResponse
import top.sincs.sinjav.api.dto.ScrapeTranslateRequest
import top.sincs.sinjav.api.dto.ScrapeTranslateResponse
import top.sincs.sinjav.scan.NumberRecognizer
import java.time.LocalDate

/**
 * 多源对比刮削：预览返回各数据源的原始字段值（不落库），
 * 用户逐字段选择来源（各源 / 留空 / 手动输入）后应用落库。
 * 落库不再自动翻译：标题/简介/类别的翻译或繁→简转换由前端「翻译」按钮调用
 * [translateFields] 预先回填输入框，保存时按输入框当前内容落库（保存即所见）。
 */
@Service
class ScrapeCompareService(
    private val chain: ScraperChain,
    private val movies: top.sincs.sinjav.domain.MovieRepository,
    private val imageStore: ImageStore,
    private val translation: TranslationService,
) {

    private val log = LoggerFactory.getLogger(ScrapeCompareService::class.java)

    /** 对比展示的字段与落库字段一一对应 */
    val fieldKeys: List<String> = listOf(
        "title", "plot", "releaseDate", "durationMin", "director", "maker",
        "label", "series", "actresses", "genres", "coverUrl", "previewUrls",
    )

    /** 预览：并发抓取全部数据源，返回字段 → 来源 → 原文值 的映射与合并默认来源 */
    fun preview(number: String): ScrapePreviewResponse {
        val n = NumberRecognizer.normalize(number.trim())
            ?: throw IllegalArgumentException("无法识别番号: $number")
        val results = chain.scrapeAllSources(n)
        val (fields, defaults) = fieldMaps(results)
        return ScrapePreviewResponse(number = n, sources = results.map { it.first }, fields = fields, defaults = defaults)
    }

    /**
     * 各源字段值映射：(fields, defaults)。
     * fields[fieldKey][sourceId] = 原文值（仅含该源有值的字段）；defaults[fieldKey] = 合并规则的默认来源（无值为 null）。
     * results 须已按 source-order 排序。
     */
    fun fieldMaps(results: List<Pair<String, MovieMetadata>>): Pair<Map<String, Map<String, String>>, Map<String, String?>> {
        val fields = fieldKeys.associateWith { key ->
            results.mapNotNull { (id, meta) ->
                render(key, meta)?.takeIf { it.isNotBlank() }?.let { id to it }
            }.toMap()
        }
        val defaults = fields.mapValues { (_, values) ->
            results.map { it.first }.firstOrNull { values.containsKey(it) }
        }
        return fields to defaults
    }

    /**
     * 解析逐字段来源选择为最终值：留空（null/缺省）不出现；manual 取用户输入；来源选择取随请求携带的原文值。
     * 返回 fieldKey → 最终文本（trim 过、空白视为无值）。
     */
    fun chosenValues(fields: Map<String, top.sincs.sinjav.api.dto.ScrapeFieldChoice>): Map<String, String> =
        fields.entries
            .filter { !it.value.source.isNullOrBlank() }
            .mapNotNull { (key, choice) ->
                choice.value?.trim()?.takeIf { it.isNotBlank() }?.let { key to it }
            }
            .toMap()

    /** 列表字段值解析：按逗号/顿号/斜杠/换行分隔 */
    fun splitListValues(value: String?): List<String> = splitList(value)

    /**
     * 手动翻译（对比界面「翻译」按钮）：标题/简介走翻译或繁→简转换（失败回退原文），
     * 类别逐个翻译后以「 / 」重新拼接。仅处理请求中携带的非空白字段，其余返回 null。
     */
    fun translateFields(req: ScrapeTranslateRequest): ScrapeTranslateResponse = ScrapeTranslateResponse(
        title = req.title?.trim()?.takeIf { it.isNotBlank() }?.let { translation.translateToZh(it) ?: it },
        plot = req.plot?.trim()?.takeIf { it.isNotBlank() }?.let { translation.translateToZh(it) ?: it },
        genres = req.genres?.trim()?.takeIf { it.isNotBlank() }
            ?.let { splitList(it).joinToString(" / ") { tag -> translation.translateLabel(tag) } },
    )

    /** 落库：按逐字段选择与输入框当前内容组装元数据（不自动翻译），写库并下载图片（同步） */
    fun apply(req: ScrapeApplyRequest) {
        val n = NumberRecognizer.normalize(req.number.trim())
            ?: throw IllegalArgumentException("无法识别番号: ${req.number}")

        // 改番号落库：对比界面编辑番号后重刮，原记录改用新番号（file_records 关联同步更新）
        req.recordNumber?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val orig = NumberRecognizer.normalize(raw)
                ?: throw IllegalArgumentException("无法识别原番号: $raw")
            if (orig != n) {
                movies.findByNumber(orig)
                    ?: throw IllegalArgumentException("原记录 $orig 不存在，无法改番号落库")
                if (movies.findByNumber(n) != null) {
                    throw IllegalArgumentException("番号 $n 已存在于库中，无法将 $orig 改番号为 $n（请先处理重复记录）")
                }
                movies.rename(orig, n)
                log.info("改番号落库: {} → {}", orig, n)
            }
        }
        // 兜底：目标记录不存在时先建 pending（避免 markScraped 空更新丢数据）
        movies.ensurePending(n, n)

        fun choice(key: String) = req.fields[key]
        fun valueOf(key: String): String? =
            choice(key)?.value?.trim()?.ifBlank { null }

        val titleVal = valueOf("title")
        val titleManual = choice("title")?.source == "manual"
        val titleOrig = if (titleManual) null else titleVal
        // 保存即所见：输入框内容为最终标题（可能已由前端「翻译」按钮回填译文），统一加番号前缀
        val title = titleVal?.let { "$n-$it" }

        val plot = valueOf("plot")

        val releaseDate = valueOf("releaseDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val durationMin = valueOf("durationMin")?.toIntOrNull()
        val coverUrl = valueOf("coverUrl")
        val previewUrls = valueOf("previewUrls")
            ?.split('\n', ',', '，', ' ')
            ?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.distinct() ?: emptyList()
        val actresses = splitList(valueOf("actresses"))
        val genres = splitList(valueOf("genres"))

        val coverSource = choice("coverUrl")?.source?.takeIf { it != "manual" }
        val previewSource = choice("previewUrls")?.source?.takeIf { it != "manual" }
        // 封面候选：请求携带的各源封面按优先级排序（所选封面置顶、javdb 沉底）后持久化，
        // 下载失败自动换下一候选；请求未携带时回退所选封面单候选
        val chosenCover = coverUrl?.let { top.sincs.sinjav.scraper.CoverCandidate(it, coverSource) }
        val coverCandidates = ScraperChain.orderCoverCandidates(
            chosenCover,
            req.coverCandidates.mapNotNull { c ->
                c.url.trim().takeIf { it.isNotBlank() }
                    ?.let { top.sincs.sinjav.scraper.CoverCandidate(it, c.source) }
            },
        )
        // 整体来源：标题选择的来源 > 任一非手动来源 > manual
        val primary = choice("title")?.source?.takeIf { it != null && it != "manual" }
            ?: req.fields.values.firstOrNull { !it.source.isNullOrBlank() && it.source != "manual" }?.source
            ?: "manual"

        movies.markScraped(
            number = n,
            title = title, titleZh = null, titleOrig = titleOrig,
            releaseDate = releaseDate,
            director = valueOf("director"), maker = valueOf("maker"),
            label = valueOf("label"), series = valueOf("series"),
            durationMin = durationMin,
            coverUrl = coverUrl, previewUrls = previewUrls, plot = plot,
            source = primary,
            actresses = actresses, genres = genres,
            coverSource = coverCandidates.firstOrNull()?.source ?: coverSource,
            previewSource = previewSource,
            coverCandidates = coverCandidates,
        )
        imageStore.downloadAll(
            n, coverUrl, previewUrls, primary,
            coverSource = coverCandidates.firstOrNull()?.source ?: coverSource,
            previewSource = previewSource,
            coverCandidates = coverCandidates,
        )
        log.info(
            "多源对比落库 {}: source={} 封面来源={} 剧照来源={} 封面候选={}个",
            n, primary, coverCandidates.firstOrNull()?.source ?: "—", previewSource ?: "—", coverCandidates.size,
        )
    }

    private fun splitList(value: String?): List<String> =
        value?.split('，', ',', '、', '/', '\n')
            ?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.distinct() ?: emptyList()

    /**
     * 手动落库：用户手动录入元数据并上传封面/剧照（不抓取、不翻译），保存后影片转为已刮削，
     * 之后可正常整理。目标记录不存在时先建 pending 兜底。返回规范化后的番号。
     */
    fun manual(
        number: String,
        title: String?, titleOrig: String?, plot: String?, releaseDate: LocalDate?, durationMin: Int?,
        director: String?, maker: String?, label: String?, series: String?,
        actresses: List<String>, genres: List<String>,
        coverUrl: String?, previewUrls: List<String>,
        cover: ByteArray?, previews: List<ByteArray>,
    ): String {
        val n = NumberRecognizer.normalize(number.trim())
            ?: throw IllegalArgumentException("无法识别番号: $number")
        movies.ensurePending(n, n)

        val t = title?.trim()?.ifBlank { null }
        // 元数据标题统一为「番号-标题」（用户输入的标题视为中文标题）
        val finalTitle = t?.let { "$n-$it" }

        movies.markScraped(
            number = n,
            title = finalTitle, titleZh = t, titleOrig = titleOrig?.trim()?.ifBlank { null },
            releaseDate = releaseDate,
            director = director?.trim()?.ifBlank { null },
            maker = maker?.trim()?.ifBlank { null },
            label = label?.trim()?.ifBlank { null },
            series = series?.trim()?.ifBlank { null },
            durationMin = durationMin,
            coverUrl = coverUrl?.trim()?.ifBlank { null },
            previewUrls = previewUrls, plot = plot?.trim()?.ifBlank { null },
            source = "manual",
            actresses = actresses, genres = genres,
        )
        // 同时填了图片 URL 时按 URL 下载；上传的文件最后写入（优先于 URL 下载结果）
        imageStore.downloadAll(n, coverUrl?.trim()?.ifBlank { null }, previewUrls, "manual")
        imageStore.saveLocal(n, cover, previews)
        log.info("手动落库 {}: 封面上传={} 剧照上传={}张", n, cover != null, previews.size)
        return n
    }

    private fun render(key: String, meta: MovieMetadata): String? = when (key) {
        "title" -> meta.title
        "plot" -> meta.plot
        "releaseDate" -> meta.releaseDate?.toString()
        "durationMin" -> meta.durationMin?.toString()
        "director" -> meta.director
        "maker" -> meta.maker
        "label" -> meta.label
        "series" -> meta.series
        "actresses" -> meta.actresses.joinToString(" / ").ifBlank { null }
        "genres" -> meta.genres.joinToString(" / ").ifBlank { null }
        "coverUrl" -> meta.coverUrl
        "previewUrls" -> meta.previewUrls.joinToString("\n").ifBlank { null }
        else -> null
    }
}
