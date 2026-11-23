package top.sincs.sinjav.actor

/**
 * Gfriends 头像库索引条目（gfriends_index 表）：
 * [name] 为 Filetree.json 中的文件名键（如 `夢乃あいか.jpg`），[url] 为对应图片地址。
 */
data class GfriendsIndexEntity(
    val name: String,
    val url: String,
)
