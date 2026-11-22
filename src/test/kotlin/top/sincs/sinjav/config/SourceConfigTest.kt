package top.sincs.sinjav.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourceConfigTest {

    @Test
    fun `未配置时使用内置默认站点地址`() {
        val props = AppProperties()
        assertEquals("https://www.javbus.com", props.baseUrlFor("javbus"))
        assertEquals("https://www.mgstage.com", props.baseUrlFor("mgstage"))
        assertNull(props.sourceConfig("javbus"))
    }

    @Test
    fun `数据源独立配置覆盖站点地址与 Cookie`() {
        val props = AppProperties()
        props.scraper.sources["javbus"] = SourceConfig(
            baseUrl = "https://javbus.example", cookie = "session=abc", timeoutMs = 8000, intervalMs = 500,
        )
        assertEquals("https://javbus.example", props.baseUrlFor("javbus"))
        assertEquals("session=abc", props.cookieFor("javbus"))
        assertEquals(8000, props.sourceConfig("javbus")?.timeoutMs)
        assertEquals(500, props.sourceConfig("javbus")?.intervalMs)
    }

    @Test
    fun `鉴权 Cookie 解析优先级为显式传入大于独立配置大于内置默认`() {
        val props = AppProperties()
        // 内置年龄验证 Cookie
        assertEquals("adc=1", props.cookieFor("mgstage"))
        assertEquals("__age_auth__=true", props.cookieFor("prestige"))
        assertEquals("age_check_done=1", props.cookieFor("fanza"))
        // javdb 内置回退全局 javdbCookie
        props.scraper.javdbCookie = "legacy-cookie"
        assertEquals("legacy-cookie", props.cookieFor("javdb"))
        // 独立配置覆盖内置
        props.scraper.sources["javdb"] = SourceConfig(cookie = "new-cookie")
        assertEquals("new-cookie", props.cookieFor("javdb"))
        // 显式传入最高优先（如 arzon 年龄验证动态 Cookie）
        assertEquals("dynamic", props.cookieFor("mgstage", "dynamic"))
    }

    @Test
    fun `arzon 系默认同站 独立配置互不影响`() {
        val props = AppProperties()
        // 默认同一站点
        assertEquals(props.baseUrlFor("arzon"), props.baseUrlFor("arzon_iv"))
        // 覆盖只作用于各自配置行
        props.scraper.sources["arzon"] = SourceConfig(baseUrl = "https://arzon.example")
        assertEquals("https://arzon.example", props.baseUrlFor("arzon"))
        assertEquals("https://www.arzon.jp", props.baseUrlFor("arzon_iv"))
        props.scraper.sources["arzon_iv"] = SourceConfig(baseUrl = "https://arzon-iv.example")
        assertEquals("https://arzon-iv.example", props.baseUrlFor("arzon_iv"))
    }
}
