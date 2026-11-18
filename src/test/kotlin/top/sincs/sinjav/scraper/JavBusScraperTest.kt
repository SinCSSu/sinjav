package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties
import java.time.LocalDate

/**
 * 使用真实页面快照（src/test/resources/javbus/）验证 JavBus 详情页解析。
 * 站点改版时需重新抓取快照并更新本测试。
 */
class JavBusScraperTest {

    private val scraper = JavBusScraper(HttpClientFactory(AppProperties()), AppProperties())

    private fun parse(fixture: String, baseUrl: String, number: String): MovieMetadata {
        val html = javaClass.getResourceAsStream("/javbus/$fixture")!!
            .readBytes().toString(Charsets.UTF_8)
        val doc = Jsoup.parse(html, baseUrl)
        return scraper.parseDetail(doc, number)
    }

    @Test
    fun `解析 ABP-123 基本信息`() {
        val meta = parse("ABP-123.html", "https://www.javbus.com/ABP-123", "ABP-123")
        assertEquals("酒井ももか、満足度満点新人ソープ DX", meta.title)
        assertEquals(LocalDate.parse("2014-04-01"), meta.releaseDate)
        assertEquals(140, meta.durationMin)
        assertEquals("PornStars", meta.director)
        assertEquals("プレステージ", meta.maker)
        assertEquals("ABSOLUTELYPERFECT", meta.label)
        assertEquals("満足度満点ソープ", meta.series)
        assertTrue(meta.coverUrl?.startsWith("https://www.javbus.com/pics/") == true, "封面 URL 应为绝对地址，实际=${meta.coverUrl}")
        assertEquals(listOf("酒井ももか"), meta.actresses)
        assertTrue(meta.genres.contains("巨乳"), "类别应包含 巨乳，实际=${meta.genres}")
        assertTrue(meta.genres.none { it == "酒井ももか" }, "类别不应混入女优名，实际=${meta.genres}")
        assertTrue(meta.previewUrls.isEmpty(), "该影片页面无剧照样本")
    }

    @Test
    fun `解析 MIDV-002 剧照`() {
        val meta = parse("MIDV-002.html", "https://www.javbus.com/MIDV-002", "MIDV-002")
        assertTrue(meta.title?.isNotBlank() == true, "标题应非空，实际=${meta.title}")
        assertTrue(meta.releaseDate != null, "发行日期应非空")
        assertTrue(meta.coverUrl?.isNotBlank() == true, "封面应非空")
        assertTrue(meta.previewUrls.isNotEmpty(), "该影片页面应有剧照样本，实际=${meta.previewUrls.size}")
        assertTrue(meta.previewUrls.all { it.startsWith("http") }, "剧照 URL 应为绝对地址")
    }
}
