package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用本地保存的 missav.ai 真实页面快照验证解析逻辑（2026-11 实测结构）。
 * 快照不在 git 中，文件缺失时跳过。
 */
class MissAvScraperParseTest {

    private fun loadSnapshot(name: String): Path? =
        sequenceOf(Path.of(".."), Path.of("."))
            .map { it.resolve(name) }
            .firstOrNull { Files.exists(it) }

    private fun scraper() = MissAvScraper(
        http = HttpClientFactory(top.sincs.sinjav.config.AppProperties()),
        props = top.sincs.sinjav.config.AppProperties(),
    )

    @Test
    fun `parseDetail extracts fields from current page structure`() {
        val snapshot = loadSnapshot("missav_detail.html") ?: return
        val doc = Jsoup.parse(Files.readString(snapshot), "https://missav.ai")
        val meta = scraper().parseDetail(doc, "PFES-107")

        assertEquals("PFES-107", meta.number)
        // 标题取信息块「標題」，为日文原题（不含番号前缀）
        assertTrue(meta.title?.startsWith("楓ふうあ170cm") == true, "title=${meta.title}")
        // titleZh 取 h1，剥离番号前缀与「 - 女优」后缀
        assertTrue(meta.titleZh?.startsWith("楓吹亞身高170cm") == true, "titleZh=${meta.titleZh}")
        assertTrue(meta.titleZh?.endsWith("射精") == true, "titleZh 尾部应剥离女优后缀: ${meta.titleZh}")
        assertEquals(java.time.LocalDate.of(2025, 4, 25), meta.releaseDate)
        // og:video:duration = 10003s → 向上取整 167 分钟
        assertEquals(167, meta.durationMin)
        assertEquals("真咲南朋", meta.director)
        assertEquals("S1", meta.maker)
        assertTrue("楓ふうあ" in meta.actresses && "明日葉みつは" in meta.actresses, "actresses=${meta.actresses}")
        assertTrue(meta.genres.contains("痴女"), "genres=${meta.genres}")
        assertTrue(meta.coverUrl?.contains("fourhoi.com") == true, "cover=${meta.coverUrl}")
        assertTrue(!meta.plot.isNullOrBlank(), "plot 应来自 og:description")
        // 简介需截断 DMM 活动广告尾巴
        assertTrue(!meta.plot!!.contains("春季內褲節") && !meta.plot.contains("活動已結束"), "plot 含广告: ${meta.plot?.takeLast(200)}")
    }

    @Test
    fun `plot with site promo boilerplate is dropped`() {
        // 影片无简介时 missav 用站点宣传语兜底（FC2 页常见，重复拼接两次）
        val boilerplate = "免費高清日本 AV 在線看，無需下載，高速播放沒有延遲，超過十萬部影片，每日更新，" +
            "開始播放後不會再有廣告，支援任何裝置包括手機，電腦及智能電視。可以番號，女優或作品系列名稱作影片搜尋。" +
            "免費加入會員後可任意收藏影片供日後觀賞。"
        val html = """
            <html><head>
              <meta property="og:image" content="https://fourhoi.com/fc2-ppv-2526023/cover-n.jpg">
              <meta property="og:video:duration" content="5160">
              <meta property="og:description" content="${boilerplate}${"\n"}${boilerplate}">
            </head><body>
              <div class="text-secondary"><span>番號:</span><span class="font-medium">FC2PPV-2526023</span></div>
              <div class="text-secondary"><span>發行日期:</span><time>2021-12-18</time></div>
              <div class="text-secondary"><span>標題:</span><span class="font-medium">FC2PPV-2526023 テスト</span></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://missav.ai")
        // 纯宣传语（含两次拼接）→ plot 应为 null
        val meta = scraper().parseDetail(doc, "FC2-PPV-2526023")
        kotlin.test.assertNull(meta.plot, "纯宣传语简介应被丢弃: ${meta.plot?.take(100)}")

        // 真实简介 + 宣传语混杂 → 只留真实部分
        doc.selectFirst("meta[property=og:description]")?.attr("content", "真實劇情介紹。$boilerplate")
        val meta2 = scraper().parseDetail(doc, "FC2-PPV-2526023")
        assertEquals("真實劇情介紹。", meta2.plot, "混排时应保留真实简介")
    }
}
