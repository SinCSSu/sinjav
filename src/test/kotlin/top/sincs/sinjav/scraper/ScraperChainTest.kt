package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties

class ScraperChainTest {

    /** 测试桩：result 为 null 时抛异常（未命中）；否则返回固定元数据 */
    private class FakeScraper(private val id: String, private val result: MovieMetadata? = null) : Scraper {
        override fun id() = id
        override fun scrape(number: String): MovieMetadata =
            result ?: throw ScrapeException("测试桩：不发起网络请求")
    }

    private val props = AppProperties()
    private val chain = ScraperChain(listOf("javbus", "javdb", "missav", "fc2", "fanza", "dl_getchu", "gyutto").map(::FakeScraper), props)

    @Test
    fun `番号类型归类`() {
        assertEquals(ScrapeChainType.FC2, chain.chainTypeOf("FC2-PPV-1234567"))
        assertEquals(ScrapeChainType.GETCHU, chain.chainTypeOf("GETCHU-325440"))
        assertEquals(ScrapeChainType.GYUTTO, chain.chainTypeOf("GYUTTO-0123456"))
        assertEquals(ScrapeChainType.CID, chain.chainTypeOf("12345678"))
        assertEquals(ScrapeChainType.NORMAL, chain.chainTypeOf("ABP-123"))
        assertEquals(ScrapeChainType.NORMAL, chain.chainTypeOf("HEYZO-1031"))
        assertEquals(ScrapeChainType.NORMAL, chain.chainTypeOf("CARIB-081621-001"))
    }

    @Test
    fun `按类型返回对应刮削链并过滤未注册源`() {
        // 默认 fc2 链含 javmenu/fc2ppvdb/avsox，测试桩未注册 → 剩 fc2, javdb
        assertEquals(listOf("fc2", "javdb"), chain.chainFor("FC2-PPV-1234567"))
        // cid 链只有 fanza
        assertEquals(listOf("fanza"), chain.chainFor("12345678"))
        assertEquals(listOf("dl_getchu"), chain.chainFor("GETCHU-325440"))
        assertEquals(listOf("gyutto"), chain.chainFor("GYUTTO-0123456"))
    }

    @Test
    fun `链内全部源未注册时回退 sourceOrder`() {
        val emptyChain = ScraperChain(listOf("javbus", "missav").map(::FakeScraper), props)
        val order = emptyChain.chainFor("FC2-PPV-1234567")
        assertTrue(order.isNotEmpty())
        assertEquals(setOf("javbus", "missav"), order.toSet())
    }

    @Test
    fun `合并时封面候选按链序构建且 javdb 沉底`() {
        val scrapers = listOf(
            FakeScraper("javdb", MovieMetadata(number = "ABP-123", coverUrl = "https://javdb/c.jpg")),
            FakeScraper(
                "javbus",
                MovieMetadata(number = "ABP-123", coverUrl = "https://javbus/c.jpg", previewUrls = listOf("https://javbus/p1.jpg")),
            ),
            FakeScraper("airav", MovieMetadata(number = "ABP-123", coverUrl = "https://airav/c.jpg")),
        )
        val chain2 = ScraperChain(scrapers, props)
        // chainNormal 默认 airav → javbus → javdb（三个测试源全部注册命中）
        val merged = chain2.mergeSources(chain2.scrapeAllSources("ABP-123"))
        // javdb 封面（站点水印）固定沉底：airav → javbus → javdb
        assertEquals(
            listOf("airav", "javbus", "javdb"),
            merged.coverCandidates.map { it.source },
        )
        assertEquals("https://airav/c.jpg", merged.coverUrl)
        assertEquals("airav", merged.coverSource)
        assertEquals("javbus", merged.previewSource)
        assertEquals(listOf("https://javbus/p1.jpg"), merged.previewUrls)
    }

    @Test
    fun `合并时每张剧照记录各自来源（逐张路由）`() {
        // 多源各有剧照：javbus 的剧照来自 dmm 图床、javdb 的来自 jdbstatic，
        // 只留单一 previewSource 会把 javdb 的图错发到 javbus 的直连通道
        val scrapers = listOf(
            FakeScraper("javbus", MovieMetadata(number = "ABP-123", previewUrls = listOf("https://pics.dmm.co.jp/hmn00607jp-1.jpg"))),
            FakeScraper("javdb", MovieMetadata(number = "ABP-123", previewUrls = listOf("https://c0.jdbstatic.com/nKKYEM_l_0.jpg"))),
        )
        val chain2 = ScraperChain(scrapers, props)
        val merged = chain2.mergeSources(chain2.scrapeAllSources("ABP-123"))

        assertEquals("javbus", merged.previewSource, "整体来源仍取链序第一个有剧照的源")
        assertEquals(
            listOf("javbus", "javdb"),
            merged.previewCandidates.map { it.source },
            "每张剧照应记录产生它的数据源",
        )
        assertEquals(merged.previewUrls, merged.previewCandidates.map { it.url }, "候选顺序应与 previewUrls 一致")
    }

    @Test
    fun `单源刮削时剧照来源即该源`() {
        val scrapers = listOf(
            FakeScraper("javdb", MovieMetadata(number = "ABP-123", previewUrls = listOf("https://c0.jdbstatic.com/a.jpg"))),
        )
        val (source, meta) = ScraperChain(scrapers, props).scrapeWithSource("ABP-123")
        assertEquals("javdb", source)
        assertEquals(
            listOf(CoverCandidate("https://c0.jdbstatic.com/a.jpg", "javdb")),
            meta.previewCandidates,
        )
    }

    @Test
    fun `候选排序：用户所选封面置顶并去重`() {
        val pool = listOf(
            CoverCandidate("https://javbus/c.jpg", "javbus"),
            CoverCandidate("https://javdb/c.jpg", "javdb"),
            CoverCandidate("https://missav/c.jpg", "missav"),
        )
        val ordered = ScraperChain.orderCoverCandidates(CoverCandidate("https://javdb/c.jpg", "javdb"), pool)
        // 用户明确选择 javdb → 置顶（覆盖沉底规则）；无重复 URL
        assertEquals(listOf("javdb", "javbus", "missav"), ordered.map { it.source })
    }
}
