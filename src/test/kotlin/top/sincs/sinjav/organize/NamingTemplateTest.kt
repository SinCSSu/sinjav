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
}
