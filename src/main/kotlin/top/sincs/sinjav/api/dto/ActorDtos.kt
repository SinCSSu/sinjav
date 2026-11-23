package top.sincs.sinjav.api.dto

import top.sincs.sinjav.actor.ActressRepository
import java.time.Instant

// ---------- 请求 ----------

/**
 * 演员编辑请求（PATCH /actors/{id}）：
 * - [alias]：规范名（Gfriends 匹配名 + 照片文件名）。null = 不修改；空串 = 清空（回退 `actresses.name`）；
 * - [aliases]：别名列表**全量替换**；省略/空数组即清空全部别名。
 */
data class UpdateActorDto(
    val alias: String? = null,
    val aliases: List<String> = emptyList(),
)

/** 批量补全头像请求（POST /actors/scrape-photos）：[actorIds] 为空 → 全部缺头像的演员 */
data class ScrapePhotosRequest(
    val actorIds: List<Long>? = null,
)

// ---------- 响应 ----------

/** 分页列表行（演员网格）：[movieCount] 为关联影片数（详情/排序展示用） */
data class ActorListDto(
    val id: Long,
    val name: String,
    /** 规范名（Gfriends 匹配名）；未设置为 null */
    val alias: String?,
    /**
     * 本地照片相对路径（如 `actors/夢乃あいか.jpg`），未抓取为 null。
     * 前端按 `/local-actors/{文件名}` 访问（WebConfig 把 `sinjav.actors.photo-dir` 映射为静态资源）。
     */
    val photoPath: String?,
    val movieCount: Long,
) {
    companion object {
        fun of(row: ActressRepository.ActressWithCount) = with(row.actress) {
            ActorListDto(id = id, name = name, alias = alias, photoPath = photoPath, movieCount = row.movieCount)
        }
    }
}

/** 演员详情：演员信息 + 别名列表 + 所属影片（movie_actresses JOIN movies） */
data class ActorDetailDto(
    val id: Long,
    val name: String,
    val alias: String?,
    /** 别名列表（不含规范名本身；Gfriends 匹配按此顺序逐一尝试） */
    val aliases: List<String>,
    val photoPath: String?,
    /** Emby Person Id（已同步 Emby 演员时回写的缓存值） */
    val embyPersonId: String?,
    val movieCount: Long,
    val movies: List<MovieBriefDto>,
    val updatedAt: String?,
) {
    companion object {
        fun of(detail: ActressRepository.ActressDetail, aliases: List<String>) = with(detail.actress) {
            ActorDetailDto(
                id = id, name = name, alias = alias, aliases = aliases, photoPath = photoPath,
                embyPersonId = embyPersonId, movieCount = detail.movieCount,
                movies = detail.movies.map { MovieBriefDto.of(it) },
                updatedAt = updatedAt?.toString(),
            )
        }
    }
}

/**
 * 演员所属影片的精简视图（详情弹窗的影片卡片）。
 *
 * [coverPath] 为本地封面 Web 路径（`/local-images/{番号}/poster.jpg`，与 `ImageStore.localUrl`/WebConfig 映射一致）；
 * 封面未下载时该地址 404，前端回退到影片详情接口的 coverUrl 代理。
 */
data class MovieBriefDto(
    val id: Long,
    val number: String,
    val title: String?,
    val coverPath: String,
) {
    companion object {
        fun of(movie: ActressRepository.ActressMovie) = MovieBriefDto(
            id = movie.id, number = movie.number, title = movie.title,
            coverPath = "$LOCAL_IMAGES_PREFIX/${movie.number}/poster.jpg",
        )
    }
}

/** 异步任务受理（批量补全 / Emby 同步）：进度复用 GET /jobs/{jobId} */
data class ActorJobAccepted(val jobId: Long)

/** Gfriends 索引状态：最后同步时间（null = 从未成功同步）+ 索引条目数 */
data class GfriendsStatusDto(
    val lastSyncAt: String?,
    val indexCount: Long,
) {
    companion object {
        fun of(lastSyncAt: Instant?, indexCount: Long) = GfriendsStatusDto(
            lastSyncAt = lastSyncAt?.toString(), indexCount = indexCount,
        )
    }
}

/**
 * Gfriends 索引强制同步结果：
 * [success] = false（下载/解析失败或首次同步无可用索引）时 [message] 说明原因，旧索引仍可继续使用。
 */
data class GfriendsSyncResultDto(
    val success: Boolean,
    val message: String? = null,
    val lastSyncAt: String? = null,
    val indexCount: Long = 0,
)

/** 本地刮削图片的 Web 路径前缀（WebConfig 把 sinjav.images.dir 映射到该前缀） */
private const val LOCAL_IMAGES_PREFIX = "/local-images"
