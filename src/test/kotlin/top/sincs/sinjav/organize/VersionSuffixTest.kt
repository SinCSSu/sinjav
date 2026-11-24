package top.sincs.sinjav.organize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VersionSuffixTest {

    private fun extract(fileName: String, number: String = "SSIS-001") = VersionSuffix.extract(fileName, number)

    @Test
    fun `分辨率标记`() {
        assertEquals("1080p", extract("SSIS-001-1080p.mp4"))
        assertEquals("720p", extract("SSIS-001.720p.mkv"))
        assertEquals("2160p", extract("SSIS-001_2160p.mp4"))
        assertEquals("4K", extract("SSIS-001-4K.mkv"))
        assertEquals("8K", extract("SSIS-001 8K.mp4"))
        assertEquals("2K", extract("SSIS-001-2K.mp4"))
        assertEquals("FHD", extract("SSIS-001-FHD.mkv"))
        assertEquals("UHD", extract("SSIS-001 UHD.mp4"))
    }

    @Test
    fun `来源与字幕标记`() {
        assertEquals("无码", extract("SSIS-001-无码.mp4"))
        assertEquals("无码", extract("SSIS-001-無碼.mp4"))
        assertEquals("中字", extract("SSIS-001 中文字幕.mp4"))
        assertEquals("中字", extract("SSIS-001-中字.mp4"))
        assertEquals("破解", extract("SSIS-001-破解版.mp4"))
    }

    @Test
    fun `多个标记按固定顺序拼接`() {
        assertEquals("1080p-中字", extract("SSIS-001-1080p-中文字幕.mp4"))
        assertEquals("4K-无码", extract("SSIS-001-4K-无码.mp4"))
        assertEquals("1080p-无码-中字", extract("SSIS-001-1080p-无码-中字.mp4"))
    }

    @Test
    fun `无版本标记返回null`() {
        assertNull(extract("SSIS-001.mp4"))
        assertNull(extract("SSIS-001-A.mp4"))     // 分片不是版本
        assertNull(extract("SSIS-001-cd1.mp4"))
        assertNull(extract("SSIS-001-上集.mp4"))
    }

    @Test
    fun `站点水印不被误判为分辨率`() {
        // hhd800 里的 hd 前是字母，不满足词边界
        assertEquals("1080p", extract("hhd800.com@SSIS-001-1080p.mp4"))
        assertNull(extract("hhd800.com@SSIS-001.mp4"))
    }

    @Test
    fun `番号自身的字母不被误判`() {
        // 番号 HD-123 先被抹掉，其中的 HD 不应被当成分辨率标记
        assertEquals("1080p", extract("HD-123-1080p.mp4", "HD-123"))
        assertNull(extract("HD-123.mp4", "HD-123"))
    }

    @Test
    fun `FC2 与无码站番号`() {
        assertEquals("1080p", extract("FC2-PPV-1234567-1080p.mp4", "FC2-PPV-1234567"))
        assertEquals("4K", extract("1pondo 081621_001 4K.mp4", "CARIB-081621-001"))
    }
}
