package top.sincs.sinjav.organize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PartSuffixTest {

    private fun extract(fileName: String, number: String = "SSIS-001") = PartSuffix.extract(fileName, number)

    @Test
    fun `显式分片标记`() {
        assertEquals("cd1", extract("SSIS-001-cd1.mp4"))
        assertEquals("cd2", extract("SSIS-001_cd2.mp4"))
        assertEquals("cd1", extract("SSIS-001 part1.mkv"))
        assertEquals("cd2", extract("SSIS-001-pt2.mkv"))
        assertEquals("cd1", extract("SSIS-001-disc1.mp4"))
        assertEquals("cd2", extract("SSIS-001 disk2.mp4"))
    }

    @Test
    fun `显式分片去掉前导零`() {
        assertEquals("cd1", extract("SSIS-001-CD01.mp4"))
    }

    @Test
    fun `单字母后缀映射为序号`() {
        assertEquals("cd1", extract("SSIS-001-A.mp4"))
        assertEquals("cd2", extract("SSIS-001_b.mp4"))
        assertEquals("cd3", extract("SSIS-001-C.mp4"))
    }

    @Test
    fun `数字后缀映射为序号`() {
        assertEquals("cd1", extract("SSIS-001-1.mp4"))
        assertEquals("cd2", extract("SSIS-001_2.mp4"))
        assertEquals("cd10", extract("SSIS-001-10.mp4"))
    }

    @Test
    fun `中文上下集`() {
        assertEquals("cd1", extract("SSIS-001 上集.mp4"))
        assertEquals("cd2", extract("SSIS-001-下集.mp4"))
        assertEquals("cd1", extract("SSIS-001前篇.mp4"))
        assertEquals("cd2", extract("SSIS-001-后篇.mp4"))
    }

    @Test
    fun `分片后缀后可带其他说明`() {
        assertEquals("cd1", extract("SSIS-001-A 中文字幕.mp4"))
        assertEquals("cd2", extract("SSIS-001-cd2 [1080p].mkv"))
    }

    @Test
    fun `单文件影片不产生分片`() {
        assertNull(extract("SSIS-001.mp4"))
        assertNull(extract("SSIS-001.mkv"))
    }

    @Test
    fun `画质与字幕标记不误判为分片`() {
        assertNull(extract("SSIS-001-1080p.mkv"))
        assertNull(extract("SSIS-001-4K.mkv"))
        assertNull(extract("SSIS-001-CH.mp4"))   // 中文字幕
        assertNull(extract("SSIS-001-UC.mp4"))   // 无码
        assertNull(extract("SSIS-001-10bit.mkv"))
        assertNull(extract("SSIS-001-hhd800.com.mp4"))
    }

    @Test
    fun `带站点前缀的文件名仍能定位分片`() {
        assertEquals("cd1", extract("hhd800.com@SSIS-001-A.mp4"))
        assertEquals("cd2", extract("[ThZu.Cc]SSIS-001-cd2.mkv"))
    }

    @Test
    fun `番号写法分隔符不同也能定位`() {
        assertEquals("cd1", extract("ssis_001_A.mp4"))
        assertEquals("cd2", extract("SSIS 001 cd2.mp4"))
    }

    @Test
    fun `其他番号形态`() {
        assertEquals("cd1", extract("FC2-PPV-1234567-A.mp4", "FC2-PPV-1234567"))
        assertEquals("cd2", extract("MIDE-882-cd2.mp4", "MIDE-882"))
        assertEquals("cd1", extract("heyzo_hdid_1031-A.mp4", "HEYZO-1031"))
    }

    @Test
    fun `番号数字段在文件名中不存在时返回null`() {
        assertNull(extract("ABC-999-A.mp4", "SSIS-001"))
        assertNull(PartSuffix.extract("SSIS-001-A.mp4", ""))
    }

    @Test
    fun `无码站番号也支持分片`() {
        assertEquals("cd1", extract("1pondo 081621_001-A.mp4", "CARIB-081621-001"))
    }
}
