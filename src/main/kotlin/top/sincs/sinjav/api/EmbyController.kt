package top.sincs.sinjav.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.sincs.sinjav.api.dto.EmbyItemDetailDto
import top.sincs.sinjav.api.dto.EmbyPageDto
import top.sincs.sinjav.api.dto.EmbyLibraryDto
import top.sincs.sinjav.api.dto.EmbyMetadataUpdate
import top.sincs.sinjav.api.dto.EmbyRescrapeApplyRequest
import top.sincs.sinjav.api.dto.EmbyRescrapePreviewDto
import top.sincs.sinjav.api.dto.EmbyRescrapePreviewRequest
import top.sincs.sinjav.emby.EmbyClient
import top.sincs.sinjav.emby.EmbyException
import top.sincs.sinjav.emby.EmbyRescrapeService

/** Emby 集成：媒体库/条目只读代理 + 元数据编辑 + 本地刮削回写（基础实现，实时代理不落库） */
@RestController
@RequestMapping("/api/v1")
class EmbyController(private val emby: EmbyClient, private val rescrape: EmbyRescrapeService) {

    /** 测试连接：返回 Emby 服务器名称与版本 */
    @GetMapping("/emby/status")
    fun status(): Map<String, Any?> {
        val info = emby.serverInfo()
        return mapOf(
            "serverName" to info.path("ServerName").asString(""),
            "version" to info.path("Version").asString(""),
        )
    }

    @GetMapping("/emby/libraries")
    fun libraries(): List<EmbyLibraryDto> = emby.listLibraries()

    @GetMapping("/emby/items")
    fun items(
        @RequestParam libraryId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "24") size: Int,
        @RequestParam(required = false) keyword: String?,
    ): EmbyPageDto {
        if (libraryId.isBlank()) throw IllegalArgumentException("libraryId 不能为空")
        val p = page.coerceAtLeast(1)
        val s = size.coerceIn(1, 100)
        return emby.listItems(libraryId, (p - 1) * s, s, keyword?.trim()?.ifBlank { null })
    }

    @GetMapping("/emby/items/{id}")
    fun item(@PathVariable id: String): EmbyItemDetailDto = emby.getItem(id)

    /** 修改元数据：仅提交需要修改的字段，返回更新后的完整条目 */
    @PutMapping("/emby/items/{id}/metadata")
    fun updateMetadata(@PathVariable id: String, @RequestBody req: EmbyMetadataUpdate): EmbyItemDetailDto =
        emby.updateMetadata(id, req)

    /** 重新刮削预览：识别番号（可手动指定）→ 并发抓全源 → 返回各源原始值、合并默认值与前后对比（不推送，同步执行） */
    @PostMapping("/emby/items/{id}/rescrape/preview")
    fun rescrapePreview(
        @PathVariable id: String,
        @RequestBody(required = false) req: EmbyRescrapePreviewRequest?,
    ): EmbyRescrapePreviewDto = rescrape.preview(id, req?.number?.trim()?.ifBlank { null })

    /** 重新刮削应用：按逐字段来源选择（各源/留空/manual 手动输入）组装元数据并回写 Emby（同步） */
    @PostMapping("/emby/items/{id}/rescrape/apply")
    fun rescrapeApply(
        @PathVariable id: String,
        @RequestBody req: EmbyRescrapeApplyRequest,
    ): EmbyMetadataUpdate = rescrape.apply(id, req)

    /** 封面代理：Emby 主图经后端转发，不暴露 API Key；无图时 404 */
    @GetMapping("/emby/items/{id}/images/primary")
    fun primaryImage(
        @PathVariable id: String,
        @RequestParam(defaultValue = "300") maxWidth: Int,
    ): ResponseEntity<ByteArray> =
        try {
            val img = emby.downloadPrimaryImage(id, maxWidth.coerceIn(50, 2000))
            ResponseEntity.ok()
                .header("Content-Type", img.contentType ?: "image/jpeg")
                .header("Cache-Control", "max-age=86400")
                .body(img.bytes)
        } catch (e: EmbyException) {
            if (e.status == 404) ResponseEntity.notFound().build() else throw e
        }
}
