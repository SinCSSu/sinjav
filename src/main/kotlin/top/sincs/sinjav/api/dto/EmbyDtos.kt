package top.sincs.sinjav.api.dto

// ---------- Emby 集成 ----------

data class EmbyLibraryDto(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class EmbyItemSummaryDto(
    val id: String,
    val name: String,
    val originalTitle: String?,
    val productionYear: Int?,
    val premiereDate: String?,
    val path: String?,
    /** 是否有主图（封面）；封面统一走 /emby/items/{id}/images/primary 代理 */
    val hasImage: Boolean,
)

data class EmbyPageDto(
    val page: Int,
    val size: Int,
    val total: Long,
    val items: List<EmbyItemSummaryDto>,
)

data class EmbyItemDetailDto(
    val id: String,
    val name: String,
    val originalTitle: String?,
    val overview: String?,
    val genres: List<String>,
    val studios: List<String>,
    val tags: List<String>,
    val premiereDate: String?,
    val productionYear: Int?,
    val officialRating: String?,
    val communityRating: Double?,
    val runtimeMin: Int?,
    val path: String?,
    val providerIds: Map<String, String>,
    val dateCreated: String?,
    val hasImage: Boolean,
)

/** 元数据修改请求：仅提交需要修改的字段，null 不动 */
data class EmbyMetadataUpdate(
    val name: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val studios: List<String>? = null,
    val tags: List<String>? = null,
    val premiereDate: String? = null,
    val productionYear: Int? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
)

/** 本地刮削预览：返回刮削前后的元数据对比，确认后再应用 */
data class EmbyRescrapePreviewDto(
    val itemId: String,
    /** 从条目文件名/原标题/标题识别出的番号（或手动输入） */
    val matchedNumber: String,
    /** 刮削数据源（javbus/javdb/missav/merged） */
    val source: String,
    /** 刮削前的当前 Emby 元数据 */
    val current: EmbyItemDetailDto,
    /** 刮削得到的元数据（待确认应用） */
    val proposed: EmbyMetadataUpdate,
)

/** 刮削预览请求：自动识别失败时可手动指定番号 */
data class EmbyRescrapePreviewRequest(
    val number: String? = null,
)
