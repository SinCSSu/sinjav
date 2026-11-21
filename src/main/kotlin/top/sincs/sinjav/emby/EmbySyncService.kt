package top.sincs.sinjav.emby

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.api.dto.EmbySyncResultDto
import top.sincs.sinjav.domain.EmbyItemRecord
import top.sincs.sinjav.domain.EmbyItemRepository
import top.sincs.sinjav.scan.NumberRecognizer
import java.nio.file.Path
import java.time.Instant

/**
 * Emby 媒体库同步：遍历全部电影媒体库，从条目路径/标题识别番号，
 * 全量替换写入 emby_items 快照表。
 * 查询侧据此给本地影片打「已存在于emby」标记，并把「emby 已存在但本地未整理」
 * 的文件归入整理弹窗的「已存在」分组。
 */
@Service
class EmbySyncService(
    private val emby: EmbyClient,
    private val embyItems: EmbyItemRepository,
    private val recognizer: NumberRecognizer,
) {
    private val log = LoggerFactory.getLogger(EmbySyncService::class.java)

    /** 每页拉取的条目数（/Items 接口分页上限内取较大值，减少请求数） */
    private val pageSize = 500

    /** 同步执行：返回扫描条目数与成功识别出番号的条目数 */
    fun sync(): EmbySyncResultDto {
        if (!emby.isConfigured()) {
            throw IllegalStateException("未配置 Emby 服务地址或 API Key，请在设置页填写后重试")
        }
        var scanned = 0
        val rows = mutableListOf<EmbyItemRecord>()
        for (library in emby.listLibraries()) {
            var start = 0
            while (true) {
                val page = emby.listItems(library.id, start, pageSize, null)
                for (item in page.items) {
                    scanned++
                    // 识别链：文件路径 → 原标题 → 标题（与 Emby 条目重新刮削一致）
                    val fileName = item.path?.let { Path.of(it).fileName.toString() } ?: ""
                    val number = listOf(fileName, item.originalTitle ?: "", item.name)
                        .firstNotNullOfOrNull { recognizer.recognize(it)?.normalized }
                    if (number != null) {
                        rows += EmbyItemRecord(
                            itemId = item.id, number = number,
                            name = item.name.ifBlank { null }, path = item.path,
                            syncedAt = Instant.now(),
                        )
                    }
                }
                start += pageSize
                if (page.items.isEmpty() || start >= page.total) break
            }
        }
        embyItems.replaceAll(rows)
        log.info("Emby 媒体库同步完成：扫描 {} 条，识别番号 {} 条", scanned, rows.size)
        return EmbySyncResultDto(
            scanned = scanned,
            matched = rows.size,
            syncedAt = Instant.now().toString(),
        )
    }
}
