package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties

class ScraperChainTest {

    private class FakeScraper(private val id: String) : Scraper {
        override fun id() = id
        override fun scrape(number: String): MovieMetadata =
            throw ScrapeException("测试桩：不发起网络请求")
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
}
