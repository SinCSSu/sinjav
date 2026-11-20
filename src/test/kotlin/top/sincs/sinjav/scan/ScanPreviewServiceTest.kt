package top.sincs.sinjav.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.config.ScanProps
import java.nio.file.Files

/** 预览扫描：同步遍历返回候选文件，仅保留识别出番号的文件，支持文件名正则过滤与截断。 */
class ScanPreviewServiceTest {

    /** globalMinMb：全局配置的最小文件大小（模拟用户在设置页配置的默认值） */
    private fun newService(cap: Int = 5000, globalMinMb: Long = 0): ScanPreviewService =
        ScanPreviewService(
            AppProperties(scan = ScanProps(minFileSizeMb = globalMinMb)),
            DirectoryScanner(AppProperties(scan = ScanProps(minFileSizeMb = globalMinMb))),
            NumberRecognizer(),
            cap,
        )

    @Test
    fun `预览仅返回识别出番号的文件`() {
        val base = Files.createTempDirectory("sinjav-preview")
        val big = ByteArray(2 * 1024 * 1024)
        Files.write(base.resolve("PFES-107-UC.mp4"), big)
        Files.write(base.resolve("test.mp4"), big)
        Files.write(base.resolve("tiny.mp4"), ByteArray(1024))
        Files.createDirectory(base.resolve("sub"))
        Files.write(base.resolve("sub").resolve("ABCD-001.mp4"), big)

        val resp = newService().preview(
            sourceDirs = listOf(base.toString()),
            recursive = true,
            extensions = null,
            minFileSizeMb = 0,
            filenamePattern = null,
        )
        assertEquals(
            listOf("PFES-107-UC.mp4", "ABCD-001.mp4"),
            resp.items.map { it.fileName },
            "应只保留识别出番号的大文件（过滤 test/tiny）",
        )
        val pfes = resp.items.first { it.fileName == "PFES-107-UC.mp4" }
        assertEquals("PFES-107", pfes.number)
        assertEquals("recognized", pfes.recognizeStatus)
        assertTrue(resp.items.any { it.fileName == "ABCD-001.mp4" }, "递归应包含子目录文件")
        assertFalse(resp.truncated)
    }

    @Test
    fun `文件名正则过滤不匹配的文件`() {
        val base = Files.createTempDirectory("sinjav-preview2")
        val big = ByteArray(2 * 1024 * 1024)
        Files.write(base.resolve("PFES-107-UC.mp4"), big)
        Files.write(base.resolve("test.mp4"), big)

        val resp = newService().preview(
            sourceDirs = listOf(base.toString()),
            recursive = true,
            extensions = null,
            minFileSizeMb = 0,
            filenamePattern = "(?i)(?:^|[^a-z0-9])[a-z]{2,6}[-_]?\\d{2,5}",
        )
        assertEquals(listOf("PFES-107-UC.mp4"), resp.items.map { it.fileName })
    }

    @Test
    fun `非法正则返回400`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            newService().preview(
                sourceDirs = listOf(Files.createTempDirectory("p3").toString()),
                recursive = true, extensions = null, minFileSizeMb = 0,
                filenamePattern = "([",
            )
        }
    }

    @Test
    fun `超出上限截断`() {
        val base = Files.createTempDirectory("sinjav-preview4")
        val big = ByteArray(2 * 1024 * 1024)
        repeat(5) { Files.write(base.resolve("ABCD-00$it.mp4"), big) }
        val resp = newService(cap = 3).preview(
            sourceDirs = listOf(base.toString()),
            recursive = true, extensions = null, minFileSizeMb = 0, filenamePattern = null,
        )
        assertEquals(3, resp.items.size)
        assertTrue(resp.truncated)
        assertEquals(5L, resp.total)
    }

    @Test
    fun `高级选项为0时回退到全局最小文件大小`() {
        val base = Files.createTempDirectory("sinjav-preview5")
        Files.write(base.resolve("ABCD-001.mp4"), ByteArray(2 * 1024 * 1024)) // 2MB
        Files.write(base.resolve("ABCD-002.mp4"), ByteArray(512 * 1024)) // 0.5MB

        // 全局配置 1MB：高级选项填 0 → 生效全局，0.5MB 文件被过滤
        val resp = newService(globalMinMb = 1).preview(
            sourceDirs = listOf(base.toString()),
            recursive = true, extensions = null, minFileSizeMb = 0, filenamePattern = null,
        )
        assertEquals(listOf("ABCD-001.mp4"), resp.items.map { it.fileName })

        // 高级选项显式覆盖：填 3MB → 全部被过滤
        val resp2 = newService(globalMinMb = 1).preview(
            sourceDirs = listOf(base.toString()),
            recursive = true, extensions = null, minFileSizeMb = 3, filenamePattern = null,
        )
        assertEquals(0, resp2.total)
    }

    private fun assertFalse(b: Boolean) = org.junit.jupiter.api.Assertions.assertFalse(b)
}
