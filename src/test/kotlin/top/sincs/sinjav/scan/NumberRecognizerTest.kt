package top.sincs.sinjav.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NumberRecognizerTest {

    private val r = NumberRecognizer()

    @Test
    fun `标准有码番号`() {
        assertEquals("ABP-123", r.recognize("ABP-123.mp4")!!.normalized)
        assertEquals("SSIS-456", r.recognize("SSIS_456 [1080p].mkv")!!.normalized)
        assertEquals("ABCD-1", r.recognize("abcd-001.mp4")!!.normalized)
    }

    @Test
    fun `番号前导零被规范化`() {
        assertEquals("IPX-45", r.recognize("IPX-045.mp4")!!.normalized)
    }

    @Test
    fun `FC2番号`() {
        assertEquals("FC2-PPV-1234567", r.recognize("FC2-PPV-1234567.mp4")!!.normalized)
        assertEquals("FC2-PPV-1234567", r.recognize("fc2 1234567.mkv")!!.normalized)
    }

    @Test
    fun `Heyzo番号`() {
        assertEquals("HEYZO-1031", r.recognize("heyzo_hdid_1031.mp4")!!.normalized)
        assertEquals("HEYZO-1234", r.recognize("HEYZO-1234.mp4")!!.normalized)
    }

    @Test
    fun `无码站点番号需站点标识`() {
        assertEquals("CARIB-081621-001", r.recognize("1pondo 081621_001.mp4")!!.normalized)
        assertNull(r.recognize("081621-001.mp4")) // 无标识不归一，避免误伤
    }

    @Test
    fun `多候选取最后一个匹配`() {
        assertEquals("MIDE-882", r.recognize("MIDE-882-cd2.mp4")!!.normalized)
    }

    @Test
    fun `无法识别返回null`() {
        assertNull(r.recognize("私人收藏 精选合集 2024.mp4"))
    }

    @Test
    fun `normalize处理直接传入的番号`() {
        assertEquals("ABP-123", NumberRecognizer.normalize("ABP-123"))
        assertEquals("FC2-PPV-1234567", NumberRecognizer.normalize("fc2ppv-1234567"))
    }
}
