package top.sincs.sinjav.organize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.sincs.sinjav.domain.Movie
import java.time.LocalDate
import java.time.Instant

class NamingTemplateTest {

    private val t = NamingTemplate()

    private fun movie() = Movie(
        id = 1, number = "ABP-123", rawNumber = "abp-123", title = "Title: Test/1?",
        titleZh = null, releaseDate = LocalDate.of(2024, 5, 1), director = null,
        maker = "Studio", label = null, series = "Series", durationMin = 120,
        coverUrl = null, previewUrls = emptyList(), plot = null, source = "javbus",
        status = "scraped", failReason = null, scrapedAt = Instant.now(),
        createdAt = Instant.now(), updatedAt = Instant.now(),
    )

    @Test
    fun `占位符渲染`() {
        val out = t.render("{number}/{number} - {title}", movie(), listOf("Actress A", "Actress B"))
        // 标题中的 '/' 按路径分隔符处理，':''?' 被清洗
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_", out)
    }

    @Test
    fun `标题优先使用原始标题`() {
        val m = movie().copy(titleOrig = "Original: Title/2?", title = "中文标题")
        assertEquals("ABP-123/ABP-123 - Original_ Title/2_", t.render("{number}/{number} - {title}", m))
    }

    @Test
    fun `date maker series actress 占位符`() {
        val out = t.render("{date}/{maker}-{series}-{actress}", movie(), listOf("Sora"))
        assertEquals("2024-05-01/Studio-Series-Sora", out)
    }

    @Test
    fun `缺省字段为空`() {
        val out = t.render("{number}/{actress}", movie(), emptyList())
        assertEquals("ABP-123", out)
    }

    @Test
    fun `非法字符清洗`() {
        assertEquals("a_b__c", t.sanitizeFileName("a<b>:c"))
        assertEquals("_CON", t.sanitizeFileName("CON"))
    }

    @Test
    fun `part 占位符渲染分片后缀`() {
        assertEquals("ABP-123/ABP-123", t.render("{number}/{number}-{part}", movie()))
        assertEquals("ABP-123/ABP-123-cd1", t.render("{number}/{number}-{part}", movie(), part = "cd1"))
        assertEquals("ABP-123/ABP-123-cd2", t.render("{number}/{number}-{part}", movie(), part = "cd2"))
    }

    @Test
    fun `无分片时 part 连同前导分隔符一起消失`() {
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_", t.render("{number}/{number} - {title}-{part}", movie()))
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_", t.render("{number}/{number} - {title} - {part}", movie()))
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_", t.render("{number}/{number} - {title}{part}", movie()))
    }

    @Test
    fun `有分片时两种写法都得到短横线加序号`() {
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_-cd1", t.render("{number}/{number} - {title}-{part}", movie(), part = "cd1"))
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_-cd1", t.render("{number}/{number} - {title}{part}", movie(), part = "cd1"))
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_-cd2", t.render("{number}/{number} - {title} - {part}", movie(), part = "cd2"))
    }

    @Test
    fun `version 占位符渲染空格短横线空格加版本名`() {
        assertEquals("ABP-123/ABP-123", t.render("{number}/{number}{version}", movie()))
        assertEquals("ABP-123/ABP-123 - 1080p", t.render("{number}/{number}{version}", movie(), version = "1080p"))
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_ - 4K", t.render("{number}/{number} - {title}{version}", movie(), version = "4K"))
        assertEquals("ABP-123/ABP-123 - Title_ Test/1_ - 1080p", t.render("{number}/{number} - {title} - {version}", movie(), version = "1080p"))
    }

    @Test
    fun `part 与 version 可同时使用`() {
        assertEquals(
            "ABP-123/ABP-123 - Title_ Test/1_-cd1 - 1080p",
            t.render("{number}/{number} - {title}{part}{version}", movie(), part = "cd1", version = "1080p"),
        )
        // 只有其中一个时，另一个不留下多余分隔符
        assertEquals(
            "ABP-123/ABP-123 - Title_ Test/1_-cd1",
            t.render("{number}/{number} - {title}{part}{version}", movie(), part = "cd1"),
        )
        assertEquals(
            "ABP-123/ABP-123 - Title_ Test/1_ - 1080p",
            t.render("{number}/{number} - {title}{part}{version}", movie(), version = "1080p"),
        )
    }
}
