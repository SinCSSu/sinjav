package top.sincs.sinjav.actor

import java.time.Instant

/**
 * 演员主数据（actresses 表）。
 *
 * [photoPath] / [alias] / [embyPersonId] / [updatedAt] 由 V6__actress_photo.sql 引入。
 * updated_at 允许为 null：存量行在迁移中已回填，手工维护的数据可能仍为空。
 */
data class ActressEntity(
    val id: Long,
    val name: String,
    /** 本地照片相对路径（如 actors/夢乃あいか.jpg）；未抓取为 null */
    val photoPath: String? = null,
    /** 规范名（Gfriends 匹配与照片文件命名用，可与 name 不同） */
    val alias: String? = null,
    /** Emby Person Id 缓存（推送头像用） */
    val embyPersonId: String? = null,
    val updatedAt: Instant? = null,
)
