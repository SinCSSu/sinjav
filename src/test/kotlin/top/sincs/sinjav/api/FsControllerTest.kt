package top.sincs.sinjav.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/** 目录树接口：盘符/根列表 + 子目录懒加载（Windows 盘符与 Linux 根均适配）。 */
class FsControllerTest {

    private val controller = FsController()

    @Test
    fun `根列表至少包含一个根节点`() {
        val roots = controller.roots()
        assertTrue(roots.isNotEmpty(), "至少应有一个根（Windows 盘符或 Linux /）")
        assertTrue(roots.all { it.path.isNotBlank() && it.name.isNotBlank() })
    }

    @Test
    fun `列出子目录且不含文件`() {
        val base = Files.createTempDirectory("sinjav-fs")
        val sub = Files.createDirectory(base.resolve("子目录A"))
        Files.writeString(base.resolve("file.txt"), "x")
        Files.createDirectory(base.resolve("B目录"))

        val listing = controller.dirs(base.toString())
        assertEquals(base.toAbsolutePath().toString(), listing.path)
        // 按名称排序：ASCII 字母在前，中文在后
        assertEquals(
            listOf(base.resolve("B目录").toAbsolutePath().toString(), sub.toAbsolutePath().toString()),
            listing.directories.map { it.path },
        )
        assertTrue(listing.directories.none { it.name.contains('.') && it.name.endsWith(".txt") })
    }

    @Test
    fun `路径不存在或不是目录返回404`() {
        assertThrows<NoSuchElementException> { controller.dirs("Z:/__不存在__/__nope__") }
        val file = Files.createTempFile("sinjav-fs-file", ".txt")
        assertThrows<NoSuchElementException> { controller.dirs(file.toString()) }
    }
}
