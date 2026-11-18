package top.sincs.sinjav.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/** 视频流接口：全量 200 与 Range 分片 206。 */
class VideoControllerTest {

    private val controller = VideoController()

    private fun tempVideo(bytes: ByteArray, ext: String = "mp4"): String {
        val f = Files.createTempFile("sinjav-video-", ".$ext")
        Files.write(f, bytes)
        return f.toAbsolutePath().toString()
    }

    @Test
    fun `无Range请求返回完整文件`() {
        val content = ByteArray(300) { it.toByte() }
        val path = tempVideo(content)
        val resp = controller.video(path, range = null)
        assertEquals(200, resp.statusCode.value())
        assertEquals("300", resp.headers.getFirst("Content-Length"))
        assertEquals("bytes", resp.headers.getFirst("Accept-Ranges"))
        assertEquals("video/mp4", resp.headers.getFirst("Content-Type"))
        val body = resp.body!!.inputStream.readAllBytes()
        assertTrue(body.contentEquals(content))
    }

    @Test
    fun `Range请求返回206分片`() {
        val content = ByteArray(300) { it.toByte() }
        val path = tempVideo(content)

        val resp = controller.video(path, range = "bytes=0-99")
        assertEquals(206, resp.statusCode.value())
        assertEquals("bytes 0-99/300", resp.headers.getFirst("Content-Range"))
        assertEquals("100", resp.headers.getFirst("Content-Length"))
        assertTrue(resp.body!!.inputStream.readAllBytes().contentEquals(content.copyOfRange(0, 100)))

        // 开放右端
        val resp2 = controller.video(path, range = "bytes=100-")
        assertEquals(206, resp2.statusCode.value())
        assertEquals("bytes 100-299/300", resp2.headers.getFirst("Content-Range"))
        assertTrue(resp2.body!!.inputStream.readAllBytes().contentEquals(content.copyOfRange(100, 300)))

        // 后缀区间（最后 50 字节）
        val resp3 = controller.video(path, range = "bytes=-50")
        assertEquals("bytes 250-299/300", resp3.headers.getFirst("Content-Range"))
        assertTrue(resp3.body!!.inputStream.readAllBytes().contentEquals(content.copyOfRange(250, 300)))
    }

    @Test
    fun `按扩展名推断Content-Type`() {
        val path = tempVideo(ByteArray(10), ext = "mkv")
        val resp = controller.video(path, range = null)
        assertEquals("video/x-matroska", resp.headers.getFirst("Content-Type"))
    }

    @Test
    fun `文件不存在返回404`() {
        assertThrows<NoSuchElementException> { controller.video("Z:/__nope__.mp4", range = null) }
    }

    @Test
    fun `非法Range头按全量处理`() {
        val content = ByteArray(50) { it.toByte() }
        val path = tempVideo(content)
        val resp = controller.video(path, range = "bytes=abc")
        assertEquals(200, resp.statusCode.value())
    }
}
