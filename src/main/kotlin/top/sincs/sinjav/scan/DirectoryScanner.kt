package top.sincs.sinjav.scan

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name

data class ScannedFile(
    val sourcePath: String,
    val fileName: String,
    val fileSize: Long,
    val fileMtime: Instant?,
)

@Component
class DirectoryScanner(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(DirectoryScanner::class.java)

    /**
     * 遍历目录（零侵入，只读取），过滤出视频文件。
     * @param extensions 自定义扩展名白名单，空则使用全局配置
     * @param minFileSizeMb 最小文件大小过滤，null 使用全局配置
     */
    fun scan(sourceDirs: List<String>, recursive: Boolean, extensions: Set<String>, minFileSizeMb: Long): List<ScannedFile> {
        val exts = if (extensions.isNotEmpty()) extensions else props.videoExtensionSet()
        val minSize = minFileSizeMb * 1024 * 1024
        val result = mutableListOf<ScannedFile>()
        for (dir in sourceDirs) {
            val root = Path.of(dir)
            if (!Files.isDirectory(root)) {
                log.warn("源目录不存在或不是目录，跳过: {}", dir)
                continue
            }
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.name.substringAfterLast('.', "").lowercase() in exts }
                    .forEach { p ->
                        try {
                            val size = Files.size(p)
                            if (size < minSize) return@forEach
                            result += ScannedFile(
                                sourcePath = p.toAbsolutePath().toString(),
                                fileName = p.name,
                                fileSize = size,
                                fileMtime = Files.getLastModifiedTime(p).toInstant(),
                            )
                        } catch (e: Exception) {
                            log.warn("读取文件属性失败 {}: {}", p, e.message)
                        }
                    }
            }
        }
        return result
    }
}
