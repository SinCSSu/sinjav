package top.sincs.sinjav.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** 目录树节点 */
data class FsNode(val path: String, val name: String)

/** 子目录列表 */
data class FsDirListing(val path: String, val directories: List<FsNode>)

/**
 * 文件系统浏览接口（目录树）：支持 Windows 盘符与 Linux（Docker）根目录。
 * 与扫描一致：本地只读、零侵入。
 */
@RestController
@RequestMapping("/api/v1/fs")
class FsController {

    /** 根节点列表：Windows 为各盘符，Linux 为 / */
    @GetMapping("/roots")
    fun roots(): List<FsNode> =
        File.listRoots().map { root ->
            val path = root.absolutePath
            FsNode(path = path, name = path)
        }

    /** 懒加载：列出指定目录的子目录（按名称排序，不含文件） */
    @GetMapping("/dirs")
    fun dirs(@RequestParam path: String): FsDirListing {
        val dir = Path.of(path)
        if (!Files.isDirectory(dir)) throw NoSuchElementException("目录不存在或不是目录: $path")
        val children = Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { p ->
                    val abs = p.toAbsolutePath().toString()
                    FsNode(path = abs, name = p.fileName?.toString() ?: abs)
                }
                .sorted { a, b -> a.name.lowercase().compareTo(b.name.lowercase()) }
                .toList()
        }
        return FsDirListing(path = dir.toAbsolutePath().toString(), directories = children)
    }
}
