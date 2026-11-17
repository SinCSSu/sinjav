package top.sincs.sinjav.organize

import java.nio.file.Path

/** 目标文件冲突处理 */
enum class ConflictPolicy { SKIP, OVERWRITE, RENAME }

/** 整理方式：hardlink / symlink / move / copy */
interface LinkStrategy {
    val mode: String

    /**
     * 将源文件落地到目标路径。返回实际写入的目标路径（rename 冲突策略时可能变化）。
     * 跨分区/权限不足等问题以异常抛出，由调用方记录明细，不做静默降级。
     */
    fun place(source: Path, target: Path, conflict: ConflictPolicy): Path
}

abstract class BaseLinkStrategy : LinkStrategy {

    protected fun resolveTarget(source: Path, target: Path, conflict: ConflictPolicy): Path {
        val parent = target.parent ?: throw IllegalArgumentException("目标路径缺少父目录: $target")
        java.nio.file.Files.createDirectories(parent)
        if (!java.nio.file.Files.exists(target)) return target
        return when (conflict) {
            ConflictPolicy.SKIP -> throw OrganizeSkipException("目标已存在: $target")
            ConflictPolicy.OVERWRITE -> {
                java.nio.file.Files.delete(target)
                target
            }
            ConflictPolicy.RENAME -> {
                var i = 1
                var candidate: Path
                val base = target.fileName.toString()
                val stem = base.substringBeforeLast('.')
                val ext = base.substringAfterLast('.', "")
                do {
                    candidate = parent.resolve("$stem($i).$ext")
                    i++
                } while (java.nio.file.Files.exists(candidate))
                candidate
            }
        }
    }
}

class OrganizeSkipException(message: String) : Exception(message)

/** 硬链接：需同一分区；Windows 需管理员权限或开发者模式 */
class HardLinkStrategy : BaseLinkStrategy() {
    override val mode = "hardlink"

    override fun place(source: Path, target: Path, conflict: ConflictPolicy): Path {
        val t = resolveTarget(source, target, conflict)
        try {
            java.nio.file.Files.createLink(t, source)
        } catch (e: Exception) {
            throw IllegalStateException(
                "硬链接失败（需同一分区；Windows 下需管理员权限或开发者模式）: ${e.message}", e,
            )
        }
        return t
    }
}

/** 符号链接：Windows 需管理员权限或开发者模式 */
class SymLinkStrategy : BaseLinkStrategy() {
    override val mode = "symlink"

    override fun place(source: Path, target: Path, conflict: ConflictPolicy): Path {
        val t = resolveTarget(source, target, conflict)
        try {
            java.nio.file.Files.createSymbolicLink(t, source.toAbsolutePath())
        } catch (e: Exception) {
            throw IllegalStateException("符号链接失败（Windows 下需管理员权限或开发者模式）: ${e.message}", e)
        }
        return t
    }
}

/** 移动（唯一会修改源目录的模式） */
class MoveStrategy : BaseLinkStrategy() {
    override val mode = "move"

    override fun place(source: Path, target: Path, conflict: ConflictPolicy): Path {
        val t = resolveTarget(source, target, conflict)
        java.nio.file.Files.move(source, t)
        return t
    }
}

/** 复制 */
class CopyStrategy : BaseLinkStrategy() {
    override val mode = "copy"

    override fun place(source: Path, target: Path, conflict: ConflictPolicy): Path {
        val t = resolveTarget(source, target, conflict)
        java.nio.file.Files.copy(source, t)
        return t
    }
}
