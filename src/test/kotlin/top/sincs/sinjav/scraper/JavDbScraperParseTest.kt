package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用本地保存的 javdb 真实页面快照验证解析逻辑（2026-11 实测结构）。
 * 快照不在 git 中，文件缺失时跳过。
 */
class JavDbScraperParseTest {

    private fun loadSnapshot(name: String): Path? =
        sequenceOf(Path.of(".."), Path.of("."))
            .map { it.resolve(name) }
            .firstOrNull { Files.exists(it) }

    private fun scraper() = JavDbScraper(
        http = HttpClientFactory(top.sincs.sinjav.config.AppProperties()),
        props = top.sincs.sinjav.config.AppProperties(),
    )

    @Test
    fun `parseDetail extracts fields from current page structure`() {
        val snapshot = loadSnapshot("javdb_detail_cn.html") ?: return
        val doc = Jsoup.parse(Files.readString(snapshot), "https://javdb.com/v/yx0qNb")
        val meta = scraper().parseDetail(doc, "PFES-107")

        assertEquals("PFES-107", meta.number)
        // 标题取 current-title，不含番号前缀
        assertTrue(meta.title?.startsWith("楓ふうあ") == true, "title=${meta.title}")
        assertEquals(LocalDate.of(2025, 4, 30), meta.releaseDate)
        assertEquals(170, meta.durationMin)
        assertEquals("真咲南朋", meta.director)
        assertEquals("S1 NO.1 STYLE", meta.maker)
        // 女优只取 actor-female 链接，排除男性演员
        assertTrue("楓ふうあ" in meta.actresses && "畑中哲也" !in meta.actresses, "actresses=${meta.actresses}")
        // 类别来自 /tags? 链接
        assertTrue(meta.genres.isNotEmpty() && meta.genres.contains("高挑"), "genres=${meta.genres}")
        // 剧照来自 preview-images 容器
        assertTrue(meta.previewUrls.any { it.contains("/samples/") }, "previews=${meta.previewUrls}")
        assertTrue(meta.coverUrl?.contains("c0.jdbstatic.com") == true)
    }
}
