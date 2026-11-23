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

/** Emby 媒体库同步结果（POST /emby/sync，同步执行后全量替换 emby_items 快照） */
data class EmbySyncResultDto(
    /** 扫描的 Emby 条目总数 */
    val scanned: Int,
    /** 成功识别出番号的条目数 */
    val matched: Int,
    /** 快照时间 */
    val syncedAt: String,
)

/**
 * 图片推送请求（重新刮削界面「推送封面/推送剧照」）：
 * number 番号用于本地图片缓存与 FC2 裁剪排除；coverUrl/previewUrls 为重新刮削预览中的
 * 图片 URL（可空，为空时回退读取本地图片缓存）。
 */
data class EmbyImagePushRequest(
    val number: String,
    val coverUrl: String? = null,
    val previewUrls: List<String>? = null,
)

/** 图片推送结果：成功上传到 Emby 的图片数 */
data class EmbyImagePushResult(val pushed: Int)

/** 条目演员（Emby `People[]` 中的一项）；[role] 为 Emby 的角色名，JAV 库通常是 "Actor" */
data class EmbyActor(
    val id: String,
    val name: String,
    val role: String? = null,
)

/** Emby Person（`GET /emby/Persons` 的返回项）；[hasPrimaryImage] 标记该人物已有主图 */
data class EmbyPerson(
    val id: String,
    val name: String,
    val hasPrimaryImage: Boolean = false,
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
    /** 条目演员（People 中 Type 为空或 Actor 的项）；演员库同步的数据来源 */
    val actors: List<EmbyActor> = emptyList(),
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
    /** 多源对比：字段键 → 来源 id → 原文值（仅含该源有值的字段） */
    val fields: Map<String, Map<String, String>> = emptyMap(),
    /** 多源对比：字段键 → 合并规则的默认来源 id（该字段所有来源均无值时为 null） */
    val defaults: Map<String, String?> = emptyMap(),
    /** 多源对比：有结果的来源 id 列表（按 source-order 排序，供前端下拉选项） */
    val sources: List<String> = emptyList(),
)

/** 刮削预览请求：自动识别失败时可手动指定番号 */
data class EmbyRescrapePreviewRequest(
    val number: String? = null,
)

/** 多源对比重新刮削应用：按逐字段来源选择组装元数据并回写 Emby（留空字段不回写，manual 值视为最终文本不翻译） */
data class EmbyRescrapeApplyRequest(
    @field:jakarta.validation.constraints.NotBlank(message = "number 必填")
    val number: String,
    val fields: Map<String, ScrapeFieldChoice> = emptyMap(),
)
